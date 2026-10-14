package io.github.cryptid11.gps;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows the fixes of one 24-hour window, the most recent one by default. */
public final class TrackActivity extends Activity {
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** Shorter hops are treated as GPS jitter and left out of the distance. */
    private static final float MIN_HOP_M = 30f;

    private TrackView track;
    private TextView title;
    private TextView summary;
    private TextView detail;
    private View previous;
    private View next;

    private int daysBack;
    private long windowStart;
    private long windowEnd;
    private long[] times = {};
    private int[] zones = {};
    private double[] lats = {};
    private double[] lons = {};
    private double[] accuracies = {};
    private int selected = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.track);
        track = findViewById(R.id.track);
        title = findViewById(R.id.title);
        summary = findViewById(R.id.summary);
        detail = findViewById(R.id.detail);
        previous = findViewById(R.id.previous);
        next = findViewById(R.id.next);

        previous.setOnClickListener(v -> show(daysBack + 1));
        next.setOnClickListener(v -> show(daysBack - 1));
        track.setOnSelect(this::select);
        detail.setOnClickListener(v -> openInMaps());
        findViewById(R.id.share).setOnClickListener(v -> startActivity(new Intent(this, ShareActivity.class)
                .putExtra(ShareActivity.EXTRA_FROM, windowStart)
                .putExtra(ShareActivity.EXTRA_TO, windowEnd)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tiles.get(this).prefetchRecent();
        show(daysBack);
    }

    private void show(int back) {
        daysBack = Math.max(0, back);
        long end = System.currentTimeMillis() - daysBack * DAY_MS;
        long start = end - DAY_MS;
        windowStart = start;
        windowEnd = end + 1;

        List<double[]> rows = new ArrayList<>();
        List<long[]> stamps = new ArrayList<>();
        Db db = Db.get(this);
        try {
            db.range(start, end, (t, lat, lon, acc, alt, spd, src, tz) -> {
                stamps.add(new long[] {t, tz});
                rows.add(new double[] {lat, lon, acc});
            });
        } catch (IOException ignored) {
        }
        int n = rows.size();
        times = new long[n];
        zones = new int[n];
        lats = new double[n];
        lons = new double[n];
        accuracies = new double[n];
        float metres = 0;
        float[] hop = new float[1];
        for (int i = 0; i < n; i++) {
            times[i] = stamps.get(i)[0];
            zones[i] = (int) stamps.get(i)[1];
            lats[i] = rows.get(i)[0];
            lons[i] = rows.get(i)[1];
            accuracies[i] = rows.get(i)[2];
            if (i > 0) {
                Location.distanceBetween(lats[i - 1], lons[i - 1], lats[i], lons[i], hop);
                if (hop[0] >= MIN_HOP_M) metres += hop[0];
            }
        }

        DateTimeFormatter when = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
        title.setText(daysBack == 0 ? getString(R.string.track_title)
                : when.format(Instant.ofEpochMilli(start)) + " – " + when.format(Instant.ofEpochMilli(end)));
        summary.setText(n == 0 ? getString(R.string.track_empty)
                : getString(R.string.track_summary,
                        getResources().getQuantityString(R.plurals.points, n, n),
                        TrackView.distance(metres),
                        TrackView.clock(times[0], zones[0]),
                        TrackView.clock(times[n - 1], zones[n - 1])));
        next.setEnabled(daysBack > 0);
        findViewById(R.id.share).setEnabled(n >= 2);
        previous.setEnabled(db.hasBefore(start));
        track.setPoints(times, zones, lats, lons);
        select(-1);
    }

    private void select(int index) {
        selected = index;
        if (index < 0) {
            detail.setText(times.length == 0 ? "" : getString(R.string.track_hint));
            return;
        }
        String accuracy = Double.isNaN(accuracies[index]) ? ""
                : String.format(Locale.US, " · ±%d m", Math.round(accuracies[index]));
        detail.setText(getString(R.string.track_point,
                track.label(index).replace("\n", ", "),
                String.format(Locale.US, "%.5f, %.5f", lats[index], lons[index]),
                accuracy));
    }

    private void openInMaps() {
        if (selected < 0) return;
        String at = String.format(Locale.US, "%.6f,%.6f", lats[selected], lons[selected]);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + at + "?q=" + at)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.track_no_maps, Toast.LENGTH_SHORT).show();
        }
    }
}
