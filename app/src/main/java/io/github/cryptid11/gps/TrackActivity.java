package io.github.cryptid11.gps;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows the fixes of one window of 1, 2 or 7 days, the most recent one by default. */
public final class TrackActivity extends Activity {
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    /** Window lengths in days, in the order of the buttons and of R.array.track_titles. */
    private static final int[] SPANS = {1, 2, 7};
    /** Shorter hops are treated as GPS jitter and left out of the distance. */
    private static final float MIN_HOP_M = 30f;

    private TrackView track;
    private TextView title;
    private TextView summary;
    private TextView detail;
    private View previous;
    private View next;

    private int span;
    /** How far the end of the window is before now. */
    private long backMs;
    private long windowStart;
    private long windowEnd;
    private long[] times = {};
    private int[] zones = {};
    private double[] lats = {};
    private double[] lons = {};
    private double[] accuracies = {};
    private int selected = -1;
    /** Bumped on every selection, so a lookup that returns after the user moved on is ignored. */
    private int lookup;
    private double[] stretchSpeeds = {};
    /** Guessed mode of each stretch, or null while the overlay is off. */
    private int[] stretchModes;
    /** Wikipedia results by position rounded to about 100 m, for this screen's lifetime. */
    private final Map<String, List<Wiki.Article>> articles = new HashMap<>();
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();

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

