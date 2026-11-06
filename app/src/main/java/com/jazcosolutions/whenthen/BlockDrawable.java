package com.jazcosolutions.whenthen;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Soft satin brick faces and round studs matching the approved app artwork. */
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
    float top = b.top + 19 * d;
    paint.setShader(null);
    paint.setColor(Color.argb(24, 35, 16, 65));
    c.drawRoundRect(
        new RectF(b.left + 2 * d, top + 3 * d, b.right, b.bottom), 10 * d, 10 * d, paint);
    paint.setColor(Color.WHITE);
    paint.setShader(
        new LinearGradient(
            0, top, 0, b.bottom, tint(color, 0.08f), tint(color, -0.06f), Shader.TileMode.CLAMP));
    c.drawRoundRect(new RectF(b.left, top, b.right - 2 * d, b.bottom - 4 * d), 9 * d, 9 * d, paint);
    paint.setShader(null);
    paint.setColor(tint(color, -0.16f));
    c.drawRoundRect(
        new RectF(b.left + 3 * d, b.bottom - 11 * d, b.right - 4 * d, b.bottom - 4 * d),
        4 * d,
        4 * d,
        paint);
    paint.setColor(Color.argb(50, 255, 255, 255));
    c.drawRoundRect(
        new RectF(b.left + 9 * d, top + 4 * d, b.right - 11 * d, top + 6 * d), 2 * d, 2 * d, paint);
    int studs = Math.min(4, Math.max(2, (int) (b.width() / (66 * d))));
    for (int i = 0; i < studs; i++) {
      float left = b.left + (23 + 42 * i) * d;
      paint.setColor(Color.WHITE);
      paint.setShader(
          new LinearGradient(
              left,
              0,
              left + 29 * d,
              0,
              tint(color, -0.06f),
              tint(color, -0.20f),
              Shader.TileMode.CLAMP));
      c.drawRoundRect(
          new RectF(left, b.top + 9 * d, left + 29 * d, top + 8 * d), 7 * d, 7 * d, paint);
      paint.setShader(
          new LinearGradient(
              0,
              b.top + 3 * d,
              0,
              b.top + 17 * d,
              tint(color, 0.17f),
              color,
              Shader.TileMode.CLAMP));
      c.drawOval(new RectF(left, b.top + 3 * d, left + 29 * d, b.top + 17 * d), paint);
      paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(d);
      paint.setColor(Color.argb(60, 255, 255, 255));
      c.drawArc(
          new RectF(left + d, b.top + 3 * d, left + 28 * d, b.top + 16 * d),
          185,
          150,
          false,
          paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }

  private static int tint(int color, float amount) {
    int target = amount >= 0 ? 255 : 0;
    float a = Math.abs(amount);
    return Color.rgb(
        Math.round(Color.red(color) * (1 - a) + target * a),
        Math.round(Color.green(color) * (1 - a) + target * a),
        Math.round(Color.blue(color) * (1 - a) + target * a));
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
