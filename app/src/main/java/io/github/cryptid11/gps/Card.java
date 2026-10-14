package io.github.cryptid11.gps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders a shareable activity summary in the style of a sports-tracking app: the route on a
 * map, the headline numbers, speed and elevation charts, and the user's photos. Slow (it may
 * download map tiles), so it runs off the main thread.
 */
final class Card {
    static final int WIDTH = 1080;

    private static final int PAD = 56;
    private static final int HEADER_H = 250;
    private static final int MAP_H = 1000;
    private static final int STAT_ROW_H = 190;
    private static final int CHART_H = 360;
    private static final int FOOTER_H = 130;
    private static final int GAP = 16;
    private static final float CORNER = 28;

    private static final int ORANGE = 0xFFFC4C02;
    private static final int INK = 0xFF242428;
    private static final int GREY = 0xFF6D6D78;
    private static final int RULE = 0xFFE6E6EB;
    private static final int PAPER = 0xFFFFFFFF;
    private static final int LAND = 0xFFE9E7E2;
    private static final int START = 0xFF2E7D32;
    private static final int CLIMB = 0xFF5C6BC0;

    /** Parallel arrays, oldest first; {@code alt} and {@code spd} hold NaN where unknown. */
    static final class Route {
        long[] t;
        int[] tz;
        double[] lat;
        double[] lon;
        double[] alt;
        double[] spd;
    }

    private final Context context;
    private final Route route;
    private final Stats stats;
    private final List<Bitmap> photos;
    private final Typeface bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private Card(Context context, Route route, List<Bitmap> photos) {
        this.context = context;
        this.route = route;
        this.photos = photos;
        stats = Stats.of(route.t, route.lat, route.lon, route.alt, route.spd);
    }

    static Bitmap render(Context context, Route route, List<Bitmap> photos) {
        return new Card(context, route, photos).draw();
    }

