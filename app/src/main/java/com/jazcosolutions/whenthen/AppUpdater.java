package com.jazcosolutions.whenthen;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

final class AppUpdater {
  private static final long CHECK_INTERVAL = 15 * 60 * 1000L;
  private static final long LATER_INTERVAL = 24 * 60 * 60 * 1000L;
  private final Activity activity;
  private final BooleanSupplier canPrompt;
  private final Runnable refresh;
  private final UpdateClient client;
  private final SharedPreferences prefs;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private boolean busy, closed, resumed;
  private AlertDialog dialog;

  AppUpdater(Activity activity, BooleanSupplier canPrompt, Runnable refresh) {
    this(activity, canPrompt, refresh, new UpdateClient());
  }

  AppUpdater(Activity activity, BooleanSupplier canPrompt, Runnable refresh, UpdateClient client) {
    this.activity = activity;
    this.canPrompt = canPrompt;
    this.refresh = refresh;
    this.client = client;
    prefs = activity.getSharedPreferences("app_updates", 0);
  }

  String status() {
    String status = prefs.getString("status", "Check for the latest release on GitHub.");
    return "Installed version " + BuildConfig.VERSION_NAME + "\n" + status;
  }

  void onResume() {
    resumed = true;
    String pending = prefs.getString("pending_permission", null);
    String tag = prefs.getString("pending_tag", "");
    long requested = prefs.getLong("permission_requested_at", 0);
    prefs
        .edit()
        .remove("pending_permission")
        .remove("pending_tag")
        .remove("permission_requested_at")
        .apply();
    if (pending != null && System.currentTimeMillis() - requested < CHECK_INTERVAL) {
      try {
        UpdateRelease release = UpdateRelease.parse(pending, tag);
        if (activity.getPackageManager().canRequestPackageInstalls() && canPrompt.getAsBoolean()) {
          download(release);
          return;
        }
        message(
            "Update permission needed",
            "Installation was not started. You can try again from Setup → Check for updates.");
      } catch (IllegalArgumentException ignored) {
        /* A fresh check replaces invalid cached metadata. */
      }
      return;
    }
    if (canPrompt.getAsBoolean()) check(false);
  }

  void onPause() {
    resumed = false;
  }

  void close() {
    closed = true;
    cancelled.set(true);
    worker.shutdownNow();
    if (dialog != null) dialog.dismiss();
  }

  void check(boolean manual) {
    if (closed || busy) return;
    long now = System.currentTimeMillis();
    if (!manual && now - prefs.getLong("last_attempt", 0) < CHECK_INTERVAL) {
      UpdateRelease cached = cached();
      if (cached != null && shouldOffer(cached)) offer(cached);
      return;
    }
    busy = true;
    prefs.edit().putLong("last_attempt", now).apply();
    if (manual) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
    worker.execute(
        () -> {
          try {
            UpdateRelease release = client.latest();
            post(
                () -> {
                  busy = false;
                  SharedPreferences.Editor edit = prefs.edit();
                  if (release != null)
                    edit.putString("manifest", release.manifestJson)
                        .putString("tag", release.tag());
                  else edit.remove("manifest").remove("tag");
                  boolean newer = release != null && release.newerThan(BuildConfig.VERSION_CODE);
                  edit.putString(
                          "status",
                          newer
                              ? "Version " + release.versionName + " is available."
                              : "You have the latest available version.")
                      .apply();
                  refresh.run();
                  if (newer && (manual || shouldOffer(release))) offer(release);
                  else if (manual && resumed)
                    message(
                        "You're up to date",
                        "WhenThen " + BuildConfig.VERSION_NAME + " is installed.");
                });
          } catch (Exception error) {
            post(
                () -> {
                  busy = false;
                  prefs
                      .edit()
                      .putString(
                          "status", "Could not check for updates. Try again when you're online.")
                      .apply();
                  refresh.run();
                  if (manual && resumed) message("Couldn't check for updates", readable(error));
                });
          }
        });
  }

