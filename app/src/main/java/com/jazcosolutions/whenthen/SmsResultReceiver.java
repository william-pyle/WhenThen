package com.jazcosolutions.whenthen;

import android.app.Activity;
import android.content.*;

public final class SmsResultReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    int code = getResultCode();
    String stage = i.getStringExtra("stage");
    new Store(c)
        .log(
            i.getStringExtra("rule") + " · SMS " + stage,
            "Part "
                + i.getIntExtra("part", 0)
                + " of "
                + i.getIntExtra("total", 0)
                + "; result "
                + code
                + (code == Activity.RESULT_OK
                    ? ". "
                        + ("delivered".equals(stage)
                            ? "Delivery callback received."
                            : "Sent callback received; delivery is separate.")
                    : ". Failed or uncertain; no automatic retry."),
            code == Activity.RESULT_OK ? "observed" : "failed");
  }
}
