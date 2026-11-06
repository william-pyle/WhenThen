package com.jazcosolutions.whenthen;

import android.content.*;
import android.telephony.TelephonyManager;

public final class EventReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(i.getAction())) return;
    MonitorService s = MonitorService.active;
    if (s != null)
      s.phone(
          i.getStringExtra(TelephonyManager.EXTRA_STATE),
          i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER));
  }
}
