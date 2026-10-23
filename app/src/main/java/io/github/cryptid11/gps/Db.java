package io.github.cryptid11.gps;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

final class Db extends SQLiteOpenHelper implements Exporter.Rows {
    private static final String COLUMNS = "t, lat, lon, acc, alt, spd, src, tz";
    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "fixes.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // t = UTC millis, tz = UTC offset in minutes at the time of the fix.
        db.execSQL("CREATE TABLE fix (t INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " acc REAL, alt REAL, spd REAL, src TEXT, tz INTEGER NOT NULL)");
        createChosenModes(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) createChosenModes(db);
    }

    /** Transport {@link Modes} set by hand, for the stretch that ends at the fix at time t. */
    private static void createChosenModes(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chosen_mode (t INTEGER PRIMARY KEY, mode INTEGER NOT NULL)");
    }

    void add(long t, Location l) {
        ContentValues v = new ContentValues();
        v.put("t", t);
        v.put("lat", l.getLatitude());
        v.put("lon", l.getLongitude());
        if (l.hasAccuracy()) v.put("acc", l.getAccuracy());
        if (l.hasAltitude()) v.put("alt", l.getAltitude());
        if (l.hasSpeed()) v.put("spd", l.getSpeed());
        v.put("src", l.getProvider());
        v.put("tz", TimeZone.getDefault().getOffset(t) / 60000);
        getWritableDatabase().insertWithOnConflict("fix", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long count() {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), "fix");
    }

    /** Passes the newest row to {@code r}; returns false when the table is empty. */
    boolean last(Exporter.Row r) throws IOException {
        return read("SELECT " + COLUMNS + " FROM fix ORDER BY t DESC LIMIT 1", r) > 0;
    }

    /** Replays the fixes with {@code from <= t < to}, oldest first. */
    void range(long from, long to, Exporter.Row r) throws IOException {
        read("SELECT " + COLUMNS + " FROM fix WHERE t >= " + from + " AND t < " + to + " ORDER BY t", r);
    }

    /** Deletes the fixes in {@code from <= t < to} that {@link Outliers} rejects; returns how many. */
    int removeOutliers(long from, long to) throws IOException {
        List<long[]> times = new ArrayList<>();
        List<double[]> points = new ArrayList<>();
        range(from, to, (t, lat, lon, acc, alt, spd, src, tz) -> {
            times.add(new long[] {t});
            points.add(new double[] {lat, lon, acc});
        });
        int n = times.size();
        long[] t = new long[n];
        double[] lat = new double[n], lon = new double[n], acc = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = times.get(i)[0];
            lat[i] = points.get(i)[0];
            lon[i] = points.get(i)[1];
            acc[i] = points.get(i)[2];
        }
        boolean[] bad = Outliers.find(t, lat, lon, acc);
        int removed = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < n; i++) {
                if (!bad[i]) continue;
                removed += db.delete("fix", "t = " + t[i], null);
                db.delete("chosen_mode", "t = " + t[i], null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return removed;
    }

    /** Modes set by hand for the stretches ending at fixes with {@code from <= t < to}, by that time. */
    Map<Long, Integer> chosenModes(long from, long to) {
        Map<Long, Integer> modes = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT t, mode FROM chosen_mode WHERE t >= " + from + " AND t < " + to, null)) {
            while (c.moveToNext()) modes.put(c.getLong(0), c.getInt(1));
        }
        return modes;
    }

    /** Sets the mode of the stretches ending at {@code ends}; a negative mode goes back to the guess. */
    void chooseMode(long[] ends, int mode) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long t : ends) {
                if (mode < 0) {
                    db.delete("chosen_mode", "t = " + t, null);
                    continue;
                }
                ContentValues v = new ContentValues();
                v.put("t", t);
                v.put("mode", mode);
                db.insertWithOnConflict("chosen_mode", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    boolean hasBefore(long t) {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), "fix", "t < " + t) > 0;
    }

    @Override
    public void each(Exporter.Row r) throws IOException {
        read("SELECT " + COLUMNS + " FROM fix ORDER BY t", r);
    }

    private int read(String sql, Exporter.Row r) throws IOException {
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                r.row(c.getLong(0), c.getDouble(1), c.getDouble(2),
                        c.isNull(3) ? Double.NaN : c.getDouble(3),
                        c.isNull(4) ? Double.NaN : c.getDouble(4),
                        c.isNull(5) ? Double.NaN : c.getDouble(5),
                        c.getString(6), c.getInt(7));
            }
            return c.getCount();
        }
    }
}
