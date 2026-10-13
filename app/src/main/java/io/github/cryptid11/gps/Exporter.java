package io.github.cryptid11.gps;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes saved fixes as a Google Maps Timeline on-device export ("Timeline.json"):
 * {@code semanticSegments} holding two-hour {@code timelinePath} blocks, {@code rawSignals}
 * holding one {@code position} per fix, and an empty {@code userLocationProfile}.
 * Visits and activities are Google's own inferences and are not produced.
 */
final class Exporter {
    interface Row {
        /** {@code acc}, {@code alt} and {@code spd} are NaN when unknown. */
        void row(long t, double lat, double lon, double acc, double alt, double spd, String src, int tz)
                throws IOException;
    }

    interface Rows {
        /** Replays every fix, oldest first. */
        void each(Row r) throws IOException;
    }

    private static final long BLOCK_MS = 2 * 60 * 60 * 1000L;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);

    private Exporter() {}

    /** Returns the number of fixes written. */
    static int write(Rows rows, Writer w) throws IOException {
        w.write("{\n  \"semanticSegments\": [");
        long[] block = {Long.MIN_VALUE, 0}; // local start of the open block, its tz
        int[] n = {0};
        rows.each((t, lat, lon, acc, alt, spd, src, tz) -> {
            long start = Math.floorDiv(t + tz * 60000L, BLOCK_MS) * BLOCK_MS;
            if (n[0] == 0 || start != block[0] || tz != block[1]) {
                if (n[0] > 0) w.write("\n      ]\n    },");
                long utc = start - tz * 60000L;
                w.write("\n    {\n      \"startTime\": \"" + time(utc, tz)
                        + "\",\n      \"endTime\": \"" + time(utc + BLOCK_MS, tz)
                        + "\",\n      \"timelinePath\": [");
                block[0] = start;
                block[1] = tz;
            } else {
                w.write(",");
            }
            w.write("\n        {\n          \"point\": \"" + latLng(lat, lon)
                    + "\",\n          \"time\": \"" + time(t, tz) + "\"\n        }");
            n[0]++;
        });
        if (n[0] > 0) w.write("\n      ]\n    }\n  ");
        w.write("],\n  \"rawSignals\": [");

        boolean[] first = {true};
        rows.each((t, lat, lon, acc, alt, spd, src, tz) -> {
            StringBuilder s = new StringBuilder(first[0] ? "" : ",");
            first[0] = false;
            s.append("\n    {\n      \"position\": {\n        \"LatLng\": \"").append(latLng(lat, lon)).append('"');
            if (!Double.isNaN(acc)) s.append(",\n        \"accuracyMeters\": ").append(Math.round(acc));
            if (!Double.isNaN(alt)) s.append(",\n        \"altitudeMeters\": ").append(number("%.1f", alt));
            s.append(",\n        \"source\": \"").append("gps".equals(src) ? "GPS" : "UNKNOWN").append('"');
            s.append(",\n        \"timestamp\": \"").append(time(t, tz)).append('"');
            if (!Double.isNaN(spd)) s.append(",\n        \"speedMetersPerSecond\": ").append(number("%.2f", spd));
            s.append("\n      }\n    }");
            w.write(s.toString());
        });
        if (!first[0]) w.write("\n  ");
        w.write("],\n  \"userLocationProfile\": {\n    \"frequentPlaces\": []\n  }\n}\n");
        return n[0];
    }

    static String latLng(double lat, double lon) {
        return String.format(Locale.US, "%.7f°, %.7f°", lat, lon);
    }

    /** Local time at the UTC offset the fix was taken in, e.g. 2024-06-06T11:44:37.000+08:00. */
    static String time(long utcMillis, int tzMinutes) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(utcMillis), ZoneOffset.ofTotalSeconds(tzMinutes * 60))
                .format(TIME);
    }

    private static String number(String format, double v) {
        return String.format(Locale.US, format, v);
    }
}
