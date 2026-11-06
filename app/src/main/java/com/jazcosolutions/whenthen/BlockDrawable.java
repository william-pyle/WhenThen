package com.jazcosolutions.whenthen;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** A tactile card with actual connector studs, not a flat section heading. */
public final class BlockDrawable extends Drawable {
  private final Paint paint = new Paint(3);
  private final int color;
  private final float d;

  public BlockDrawable(int color, float density) {
    this.color = color;
    d = density;
  }

  @Override
  public void draw(Canvas c) {
    Rect b = getBounds();
    float top = b.top + 10 * d;
    paint.setColor(color);
    c.drawRoundRect(new RectF(b.left, top, b.right, b.bottom - 4 * d), 20 * d, 20 * d, paint);
    for (int i = 0; i < 2; i++) {
      float left = b.left + (28 + 38 * i) * d;
      c.drawRoundRect(new RectF(left, b.top, left + 25 * d, top + 8 * d), 6 * d, 6 * d, paint);
    }
    paint.setColor(Color.argb(35, 0, 0, 0));
    c.drawRoundRect(
        new RectF(b.left + 3 * d, b.bottom - 10 * d, b.right - 3 * d, b.bottom),
        14 * d,
        14 * d,
        paint);
    paint.setColor(Color.argb(36, 255, 255, 255));
    c.drawRoundRect(
        new RectF(b.left + 12 * d, top + 6 * d, b.right - 12 * d, top + 9 * d),
        2 * d,
        2 * d,
        paint);
  }

  @Override
  public void setAlpha(int a) {
    paint.setAlpha(a);
  }

  @Override
  public void setColorFilter(ColorFilter f) {
    paint.setColorFilter(f);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
