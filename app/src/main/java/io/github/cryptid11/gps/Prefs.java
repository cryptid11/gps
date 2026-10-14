package io.github.cryptid11.gps;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String ENABLED = "enabled";
    static final String INTERVAL = "interval";
    static final String GPS = "gps";
    static final String STILL = "still";
    /** Outcome of the last attempt when it did not save a point; absent otherwise. */
    static final String NOTE = "note";
    /** Bumped after every attempt so the UI knows to refresh. */
    static final String TICK = "tick";
    static final String ASKED_BATTERY = "asked_battery";
    /** {@link Outliers#VERSION} the stored history was last cleaned with. */
    static final String OUTLIER_RULES = "outlier_rules";

    static final int DEFAULT_INTERVAL_MIN = 10;
    static final int MAX_INTERVAL_MIN = 1440;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int intervalMinutes(Context c) {
        int v = get(c).getInt(INTERVAL, DEFAULT_INTERVAL_MIN);
        return Math.max(1, Math.min(MAX_INTERVAL_MIN, v));
    }
}
