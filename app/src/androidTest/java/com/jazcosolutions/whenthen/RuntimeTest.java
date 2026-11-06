package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.NotificationManager;
import android.content.*;
import android.media.AudioManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RuntimeTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @org.junit.Rule
  public GrantPermissionRule permissions =
      GrantPermissionRule.grant(
          Manifest.permission.POST_NOTIFICATIONS,
          Manifest.permission.RECEIVE_SMS,
          Manifest.permission.READ_PHONE_STATE,
          Manifest.permission.READ_CALL_LOG,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION);

  private Context c;
  private Store store;

  @Before
  public void before() throws Exception {
    assertTrue(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    c.stopService(new Intent(c, MonitorService.class));
    Thread.sleep(300);
    store = new Store(c);
    store.save(new ArrayList<>());
    store.clearHistory();
    store.prefs().edit().clear().commit();
    store.engineState(new RuleEngine.State());
  }

  private Rule rule(String name, String type) {
    Rule r = new Rule();
    r.name = name;
    r.enabled = true;
    r.cooldownSeconds = 10;
    r.when.type = type;
    r.when
        .set("contact", "+15551234567")
        .set("matchText", "HELP")
        .set("count", 2)
        .set("minutes", 1)
        .set("ssid", "AndroidWifi");
    r.then.add(Rule.action("show_message").set("message", name + " ran"));
    return r;
  }

  @Test
  public void foregroundMonitorLifecycleAndActionDispatch() throws Exception {
    Rule r = rule("Runtime notification", "sms_received");
    store.save(List.of(r));
    activity.launchActivity(null);
    activity
        .getActivity()
        .runOnUiThread(
            () ->
                activity
                    .getActivity()
                    .startForegroundService(
                        new Intent(c, MonitorService.class).setAction("START")));
    Thread.sleep(1200);
    assertNotNull(MonitorService.active);
    RuleEngine.Event e =
        new RuleEngine.Event("runtime-sms", "sms_received", System.currentTimeMillis());
    e.contact = "15551234567";
    MonitorService.deliver(c, e);
    Thread.sleep(800);
    assertTrue(
        store.history().stream()
            .anyMatch(
                x -> x.title.equals("Runtime notification") && x.status.equals("dispatched")));
    assertTrue(c.getSystemService(NotificationManager.class).getActiveNotifications().length >= 2);
    c.startService(new Intent(c, MonitorService.class).setAction("STOP"));
    Thread.sleep(500);
    assertNull(MonitorService.active);
    assertFalse(store.prefs().getBoolean("monitorWanted", true));
  }

  @Test
  public void volumeActionReadsBackAndRestores() throws Exception {
    activity.launchActivity(null);
    AudioManager audio = c.getSystemService(AudioManager.class);
    int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    Rule r = rule("Volume test", "sms_received");
    r.then = List.of(Rule.action("set_volume").set("channels", "media").set("percent", 40));
    new ActionRunner(c).run(r);
    int target = (int) Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4);
    assertEquals(target, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    assertTrue(store.history().stream().anyMatch(x -> x.detail.contains("observed " + target)));
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0);
  }

  @Test
  public void bootPromptsWithoutStartingMonitoring() {
    store.prefs().edit().putBoolean("monitorWanted", true).commit();
    new BootReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
    assertNull(MonitorService.active);
    assertTrue(store.history().stream().anyMatch(e -> e.title.equals("Restart needed")));
    assertTrue(c.getSystemService(NotificationManager.class).getActiveNotifications().length > 0);
  }

  @Test
  public void stoppedMonitorNeverDispatches() {
    Rule r = rule("Stopped", "sms_received");
    store.save(List.of(r));
    RuleEngine.Event e =
        new RuleEngine.Event("stopped", "sms_received", System.currentTimeMillis());
    e.contact = "15551234567";
    MonitorService.deliver(c, e);
    assertFalse(store.history().stream().anyMatch(x -> x.status.equals("dispatched")));
  }

  @Test
  public void prepareHostIntegrationFixtures() throws Exception {
    List<Rule> rules =
        List.of(
            rule("Incoming call", "call_incoming"),
            rule("Repeated calls", "call_count"),
            rule("Missed call", "call_missed"),
            rule("Incoming text", "sms_received"),
            rule("Exact emergency text", "sms_matches"),
            rule("Wi-Fi left", "wifi_disconnected"),
            rule("Wi-Fi joined", "wifi_connected"));
    store.save(rules);
    activity.launchActivity(null);
    activity
        .getActivity()
        .runOnUiThread(
            () ->
                activity
                    .getActivity()
                    .startForegroundService(
                        new Intent(c, MonitorService.class).setAction("START")));
    Thread.sleep(1500);
    assertNotNull(MonitorService.active);
  }
}
