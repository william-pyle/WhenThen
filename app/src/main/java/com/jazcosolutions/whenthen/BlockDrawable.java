package com.jazcosolutions.whenthen;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Flat outlined construction brick, shared by every app surface and control. */
public final class BlockDrawable extends Drawable {
  public static final float STUD_HEIGHT = 12f;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path outline = new Path();
  private final int color;
  private final float d;
  private int alpha = 255;
  private float gapStart = -1, gapRightInset;

  public BlockDrawable(int color, float density) {
    this.color = color;
    d = density;
  }

  /** Leave a clear connection beneath another brick while keeping the outer studs. */
  public BlockDrawable studGap(float leftDp, float rightInsetDp) {
    gapStart = leftDp * d;
    gapRightInset = rightInsetDp * d;
    return this;
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    float stroke = 1.5f * d, inset = stroke / 2;
    float left = b.left + inset, right = b.right - inset;
    float high = b.top + inset, top = high + STUD_HEIGHT * d, bottom = b.bottom - inset;
    if (right <= left || bottom <= top) return;
    float stud = Math.min(28 * d, (right - left) * .5f), radius = 3 * d;
    int count = Math.max(1, (int) ((right - left - 24 * d) / (56 * d)) + 1);
    float first = count == 1 ? left + (right - left - stud) / 2 : left + 12 * d;
    float pitch = count == 1 ? 0 : (right - left - 24 * d - stud) / (count - 1);
    outline.reset();
    outline.moveTo(left, top);
    for (int i = 0; i < count; i++) {
      float x = first + i * pitch;
      if (gapStart >= 0 && x + stud / 2 > b.left + gapStart && x + stud / 2 < b.right - gapRightInset) continue;
      outline.lineTo(x, top);
      outline.lineTo(x, high + radius);
      outline.quadTo(x, high, x + radius, high);
      outline.lineTo(x + stud - radius, high);
      outline.quadTo(x + stud, high, x + stud, high + radius);
      outline.lineTo(x + stud, top);
    }
    outline.lineTo(right, top);
    outline.lineTo(right, bottom);
    outline.lineTo(left, bottom);
    outline.close();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(color);
    paint.setAlpha(alpha);
    canvas.drawPath(outline, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(stroke);
    paint.setStrokeJoin(Paint.Join.MITER);
    paint.setColor(Color.BLACK);
    paint.setAlpha(alpha);
    canvas.drawPath(outline, paint);
  }

  @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
  @Override public void setColorFilter(ColorFilter value) { paint.setColorFilter(value); invalidateSelf(); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
