package io.github.cryptid11.gps;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.io.IOException;
import java.util.TimeZone;

final class Db extends SQLiteOpenHelper implements Exporter.Rows {
    private static final String COLUMNS = "t, lat, lon, acc, alt, spd, src, tz";
    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "fixes.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // t = UTC millis, tz = UTC offset in minutes at the time of the fix.
        db.execSQL("CREATE TABLE fix (t INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,"
                + " acc REAL, alt REAL, spd REAL, src TEXT, tz INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {}

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

    void clear() {
        getWritableDatabase().delete("fix", null, null);
    }

    /** Passes the newest row to {@code r}; returns false when the table is empty. */
    boolean last(Exporter.Row r) throws IOException {
        return read("SELECT " + COLUMNS + " FROM fix ORDER BY t DESC LIMIT 1", r) > 0;
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