  private UpdateRelease cached() {
    try {
      String manifest = prefs.getString("manifest", null);
      return manifest == null ? null : UpdateRelease.parse(manifest, prefs.getString("tag", ""));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private boolean shouldOffer(UpdateRelease release) {
    return release.newerThan(BuildConfig.VERSION_CODE)
        && (prefs.getLong("later_version", -1) != release.versionCode
            || System.currentTimeMillis() >= prefs.getLong("later_until", 0));
  }

  private void later(UpdateRelease release) {
    prefs
        .edit()
        .putLong("later_version", release.versionCode)
        .putLong("later_until", System.currentTimeMillis() + LATER_INTERVAL)
        .apply();
  }

  void offer(UpdateRelease release) {
    if (!resumed || !canPrompt.getAsBoolean() || (dialog != null && dialog.isShowing())) return;
    dialog =
        new BrickDialogBuilder(activity)
            .setTitle("WhenThen " + release.versionName + " is available")
            .setMessage(
                release.notes
                    + "\n\n"
                    + "Your saved rules and templates stay on this phone. Monitoring resumes"
                    + " automatically after installation, even if you paused it earlier.")
            .setPositiveButton("Update now", (d, which) -> requestInstall(release))
            .setNegativeButton("Later", (d, which) -> later(release))
            .setOnCancelListener(d -> later(release))
            .show();
  }

  private void requestInstall(UpdateRelease release) {
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      dialog =
          new BrickDialogBuilder(activity)
              .setTitle("Allow WhenThen updates")
              .setMessage(
                  "On the next screen, enable Allow from this source for WhenThen, then return"
                      + " here. Android will still ask you to confirm the update.")
              .setPositiveButton(
                  "Open settings",
                  (d, which) -> {
                    prefs
                        .edit()
                        .putString("pending_permission", release.manifestJson)
                        .putString("pending_tag", release.tag())
                        .putLong("permission_requested_at", System.currentTimeMillis())
                        .apply();
                    try {
                      activity.startActivity(
                          new Intent(
                              Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                              Uri.parse("package:" + activity.getPackageName())));
                    } catch (ActivityNotFoundException e) {
                      prefs.edit().remove("pending_permission").apply();
                      message(
                          "Open phone settings",
                          "Allow app installation from WhenThen in your phone settings, then check"
                              + " for updates again.");
                    }
                  })
              .setNegativeButton("Later", (d, which) -> later(release))
              .show();
      return;
    }
    download(release);
  }

  private void download(UpdateRelease release) {
    if (busy || closed) return;
    busy = true;
    cancelled.set(false);
    ProgressBar progress =
        new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setPadding(32, 16, 32, 16);
    dialog =
        new BrickDialogBuilder(activity)
            .setTitle("Downloading WhenThen " + release.versionName)
            .setMessage("Checking the download before opening Android's installer.")
            .setView(progress)
            .setNegativeButton("Cancel", (d, which) -> cancelled.set(true))
            .setCancelable(false)
            .show();
    AlertDialog downloadDialog = dialog;
    worker.execute(
        () -> {
          try {
            File apk =
                client.download(
                    activity.getApplicationContext(),
                    release,
                    cancelled,
                    percent -> post(() -> progress.setProgress(percent)));
            post(
                () -> {
                  busy = false;
                  downloadDialog.dismiss();
                  if (cancelled.get()) return;
                  if (!resumed || !canPrompt.getAsBoolean()) {
                    prefs
                        .edit()
                        .putString(
                            "status",
                            "Update downloaded. Finish editing and check for updates to install.")
                        .apply();
                    return;
                  }
                  try {
                    Uri uri =
                        Uri.parse(
                            "content://" + activity.getPackageName() + ".updates/WhenThen.apk");
                    Intent install =
                        new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.setClipData(ClipData.newRawUri("WhenThen update", uri));
                    later(release);
                    activity.startActivity(install);
                  } catch (ActivityNotFoundException | SecurityException e) {
                    message(
                        "Couldn't open the installer",
                        "Check WhenThen's install permission in phone settings and try again.");
                  }
                });
          } catch (Exception error) {
            post(
                () -> {
                  busy = false;
                  downloadDialog.dismiss();
                  if (!cancelled.get() && !(error instanceof CancellationException) && resumed)
                    message("Update not installed", readable(error));
                });
          }
        });
  }

  private void post(Runnable task) {
    main.post(
        () -> {
          if (!closed && !activity.isFinishing() && !activity.isDestroyed()) task.run();
        });
  }

  private void message(String title, String message) {
    if (!closed && resumed)
      dialog =
          new BrickDialogBuilder(activity)
              .setTitle(title)
              .setMessage(message)
              .setPositiveButton("OK", null)
              .show();
  }

  private String readable(Exception error) {
    if (error instanceof java.io.IOException
        && error.getMessage() != null
        && !error.getMessage().contains("https:"))
      return error.getMessage() + "\n\nYour existing app and rules are unchanged.";
    return "Check your connection and try again. Your existing app and rules are unchanged.";
  }
}
