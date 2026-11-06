package com.jazcosolutions.whenthen;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.telephony.*;
import java.util.*;

public final class ActionRunner {
  private final Context c;
  private final Store store;
  private final Handler main = new Handler(Looper.getMainLooper());
  private MediaPlayer player;
  private AudioFocusRequest focus;
  private Runnable stopTask;
  private volatile long emergencyUntil;
  private volatile boolean closed;

  public ActionRunner(Context c) {
    this.c = c;
    store = new Store(c);
  }

  public void run(Rule rule) {
    int failed = 0;
    List<String> blockers = rule.errors();
    if (!blockers.isEmpty()) {
      store.log(rule.name, String.join("\n", blockers), "blocked");
      return;
    }
    for (Rule.Piece p : rule.then) {
      if (closed || Thread.currentThread().isInterrupted()) {
        store.log(
            rule.name, "Remaining actions cancelled because monitoring stopped.", "interrupted");
        return;
      }
      try {
        String result;
        switch (p.type) {
          case "mute":
          case "unmute":
          case "set_volume":
          case "emergency_loud":
            result = volume(p, rule.priority);
            break;
          case "play_audio":
            if (rule.priority < 10 && System.currentTimeMillis() < emergencyUntil)
              throw new IllegalStateException(
                  "Emergency audio protection is active; normal-priority audio was skipped.");
            main.post(() -> play(p, rule.name));
            result =
                "Playback queued; see the separate playback result. Audibility needs a phone test.";
            break;
          case "show_message":
            Notices.message(c, rule.name, p.get("message"), "");
            result = "Notification posted; visibility and sound depend on phone settings.";
            break;
          case "show_image":
            Notices.message(c, rule.name, p.get("message"), p.get("uri"));
            result = "Image notification posted. Tap it to enlarge.";
            break;
          case "send_sms":
            result = send(rule, p);
            break;
          default:
            throw new IllegalArgumentException("Unknown action.");
        }
        store.log(rule.name + " · " + Rule.label(p.type), result, "observed");
      } catch (Exception ex) {
        failed++;
        store.log(
            rule.name + " · " + Rule.label(p.type),
            ex.getMessage() == null ? ex.toString() : ex.getMessage(),
            "failed");
      }
    }
    store.log(
        rule.name,
        failed == 0
            ? "Actions dispatched. Check their individual observations; pending SMS/audio is not"
                  + " proof of delivery or audibility."
            : failed + " action(s) failed. Other independent actions were attempted.",
        failed == 0 ? "dispatched" : "partial");
  }

