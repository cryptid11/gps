package io.github.cryptid11.gps;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

/**
 * Estimates the app's battery use per day from the settings and from how long fixes really
 * take on this phone. The power figures are typical values for a phone's GNSS chip and Wi-Fi
 * scanning with the CPU awake; the result is an order of magnitude, not a measurement.
 */
final class Consumption {
    private static final String GPS_MS = "avg_gps_ms";
    private static final String NETWORK_MS = "avg_network_ms";
    private static final String STILL_SHARE = "still_share";
    private static final String SAMPLES = "fix_samples";

    /** Starting guesses until the phone has measured its own. */
    private static final float DEFAULT_GPS_MS = 12_000;
    private static final float DEFAULT_NETWORK_MS = 4_000;
    private static final float DEFAULT_STILL_SHARE = 0.4f;
    /** Weight of each new measurement in the running averages. */
    private static final float WEIGHT = 0.1f;
    /** Below this many measured fixes the estimate says it is still learning. */
    static final int LEARNED_AFTER = 10;

    private static final double GPS_WATTS = 0.17;
    private static final double NETWORK_WATTS = 0.25;
    /** Waking up, saving the point and going back to sleep. */
    private static final double FIX_OVERHEAD_J = 0.8;
    /** Waking up only to find the phone has not moved. */
    private static final double SKIP_J = 0.15;
    private static final double VOLTS = 3.85;
    private static final double FALLBACK_MAH = 4500;

    private Consumption() {}

    /** Records how long the radios were on for one attempt (0 when another app's fix was reused). */
    static void recordAttempt(SharedPreferences prefs, boolean gps, long radioMs) {
        String key = gps ? GPS_MS : NETWORK_MS;
        float avg = prefs.getFloat(key, gps ? DEFAULT_GPS_MS : DEFAULT_NETWORK_MS);
        prefs.edit()
                .putFloat(key, avg + WEIGHT * (radioMs - avg))
                .putInt(SAMPLES, prefs.getInt(SAMPLES, 0) + 1)
                .apply();
        recordTick(prefs, false);
    }

    /** Records whether a scheduled tick was skipped because the phone had not moved. */
    static void recordTick(SharedPreferences prefs, boolean skipped) {
        float share = prefs.getFloat(STILL_SHARE, DEFAULT_STILL_SHARE);
        prefs.edit().putFloat(STILL_SHARE, share + WEIGHT * ((skipped ? 1 : 0) - share)).apply();
    }

    static final class Estimate {
        double percentPerDay;
        int fixesPerDay;
        double secondsPerFix;
        boolean learned;
    }

    static Estimate estimate(Context c, int intervalMinutes, boolean gps, boolean pauseWhenStill) {
        SharedPreferences prefs = Prefs.get(c);
        Estimate e = new Estimate();
        double ticks = 1440.0 / intervalMinutes;
        double still = pauseWhenStill ? prefs.getFloat(STILL_SHARE, DEFAULT_STILL_SHARE) : 0;
        // While still, a fix is still taken once an hour.
        double fixes = ticks * (1 - still) + Math.min(ticks, 24) * still;
        double skips = ticks - fixes;
        e.secondsPerFix = prefs.getFloat(gps ? GPS_MS : NETWORK_MS, gps ? DEFAULT_GPS_MS : DEFAULT_NETWORK_MS) / 1000.0;
        double perFix = e.secondsPerFix * (gps ? GPS_WATTS : NETWORK_WATTS) + FIX_OVERHEAD_J;
        double joules = fixes * perFix + skips * SKIP_J;
        e.percentPerDay = 100 * joules / capacityJoules(c);
        e.fixesPerDay = (int) Math.round(fixes);
        e.learned = prefs.getInt(SAMPLES, 0) >= LEARNED_AFTER;
        return e;
    }

    /** Battery capacity, derived from the charge counter and level where the phone reports them. */
    private static double capacityJoules(Context c) {
        double mAh = FALLBACK_MAH;
        BatteryManager battery = c.getSystemService(BatteryManager.class);
        Intent status = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null && status != null) {
            int microAh = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (microAh > 0 && level > 10 && scale > 0) {
                double full = microAh / 1000.0 / ((double) level / scale);
                if (full >= 1500 && full <= 10_000) mAh = full;
            }
        }
        return mAh / 1000 * VOLTS * 3600;
    }
}
