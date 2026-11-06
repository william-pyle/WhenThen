package com.jazcosolutions.whenthen;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import java.util.*;

public final class Readiness {
  public static boolean has(Context c, String permission) {
    return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  public static boolean locationOn(Context c) {
    return c.getSystemService(LocationManager.class).isLocationEnabled();
  }

  public static List<String> missing(Context c, Rule rule) {
    List<String> e = new ArrayList<>(rule.errors());
    if (!c.getSystemService(NotificationManager.class).areNotificationsEnabled())
      e.add("Allow notifications in Setup.");
    if (rule.when == null) return e;
    String t = rule.when.type;
    if (t.startsWith("call")) {
      need(c, Manifest.permission.READ_PHONE_STATE, "Phone access", e);
      need(c, Manifest.permission.READ_CALL_LOG, "Call log access (caller identity)", e);
    }
    if (t.startsWith("sms")) need(c, Manifest.permission.RECEIVE_SMS, "Receive SMS", e);
    if (t.startsWith("wifi") || t.startsWith("waypoint")) {
      need(
          c,
          Manifest.permission.ACCESS_FINE_LOCATION,
          "Precise location (also needed for Wi-Fi names)",
          e);
      if (!locationOn(c)) e.add("Turn on the phone's Location setting.");
    }
    if (rule.then != null)
      for (Rule.Piece p : rule.then) {
        if (p.type.equals("send_sms")) need(c, Manifest.permission.SEND_SMS, "Send SMS", e);
        if (p.type.equals("play_audio") || p.type.equals("show_image")) {
          try (android.os.ParcelFileDescriptor fd =
              c.getContentResolver().openFileDescriptor(Uri.parse(p.get("uri")), "r")) {
            if (fd == null) e.add("Choose the file again.");
          } catch (Exception ex) {
            e.add("File access expired: choose " + Rule.label(p.type) + " file again.");
          }
        }
      }
    return new ArrayList<>(new LinkedHashSet<>(e));
  }

  private static void need(Context c, String p, String label, List<String> e) {
    if (!has(c, p)) e.add("Allow " + label + " in Setup.");
  }

  public static boolean exempt(Context c) {
    return c.getSystemService(PowerManager.class)
        .isIgnoringBatteryOptimizations(c.getPackageName());
  }

  public static boolean dndAccess(Context c) {
    return c.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted();
  }
}
