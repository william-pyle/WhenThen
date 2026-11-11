package com.jazcosolutions.whenthen;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.InsetDrawable;
import android.widget.Button;

/** Keeps Android dialog behavior while using the same brick shell and actions as the app. */
public final class BrickDialogBuilder extends AlertDialog.Builder {
  public BrickDialogBuilder(Context context) { super(context); }

  @Override public AlertDialog show() {
    AlertDialog dialog = super.show();
    Context context = getContext();
    if (dialog.getWindow() != null) {
      dialog.getWindow().setBackgroundDrawable(new InsetDrawable(
          BrickStyle.block(context, Color.WHITE), 0, BrickStyle.dp(context, 12), 0, 0));
      dialog.getWindow().getDecorView().setElevation(0);
    }
    for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
      Button button = dialog.getButton(which);
      if (button != null) BrickStyle.button(button, BrickStyle.BLUE);
    }
    return dialog;
  }
}
