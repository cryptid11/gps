package io.github.cryptid11.gps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A seek bar track over the fixes of a route, coloured by the {@link Modes} of each stretch and
 * faded outside the chosen range. Stretch i sits between the thumb positions of fixes i and i + 1.
 */
final class ModeStrip extends Drawable {
    private static final int FADED = 70;

    private final Paint paint = new Paint();
    private final Path clip = new Path();
    private final float height;
    private final int[] modes;
    private int first;
    private int last;

    ModeStrip(Context c, int[] stretchModes) {
        height = 10 * c.getResources().getDisplayMetrics().density;
        modes = stretchModes;
        last = modes.length;
    }

    /** Highlights the stretches between fixes {@code from} and {@code to}. */
    void select(int from, int to) {
        first = from;
        last = to;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        int n = modes.length;
        Rect r = getBounds();
        if (n == 0 || r.isEmpty()) return;
        float top = r.exactCenterY() - height / 2, bottom = top + height;
        clip.rewind();
        clip.addRoundRect(r.left, top, r.right, bottom, height / 2, height / 2, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        // One rectangle per run of stretches with the same mode and the same side of the range.
        for (int i = 0; i < n; ) {
            boolean in = i >= first && i < last;
            int j = i + 1;
            while (j < n && modes[j] == modes[i] && (j >= first && j < last) == in) j++;
            paint.setColor(Modes.color(modes[i]));
            paint.setAlpha(in ? 255 : FADED);
            canvas.drawRect(r.left + r.width() * (float) i / n, top, r.left + r.width() * (float) j / n, bottom, paint);
            i = j;
        }
        canvas.restore();
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(height);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter filter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