    private Bitmap draw() {
        boolean speedChart = stats.speed.length >= 1;
        boolean climbChart = stats.alt.length >= 2;
        List<RectF> photoRects = photoLayout(0);
        float photosH = photoRects.isEmpty() ? 0 : photoRects.get(photoRects.size() - 1).bottom + PAD;
        int height = HEADER_H + MAP_H + 2 * STAT_ROW_H + PAD
                + (speedChart ? CHART_H : 0) + (climbChart ? CHART_H : 0) + (int) photosH + FOOTER_H;
        Bitmap out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(PAPER);

        float y = 0;
        drawHeader(canvas);
        y += HEADER_H;
        drawMap(canvas, new RectF(0, y, WIDTH, y + MAP_H));
        y += MAP_H + PAD / 2f;
        drawStats(canvas, y);
        y += 2 * STAT_ROW_H + PAD / 2f;
        if (speedChart) {
            double[] kmh = new double[stats.speed.length];
            for (int i = 0; i < kmh.length; i++) kmh[i] = stats.speed[i] * 3.6;
            drawChart(canvas, y, context.getString(R.string.card_speed), stats.speedAt, kmh, ORANGE, true,
                    clock(route.t[0]), clock(route.t[route.t.length - 1]),
                    String.format(Locale.US, "max %.0f km/h", stats.maxSpeedMps * 3.6));
            y += CHART_H;
        }
        if (climbChart) {
            drawChart(canvas, y, context.getString(R.string.card_elevation), stats.altAt, stats.alt, CLIMB, false,
                    "0 km", TrackView.distance(stats.distanceM),
                    String.format(Locale.US, "max %.0f m", stats.maxAltM));
            y += CHART_H;
        }
        drawPhotos(canvas, photoLayout(y));
        y += photosH;
        text(28, GREY, false);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.card_footer), WIDTH / 2f, height - FOOTER_H / 2f + 10, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        return out;
    }

    private void text(float size, int color, boolean isBold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface(isBold ? bold : Typeface.DEFAULT);
        paint.setLetterSpacing(0);
    }

    private OffsetDateTime local(long t, int tz) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneOffset.ofTotalSeconds(tz * 60));
    }

    private String clock(long t) {
        int i = route.t[0] == t ? 0 : route.t.length - 1;
        return TrackView.clock(t, route.tz[i]);
    }

    private void drawHeader(Canvas canvas) {
        int last = route.t.length - 1;
        OffsetDateTime from = local(route.t[0], route.tz[0]), to = local(route.t[last], route.tz[last]);
        text(30, ORANGE, true);
        paint.setLetterSpacing(0.12f);
        canvas.drawText(context.getString(R.string.app_name).toUpperCase(Locale.ROOT), PAD, 92, paint);
        text(58, INK, true);
        canvas.drawText(from.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())), PAD, 165, paint);
        String span = from.toLocalDate().equals(to.toLocalDate())
                ? clock(route.t[0]) + " – " + clock(route.t[last])
                : from.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault())) + " – "
                        + to.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()));
        text(36, GREY, false);
        canvas.drawText(span, PAD, 220, paint);
    }

    /** The route over map tiles, fitted to the box with a margin; Web Mercator like {@link TrackView}. */
    private void drawMap(Canvas canvas, RectF box) {
        int n = route.t.length;
        double[] x = new double[n], y = new double[n];
        double minX = 1, maxX = 0, minY = 1, maxY = 0;
        for (int i = 0; i < n; i++) {
            x[i] = Tiles.worldX(route.lon[i]);
            y[i] = Tiles.worldY(route.lat[i]);
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        double centreX = (minX + maxX) / 2, centreY = (minY + maxY) / 2;
        double minSpan = 500 / (40_075_016.686 * Math.cos(Math.toRadians(Tiles.latitude(centreY))));
        double margin = 90;
        double scale = Math.min((box.width() - 2 * margin) / Math.max(minSpan, maxX - minX),
                (box.height() - 2 * margin) / Math.max(minSpan, maxY - minY));

        canvas.save();
        canvas.clipRect(box);
        canvas.drawColor(LAND);
        int z = (int) Math.max(0, Math.min(Tiles.MAX_ZOOM, Math.floor(Math.log(scale / 384) / Math.log(2))));
        int count = 1 << z;
        double size = scale / count;
        double left = centreX * scale - box.centerX(), top = centreY * scale - box.centerY();
        Tiles tiles = Tiles.get(context);
        RectF target = new RectF();
        for (int ty = Math.max(0, (int) Math.floor((top + box.top) / size));
                ty <= Math.min(count - 1, (int) Math.floor((top + box.bottom) / size)); ty++) {
            for (int tx = (int) Math.floor((left + box.left) / size); tx <= (int) Math.floor((left + box.right) / size); tx++) {
                Bitmap tile = tiles.loadNow(z, Math.floorMod(tx, count), ty);
                if (tile == null) continue;
                target.set((float) (tx * size - left), (float) (ty * size - top),
                        (float) ((tx + 1) * size - left), (float) ((ty + 1) * size - top));
                canvas.drawBitmap(tile, null, target, paint);
            }
        }

        Path path = new Path();
        float[] sx = new float[n], sy = new float[n];
        for (int i = 0; i < n; i++) {
            sx[i] = (float) (x[i] * scale - left);
            sy[i] = (float) (y[i] * scale - top);
            if (i == 0) path.moveTo(sx[i], sy[i]);
            else path.lineTo(sx[i], sy[i]);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(PAPER);
        paint.setStrokeWidth(16);
        canvas.drawPath(path, paint);
        paint.setColor(ORANGE);
        paint.setStrokeWidth(9);
        canvas.drawPath(path, paint);
        marker(canvas, sx[0], sy[0], START);
        marker(canvas, sx[n - 1], sy[n - 1], INK);

        text(22, INK, false);
        String credit = context.getString(R.string.map_credit_long);
        float w = paint.measureText(credit);
        paint.setColor(0xCCFFFFFF);
        canvas.drawRect(box.right - w - 24, box.bottom - 40, box.right, box.bottom, paint);
        paint.setColor(INK);
        canvas.drawText(credit, box.right - w - 12, box.bottom - 12, paint);
        canvas.restore();
    }

    private void marker(Canvas canvas, float x, float y, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PAPER);
        canvas.drawCircle(x, y, 22, paint);
        paint.setColor(color);
        canvas.drawCircle(x, y, 15, paint);
    }

    private void drawStats(Canvas canvas, float top) {
        String[][] cells = {
                {context.getString(R.string.card_distance), TrackView.distance(stats.distanceM)},
                {context.getString(R.string.card_moving), duration(stats.movingMs)},
                {context.getString(R.string.card_avg_speed), speed(stats.averageSpeedMps())},
                {context.getString(R.string.card_elapsed), duration(stats.elapsedMs)},
                {context.getString(R.string.card_max_speed), speed(stats.maxSpeedMps)},
                {context.getString(R.string.card_climb), stats.alt.length >= 2
                        ? String.format(Locale.US, "%.0f m", stats.gainM) : "–"},
        };
        float column = (WIDTH - 2f * PAD) / 3;
        for (int i = 0; i < cells.length; i++) {
            float x = PAD + (i % 3) * column, y = top + (i / 3) * STAT_ROW_H;
            text(26, GREY, false);
            paint.setLetterSpacing(0.06f);
            canvas.drawText(cells[i][0].toUpperCase(Locale.getDefault()), x, y + 50, paint);
            text(56, INK, true);
            canvas.drawText(cells[i][1], x, y + 125, paint);
        }
        paint.setColor(RULE);
        paint.setStrokeWidth(2);
        canvas.drawLine(PAD, top + STAT_ROW_H - 10, WIDTH - PAD, top + STAT_ROW_H - 10, paint);
    }

    private static String duration(long ms) {
        long minutes = Math.round(ms / 60_000.0);
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private static String speed(double mps) {
        return String.format(Locale.US, "%.1f km/h", mps * 3.6);
    }

    /** An area chart with its title, its maximum, and the first and last x labels. */
    private void drawChart(Canvas canvas, float top, String title, double[] xs, double[] ys, int color,
            boolean fromZero, String firstX, String lastX, String max) {
        text(26, GREY, false);
        paint.setLetterSpacing(0.06f);
        canvas.drawText(title.toUpperCase(Locale.getDefault()), PAD, top + 50, paint);
        text(28, INK, true);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(max, WIDTH - PAD, top + 50, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        RectF plot = new RectF(PAD, top + 80, WIDTH - PAD, top + CHART_H - 80);
        double minX = xs[0], maxX = xs[xs.length - 1];
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double v : ys) {
            minY = Math.min(minY, v);
            maxY = Math.max(maxY, v);
        }
        if (fromZero) minY = 0;
        double spanY = Math.max(maxY - minY, fromZero ? 1 : 10);
        minY -= fromZero ? 0 : spanY * 0.1;
        spanY *= fromZero ? 1.05 : 1.2;
        double spanX = Math.max(maxX - minX, 1);

        Path line = new Path(), area = new Path();
        for (int i = 0; i < xs.length; i++) {
            float px = xs.length == 1 ? plot.left : (float) (plot.left + (xs[i] - minX) / spanX * plot.width());
            float py = (float) (plot.bottom - (ys[i] - minY) / spanY * plot.height());
            if (i == 0) {
                line.moveTo(px, py);
                area.moveTo(px, plot.bottom);
            } else {
                line.lineTo(px, py);
            }
            area.lineTo(px, py);
            if (xs.length == 1) {
                line.lineTo(plot.right, py);
                area.lineTo(plot.right, py);
            }
        }
        area.lineTo(xs.length == 1 ? plot.right : (float) (plot.left + (xs[xs.length - 1] - minX) / spanX * plot.width()), plot.bottom);
        area.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((color & 0x00FFFFFF) | 0x33000000);
        canvas.drawPath(area, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        canvas.drawPath(line, paint);
        paint.setStrokeWidth(2);
        paint.setColor(RULE);
        canvas.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, paint);

        text(24, GREY, false);
        canvas.drawText(firstX, plot.left, plot.bottom + 40, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(lastX, plot.right, plot.bottom + 40, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** First photo full width at 4:3, the rest in rows of up to three squares (a lone one is 4:3 too). */
    private List<RectF> photoLayout(float top) {
        List<RectF> rects = new ArrayList<>();
        float inner = WIDTH - 2f * PAD, y = top;
        for (int i = 0; i < photos.size(); ) {
            int columns = i == 0 ? 1 : Math.min(3, photos.size() - i);
            float w = (inner - GAP * (columns - 1)) / columns, h = columns == 1 ? inner * 0.75f : w;
            for (int c = 0; c < columns; c++) rects.add(new RectF(PAD + c * (w + GAP), y, PAD + c * (w + GAP) + w, y + h));
            y += h + GAP;
            i += columns;
        }
        return rects;
    }

    private void drawPhotos(Canvas canvas, List<RectF> rects) {
        Rect crop = new Rect();
        Path clip = new Path();
        for (int i = 0; i < rects.size(); i++) {
            Bitmap photo = photos.get(i);
            RectF r = rects.get(i);
            // Centre crop to the cell's shape.
            float want = r.width() / r.height(), have = (float) photo.getWidth() / photo.getHeight();
            if (have > want) {
                int w = Math.round(photo.getHeight() * want);
                crop.set((photo.getWidth() - w) / 2, 0, (photo.getWidth() + w) / 2, photo.getHeight());
            } else {
                int h = Math.round(photo.getWidth() / want);
                crop.set(0, (photo.getHeight() - h) / 2, photo.getWidth(), (photo.getHeight() + h) / 2);
            }
            clip.reset();
            clip.addRoundRect(r, CORNER, CORNER, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(photo, crop, r, paint);
            canvas.restore();
        }
    }
}
