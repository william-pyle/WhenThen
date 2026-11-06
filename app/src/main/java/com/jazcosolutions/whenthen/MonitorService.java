package com.jazcosolutions.whenthen;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.database.*;
import android.location.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.CallLog;
import android.telephony.TelephonyManager;
import java.util.*;
import java.util.concurrent.*;

public final class MonitorService extends Service {
  public static volatile MonitorService active;
  public static volatile String health = "";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Store store;
  private RuleEngine engine;
  private ActionRunner runner;
  private volatile boolean running;
  private volatile int generation;
  private String configuration = "";
  private ConnectivityManager.NetworkCallback networkCallback;
  private LocationListener locationListener;
  private ContentObserver callObserver;
  private final Map<Network, String> networks = new HashMap<>();
  private final Set<String> connectedSsids = new HashSet<>();
  private boolean wifiBaseline;
  private long startAt, lastLogId;
  private String ringingId, ringingNumber = "";
  private long ringingAt;

  @Override
  public void onCreate() {
    super.onCreate();
    store = new Store(this);
    runner = new ActionRunner(this);
    Notices.channels(this);
  }

  @Override
  public int onStartCommand(Intent i, int flags, int startId) {
    if (i == null && !Monitoring.wanted(this)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = i == null ? "START" : i.getAction();
    if ("STOP".equals(action)) {
      stopRequested();
      return START_NOT_STICKY;
    }
    if ("QUIET".equals(action)) {
      runner.stopAudio();
      if (!running) stopSelf();
      return running ? START_STICKY : START_NOT_STICKY;
    }
    try {
      List<Rule> rules = store.rules();
      String nextConfiguration = Store.json(rules) + accessKey();
      if (running && !"RELOAD".equals(action) && configuration.equals(nextConfiguration))
        return START_STICKY;
      boolean needsLocation =
          rules.stream()
              .anyMatch(
                  r ->
                      r.enabled
                          && (r.when.type.startsWith("waypoint")
                              || r.when.type.startsWith("wifi")));
      int types = 0;
      if (Build.VERSION.SDK_INT >= 34) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
      if (needsLocation
          && Readiness.has(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
          && Readiness.has(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
          && Readiness.locationOn(this)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
      // Monitoring itself is specialUse, not media playback. Never declare mediaPlayback
      // at boot merely because a dormant rule contains a future short audio action.
      health = "";
      startForeground(1, Notices.monitor(this), types);
      running = false;
      generation++;
      clearObservers();
      runner.close();
      runner = new ActionRunner(this);
      // Serialize engine replacement after any in-flight event; queued old events are rejected.
      worker.execute(
          () -> {
            engine = new RuleEngine(store.engineState());
            engine.state.boundaries.clear();
          });
      configuration = nextConfiguration;
      running = true;
      active = this;
      startAt = System.currentTimeMillis();
      store.prefs().edit().putBoolean("monitorWanted", true).apply();
      observeWifi();
      observeLocation(rules);
      observeCallLog();
      store.log(
          "Monitoring started",
          (i == null
                  ? "Android restored monitoring"
                  : i.getStringExtra("reason") == null
                      ? "Monitoring requested"
                      : i.getStringExtra("reason"))
              + ". Wi-Fi and location establish a baseline without firing actions.",
          "observed");
      health = "";
      checkHealth();
    } catch (Exception e) {
      health = "Monitoring could not start. Open Setup and review permissions.";
      store.log("Monitoring failed", e.toString(), "failed");
      stopSelf();
    }
    return START_STICKY;
  }

  private String accessKey() {
    StringBuilder key = new StringBuilder();
    for (String permission :
        new String[] {
          android.Manifest.permission.READ_PHONE_STATE,
          android.Manifest.permission.READ_CALL_LOG,
          android.Manifest.permission.RECEIVE_SMS,
          android.Manifest.permission.SEND_SMS,
          android.Manifest.permission.ACCESS_FINE_LOCATION,
          android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }) key.append(Readiness.has(this, permission));
    return key.append(Readiness.locationOn(this))
        .append(getSystemService(NotificationManager.class).areNotificationsEnabled())
        .toString();
  }

  public void accept(RuleEngine.Event event) {
    if (!running) return;
    final int acceptedGeneration = generation;
    worker.execute(
        () -> {
          PowerManager.WakeLock lock =
              getSystemService(PowerManager.class)
                  .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhenThen:event");
          try {
            lock.acquire(30000);
            if (!running || acceptedGeneration != generation) return;
            List<Rule> hits = engine.accept(store.rules(), event, System.currentTimeMillis());
            store.engineState(engine.state);
            for (Rule rule : hits) {
              if (!running || acceptedGeneration != generation) break;
              List<String> blockers = Readiness.missing(this, rule);
              if (!blockers.isEmpty()) {
                store.log(rule.name, String.join("\n", blockers), "blocked");
                continue;
              }
              runner.run(rule);
            }
          } catch (Exception e) {
            store.log("Event processing", e.toString(), "failed");
          } finally {
            if (lock.isHeld()) lock.release();
          }
        });
  }

  private void checkHealth() {
    if (!running) return;
    try {
      List<String> missing = new ArrayList<>();
      if (!getSystemService(NotificationManager.class).areNotificationsEnabled())
        missing.add("Allow notifications in Setup to see monitoring status.");
      for (Rule r : store.rules())
        if (r.enabled)
          for (String reason : Readiness.missing(this, r)) missing.add(r.name + ": " + reason);
      String current = String.join("\n", missing);
      if (!current.equals(health)) {
        health = current;
        store.log(
            "Readiness changed",
            current.isEmpty()
                ? "Required access is available again. Device verification is separate."
                : current,
            current.isEmpty() ? "observed" : "blocked");
        getSystemService(NotificationManager.class).notify(1, Notices.monitor(this));
      }
    } catch (Exception e) {
      store.log("Readiness check", e.toString(), "blocked");
    }
    main.postDelayed(this::checkHealth, 30000);
  }

  public static void deliver(Context c, RuleEngine.Event event) {
    MonitorService s = active;
    if (s != null) s.accept(event);
    else if (Monitoring.wanted(c)) {
      Store st = new Store(c);
      long now = System.currentTimeMillis();
      if (now - st.prefs().getLong("resumePrompt", 0) > 300000) {
        st.prefs().edit().putLong("resumePrompt", now).apply();
        st.log(
            "Monitoring is paused",
            "An event arrived while monitoring was not running. Open the app to resume.",
            "blocked");
        Notices.resumePrompt(c);
      }
    }
  }

  public void phone(String state, String number) {
    main.post(
        () -> {
          long now = System.currentTimeMillis();
          if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            if (ringingId == null || now - ringingAt > 180000) {
              ringingId = "call:" + UUID.randomUUID();
              ringingAt = now;
              ringingNumber = "";
            }
            if (number != null && !number.isEmpty() && ringingNumber.isEmpty()) {
              ringingNumber = number;
              RuleEngine.Event e = new RuleEngine.Event(ringingId, "call_incoming", now);
              e.contact = number;
              accept(e);
            }
          } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            ringingId = null;
            ringingNumber = "";
            main.postDelayed(this::readMissedCalls, 1500);
          }
        });
  }

  private void observeCallLog() {
    if (!Readiness.has(this, android.Manifest.permission.READ_CALL_LOG)) return;
    try (Cursor cursor =
        getContentResolver()
            .query(
                CallLog.Calls.CONTENT_URI,
                new String[] {CallLog.Calls._ID},
                null,
                null,
                CallLog.Calls._ID + " DESC")) {
      if (cursor != null && cursor.moveToFirst()) lastLogId = cursor.getLong(0);
    } catch (Exception e) {
      store.log("Call log", e.toString(), "blocked");
      return;
    }
    callObserver =
        new ContentObserver(main) {
          @Override
          public void onChange(boolean self) {
            readMissedCalls();
          }
        };
    getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver);
  }

  private void readMissedCalls() {
    if (!running) return;
    try (Cursor cursor =
        getContentResolver()
            .query(
                CallLog.Calls.CONTENT_URI,
                new String[] {
                  CallLog.Calls._ID, CallLog.Calls.TYPE, CallLog.Calls.NUMBER, CallLog.Calls.DATE
                },
                CallLog.Calls._ID + ">?",
                new String[] {String.valueOf(lastLogId)},
                CallLog.Calls._ID + " ASC")) {
      if (cursor != null)
        while (cursor.moveToNext()) {
          long id = cursor.getLong(0);
          lastLogId = Math.max(lastLogId, id);
          if (cursor.getInt(1) == CallLog.Calls.MISSED_TYPE && cursor.getLong(3) >= startAt) {
            RuleEngine.Event e =
                new RuleEngine.Event("missed:" + id, "call_missed", System.currentTimeMillis());
            e.contact = cursor.getString(2);
            accept(e);
          }
        }
    } catch (Exception e) {
      store.log("Missed-call access", e.toString(), "blocked");
    }
  }

  private void observeWifi() {
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    final int observedGeneration = generation;
    networkCallback =
        new ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
          @Override
          public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            main.post(
                () -> {
                  if (observedGeneration == generation) wifi(n, caps);
                });
          }

          @Override
          public void onLost(Network n) {
            main.post(
                () -> {
                  if (observedGeneration == generation) lost(n);
                });
          }
        };
    cm.registerNetworkCallback(
        new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build(),
        networkCallback);
    main.postDelayed(
        () -> {
          wifiBaseline = true;
          connectedSsids.addAll(networks.values());
        },
        3000);
  }

  private void wifi(Network network, NetworkCapabilities caps) {
    if (!running) return;
    WifiInfo info =
        caps.getTransportInfo() instanceof WifiInfo ? (WifiInfo) caps.getTransportInfo() : null;
    String ssid = info == null ? "" : info.getSSID();
    if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) return;
    if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\""))
      ssid = ssid.substring(1, ssid.length() - 1);
    String old = networks.put(network, ssid);
    if (wifiBaseline) {
      if (old != null && !old.equals(ssid)) scheduleDisconnect(old);
      if (connectedSsids.add(ssid)) wifiEvent("wifi_connected", ssid);
    }
  }

  private void lost(Network n) {
    String old = networks.remove(n);
    if (old != null && wifiBaseline) scheduleDisconnect(old);
  }

  private void scheduleDisconnect(String ssid) {
    main.postDelayed(
        () -> {
          if (running && !networks.containsValue(ssid) && connectedSsids.remove(ssid))
            wifiEvent("wifi_disconnected", ssid);
        },
        2500);
  }

  private void wifiEvent(String type, String ssid) {
    RuleEngine.Event e =
        new RuleEngine.Event(type + ":" + UUID.randomUUID(), type, System.currentTimeMillis());
    e.ssid = ssid;
    accept(e);
  }

  private void observeLocation(List<Rule> rules) {
    if (rules.stream().noneMatch(r -> r.enabled && r.when.type.startsWith("waypoint"))) return;
    if (!Readiness.has(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        || !Readiness.has(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
      store.log("Places", "Allow precise location all the time in Setup.", "blocked");
      return;
    }
    LocationManager lm = getSystemService(LocationManager.class);
    final int observedGeneration = generation;
    locationListener =
        new LocationListener() {
          @Override
          public void onLocationChanged(Location l) {
            if (!running || observedGeneration != generation) return;
            if (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() > 60000000000L
                || !l.hasAccuracy()) return;
            RuleEngine.Event e =
                new RuleEngine.Event(
                    "location:" + l.getElapsedRealtimeNanos(),
                    "location",
                    System.currentTimeMillis());
            e.latitude = l.getLatitude();
            e.longitude = l.getLongitude();
            e.accuracy = l.getAccuracy();
            accept(e);
          }
        };
    for (String provider :
        new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
      if (lm.isProviderEnabled(provider))
        try {
          lm.requestLocationUpdates(provider, 15000, 0, locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
          store.log("Place monitoring", "Location permission was revoked.", "blocked");
        }
  }

  private void stopRequested() {
    store.prefs().edit().putBoolean("monitorWanted", false).apply();
    store.log("Monitoring stopped", "Stopped by you. No rule actions will run.", "observed");
    running = false;
    generation++;
    runner.stopAudio();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    running = false;
    generation++;
    if (active == this) active = null;
    clearObservers();
    runner.close();
    worker.shutdownNow();
    if (Monitoring.wanted(this)) {
      store.log(
          "Monitoring interrupted",
          "Android may restore the service; open WhenThen if it stays paused.",
          "blocked");
      Notices.resumePrompt(this);
    }
    super.onDestroy();
  }

  private void clearObservers() {
    main.removeCallbacksAndMessages(null);
    try {
      if (networkCallback != null)
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
    } catch (Exception ignored) {
    }
    try {
      if (locationListener != null)
        getSystemService(LocationManager.class).removeUpdates(locationListener);
    } catch (Exception ignored) {
    }
    try {
      if (callObserver != null) getContentResolver().unregisterContentObserver(callObserver);
    } catch (Exception ignored) {
    }
    networkCallback = null;
    locationListener = null;
    callObserver = null;
    networks.clear();
    connectedSsids.clear();
    wifiBaseline = false;
    ringingId = null;
    ringingNumber = "";
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }
}
