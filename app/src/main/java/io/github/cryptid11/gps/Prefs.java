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
    /** Whether the track screen colours stretches by guessed transport mode; replaced by MAP_STYLE. */
    static final String SHOW_MODES = "show_modes";
    /** How the map draws the track: one of the MAP_ values, in the order of R.array.map_styles. */
    static final String MAP_STYLE = "map_style";
    static final int MAP_TRACK = 0;
    static final int MAP_TRANSPORT = 1;
    static final int MAP_HEAT = 2;
    /** How many days the track screen shows at once. */
    static final String TRACK_DAYS = "track_days";
    /** {@link Outliers#VERSION} the stored history was last cleaned with. */
    static final String OUTLIER_RULES = "outlier_rules";

    static final int DEFAULT_INTERVAL_MIN = 10;
    static final int MAX_INTERVAL_MIN = 1440;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static int mapStyle(Context c) {
        SharedPreferences p = get(c);
        int style = p.getInt(MAP_STYLE, p.getBoolean(SHOW_MODES, false) ? MAP_TRANSPORT : MAP_TRACK);
        return style >= MAP_TRACK && style <= MAP_HEAT ? style : MAP_TRACK;
    }

    static int intervalMinutes(Context c) {
        int v = get(c).getInt(INTERVAL, DEFAULT_INTERVAL_MIN);
        return Math.max(1, Math.min(MAX_INTERVAL_MIN, v));
    }
}
