package io.github.cryptid11.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Draws a sequence of fixes as a north-up track over an OpenStreetMap background, fading from
 * oldest to newest. Drag to pan, pinch to zoom, double-tap to fit, tap a point to select it.
 * Positions are kept in Web Mercator "world" units: 0..1 across the whole map.
 */
public final class TrackView extends View {
    interface OnSelect {
        /** {@code index} is -1 when the selection is cleared. */
        void onSelect(int index);
    }

    private static final double EQUATOR_M = 40_075_016.686;
    /** A day spent in one spot still gets a readable scale. */
    private static final double MIN_SPAN_M = 300;
    /** Pixels per world unit: from a continental view down to a few houses. */
    private static final double MIN_SCALE = Tiles.SIZE * 16.0;
    private static final double MAX_SCALE = Tiles.SIZE * (double) (1 << 21);
    /** Consecutive fixes this close together, over at least this long, are drawn as one stay. */
    private static final double STAY_RADIUS_M = 75;
    private static final long STAY_MIN_MS = 20 * 60_000L;
    /** How many zoom levels up to look for a stand-in while a tile is missing. */
    private static final int MAX_STAND_IN_LEVELS = 5;

    // The map is light whatever the theme, so the overlay colours are fixed.
    private static final int LAND = 0xFFE9E7E2;
    private static final int TRACK = 0xFFC2185B;
    private static final int INK = 0xFF1B1B1F;
    private static final int PAPER = 0xFFFFFFFF;
    /** The latest known position, in the blue maps apps use for "you are here". */
    private static final int HERE = 0xFF1A73E8;

    private final float dp = getResources().getDisplayMetrics().density;
    private final Tiles tiles = Tiles.get(getContext());
    private final Paint map = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private final List<RectF> placed = new ArrayList<>();
    /** Per point: whether its label is already on the current frame. */
    private boolean[] drawn = {};
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;

    private long[] times = {};
    private int[] zones = {};
    private double[] worldX = {};
    private double[] worldY = {};
    /** Per point: its time, a time range on the newest point of a stay, null on the rest of a stay. */
    private String[] labels = {};
    private boolean[] stays = {};
    /** Length of the window in days, which decides how times name their day. */
    private int spanDays = 1;
    /** Whether the newest point is the latest known position rather than the end of a past window. */
    private boolean live;
    /** Per point: the point that is drawn and labelled for it (itself unless part of a stay). */
    private int[] anchors = {};
    private float[] sx = {};
    private float[] sy = {};

    private double minX, maxX, minY, maxY;
    private double centreX = 0.5;
    private double centreY = 0.5;
    private double scale = MIN_SCALE;
    private int selected = -1;
    /** Guessed mode per stretch between fixes, or null when the overlay is off. */
    private int[] modes;
    private double[] metresByMode;
    private final Paint legendBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private OnSelect onSelect;

