package io.github.cryptid11.gps;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_BACKGROUND = 2;
    private static final int REQUEST_EXPORT = 3;

    private SharedPreferences prefs;
    private Switch tracking;
    private EditText interval;
    private TextView status;
    private TextView estimate;
    private Button battery;
    private boolean askedBackground;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        prefs = Prefs.get(this);

        tracking = findViewById(R.id.tracking);
        interval = findViewById(R.id.interval);
        status = findViewById(R.id.status);
        estimate = findViewById(R.id.estimate);
        battery = findViewById(R.id.battery);

        tracking.setChecked(prefs.getBoolean(Prefs.ENABLED, false));
        tracking.setOnCheckedChangeListener((v, on) -> {
            if (on == prefs.getBoolean(Prefs.ENABLED, false)) return;
            if (on) enable(); else disable();
        });

        interval.setText(String.valueOf(Prefs.intervalMinutes(this)));
        interval.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                showEstimate();
            }
        });
        interval.setOnEditorActionListener((v, action, event) -> {
            applyInterval();
            interval.clearFocus();
            return false;
        });

        bind(findViewById(R.id.gps), Prefs.GPS);
        bind(findViewById(R.id.still), Prefs.STILL);

        findViewById(R.id.show_track).setOnClickListener(v -> startActivity(new Intent(this, TrackActivity.class)));
        findViewById(R.id.export).setOnClickListener(v -> pickExportFile());
        battery.setOnClickListener(v -> askBattery());
        findViewById(R.id.clear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.clear_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Db.get(this).clear();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        try {
            ((TextView) findViewById(R.id.version))
                    .setText(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private void bind(Switch s, String key) {
        s.setChecked(prefs.getBoolean(key, true));
        s.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            showEstimate();
        });
    }

    /** Battery estimate for the interval as typed, before it is applied. */
    private void showEstimate() {
        int minutes;
        try {
            minutes = Integer.parseInt(interval.getText().toString().trim());
        } catch (NumberFormatException e) {
            minutes = Prefs.intervalMinutes(this);
        }
        minutes = Math.max(1, Math.min(Prefs.MAX_INTERVAL_MIN, minutes));
        Consumption.Estimate e = Consumption.estimate(this, minutes,
                prefs.getBoolean(Prefs.GPS, true), prefs.getBoolean(Prefs.STILL, true));
        String percent = e.percentPerDay < 0.1 ? "<0.1" : String.format(Locale.US,
                e.percentPerDay < 10 ? "%.1f" : "%.0f", e.percentPerDay);
        String fixes = getResources().getQuantityString(R.plurals.fixes_per_day, e.fixesPerDay, e.fixesPerDay);
        estimate.setText(getString(e.learned ? R.string.estimate_measured : R.string.estimate_default,
                percent, fixes, getString(R.string.seconds, Math.round(e.secondsPerFix))));
    }

    /** Removes jumps off the path from history recorded before the current rules existed. */
    private void cleanHistoryOnce() {
        if (prefs.getInt(Prefs.OUTLIER_RULES, 0) >= Outliers.VERSION) return;
        new Thread(() -> {
            try {
                Db.get(this).removeOutliers(Long.MIN_VALUE, Long.MAX_VALUE);
                prefs.edit().putInt(Prefs.OUTLIER_RULES, Outliers.VERSION).apply();
                runOnUiThread(this::refresh);
            } catch (IOException ignored) {
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(this);
        cleanHistoryOnce();
        // Pick up again if the system stopped the service while the app was in the background.
        if (prefs.getBoolean(Prefs.ENABLED, false) && !TrackService.isRunning()) {
            prefs.edit().remove(Prefs.NOTE).apply();
            TrackService.start(this, TrackService.ACTION_START);
        }
        refresh();
        showEstimate();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        applyInterval();
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (Prefs.TICK.equals(key)) {
            refresh();
            showEstimate();
        }
    }

    private void enable() {
        if (!TrackService.hasLocationPermission(this)) {
            String[] wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION};
            requestPermissions(wanted, REQUEST_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !askedBackground
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            askedBackground = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.background_title)
                    .setMessage(R.string.background_text)
                    .setPositiveButton(android.R.string.ok, (d, w) -> requestPermissions(
                            new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND))
                    .setNegativeButton(R.string.not_now, (d, w) -> enable())
                    .setOnCancelListener(d -> enable())
                    .show();
            return;
        }
        applyInterval();
        prefs.edit().putBoolean(Prefs.ENABLED, true).remove(Prefs.NOTE).apply();
        TrackService.start(this, TrackService.ACTION_START);
        refresh();
        if (!batteryExempt() && !prefs.getBoolean(Prefs.ASKED_BATTERY, false)) {
            prefs.edit().putBoolean(Prefs.ASKED_BATTERY, true).apply();
            askBattery();
        }
    }

    private void disable() {
        prefs.edit().putBoolean(Prefs.ENABLED, false).remove(Prefs.NOTE).apply();
        TrackService.stop(this);
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == REQUEST_BACKGROUND) {
            enable();
        } else if (request == REQUEST_LOCATION) {
            if (TrackService.hasLocationPermission(this)) {
                enable();
            } else {
                Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show();
                refresh();
            }
        }
    }

    private void applyInterval() {
        int current = Prefs.intervalMinutes(this);
        int wanted;
        try {
            wanted = Integer.parseInt(interval.getText().toString().trim());
        } catch (NumberFormatException e) {
            wanted = current;
        }
        wanted = Math.max(1, Math.min(Prefs.MAX_INTERVAL_MIN, wanted));
        String shown = String.valueOf(wanted);
        if (!shown.contentEquals(interval.getText())) interval.setText(shown);
        if (wanted == current) return;
        prefs.edit().putInt(Prefs.INTERVAL, wanted).apply();
        // Restart the alarm chain on the new interval.
        if (prefs.getBoolean(Prefs.ENABLED, false)) TrackService.start(this, TrackService.ACTION_START);
    }

    private boolean batteryExempt() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void refresh() {
        boolean enabled = prefs.getBoolean(Prefs.ENABLED, false);
        if (tracking.isChecked() != enabled) tracking.setChecked(enabled);
        battery.setVisibility(batteryExempt() ? View.GONE : View.VISIBLE);

        Db db = Db.get(this);
        long count = db.count();
        StringBuilder text = new StringBuilder(
                count == 0 ? getString(R.string.no_points)
                        : getResources().getQuantityString(R.plurals.points, (int) count, count));
        try {
            db.last((t, lat, lon, acc, alt, spd, src, tz) -> text.append('\n').append(getString(R.string.last,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(t)),
                    String.format(Locale.US, "%.5f, %.5f", lat, lon),
                    Double.isNaN(acc) ? 0 : Math.round(acc), src)));
        } catch (IOException ignored) {
        }
        String note = enabled ? prefs.getString(Prefs.NOTE, null) : null;
        if (note != null) text.append('\n').append(note);
        status.setText(text);
    }

    private void pickExportFile() {
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "Timeline.json");
        try {
            startActivityForResult(pick, REQUEST_EXPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, getString(R.string.export_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri target = data.getData();
        new Thread(() -> {
            String message;
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("cannot open file");
                Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                int n = Exporter.write(Db.get(this), w);
                w.flush();
                message = getResources().getQuantityString(R.plurals.exported, n, n);
            } catch (IOException | RuntimeException e) {
                message = getString(R.string.export_failed, e.getMessage());
            }
            String shown = message;
            runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
        }).start();
    }
}
