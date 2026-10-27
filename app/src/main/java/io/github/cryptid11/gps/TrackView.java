package io.github.cryptid11.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

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

    interface OnPlace {
        void onPlace(int index);
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
    /** Dragging this far after a double tap zooms by one level: down in, up out. */
    private static final float QUICK_ZOOM_DP = 100;
    /** Wikipedia titles longer than this are cut short on the map. */
    private static final int PLACE_CHARS = 24;
    /** The heatmap is drawn at this fraction of the view's resolution, then scaled up. */
    private static final int HEAT_SHRINK = 4;
    private static final float HEAT_RADIUS_DP = 18;
    /** Heat one fix adds for each 10 minutes it stands for, and the longest it can stand for. */
    private static final double HEAT_PER_10_MIN = 0.1;
    private static final long HEAT_MAX_MS = 2 * 3600_000L;
    /** Heat colours by amount: transparent, then blue, cyan, green, yellow and red. */
    private static final int[] HEAT_COLOURS = heatColours();
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
    private final int touchSlop;
    private final int tapSlop;
    private final long tapMs = ViewConfiguration.getDoubleTapTimeout();
    /** Taps in the current run of quick taps, counting a finger that is still down. */
    private int taps;
    private long lastUp;
    private float tapX, tapY, downX, downY, lastX, lastY;
    /** The finger has moved too far for a tap. */
    private boolean moved;
    /** The finger came down for a second tap and stays down: dragging it zooms. */
    private boolean quickZoom;
    private double quickZoomFrom;
    /** The rest of this gesture does nothing, after a triple tap has reset the view. */
    private boolean swallow;
    private final Runnable tapsDone = this::tapsDone;

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
    private OnPlace onPlace;
    /** Wikipedia articles shown on the map, or empty. */
    private double[] placeX = {};
    private double[] placeY = {};
    private String[] placeNames = {};
    private float[] placeSx = {};
    private float[] placeSy = {};
    private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Whether the map shows a heatmap of where the time went instead of the track. */
    private boolean heat;
    /** Per point: the alpha of its heat blob, from the time until the next point. */
    private int[] heatAlphas = {};
    private Bitmap heatMask;
    private Bitmap heatImage;
    private Canvas heatCanvas;
    private int[] heatPixels;
    private Bitmap blob;
    private final Paint splat = new Paint();
    private final Paint heatPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** Whether the view has been fitted to the current points; resizing keeps the user's view. */
    private boolean fitted;

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
        pin.setColor(INK);
        pin.setTextAlign(Paint.Align.CENTER);
        pin.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        pin.setTextSize(11 * dp);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tapSlop = ViewConfiguration.get(context).getScaledDoubleTapSlop();

        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getFocusX(), d.getFocusY(), scale * d.getScaleFactor());
                return true;
            }
        });
        // Double-tap-and-drag zooming is done in onTouchEvent, around triple taps.
        pinch.setQuickScaleEnabled(false);
    }

    /** Zooms to {@code next} pixels per world unit, keeping the spot under (x, y) where it is. */
    private void zoomAt(float x, float y, double next) {
        next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, next));
        double fx = x - getWidth() / 2.0, fy = y - getHeight() / 2.0;
        centreX += fx / scale - fx / next;
        centreY += fy / scale - fy / next;
        scale = next;
        invalidate();
    }

    void setOnPlace(OnPlace listener) {
        onPlace = listener;
    }

    /** Shows Wikipedia articles as pins with their titles; empty arrays clear them. */
    void setPlaces(double[] lat, double[] lon, String[] names) {
        int n = names.length;
        placeX = new double[n];
        placeY = new double[n];
        placeSx = new float[n];
        placeSy = new float[n];
        placeNames = new String[n];
        for (int i = 0; i < n; i++) {
            placeX[i] = Tiles.worldX(lon[i]);
            placeY[i] = Tiles.worldY(lat[i]);
            placeNames[i] = names[i].length() <= PLACE_CHARS ? names[i]
                    : names[i].substring(0, PLACE_CHARS - 1).trim() + "…";
        }
        invalidate();
    }

    void setOnSelect(OnSelect listener) {
        onSelect = listener;
    }

    /** Shows where the time went as a heatmap, over a faint track, instead of the track. */
    void setHeat(boolean on) {
        heat = on;
        invalidate();
    }

    /** Colours each stretch by its {@link Modes} guess and shows a legend; null turns it off. */
    void setModes(int[] stretchModes, double[] distances) {
        modes = stretchModes;
        metresByMode = distances;
        invalidate();
    }

    /**
     * Arrays are parallel and ordered oldest first; {@code tz} holds UTC offsets in minutes.
     * {@code days} is the window's length (see {@link #time}), {@code latest} marks the newest
     * point as where the user is now, and {@code keepView} leaves the zoom, selection and pins as
     * they are, for a reload of the same window.
     */
    void setPoints(long[] t, int[] tz, double[] lat, double[] lon, int days, boolean latest, boolean keepView) {
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
        heatAlphas = new int[n];
        for (int i = 0; i < n; i++) {
            // A fix stands for the time until the next one, so the heat doesn't depend on the interval.
            long ms = i + 1 < n ? Math.min(HEAT_MAX_MS, t[i + 1] - t[i]) : 600_000L;
            double opacity = 1 - Math.pow(1 - HEAT_PER_10_MIN, ms / 600_000.0);
            heatAlphas[i] = Math.max(1, (int) Math.round(255 * opacity));
        }
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
        if (keepView && fitted) {
            if (selected >= n) selected = -1;
            invalidate();
            return;
        }
        selected = -1;
        fitted = false;
        setPlaces(new double[0], new double[0], new String[0]);
        fit();
    }

    /** The point picked on the map, or -1. */
    int selected() {
        return selected;
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
            fitted = true;
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
        // The panel under the map grows when a point is picked; that must not undo the user's zoom.
        if (fitted) invalidate();
        else fit();
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
        if (heat) {
            // A thin, faint track under the heat shows the routes between the hot spots.
            line.setColor(INK);
            line.setAlpha(70);
            line.setStrokeWidth(1.5f * dp);
            for (int i = 1; i < n; i++) canvas.drawLine(sx[i - 1], sy[i - 1], sx[i], sy[i], line);
            line.setStrokeWidth(3.5f * dp);
            drawHeat(canvas, n);
        } else {
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
            // The oldest point is an open circle.
            dot.setAlpha(255);
            dot.setColor(PAPER);
            canvas.drawCircle(sx[0], sy[0], 6 * dp, dot);
            canvas.drawCircle(sx[0], sy[0], 6 * dp, ring);
        }
        // The newest point is solid, and blue with a halo when it is where the user is now; it
        // goes on top in case it meets the oldest.
        dot.setAlpha(255);
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

        placed.clear();
        drawPlaces(canvas);
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
        if (drawn.length != n) drawn = new boolean[n];
        Arrays.fill(drawn, false);
        if (selected >= 0 && selected < n) drawLabel(canvas, selected, true);
        // The heatmap is about places, not times: only the picked point is labelled.
        if (heat) return;
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

    /**
     * One soft blob per fix, as strong as the time the fix stands for, piled up in a
     * low-resolution mask; the pile's height then picks each pixel's colour.
     */
    private void drawHeat(Canvas canvas, int n) {
        int w = Math.max(1, getWidth() / HEAT_SHRINK), h = Math.max(1, getHeight() / HEAT_SHRINK);
        if (heatMask == null || heatMask.getWidth() != w || heatMask.getHeight() != h) {
            heatMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            heatImage = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            heatCanvas = new Canvas(heatMask);
            heatPixels = new int[w * h];
        }
        if (blob == null) blob = blob(Math.max(2, Math.round(HEAT_RADIUS_DP * dp / HEAT_SHRINK)));
        heatMask.eraseColor(0);
        float r = blob.getWidth() / 2f;
        for (int i = 0; i < n; i++) {
            float x = sx[i] / HEAT_SHRINK, y = sy[i] / HEAT_SHRINK;
            if (x < -r || y < -r || x > w + r || y > h + r) continue;
            splat.setAlpha(heatAlphas[i]);
            heatCanvas.drawBitmap(blob, x - r, y - r, splat);
        }
        heatMask.getPixels(heatPixels, 0, w, 0, 0, w, h);
        for (int k = 0; k < heatPixels.length; k++) heatPixels[k] = HEAT_COLOURS[heatPixels[k] >>> 24];
        heatImage.setPixels(heatPixels, 0, w, 0, 0, w, h);
        target.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(heatImage, null, target, heatPaint);
    }

    /** A disc of the given radius, opaque in the middle and fading to nothing at the edge. */
    private static Bitmap blob(int radius) {
        Bitmap b = Bitmap.createBitmap(2 * radius, 2 * radius, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(radius, radius, radius, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(radius, radius, radius, p);
        return b;
    }

    private static int[] heatColours() {
        int[] stops = {0xFF1E88E5, 0xFF00ACC1, 0xFF43A047, 0xFFFDD835, 0xFFE53935};
        int[] lut = new int[256];
        for (int a = 1; a < 256; a++) {
            double t = a / 255.0 * (stops.length - 1);
            int k = Math.min(stops.length - 2, (int) t);
            double f = t - k;
            int c = 0;
            for (int shift = 0; shift < 24; shift += 8) {
                double from = stops[k] >> shift & 0xFF, to = stops[k + 1] >> shift & 0xFF;
                c |= (int) Math.round(from + (to - from) * f) << shift;
            }
            // Faint heat fades in rather than starting as a hard blue edge.
            int opacity = (int) Math.round(Math.min(1, a / 40.0) * 210);
            lut[a] = opacity << 24 | c;
        }
        return lut;
    }

    /** Wikipedia pins: a serif W in a white disc, with the article's title beside it. */
    private void drawPlaces(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = 9 * dp, space = 5 * dp;
        for (int i = 0; i < placeNames.length; i++) {
            float x = (float) (cx + (placeX[i] - centreX) * scale), y = (float) (cy + (placeY[i] - centreY) * scale);
            placeSx[i] = x;
            placeSy[i] = y;
            if (x < -r || y < -r || x > getWidth() + r || y > getHeight() + r) continue;
            dot.setColor(PAPER);
            canvas.drawCircle(x, y, r, dot);
            ring.setStrokeWidth(1.5f * dp);
            canvas.drawCircle(x, y, r, ring);
            ring.setStrokeWidth(2 * dp);
            canvas.drawText("W", x, y - (pin.ascent() + pin.descent()) / 2, pin);
            float width = text.measureText(placeNames[i]);
            float tx = x + r + 4 * dp;
            if (tx + width > getWidth() - space) tx = x - r - 4 * dp - width;
            float ty = y + text.getTextSize() * 0.35f;
            RectF box = new RectF(tx - space, ty - text.getTextSize() * 1.25f, tx + width + space, ty + space);
            if (collides(box)) continue;
            placed.add(box);
            canvas.drawText(placeNames[i], tx, ty, halo);
            canvas.drawText(placeNames[i], tx, ty, text);
        }
        dot.setColor(TRACK);
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
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(tapsDone);
                boolean follows = taps > 0 && event.getEventTime() - lastUp <= tapMs
                        && Math.hypot(x - tapX, y - tapY) < tapSlop;
                taps = follows ? taps + 1 : 1;
                if (!follows) {
                    tapX = x;
                    tapY = y;
                }
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                swallow = false;
                quickZoom = taps == 2;
                quickZoomFrom = scale;
                if (taps == 3) {
                    taps = 0;
                    swallow = true;
                    fit();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger means a pinch, never a tap.
                taps = 0;
                moved = true;
                quickZoom = false;
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                // Carry on panning from the finger that stays.
                int stays = event.getActionIndex() == 0 ? 1 : 0;
                lastX = event.getX(stays);
                lastY = event.getY(stays);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (swallow || pinch.isInProgress() || event.getPointerCount() > 1) break;
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) moved = true;
                if (!moved) break;
                if (quickZoom) {
                    zoomAt(tapX, tapY, quickZoomFrom * Math.pow(2, (y - downY) / (QUICK_ZOOM_DP * dp)));
                } else {
                    centreX += (lastX - x) / scale;
                    centreY += (lastY - y) / scale;
                    invalidate();
                }
                lastX = x;
                lastY = y;
                break;
            case MotionEvent.ACTION_UP:
                if (moved || swallow) {
                    taps = 0;
                } else {
                    lastUp = event.getEventTime();
                    postDelayed(tapsDone, tapMs);
                }
                quickZoom = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                taps = 0;
                quickZoom = false;
                break;
        }
        return true;
    }

    /**
     * Once no further tap follows: one tap picks a Wikipedia pin or a point, two zoom in a level.
     * Three taps already reset the view when the third came down.
     */
    private void tapsDone() {
        if (taps == 1) {
            int place = nearestPlace(tapX, tapY);
            if (place >= 0 && onPlace != null) onPlace.onPlace(place);
            else select(nearest(tapX, tapY));
        } else if (taps == 2) {
            zoomAt(tapX, tapY, scale * 2);
        }
        taps = 0;
    }

    private int nearestPlace(float x, float y) {
        int found = -1;
        float best = 20 * dp;
        for (int i = 0; i < placeSx.length; i++) {
            float d = (float) Math.hypot(placeSx[i] - x, placeSy[i] - y);
            if (d <= best) {
                best = d;
                found = i;
            }
        }
        return found;
    }
}
