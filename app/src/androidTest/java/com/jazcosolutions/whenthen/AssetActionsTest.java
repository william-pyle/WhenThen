package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AssetActionsTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @org.junit.Rule
  public GrantPermissionRule grant =
      GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS);

  Context c;
  Store store;
  ActionRunner runner;

  @Before
  public void before() {
    Assume.assumeTrue(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    store = new Store(c);
    store.prefs().edit().remove("emergencyUntil").commit();
    activity.launchActivity(null);
    runner = new ActionRunner(c);
  }

  @After
  public void after() {
    if (runner != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(runner::close);
  }

  Rule rule(Rule.Piece... pieces) {
    Rule r = new Rule();
    r.name = "Asset test " + UUID.randomUUID();
    r.when.type = "sms_received";
    r.when.set("contact", "15551234567");
    r.then = Arrays.asList(pieces);
    return r;
  }

  Uri media(boolean image) throws Exception {
    ContentValues v = new ContentValues();
    v.put(
        MediaStore.MediaColumns.DISPLAY_NAME,
        "WhenThen-test-" + UUID.randomUUID() + (image ? ".png" : ".wav"));
    v.put(MediaStore.MediaColumns.MIME_TYPE, image ? "image/png" : "audio/wav");
    v.put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        image ? "Pictures/WhenThen-tests" : "Music/WhenThen-tests");
    Uri uri =
        c.getContentResolver()
            .insert(
                image
                    ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                v);
    try (InputStream in =
            image
                ? c.getResources().openRawResource(R.drawable.icon_art)
                : InstrumentationRegistry.getInstrumentation()
                    .getContext()
                    .getAssets()
                    .open("test-tone.wav");
        OutputStream out = c.getContentResolver().openOutputStream(uri)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) >= 0) out.write(b, 0, n);
    }
    return uri;
  }

  @Test
  public void imageActionDecodesAndPostsPicture() throws Exception {
    Uri uri = media(true);
    try {
      Rule r =
          rule(Rule.action("show_image").set("uri", uri.toString()).set("message", "A real image"));
      runner.run(r);
      assertTrue(
          Arrays.stream(c.getSystemService(NotificationManager.class).getActiveNotifications())
              .anyMatch(
                  n ->
                      r.name.equals(n.getNotification().extras.getString(Notification.EXTRA_TITLE))
                          && n.getNotification().extras.containsKey(Notification.EXTRA_PICTURE)));
    } finally {
      c.getContentResolver().delete(uri, null, null);
    }
  }

  @Test
  public void audioFileStartsAndRespectsTimeLimit() throws Exception {
    Uri uri = media(false);
    try {
      Rule r = rule(Rule.action("play_audio").set("uri", uri.toString()).set("seconds", 1));
      runner.run(r);
      Thread.sleep(2500);
      assertTrue(
          store.history().stream()
              .anyMatch(e -> e.title.equals(r.name) && e.detail.startsWith("Player started")));
      assertTrue(
          store.history().stream()
              .anyMatch(e -> e.title.equals(r.name) && e.detail.contains("configured time limit")));
    } finally {
      c.getContentResolver().delete(uri, null, null);
    }
  }

  @Test
  public void muteAndUnmuteRestorePreviousMediaLevel() {
    AudioManager a = c.getSystemService(AudioManager.class);
    int before = a.getStreamVolume(3);
    try {
      a.setStreamVolume(3, 4, 0);
      runner.run(rule(Rule.action("mute").set("channels", "media")));
      assertEquals(0, a.getStreamVolume(3));
      runner.run(rule(Rule.action("unmute").set("channels", "media")));
      assertEquals(4, a.getStreamVolume(3));
    } finally {
      a.setStreamVolume(3, before, 0);
    }
  }

  @Test
  public void emergencyVolumeProtectsAgainstNormalRule() {
    AudioManager a = c.getSystemService(AudioManager.class);
    int before = a.getStreamVolume(3);
    try {
      runner.run(rule(Rule.action("emergency_loud").set("channels", "media")));
      assertEquals(a.getStreamMaxVolume(3), a.getStreamVolume(3));
      runner.run(rule(Rule.action("mute").set("channels", "media")));
      assertEquals(a.getStreamMaxVolume(3), a.getStreamVolume(3));
    } finally {
      a.setStreamVolume(3, before, 0);
    }
  }

  @Test
  public void failedIndependentActionDoesNotPreventMessage() {
    Rule r =
        rule(
            Rule.action("show_image").set("uri", "content://missing-provider/image"),
            Rule.action("show_message").set("message", "Still delivered"));
    runner.run(r);
    assertTrue(
        store.history().stream()
            .anyMatch(e -> e.title.equals(r.name) && e.status.equals("partial")));
    assertTrue(
        Arrays.stream(c.getSystemService(NotificationManager.class).getActiveNotifications())
            .anyMatch(
                n ->
                    r.name.equals(n.getNotification().extras.getString(Notification.EXTRA_TITLE))));
  }

  @Test
  public void noSendPermissionIsRecordedWithoutSending() {
    assertFalse(Readiness.has(c, Manifest.permission.SEND_SMS));
    Rule r =
        rule(Rule.action("send_sms").set("recipient", "15551234567").set("message", "Never sent"));
    runner.run(r);
    assertTrue(
        store.history().stream()
            .anyMatch(
                e ->
                    e.title.startsWith(r.name)
                        && e.status.equals("failed")
                        && e.detail.contains("permission")));
  }
}
