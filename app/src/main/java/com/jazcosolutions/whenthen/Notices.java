package com.jazcosolutions.whenthen;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import java.util.concurrent.atomic.AtomicInteger;

public final class Notices {
  private static final AtomicInteger IDS =
      new AtomicInteger((int) (System.currentTimeMillis() % 100000) + 100);

  public static void channels(Context c) {
    NotificationManager n = c.getSystemService(NotificationManager.class);
    n.createNotificationChannel(
        new NotificationChannel(
            "monitor", "Monitoring status", NotificationManager.IMPORTANCE_LOW));
    n.createNotificationChannel(
        new NotificationChannel("actions", "Rule messages", NotificationManager.IMPORTANCE_HIGH));
  }

  public static PendingIntent open(Context c, String text, String uri) {
    Intent i =
        new Intent(c, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("detail", text)
            .putExtra("image", uri);
    return PendingIntent.getActivity(
        c,
        IDS.incrementAndGet(),
        i,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  public static Notification monitor(Context c) {
    channels(c);
    PendingIntent stop =
        PendingIntent.getService(
            c,
            0,
            new Intent(c, MonitorService.class).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent quiet =
        PendingIntent.getService(
            c,
            1,
            new Intent(c, MonitorService.class).setAction("QUIET"),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(c, "monitor")
        .setSmallIcon(R.drawable.ic_status)
        .setContentTitle("WhenThen is monitoring")
        .setContentText(
            MonitorService.health.isEmpty()
                ? "Your enabled blocks are listening. Tap to manage."
                : "Some rules are blocked. Open Setup to review access.")
        .setContentIntent(open(c, "", ""))
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "Stop audio", quiet).build())
        .addAction(new Notification.Action.Builder(null, "Stop monitoring", stop).build())
        .build();
  }

  public static Bitmap bitmap(Context c, Uri uri) throws Exception {
    return ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(c.getContentResolver(), uri),
        (decoder, info, source) -> {
          int w = info.getSize().getWidth(), h = info.getSize().getHeight();
          double scale = Math.min(1, 1200.0 / Math.max(w, h));
          decoder.setTargetSize(Math.max(1, (int) (w * scale)), Math.max(1, (int) (h * scale)));
          decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
  }

  public static void message(Context c, String title, String text, String uri) throws Exception {
    channels(c);
    NotificationManager n = c.getSystemService(NotificationManager.class);
    if (!n.areNotificationsEnabled())
      throw new IllegalStateException("Notifications are disabled.");
    Notification.Builder b =
        new Notification.Builder(c, "actions")
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(c, text, uri))
            .setStyle(new Notification.BigTextStyle().bigText(text));
    if (!uri.isEmpty())
      b.setStyle(
          new Notification.BigPictureStyle()
              .bigPicture(bitmap(c, Uri.parse(uri)))
              .setSummaryText(text));
    n.notify(IDS.incrementAndGet(), b.build());
  }

  public static void resumePrompt(Context c) {
    try {
      message(
          c,
          "Tap to resume WhenThen",
          "Monitoring is paused. Open WhenThen and start monitoring again.",
          "");
    } catch (Exception ignored) {
    }
  }
}
