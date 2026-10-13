package io.github.cryptid11.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a sequence of fixes as a north-up track, fading from oldest to newest. There is no
 * map underneath, which keeps the app offline. Drag to pan, pinch to zoom, double-tap to
 * fit, tap a point to select it.
 */
public final class TrackView extends View {
    interface OnSelect {
        /** {@code index} is -1 when the selection is cleared. */
        void onSelect(int index);
    }

    private static final double METRES_PER_DEGREE = 111_320;
    /** A day spent in one spot still gets a readable scale. */
    private static final double MIN_SPAN_M = 300;
    private static final float MAX_ZOOM = 500f;
    /** Consecutive fixes this close together, over at least this long, are drawn as one stay. */
    private static final double STAY_RADIUS_M = 75;
    private static final long STAY_MIN_MS = 20 * 60_000L;

    private final float dp = getResources().getDisplayMetrics().density;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    private long[] times = {};
    private int[] zones = {};
    /** Metres east and north of the middle of the track. */
    private double[] east = {};
    private double[] north = {};
    /** Per point: its time, a time range on the newest point of a stay, null on the rest of a stay. */
    private String[] labels = {};
    private boolean[] stays = {};
    /** Per point: the point that is drawn and labelled for it (itself unless part of a stay). */
    private int[] anchors = {};
    private float[] sx = {};
    private float[] sy = {};
    private double spanEast = MIN_SPAN_M;
    private double spanNorth = MIN_SPAN_M;

    private final List<RectF> placed = new ArrayList<>();
    private float zoom = 1f;
    private float panX;
    private float panY;
    private int selected = -1;
    private OnSelect onSelect;

