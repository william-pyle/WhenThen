package com.jazcosolutions.whenthen;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.InsetDrawable;
import android.widget.Button;

/** Keeps native Android dialog behavior with the app's simple rounded surface. */
public final class BrickDialogBuilder extends AlertDialog.Builder {
  public BrickDialogBuilder(Context context) { super(context); }

  @Override public AlertDialog show() {
    AlertDialog dialog = super.show();
    Context context = getContext();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(new InsetDrawable(
          BrickStyle.panel(context, BrickStyle.SURFACE), 0, BrickStyle.dp(context, 12), 0, 0));
    }
    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
      Button button = dialog.getButton(which);
      if (button != null) {
        button.setAllCaps(false);
        button.setTextColor(BrickStyle.BLUE);
      }
    }
    return dialog;
  }
}
