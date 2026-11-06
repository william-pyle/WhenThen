package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Targeted physical-device smoke test. Never clears owner rules or sends SMS. */
@RunWith(AndroidJUnit4.class)
public class DeviceSmokeTest {
  @Test
  public void lockedScreenAudioAndNotification() throws Exception {
    Assume.assumeTrue("Physical Samsung test only", Build.MANUFACTURER.equalsIgnoreCase("samsung"));
    Instrumentation inst = InstrumentationRegistry.getInstrumentation();
    Context c = inst.getTargetContext();
    Store store = new Store(c);
    assertTrue(
        "Enable notification access first",
        c.getSystemService(NotificationManager.class).areNotificationsEnabled());
    assertTrue(
        "Enable receive-SMS access first",
        Readiness.has(c, android.Manifest.permission.RECEIVE_SMS));
    assertNull("Pause owner monitoring before the scoped diagnostic", MonitorService.active);
    assertTrue(
        "Pause existing rules before the scoped diagnostic",
        store.rules().stream().noneMatch(r -> r.enabled));
    AudioManager audio = c.getSystemService(AudioManager.class);
    int mediaBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    int alarmBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM);
    boolean wantedBefore = store.prefs().getBoolean("monitorWanted", false);
    Uri uri = null;
    Rule fixture = new Rule();
    fixture.name = "Device check · temporary";
    fixture.when.type = "sms_matches";
    fixture.when.set("contact", "+15551234567").set("matchText", "WHENTHEN_DIAGNOSTIC_ONLY");
    fixture.enabled = true;
    try {
      ContentValues values = new ContentValues();
      values.put(
          MediaStore.Audio.Media.DISPLAY_NAME,
          "WhenThen-device-check-" + System.currentTimeMillis() + ".wav");
      values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
      values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/WhenThen-tests");
      uri = c.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(uri);
      try (InputStream source = inst.getContext().getAssets().open("test-tone.wav");
          OutputStream target = c.getContentResolver().openOutputStream(uri)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) >= 0) target.write(buffer, 0, read);
      }
      fixture.then.add(Rule.action("set_volume").set("channels", "alarm").set("percent", 75));
      fixture.then.add(Rule.action("play_audio").set("uri", uri.toString()).set("seconds", 7));
      fixture.then.add(
          Rule.action("show_message")
              .set(
                  "message",
                  "WhenThen's locked-screen device check ran. Volume will be restored"
                      + " automatically."));
      List<Rule> rules = store.rules();
      rules.add(fixture);
      store.save(rules);
      Intent launch = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      Activity screen = inst.startActivitySync(launch);
      inst.runOnMainSync(
          () -> {
            screen.setShowWhenLocked(true);
            screen.setTurnScreenOn(true);
          });
      inst.waitForIdleSync();
      Thread.sleep(1000);
      inst.runOnMainSync(
          () -> c.startForegroundService(new Intent(c, MonitorService.class).setAction("START")));
      Thread.sleep(1500);
      assertNotNull(MonitorService.active);
      inst.runOnMainSync(() -> screen.setTurnScreenOn(false));
      // Lock only after the service was started by the visible activity, as required for Android 17
      // audio.
      try (ParcelFileDescriptor ignored =
          inst.getUiAutomation().executeShellCommand("input keyevent KEYCODE_SLEEP")) {}
      Thread.sleep(1000);
      assertFalse(c.getSystemService(PowerManager.class).isInteractive());
      RuleEngine.Event event =
          new RuleEngine.Event(
              "device-diagnostic:" + UUID.randomUUID(), "sms_received", System.currentTimeMillis());
      event.contact = "15551234567";
      event.text = "WHENTHEN_DIAGNOSTIC_ONLY";
      MonitorService.deliver(c, event);
      Thread.sleep(7500);
      int wanted = (int) Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * .75);
      assertEquals(
          "Background volume read-back", wanted, audio.getStreamVolume(AudioManager.STREAM_ALARM));
      assertTrue(
          "MediaPlayer started while screen off",
          store.history().stream()
              .anyMatch(
                  e -> e.title.equals(fixture.name) && e.detail.startsWith("Player started")));
      assertTrue(
          "Playback completed",
          store.history().stream()
              .anyMatch(
                  e ->
                      e.title.equals(fixture.name) && e.detail.equals("Audio playback finished.")));
      assertTrue(
          "Notification present",
          c.getSystemService(NotificationManager.class).getActiveNotifications().length >= 2);
      assertFalse(
          "No diagnostic action failure",
          store.history().stream()
              .anyMatch(e -> e.title.startsWith(fixture.name) && e.status.equals("failed")));
      store.log(
          "Galaxy device check",
          "Screen off: foreground service remained active, requested alarm volume matched"
              + " read-back, MediaPlayer started/completed and notification was present. Human"
              + " audibility confirmation is separate. Temporary rules/media removed and previous"
              + " volume restored.",
          "observed");
    } finally {
      inst.runOnMainSync(
          () -> {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, mediaBefore, 0);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, alarmBefore, 0);
          });
      assertEquals(
          "Original alarm volume restored",
          alarmBefore,
          audio.getStreamVolume(AudioManager.STREAM_ALARM));
      if (MonitorService.active != null)
        c.startService(new Intent(c, MonitorService.class).setAction("STOP"));
      Thread.sleep(400);
      List<Rule> current = store.rules();
      current.removeIf(r -> r.id.equals(fixture.id));
      store.save(current);
      store.prefs().edit().putBoolean("monitorWanted", wantedBefore).commit();
      // Wake the display after restoring volume while the user-started service is still active.
      try (ParcelFileDescriptor ignored =
          inst.getUiAutomation().executeShellCommand("input keyevent KEYCODE_WAKEUP")) {}
      if (uri != null) c.getContentResolver().delete(uri, null, null);
      NotificationManager notices = c.getSystemService(NotificationManager.class);
      for (android.service.notification.StatusBarNotification n : notices.getActiveNotifications())
        if (fixture.name.equals(n.getNotification().extras.getString(Notification.EXTRA_TITLE)))
          notices.cancel(n.getId());
    }
  }
}
