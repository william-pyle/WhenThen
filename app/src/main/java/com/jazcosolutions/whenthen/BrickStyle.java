package com.jazcosolutions.whenthen;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import android.widget.Switch;

/** Shared app surfaces and controls. The block drawable is reserved for the logo and Overview art. */
public final class BrickStyle {
  public static final int BACKGROUND = 0xFFF7F7FA, INK = 0xFF202127, MUTED = 0xFF62656E;
  public static final int WHEN = 0xFFFFC000, THEN = 0xFFB99EE7, BLUE = 0xFF315FAD;
  public static final int ENABLED = 0xFF25845B, DISABLED = 0xFFB94A49;
  public static final int ENABLED_BODY = 0xFFE8F5EF;
  public static final int SURFACE = Color.WHITE, SUBTLE = 0xFFF0F1F5, BORDER = 0xFFE4E5EA;
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
    background.setCornerRadius(dp(c, 18));
    background.setStroke(dp(c, 1), BORDER);
    return background;
  }

  public static GradientDrawable smallPanel(Context c, int color) {
    GradientDrawable background = new GradientDrawable();
    background.setColor(color);
    background.setCornerRadius(dp(c, 12));
    return background;
  }

  private static void styleButton(Button button, boolean primary) {
    Context c = button.getContext();
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    button.setTextColor(new ColorStateList(
        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
        new int[] {0xFF999CA4, primary ? Color.WHITE : BLUE}));
    GradientDrawable shape = smallPanel(c, primary ? BLUE : SUBTLE);
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(
        primary ? 0x55FFFFFF : 0x22315FAD), shape, null));
    button.setMinHeight(dp(c, 48));
    button.setMinimumWidth(0);
    button.setMinWidth(0);
    button.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
    button.setStateListAnimator(null);
  }
  public static void button(Button button, int color) { styleButton(button, true); }
  public static void secondaryButton(Button button) { styleButton(button, false); }

  public static void toggle(Switch control, boolean enabled) {
    control.setShowText(false);
    control.setText("");
    control.setThumbTintList(new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
        new int[] {BLUE, 0xFFF9F9FB}));
    control.setTrackTintList(new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
        new int[] {0xFF9CB7E4, 0xFFB9BBC3}));
    control.setMinimumHeight(dp(control.getContext(), 48));
  }
}