    public TrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int accent = themeColor(android.R.attr.colorAccent);
        int foreground = themeColor(android.R.attr.textColorPrimary);
        line.setColor(accent);
        line.setStrokeWidth(3 * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(accent);
        ring.setColor(foreground);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        text.setColor(foreground);
        text.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        halo.set(text);
        halo.setColor(themeColor(android.R.attr.colorBackground));
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(4 * dp);

        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float next = Math.max(1f, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                float factor = next / zoom;
                // Keep the spot under the fingers where it is.
                float fx = d.getFocusX() - getWidth() / 2f;
                float fy = d.getFocusY() - getHeight() / 2f;
                panX = fx - (fx - panX) * factor;
                panY = fy - (fy - panY) * factor;
                zoom = next;
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panX -= dx;
                panY -= dy;
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fit();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                select(nearest(e.getX(), e.getY()));
                return true;
            }
        });
    }

    private int themeColor(int attr) {
        TypedArray a = getContext().obtainStyledAttributes(new int[] {attr});
        try {
            return a.getColor(0, Color.GRAY);
        } finally {
            a.recycle();
        }
    }

    void setOnSelect(OnSelect listener) {
        onSelect = listener;
    }

    /** Arrays are parallel and ordered oldest first; {@code zones} holds UTC offsets in minutes. */
    void setPoints(long[] t, int[] tz, double[] lat, double[] lon) {
        int n = t.length;
        times = t;
        zones = tz;
        east = new double[n];
        north = new double[n];
        sx = new float[n];
        sy = new float[n];
        spanEast = spanNorth = MIN_SPAN_M;
        if (n > 0) {
            double minLat = lat[0], maxLat = lat[0], minLon = lon[0], maxLon = lon[0];
            for (int i = 1; i < n; i++) {
                minLat = Math.min(minLat, lat[i]);
                maxLat = Math.max(maxLat, lat[i]);
                minLon = Math.min(minLon, lon[i]);
                maxLon = Math.max(maxLon, lon[i]);
            }
            double midLat = (minLat + maxLat) / 2, midLon = (minLon + maxLon) / 2;
            double metresPerLonDegree = METRES_PER_DEGREE * Math.cos(Math.toRadians(midLat));
            for (int i = 0; i < n; i++) {
                east[i] = (lon[i] - midLon) * metresPerLonDegree;
                north[i] = (lat[i] - midLat) * METRES_PER_DEGREE;
            }
            spanEast = Math.max(MIN_SPAN_M, (maxLon - minLon) * metresPerLonDegree);
            spanNorth = Math.max(MIN_SPAN_M, (maxLat - minLat) * METRES_PER_DEGREE);
        }
        labels = new String[n];
        stays = new boolean[n];
        anchors = new int[n];
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && Math.hypot(east[j + 1] - east[i], north[j + 1] - north[i]) < STAY_RADIUS_M) j++;
            if (t[j] - t[i] >= STAY_MIN_MS) {
                labels[j] = clock(t[i], tz[i]) + "–" + clock(t[j], tz[j]);
                stays[j] = true;
                // Coming back to a place lists both visits on its newest marker.
                for (int k = 0; k < i; k++) {
                    if (stays[k] && Math.hypot(east[k] - east[j], north[k] - north[j]) < STAY_RADIUS_M) {
                        labels[j] = labels[k] + "\n" + labels[j];
                        labels[k] = null;
                        stays[k] = false;
                        for (int m = 0; m < i; m++) {
                            if (anchors[m] == k) anchors[m] = j;
                        }
                    }
                }
                for (int k = i; k <= j; k++) anchors[k] = j;
            } else {
                for (int k = i; k <= j; k++) {
                    labels[k] = clock(t[k], tz[k]);
                    anchors[k] = k;
                }
            }
            i = j + 1;
        }
        selected = -1;
        fit();
    }

    /** The time of a point, or the time range when it stands for a stay. */
    String label(int index) {
        return labels[index] != null ? labels[index] : clock(times[index], zones[index]);
    }

    private void fit() {
        zoom = 1f;
        panX = panY = 0f;
        invalidate();
    }

    private void select(int index) {
        selected = index;
        if (onSelect != null) onSelect.onSelect(index);
        invalidate();
    }

    private int nearest(float x, float y) {
        int found = -1;
        float best = 28 * dp;
        for (int i = 0; i < sx.length; i++) {
            float d = (float) Math.hypot(sx[i] - x, sy[i] - y);
            // Newer points win ties, so overlapping fixes resolve to the latest.
            if (d <= best) {
                best = d;
                found = i;
            }
        }
        return found < 0 ? -1 : anchors[found];
    }

    /** Pixels per metre at the current zoom. */
    private double pixelsPerMetre() {
        float margin = 40 * dp;
        double fit = Math.min((getWidth() - 2 * margin) / spanEast, (getHeight() - 2 * margin) / spanNorth);
        return Math.max(fit, 1e-9) * zoom;
    }

    static String clock(long utcMillis, int tzMinutes) {
        long minute = Math.floorMod(Math.floorDiv(utcMillis, 60_000L) + tzMinutes, 1440L);
        return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = times.length;
        if (n == 0) return;
        double scale = pixelsPerMetre();
        float cx = getWidth() / 2f + panX, cy = getHeight() / 2f + panY;
        for (int i = 0; i < n; i++) {
            sx[i] = (float) (cx + east[i] * scale);
            sy[i] = (float) (cy - north[i] * scale);
        }

        for (int i = 1; i < n; i++) {
            line.setAlpha(alpha(i, n));
            canvas.drawLine(sx[i - 1], sy[i - 1], sx[i], sy[i], line);
        }
        for (int i = 0; i < n; i++) {
            dot.setAlpha(alpha(i, n));
            canvas.drawCircle(sx[i], sy[i], (stays[i] ? 6f : 3.5f) * dp, dot);
        }
        // Newest position stands out; the oldest is an open ring.
        canvas.drawCircle(sx[0], sy[0], 6 * dp, ring);
        dot.setAlpha(255);
        canvas.drawCircle(sx[n - 1], sy[n - 1], 7 * dp, dot);
        canvas.drawCircle(sx[n - 1], sy[n - 1], 7 * dp, ring);
        if (selected >= 0 && selected < n) {
            canvas.drawCircle(sx[selected], sy[selected], 12 * dp, ring);
        }

        drawTimes(canvas, n);
        drawScaleBar(canvas, scale);
    }

    private static int alpha(int i, int n) {
        return n < 2 ? 255 : 70 + 185 * i / (n - 1);
    }

    /** Labels stays first, then the newest and oldest points, then whatever still fits. */
    private void drawTimes(Canvas canvas, int n) {
        placed.clear();
        for (int i = n - 1; i >= 0; i--) {
            if (stays[i]) drawLabel(canvas, i, true);
        }
        drawLabel(canvas, n - 1, false);
        drawLabel(canvas, 0, false);
        for (int i = 1; i < n - 1; i++) {
            if (!stays[i]) drawLabel(canvas, i, i == selected);
        }
    }

    /**
     * Draws the label beside its point, on the left when the right would run off the view.
     * A {@code required} label that collides moves down a line at a time instead of being dropped.
     */
    private void drawLabel(Canvas canvas, int i, boolean required) {
        if (labels[i] == null || (stays[i] && !required)) return;
        if (sx[i] < 0 || sy[i] < 0 || sx[i] > getWidth() || sy[i] > getHeight()) return;
        String[] lines = labels[i].split("\n");
        float width = 0;
        for (String l : lines) width = Math.max(width, text.measureText(l));
        float lineHeight = text.getTextSize() * 1.25f, space = 5 * dp;
        float x = sx[i] + 10 * dp;
        if (x + width > getWidth() - space) x = sx[i] - 10 * dp - width;
        float y = sy[i] - 7 * dp;
        RectF box = new RectF(x - space, y - lineHeight, x + width + space,
                y + (lines.length - 1) * lineHeight + space);
        for (int attempt = 0; collides(box); attempt++) {
            if (!required || attempt == 4) return;
            box.offset(0, lineHeight);
            y += lineHeight;
        }
        placed.add(box);
        for (String l : lines) {
            canvas.drawText(l, x, y, halo);
            canvas.drawText(l, x, y, text);
            y += lineHeight;
        }
    }

    private boolean collides(RectF box) {
        for (RectF other : placed) {
            if (RectF.intersects(other, box)) return true;
        }
        return false;
    }

    private void drawScaleBar(Canvas canvas, double scale) {
        double target = getWidth() * 0.25 / scale;
        double unit = Math.pow(10, Math.floor(Math.log10(target)));
        double metres = (target >= 5 * unit ? 5 : target >= 2 * unit ? 2 : 1) * unit;
        float left = 16 * dp, y = getHeight() - 16 * dp, right = left + (float) (metres * scale);
        canvas.drawLine(left, y, right, y, ring);
        canvas.drawLine(left, y - 4 * dp, left, y + 4 * dp, ring);
        canvas.drawLine(right, y - 4 * dp, right, y + 4 * dp, ring);
        canvas.drawText(distance(metres), left, y - 8 * dp, text);
    }

    static String distance(double metres) {
        if (metres < 1000) return String.format(Locale.US, "%.0f m", metres);
        double km = metres / 1000;
        boolean whole = km >= 10 || Math.abs(km - Math.rint(km)) < 0.05;
        return String.format(Locale.US, whole ? "%.0f km" : "%.1f km", km);
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        pinch.onTouchEvent(event);
        if (!pinch.isInProgress()) gestures.onTouchEvent(event);
        return true;
    }
}
