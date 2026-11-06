package com.jazcosolutions.whenthen;

import android.content.*;

/** Monitoring defaults on; a stop lasts until a rule change, reboot, or update. */
final class Monitoring {
  private Monitoring() {}

  static boolean wanted(Context context) {
    return new Store(context).prefs().getBoolean("monitorWanted", true);
  }

  static void resume(Context context, String reason, boolean reload) {
    Store store = new Store(context);
    store.prefs().edit().putBoolean("monitorWanted", true).apply();
    try {
      context.startForegroundService(
          new Intent(context, MonitorService.class)
              .setAction(reload ? "RELOAD" : "START")
              .putExtra("reason", reason));
    } catch (RuntimeException e) {
      store.log(
          "Automatic monitoring needs attention",
          "Android could not start monitoring. Open WhenThen and review Setup. "
              + e.getClass().getSimpleName(),
          "blocked");
      Notices.resumePrompt(context);
    }
  }

  static void onOpen(Context context) {
    if (wanted(context)) resume(context, "App opened", false);
  }

  static void rulesChanged(Context context) {
    resume(context, "Saved rule changes", true);
  }

  static void stop(Context context) {
    new Store(context).prefs().edit().putBoolean("monitorWanted", false).apply();
    if (MonitorService.active != null)
      context.startService(new Intent(context, MonitorService.class).setAction("STOP"));
  }
}
