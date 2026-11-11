package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import android.graphics.*;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Native drawable previews for human review; the current flat artwork remains editable as SVG. */
@RunWith(AndroidJUnit4.class)
public class IconRenderingTest {
  @Test
  public void adaptiveIconPreservesBothBricksAtMultipleSizes() throws Exception {
    assertTrue(Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Bitmap sheet = Bitmap.createBitmap(960, 520, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(sheet);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    String[] labels = {"Android mask", "Circle", "Rounded square"};
    for (int row = 0; row < 2; row++) {
      paint.setColor(row == 0 ? 0xFFF7F6FC : 0xFF211A36);
      canvas.drawRect(0, row * 260, 960, (row + 1) * 260, paint);
      for (int col = 0; col < 3; col++) {
        paint.setColor(row == 0 ? 0xFF25213D : Color.WHITE);
        paint.setTextSize(18);
        canvas.drawText(labels[col] + " · 128 / 48 / 32 px", col * 320 + 12, row * 260 + 30, paint);
        int x = col * 320 + 16;
        for (int size : new int[] {128, 48, 32}) {
          var icon = (AdaptiveIconDrawable) context.getDrawable(R.mipmap.ic_launcher).mutate();
          icon.setBounds(0, 0, size, size);
          Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
          Canvas iconCanvas = new Canvas(output);
          if (col == 0) icon.draw(iconCanvas);
          else {
            Path mask = new Path();
            if (col == 1) mask.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            else mask.addRoundRect(0, 0, size, size, size * .22f, size * .22f, Path.Direction.CW);
            iconCanvas.clipPath(mask);
            icon.getBackground().draw(iconCanvas);
            icon.getForeground().draw(iconCanvas);
          }
          int orange = 0, purple = 0;
          int minX = size, minY = size, maxX = 0, maxY = 0;
          for (int y = 0; y < size; y++)
            for (int xx = 0; xx < size; xx++) {
              int pixel = output.getPixel(xx, y);
              boolean colored = (Color.red(pixel) > 190 && Color.green(pixel) > 70 && Color.blue(pixel) < 110)
                  || (Color.red(pixel) > 60 && Color.blue(pixel) > 150 && Color.green(pixel) < 200);
              if (colored) { minX = Math.min(minX, xx); minY = Math.min(minY, y); maxX = Math.max(maxX, xx); maxY = Math.max(maxY, y); }
              if (Color.red(pixel) > 190 && Color.green(pixel) > 70 && Color.blue(pixel) < 110)
                orange++;
              if (Color.red(pixel) > 60 && Color.blue(pixel) > 150 && Color.green(pixel) < 200)
                purple++;
            }
          assertTrue("Yellow brick visible at " + size, orange > size * size * .08);
          assertTrue("Purple brick visible at " + size, purple > size * size * .06);
          assertTrue("Whole brick silhouette stays inside the launcher mask at " + size,
              minX >= size * .05f && minY >= size * .05f && maxX < size * .95f && maxY < size * .95f);
          canvas.drawBitmap(output, x, row * 260 + 60, paint);
          x += size + 15;
        }
      }
    }
    try (FileOutputStream output =
        new FileOutputStream(new File(context.getFilesDir(), "icon-mask-review.png"))) {
      sheet.compress(Bitmap.CompressFormat.PNG, 100, output);
    }
  }
}
