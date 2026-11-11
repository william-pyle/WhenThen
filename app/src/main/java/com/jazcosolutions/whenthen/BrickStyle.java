package com.jazcosolutions.whenthen;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.Switch;

/** Shared palette and controls from the owner's flat-brick design. */
public final class BrickStyle {
  public static final int BACKGROUND = Color.WHITE, INK = Color.BLACK, MUTED = 0xFF333333;
  public static final int WHEN = 0xFFFFC000, THEN = 0xFFB99EE7, BLUE = 0xFF0070C0;
  public static final int ENABLED = 0xFF00B050, DISABLED = 0xFFC00000;
  public static final int ENABLED_BODY = 0xFFD0F2E2;
  private BrickStyle() {}
  public static int dp(Context c, float value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }
  public static BlockDrawable block(Context c, int color) {
    return new BlockDrawable(color, c.getResources().getDisplayMetrics().density);
  }
  public static GradientDrawable panel(Context c, int color) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setStroke(dp(c, 1.5f), INK);
    return background;
  }
  public static void button(Button button, int color) {
    Context c = button.getContext();
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {0xFF444444, INK}));
    button.setBackground(block(c, color));
    button.setMinHeight(dp(c, 56));
    button.setMinimumWidth(0);
    button.setMinWidth(0);
    button.setPadding(dp(c, 10), dp(c, 20), dp(c, 10), dp(c, 8));
    button.setStateListAnimator(null);
  }
  public static void toggle(Switch control, boolean enabled) {
    Context c = control.getContext();
    int color = enabled ? ENABLED : DISABLED;
    GradientDrawable track = new GradientDrawable();
    track.setColor(INK);
    track.setCornerRadius(dp(c, 20));
    track.setSize(dp(c, 52), dp(c, 26));
    GradientDrawable thumb = new GradientDrawable();
    thumb.setShape(GradientDrawable.OVAL);
    thumb.setColor(color);
    thumb.setStroke(dp(c, 2), INK);
    thumb.setSize(dp(c, 26), dp(c, 26));
    control.setTrackDrawable(track);
    control.setThumbDrawable(thumb);
    control.setSplitTrack(false);
    control.setShowText(false);
    control.setSwitchMinWidth(dp(c, 54));
    control.setText("");
    control.setPadding(0, 0, 0, 0);
    control.setMinimumHeight(dp(c, 28));
  }
}
