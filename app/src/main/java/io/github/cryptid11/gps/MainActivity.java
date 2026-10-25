package io.github.cryptid11.gps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's start screen: the fixes of 1, 2 or 7 calendar days on a map, ending today by
 * default, with the settings one tap away.
 */
public final class MainActivity extends Activity {
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
    private View trackingOff;

    private int span;
    /** How many days before today the window's last day is. */
    private int daysBack;
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
    /** Modes set by hand, by the time of the fix that ends their stretch. */
    private Map<Long, Integer> chosen = new HashMap<>();
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
        trackingOff = findViewById(R.id.tracking_off);
        View.OnClickListener settings = v -> startActivity(new Intent(this, SettingsActivity.class));
        findViewById(R.id.settings).setOnClickListener(settings);
        trackingOff.setOnClickListener(settings);

        int days = Prefs.get(this).getInt(Prefs.TRACK_DAYS, SPANS[0]);
        for (int i = 0; i < SPANS.length; i++) {
            if (SPANS[i] == days) span = i;
        }
        RadioGroup spans = findViewById(R.id.spans);
        spans.check(spans.getChildAt(span).getId());
        // A new length keeps the window ending on the same day.
        spans.setOnCheckedChangeListener((group, id) -> {
            span = group.indexOfChild(group.findViewById(id));
            Prefs.get(this).edit().putInt(Prefs.TRACK_DAYS, SPANS[span]).apply();
            show(daysBack);
        });
        previous.setOnClickListener(v -> show(daysBack + SPANS[span]));
        next.setOnClickListener(v -> show(daysBack - SPANS[span]));
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
        TrackService.restartIfStopped(this);
        trackingOff.setVisibility(Prefs.get(this).getBoolean(Prefs.ENABLED, false) ? View.GONE : View.VISIBLE);
        Tiles.get(this).prefetchRecent();
        cleanHistoryOnce();
        show(daysBack);
    }

    /** Removes jumps off the path from history recorded before the current rules existed. */
    private void cleanHistoryOnce() {
        SharedPreferences prefs = Prefs.get(this);
        if (prefs.getInt(Prefs.OUTLIER_RULES, 0) >= Outliers.VERSION) return;
        new Thread(() -> {
            try {
                Db.get(this).removeOutliers(Long.MIN_VALUE, Long.MAX_VALUE);
                prefs.edit().putInt(Prefs.OUTLIER_RULES, Outliers.VERSION).apply();
                runOnUiThread(() -> {
                    if (!isDestroyed()) show(daysBack);
                });
            } catch (IOException ignored) {
            }
        }).start();
    }

    private void show(int back) {
        daysBack = Math.max(0, back);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate last = LocalDate.now(zone).minusDays(daysBack);
        LocalDate first = last.minusDays(SPANS[span] - 1);
        long start = first.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        windowStart = start;
        windowEnd = end;

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

        title.setText(title(first, last));
        summary.setText(n == 0 ? getString(R.string.track_empty)
                : getString(R.string.track_summary,
                        getResources().getQuantityString(R.plurals.points, n, n),
                        TrackView.distance(metres),
                        time(0),
                        time(n - 1)));
        next.setEnabled(daysBack > 0);
        findViewById(R.id.share).setEnabled(n >= 2);
        previous.setEnabled(db.hasBefore(start));
        track.setPoints(times, zones, lats, lons, SPANS[span] > 1, daysBack == 0);
        stretchSpeeds = Modes.speeds(times, lats, lons, speedsAt, accuracies);
        chosen = db.chosenModes(start, end);
        showModes();
        select(-1);
    }

    /** "Today", "Yesterday", or the dates, with the year when it is not this year's. */
    private String title(LocalDate first, LocalDate last) {
        if (daysBack == 0) return getResources().getStringArray(R.array.track_titles)[span];
        if (daysBack == 1 && first.equals(last)) return getString(R.string.track_yesterday);
        DateTimeFormatter day = DateTimeFormatter.ofPattern(
                last.getYear() == LocalDate.now().getYear() ? "EEE d MMM" : "EEE d MMM yyyy", Locale.getDefault());
        return first.equals(last) ? day.format(last) : day.format(first) + " – " + day.format(last);
    }

    /** Applies the transport overlay switch to the map. */
    private void showModes() {
        stretchModes = Prefs.get(this).getBoolean(Prefs.SHOW_MODES, false) ? Modes.classify(times, stretchSpeeds) : null;
        if (stretchModes != null) Modes.apply(stretchModes, times, chosen);
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
        // How the user got here: for a stay, the stretch into its first point.
        int stretch = track.arrival(index) - 1;
        if (stretchModes != null && stretch >= 0) {
            int mode = stretchModes[stretch];
            if (mode == Modes.STILL) {
                text.append(getString(R.string.mode_still, time(stretch), time(stretch + 1)));
            } else {
                text.append(getString(R.string.mode_arrived, Modes.icon(mode),
                        getResources().getStringArray(R.array.mode_names)[mode].toLowerCase(Locale.getDefault()),
                        time(stretch), time(stretch + 1)));
            }
            if (stretchSpeeds[stretch] > 0) {
                text.append(getString(R.string.mode_speed, Math.round(stretchSpeeds[stretch] * 3.6)));
            }
            if (chosen.containsKey(times[stretch + 1])) text.append(getString(R.string.mode_yours));
            text.append(" · ");
            link(text, getString(R.string.mode_change), () -> chooseMode(stretch));
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

    /**
     * Asks how a stretch was travelled, for it alone or for the whole run of stretches of the
     * same mode around it, and stores the answer.
     */
    private void chooseMode(int stretch) {
        int first = stretch, last = stretch;
        while (first > 0 && stretchModes[first - 1] == stretchModes[stretch]) first--;
        while (last + 1 < stretchModes.length && stretchModes[last + 1] == stretchModes[stretch]) last++;
        String[] names = getResources().getStringArray(R.array.mode_names);
        String[] items = new String[Modes.ORDER.length + 1];
        items[0] = getString(R.string.mode_guess);
        Integer current = chosen.get(times[stretch + 1]);
        int[] picked = {0};
        for (int i = 0; i < Modes.ORDER.length; i++) {
            int mode = Modes.ORDER[i];
            items[i + 1] = (Modes.icon(mode) + " " + names[mode]).trim();
            if (current != null && current == mode) picked[0] = i + 1;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.mode_question, time(stretch), time(stretch + 1)))
                .setSingleChoiceItems(items, picked[0], (d, which) -> picked[0] = which)
                .setPositiveButton(R.string.mode_just_this, (d, w) -> setMode(stretch, stretch, picked[0]))
                .setNegativeButton(android.R.string.cancel, null);
        if (last > first) {
            int from = first, to = last;
            dialog.setNeutralButton(getString(R.string.mode_whole_leg, time(from), time(to + 1)),
                    (d, w) -> setMode(from, to, picked[0]));
        }
        dialog.show();
    }

    /** Stores choice {@code item} of {@link #chooseMode} for stretches {@code first} to {@code last}. */
    private void setMode(int first, int last, int item) {
        int mode = item == 0 ? -1 : Modes.ORDER[item - 1];
        long[] ends = new long[last - first + 1];
        for (int i = first; i <= last; i++) {
            ends[i - first] = times[i + 1];
            if (mode < 0) chosen.remove(times[i + 1]);
            else chosen.put(times[i + 1], mode);
        }
        Db.get(this).chooseMode(ends, mode);
        showModes();
        select(selected);
    }

    /** Local time of point {@code i}, with the weekday on windows longer than a day. */
    private String time(int i) {
        return TrackView.time(times[i], zones[i], SPANS[span] > 1);
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