    public TrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        casing.setColor(PAPER);
        casing.setStrokeWidth(6 * dp);
        casing.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(TRACK);
        line.setStrokeWidth(3.5f * dp);
        line.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(TRACK);
        ring.setColor(INK);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        text.setColor(INK);
        text.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        halo.set(text);
        halo.setColor(PAPER);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(4 * dp);

        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                double next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * d.getScaleFactor()));
                // Keep the spot under the fingers where it is.
                double fx = d.getFocusX() - getWidth() / 2.0, fy = d.getFocusY() - getHeight() / 2.0;
                centreX += fx / scale - fx / next;
                centreY += fy / scale - fy / next;
                scale = next;
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
                centreX += dx / scale;
                centreY += dy / scale;
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

    void setOnSelect(OnSelect listener) {
        onSelect = listener;
    }

    /** Colours each stretch by its {@link Modes} guess and shows a legend; null turns it off. */
    void setModes(int[] stretchModes, double[] distances) {
        modes = stretchModes;
        metresByMode = distances;
        invalidate();
    }

    /**
     * Arrays are parallel and ordered oldest first; {@code tz} holds UTC offsets in minutes.
     * {@code days} is the window's length (see {@link #time}), and {@code latest} marks the
     * newest point as where the user is now.
     */
    void setPoints(long[] t, int[] tz, double[] lat, double[] lon, int days, boolean latest) {
        int n = t.length;
        times = t;
        zones = tz;
        spanDays = days;
        live = latest;
        worldX = new double[n];
        worldY = new double[n];
        sx = new float[n];
        sy = new float[n];
        labels = new String[n];
        stays = new boolean[n];
        anchors = new int[n];
        for (int i = 0; i < n; i++) {
            worldX[i] = Tiles.worldX(lon[i]);
            worldY[i] = Tiles.worldY(lat[i]);
            minX = i == 0 ? worldX[i] : Math.min(minX, worldX[i]);
            maxX = i == 0 ? worldX[i] : Math.max(maxX, worldX[i]);
            minY = i == 0 ? worldY[i] : Math.min(minY, worldY[i]);
            maxY = i == 0 ? worldY[i] : Math.max(maxY, worldY[i]);
        }
        double stayRadius = n == 0 ? 0 : STAY_RADIUS_M / metresPerUnit((minY + maxY) / 2);
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && Math.hypot(worldX[j + 1] - worldX[i], worldY[j + 1] - worldY[i]) < stayRadius) j++;
            if (t[j] - t[i] >= STAY_MIN_MS) {
                labels[j] = time(t[i], tz[i], spanDays) + "–"
                        + time(t[j], tz[j], localDay(t[j], tz[j]) != localDay(t[i], tz[i]) ? spanDays : 1);
                stays[j] = true;
                // Coming back to a place lists both visits on its newest marker.
                for (int k = 0; k < i; k++) {
                    if (stays[k] && Math.hypot(worldX[k] - worldX[j], worldY[k] - worldY[j]) < stayRadius) {
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
                    labels[k] = time(t[k], tz[k], spanDays);
                    anchors[k] = k;
                }
            }
            i = j + 1;
        }
        selected = -1;
        fit();
    }

    /** Metres per world unit at a given height on the map; Mercator stretches towards the poles. */
    private static double metresPerUnit(double worldY) {
        return EQUATOR_M * Math.cos(Math.toRadians(Tiles.latitude(worldY)));
    }

    /** The first point of the visit a point stands for: itself unless it marks a stay. */
    int arrival(int index) {
        int first = index;
        while (first > 0 && anchors[first - 1] == anchors[index]) first--;
        return first;
    }

    /** The time of a point, or the time range when it stands for a stay. */
    String label(int index) {
        return labels[index] != null ? labels[index] : time(times[index], zones[index], spanDays);
    }

    private void fit() {
        if (times.length > 0 && getWidth() > 0) {
            centreX = (minX + maxX) / 2;
            centreY = (minY + maxY) / 2;
            double minSpan = MIN_SPAN_M / metresPerUnit(centreY);
            float margin = 48 * dp;
            scale = Math.min((getWidth() - 2 * margin) / Math.max(minSpan, maxX - minX),
                    (getHeight() - 2 * margin) / Math.max(minSpan, maxY - minY));
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        fit();
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

    static String clock(long utcMillis, int tzMinutes) {
        long minute = Math.floorMod(Math.floorDiv(utcMillis, 60_000L) + tzMinutes, 1440L);
        return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    /**
     * The {@link #clock} time in a window of {@code days} days: alone for one day, after the short
     * weekday for up to a week, and after the date ("14 Oct") beyond, where weekdays repeat.
     */
    static String time(long utcMillis, int tzMinutes, int days) {
        if (days <= 1) return clock(utcMillis, tzMinutes);
        LocalDate day = LocalDate.ofEpochDay(localDay(utcMillis, tzMinutes));
        String name = days <= 7 ? day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                : DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(day);
        return name + " " + clock(utcMillis, tzMinutes);
    }

    private static long localDay(long utcMillis, int tzMinutes) {
        return Math.floorDiv(Math.floorDiv(utcMillis, 60_000L) + tzMinutes, 1440L);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(LAND);
        int n = times.length;
        if (n == 0) return;
        drawMap(canvas);

        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        for (int i = 0; i < n; i++) {
            sx[i] = (float) (cx + (worldX[i] - centreX) * scale);
            sy[i] = (float) (cy + (worldY[i] - centreY) * scale);
        }
        for (int i = 1; i < n; i++) canvas.drawLine(sx[i - 1], sy[i - 1], sx[i], sy[i], casing);
        for (int i = 1; i < n; i++) {
            boolean byMode = modes != null && i - 1 < modes.length;
            line.setColor(byMode ? Modes.color(modes[i - 1]) : TRACK);
            line.setAlpha(byMode ? 255 : alpha(i, n));
            canvas.drawLine(sx[i - 1], sy[i - 1], sx[i], sy[i], line);
        }
        for (int i = 0; i < n; i++) {
            dot.setAlpha(alpha(i, n));
            canvas.drawCircle(sx[i], sy[i], (stays[i] ? 6f : 3.5f) * dp, dot);
        }
        // The oldest point is an open circle. The newest is solid, and blue with a halo when it is
        // where the user is now; it goes on top in case they meet.
        dot.setAlpha(255);
        dot.setColor(PAPER);
        canvas.drawCircle(sx[0], sy[0], 6 * dp, dot);
        canvas.drawCircle(sx[0], sy[0], 6 * dp, ring);
        if (live) {
            dot.setColor(HERE);
            dot.setAlpha(56);
            canvas.drawCircle(sx[n - 1], sy[n - 1], 20 * dp, dot);
            dot.setAlpha(255);
        }
        dot.setColor(PAPER);
        canvas.drawCircle(sx[n - 1], sy[n - 1], 9.5f * dp, dot);
        dot.setColor(live ? HERE : INK);
        canvas.drawCircle(sx[n - 1], sy[n - 1], 7 * dp, dot);
        dot.setColor(TRACK);
        if (selected >= 0 && selected < n) {
            canvas.drawCircle(sx[selected], sy[selected], 12 * dp, ring);
        }

        drawTimes(canvas, n);
        drawScaleBar(canvas);
        if (modes != null) drawLegend(canvas);
        String credit = getResources().getString(R.string.map_credit);
        float creditX = getWidth() - 8 * dp - text.measureText(credit), creditY = getHeight() - 8 * dp;
        canvas.drawText(credit, creditX, creditY, halo);
        canvas.drawText(credit, creditX, creditY, text);
    }

    private static int alpha(int i, int n) {
        return n < 2 ? 255 : 110 + 145 * i / (n - 1);
    }

    /**
     * Covers the view with tiles of the zoom level whose tiles come out closest to their
     * intended size on this screen. A tile that is not in memory is requested, and until it
     * arrives the matching part of a lower-zoom tile is stretched over its place.
     */
    private void drawMap(Canvas canvas) {
        double tileScreen = Tiles.SIZE * dp * 0.6;
        int z = (int) Math.floor(Math.log(scale / tileScreen) / Math.log(2));
        z = Math.max(0, Math.min(Tiles.MAX_ZOOM, z));
        int count = 1 << z;
        double size = scale / count;
        double left = centreX * scale - getWidth() / 2.0, top = centreY * scale - getHeight() / 2.0;
        int x0 = (int) Math.floor(left / size), x1 = (int) Math.floor((left + getWidth()) / size);
        int y0 = Math.max(0, (int) Math.floor(top / size));
        int y1 = Math.min(count - 1, (int) Math.floor((top + getHeight()) / size));
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int x = Math.floorMod(tx, count);
                target.set((float) (tx * size - left), (float) (ty * size - top),
                        (float) ((tx + 1) * size - left), (float) ((ty + 1) * size - top));
                Bitmap tile = tiles.cached(z, x, ty);
                if (tile != null) {
                    canvas.drawBitmap(tile, null, target, map);
                    continue;
                }
                tiles.load(z, x, ty, this::postInvalidate);
                for (int up = 1; up <= Math.min(z, MAX_STAND_IN_LEVELS); up++) {
                    Bitmap parent = tiles.cached(z - up, x >> up, ty >> up);
                    if (parent == null) continue;
                    int part = Tiles.SIZE >> up;
                    int px = (x & ((1 << up) - 1)) * part, py = (ty & ((1 << up) - 1)) * part;
                    source.set(px, py, px + part, py + part);
                    canvas.drawBitmap(parent, source, target, map);
                    break;
                }
            }
        }
    }

    /**
     * Labels the selected point first, then stays (newest first), then the newest and oldest
     * points, then whatever still fits. Labels that would overlap one already drawn are left
     * out, so zooming out shows fewer labels instead of piling them up away from their points.
     */
    private void drawTimes(Canvas canvas, int n) {
        placed.clear();
        if (drawn.length != n) drawn = new boolean[n];
        Arrays.fill(drawn, false);
        if (selected >= 0 && selected < n) drawLabel(canvas, selected, true);
        for (int i = n - 1; i >= 0; i--) {
            if (stays[i]) drawLabel(canvas, i, false);
        }
        drawLabel(canvas, n - 1, false);
        drawLabel(canvas, 0, false);
        for (int i = 1; i < n - 1; i++) drawLabel(canvas, i, false);
    }

    /**
     * Draws the label beside its point, on the left when the right would run off the view.
     * Each point is labelled at most once; {@code always} draws it even over another label.
     */
    private void drawLabel(Canvas canvas, int i, boolean always) {
        if (labels[i] == null || drawn[i]) return;
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
        if (!always && collides(box)) return;
        placed.add(box);
        drawn[i] = true;
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

    /** Top-left box listing each guessed mode present, with its colour and distance. */
    private void drawLegend(Canvas canvas) {
        List<String> rows = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] names = getResources().getStringArray(R.array.mode_names);
        for (int m : Modes.ORDER) {
            if (m == Modes.STILL || metresByMode == null || metresByMode[m] < 1) continue;
            rows.add(Modes.icon(m) + " " + names[m] + " · " + distance(metresByMode[m]));
            colors.add(Modes.color(m));
        }
        if (rows.isEmpty()) return;
        float pad = 8 * dp, lineHeight = text.getTextSize() * 1.45f, swatch = 14 * dp, width = 0;
        for (String r : rows) width = Math.max(width, text.measureText(r));
        RectF box = new RectF(8 * dp, 8 * dp, 8 * dp + 2 * pad + swatch + 6 * dp + width,
                8 * dp + 2 * pad + rows.size() * lineHeight);
        legendBox.setColor(0xE6FFFFFF);
        canvas.drawRoundRect(box, 8 * dp, 8 * dp, legendBox);
        for (int i = 0; i < rows.size(); i++) {
            float y = box.top + pad + (i + 0.75f) * lineHeight;
            dot.setColor(colors.get(i));
            dot.setAlpha(255);
            canvas.drawCircle(box.left + pad + swatch / 2, y - text.getTextSize() * 0.35f, swatch / 2, dot);
            canvas.drawText(rows.get(i), box.left + pad + swatch + 6 * dp, y, text);
        }
        dot.setColor(TRACK);
    }

    private void drawScaleBar(Canvas canvas) {
        double pixelsPerMetre = scale / metresPerUnit(centreY);
        double goal = getWidth() * 0.25 / pixelsPerMetre;
        double unit = Math.pow(10, Math.floor(Math.log10(goal)));
        double metres = (goal >= 5 * unit ? 5 : goal >= 2 * unit ? 2 : 1) * unit;
        float left = 16 * dp, y = getHeight() - 16 * dp, right = left + (float) (metres * pixelsPerMetre);
        casing.setStrokeCap(Paint.Cap.SQUARE);
        canvas.drawLine(left, y, right, y, casing);
        casing.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left, y, right, y, ring);
        canvas.drawLine(left, y - 4 * dp, left, y + 4 * dp, ring);
        canvas.drawLine(right, y - 4 * dp, right, y + 4 * dp, ring);
        canvas.drawText(distance(metres), left, y - 8 * dp, halo);
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