  private String volume(Rule.Piece p, int priority) {
    if (priority < 10 && System.currentTimeMillis() < emergencyUntil)
      throw new IllegalStateException(
          "Emergency sound protection is active for 60 seconds; this normal-priority sound action"
              + " was skipped.");
    AudioManager a = c.getSystemService(AudioManager.class);
    String channel = p.get("channels");
    int[] streams =
        channel.equals("all")
            ? new int[] {2, 5, 3, 4}
            : channel.equals("ring")
                ? new int[] {2, 5}
                : channel.equals("media") ? new int[] {3} : new int[] {4};
    List<String> outcomes = new ArrayList<>();
    boolean failed = false;
    for (int stream : streams) {
      try {
        int before = a.getStreamVolume(stream), max = a.getStreamMaxVolume(stream);
        String key = "volume_" + stream;
        if ((p.type.equals("mute") || p.type.equals("emergency_loud")) && before > 0)
          store.prefs().edit().putInt(key, before).apply();
        int target =
            p.type.equals("mute")
                ? 0
                : p.type.equals("set_volume")
                    ? (int) Math.round(max * p.number("percent", 100) / 100)
                    : p.type.equals("unmute")
                        ? store.prefs().getInt(key, Math.max(1, max / 2))
                        : max;
        if ((stream == 2 || stream == 5)
            && target > 0
            && a.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
          a.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        a.setStreamVolume(stream, target, 0);
        int actual = a.getStreamVolume(stream);
        outcomes.add(
            "Stream " + stream + ": requested " + target + ", observed " + actual + " / " + max);
        if (actual != target) failed = true;
      } catch (Exception e) {
        failed = true;
        outcomes.add("Stream " + stream + ": " + e.getMessage());
      }
    }
    if (p.type.equals("emergency_loud") || priority == 10)
      emergencyUntil = System.currentTimeMillis() + 60000;
    int filter = c.getSystemService(NotificationManager.class).getCurrentInterruptionFilter();
    outcomes.add(
        "DND filter: "
            + filter
            + ". Output device and audible sound must be verified; DND is not overridden.");
    String result = String.join("\n", outcomes);
    if (failed) throw new IllegalStateException(result);
    return result;
  }

  private void play(Rule.Piece p, String name) {
    if (closed) return;
    stopAudio();
    try {
      AudioManager a = c.getSystemService(AudioManager.class);
      AudioAttributes attrs =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build();
      focus =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(attrs)
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change < 0) {
                      stopAudio();
                      store.log(name, "Audio stopped after focus loss.", "interrupted");
                    }
                  },
                  main)
              .build();
      if (a.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        throw new IllegalStateException("Audio focus denied.");
      player = new MediaPlayer();
      player.setAudioAttributes(attrs);
      player.setDataSource(c, Uri.parse(p.get("uri")));
      player.setWakeMode(c, PowerManager.PARTIAL_WAKE_LOCK);
      player.setOnPreparedListener(
          mp -> {
            try {
              mp.start();
              store.log(
                  name,
                  "Player started. This does not prove the phone speaker was audible.",
                  "observed");
              stopTask =
                  () -> {
                    stopAudio();
                    store.log(name, "Audio stopped at the configured time limit.", "observed");
                  };
              main.postDelayed(stopTask, (long) p.number("seconds", 30) * 1000);
            } catch (Exception e) {
              store.log(name, e.toString(), "failed");
              stopAudio();
            }
          });
      player.setOnCompletionListener(
          mp -> {
            stopAudio();
            store.log(name, "Audio playback finished.", "observed");
          });
      player.setOnErrorListener(
          (mp, what, extra) -> {
            store.log(name, "Playback error " + what + " / " + extra, "failed");
            stopAudio();
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      store.log(name, "Could not play audio: " + e.getMessage(), "failed");
      stopAudio();
    }
  }

  public void stopAudio() {
    if (stopTask != null) main.removeCallbacks(stopTask);
    stopTask = null;
    if (player != null) {
      try {
        player.release();
      } catch (Exception ignored) {
      }
      player = null;
    }
    if (focus != null) {
      c.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
      focus = null;
    }
  }

  public void close() {
    closed = true;
    main.removeCallbacksAndMessages(null);
    stopAudio();
  }

  private String send(Rule rule, Rule.Piece p) {
    if (!Readiness.has(c, android.Manifest.permission.SEND_SMS))
      throw new IllegalStateException("Send SMS permission is missing.");
    long now = System.currentTimeMillis();
    String key = "sms_limit_" + rule.id + "_" + Rule.phone(p.get("recipient"));
    if (now - store.prefs().getLong(key, 0) < 300000)
      throw new IllegalStateException(
          "SMS repeat protection: one send per rule/recipient every five minutes.");
    long window = store.prefs().getLong("sms_window", 0);
    int count = now - window < 3600000 ? store.prefs().getInt("sms_count", 0) : 0;
    if (count >= 10) throw new IllegalStateException("Hourly SMS limit reached (10).");
    int sub = (int) p.number("subscription", -1);
    if (sub < 0) sub = SubscriptionManager.getDefaultSmsSubscriptionId();
    if (!SubscriptionManager.isValidSubscriptionId(sub))
      throw new IllegalStateException(
          "Choose a default SMS SIM in Android Settings or a specific SIM in this action.");
    SmsManager sms = c.getSystemService(SmsManager.class).createForSubscriptionId(sub);
    ArrayList<String> parts = sms.divideMessage(p.get("message"));
    if (parts.size() > 10) throw new IllegalStateException("Text has too many parts.");
    String batch = UUID.randomUUID().toString();
    ArrayList<PendingIntent> sent = new ArrayList<>(), delivered = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      sent.add(resultIntent(batch, i, parts.size(), rule.name, "sent"));
      delivered.add(resultIntent(batch, i, parts.size(), rule.name, "delivered"));
    }
    // Persist the reservation before handing off; unknown sends are never automatically retried.
    if (!store
        .prefs()
        .edit()
        .putLong(key, now)
        .putLong("sms_window", now - window < 3600000 ? window : now)
        .putInt("sms_count", count + 1)
        .commit()) throw new IllegalStateException("Could not persist SMS repeat protection.");
    sms.sendMultipartTextMessage(p.get("recipient"), null, parts, sent, delivered);
    return "SMS handed to the modem ("
        + parts.size()
        + " part(s)). Await sent/delivery callbacks; no automatic retry.";
  }

  private PendingIntent resultIntent(String batch, int part, int total, String rule, String stage) {
    Intent i =
        new Intent(c, SmsResultReceiver.class)
            .setAction("com.jazcosolutions.whenthen." + stage)
            .setData(Uri.parse("whenthen://sms/" + batch + "/" + stage + "/" + part))
            .putExtra("rule", rule)
            .putExtra("part", part + 1)
            .putExtra("total", total)
            .putExtra("stage", stage);
    return PendingIntent.getBroadcast(
        c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
}