        int days = Prefs.get(this).getInt(Prefs.TRACK_DAYS, SPANS[0]);
        for (int i = 0; i < SPANS.length; i++) {
            if (SPANS[i] == days) span = i;
        }
        RadioGroup spans = findViewById(R.id.spans);
        spans.check(spans.getChildAt(span).getId());
        // A new length keeps the window ending where it did.
        spans.setOnCheckedChangeListener((group, id) -> {
            span = group.indexOfChild(group.findViewById(id));
            Prefs.get(this).edit().putInt(Prefs.TRACK_DAYS, SPANS[span]).apply();
            show(backMs);
        });
        previous.setOnClickListener(v -> show(backMs + SPANS[span] * DAY_MS));
        next.setOnClickListener(v -> show(backMs - SPANS[span] * DAY_MS));
        track.setOnSelect(this::select);
        detail.setMovementMethod(LinkMovementMethod.getInstance());
        Switch modesSwitch = findViewById(R.id.modes);
        modesSwitch.setChecked(Prefs.get(this).getBoolean(Prefs.SHOW_MODES, false));
        modesSwitch.setOnCheckedChangeListener((v, on) -> {
            Prefs.get(this).edit().putBoolean(Prefs.SHOW_MODES, on).apply();
            showModes();
            select(selected);
        });
        findViewById(R.id.share).setOnClickListener(v -> startActivity(new Intent(this, ShareActivity.class)
                .putExtra(ShareActivity.EXTRA_FROM, windowStart)
                .putExtra(ShareActivity.EXTRA_TO, windowEnd)));
    }

    @Override
    protected void onDestroy() {
        lookups.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tiles.get(this).prefetchRecent();
        show(backMs);
    }

    private void show(long back) {
        backMs = Math.max(0, back);
        long end = System.currentTimeMillis() - backMs;
        long start = end - SPANS[span] * DAY_MS;
        windowStart = start;
        windowEnd = end + 1;

        List<double[]> rows = new ArrayList<>();
        List<long[]> stamps = new ArrayList<>();
        Db db = Db.get(this);
        try {
            db.range(start, end, (t, lat, lon, acc, alt, spd, src, tz) -> {
                stamps.add(new long[] {t, tz});
                rows.add(new double[] {lat, lon, acc, spd});
            });
        } catch (IOException ignored) {
        }
        int n = rows.size();
        times = new long[n];
        zones = new int[n];
        lats = new double[n];
        lons = new double[n];
        accuracies = new double[n];
        double[] speedsAt = new double[n];
        float metres = 0;
        float[] hop = new float[1];
        for (int i = 0; i < n; i++) {
            times[i] = stamps.get(i)[0];
            zones[i] = (int) stamps.get(i)[1];
            lats[i] = rows.get(i)[0];
            lons[i] = rows.get(i)[1];
            accuracies[i] = rows.get(i)[2];
            speedsAt[i] = rows.get(i)[3];
            if (i > 0) {
                Location.distanceBetween(lats[i - 1], lons[i - 1], lats[i], lons[i], hop);
                if (hop[0] >= MIN_HOP_M) metres += hop[0];
            }
        }

        DateTimeFormatter when = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
        boolean days = SPANS[span] > 1;
        title.setText(backMs == 0 ? getResources().getStringArray(R.array.track_titles)[span]
                : when.format(Instant.ofEpochMilli(start)) + " – " + when.format(Instant.ofEpochMilli(end)));
        summary.setText(n == 0 ? getString(R.string.track_empty)
                : getString(R.string.track_summary,
                        getResources().getQuantityString(R.plurals.points, n, n),
                        TrackView.distance(metres),
                        TrackView.time(times[0], zones[0], days),
                        TrackView.time(times[n - 1], zones[n - 1], days)));
        next.setEnabled(backMs > 0);
        findViewById(R.id.share).setEnabled(n >= 2);
        previous.setEnabled(db.hasBefore(start));
        track.setPoints(times, zones, lats, lons, days);
        stretchSpeeds = Modes.speeds(times, lats, lons, speedsAt, accuracies);
        showModes();
        select(-1);
    }

    /** Applies the transport overlay switch to the map. */
    private void showModes() {
        stretchModes = Prefs.get(this).getBoolean(Prefs.SHOW_MODES, false) ? Modes.classify(stretchSpeeds) : null;
        track.setModes(stretchModes, stretchModes == null ? null : Modes.distances(lats, lons, stretchModes));
    }

    private void select(int index) {
        selected = index;
        lookup++;
        if (index < 0) {
            detail.setText(times.length == 0 ? "" : getString(R.string.track_hint));
            return;
        }
        String key = String.format(Locale.US, "%.3f,%.3f", lats[index], lons[index]);
        List<Wiki.Article> known = articles.get(key);
        showDetail(index, known, false);
        if (known != null) return;
        int mine = lookup;
        double lat = lats[index], lon = lons[index];
        String agent = Tiles.get(this).agent();
        lookups.execute(() -> {
            List<Wiki.Article> found;
            try {
                found = Wiki.near(lat, lon, agent);
            } catch (IOException | RuntimeException e) {
                found = null;
            }
            List<Wiki.Article> result = found;
            runOnUiThread(() -> {
                if (result != null) articles.put(key, result);
                if (mine == lookup && !isDestroyed()) showDetail(index, result, result == null);
            });
        });
    }

    /** Time, position, a maps link and, once looked up, links to nearby Wikipedia articles. */
    private void showDetail(int index, List<Wiki.Article> nearby, boolean failed) {
        String accuracy = Double.isNaN(accuracies[index]) ? ""
                : String.format(Locale.US, " · ±%d m", Math.round(accuracies[index]));
        SpannableStringBuilder text = new SpannableStringBuilder(getString(R.string.track_point,
                track.label(index).replace("\n", ", "),
                String.format(Locale.US, "%.5f, %.5f", lats[index], lons[index]),
                accuracy));
        if (stretchModes != null && index > 0 && stretchModes[index - 1] != Modes.STILL) {
            int mode = stretchModes[index - 1];
            text.append(getString(R.string.mode_arrived, Modes.icon(mode),
                    getResources().getStringArray(R.array.mode_names)[mode].toLowerCase(Locale.getDefault()),
                    Math.round(stretchSpeeds[index - 1] * 3.6)));
        }
        text.append('\n');
        link(text, getString(R.string.track_open_maps), this::openInMaps);
        text.append('\n');
        if (failed) {
            text.append(getString(R.string.wiki_failed));
        } else if (nearby == null) {
            text.append(getString(R.string.wiki_loading));
        } else if (nearby.isEmpty()) {
            text.append(getString(R.string.wiki_none));
        } else {
            text.append(getString(R.string.wiki_prefix));
            for (int i = 0; i < nearby.size(); i++) {
                Wiki.Article article = nearby.get(i);
                if (i > 0) text.append(" · ");
                link(text, getString(R.string.wiki_article, article.title, TrackView.distance(article.metres)),
                        () -> open(article.url));
            }
        }
        detail.setText(text);
    }

    private static void link(SpannableStringBuilder text, String label, Runnable action) {
        int start = text.length();
        text.append(label);
        text.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                action.run();
            }
        }, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
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
