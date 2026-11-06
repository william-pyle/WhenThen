package com.jazcosolutions.whenthen;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
        && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
    Store s = new Store(c);
    if (s.prefs().getBoolean("monitorWanted", false)) {
      s.log(
          "Restart needed",
          "Phone restarted or app updated. Tap Start monitoring to restore a user-started session.",
          "blocked");
      Notices.resumePrompt(c);
    }
  }
}
