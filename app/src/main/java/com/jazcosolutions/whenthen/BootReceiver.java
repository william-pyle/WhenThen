package com.jazcosolutions.whenthen;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
    // A GUI stop is temporary. A reboot/update explicitly starts a new session.
    Monitoring.resume(
        c,
        Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) ? "Phone restarted" : "App updated",
        true);
  }
}
