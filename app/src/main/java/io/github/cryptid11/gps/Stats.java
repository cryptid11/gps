package io.github.cryptid11.gps;

/** Distance, time, speed and climbing for a stretch of fixes, plus the series for the charts. */
final class Stats {
    /** Hops shorter than this, or slower than the speed below, count as standing still. */
    private static final double MIN_MOVE_M = 15;
    private static final double MIN_MOVE_MPS = 0.5;
    /** Altitude changes smaller than this are treated as GPS noise when adding up climbing. */
    private static final double CLIMB_STEP_M = 3;

    double distanceM;
    long elapsedMs;
    long movingMs;
    double maxSpeedMps;
    double gainM;
    double maxAltM = Double.NaN;
    /** Speed between consecutive fixes, at the middle of each hop: ms since start, m/s. */
    double[] speedAt = {};
    double[] speed = {};
    /** Smoothed altitude by distance travelled: metres, metres. */
    double[] altAt = {};
    double[] alt = {};

    double averageSpeedMps() {
        return movingMs > 0 ? distanceM / (movingMs / 1000.0) : 0;
    }

    /** {@code alt} and {@code spd} entries are NaN where unknown. */
    static Stats of(long[] t, double[] lat, double[] lon, double[] alt, double[] spd) {
        Stats s = new Stats();
        int n = t.length;
        if (n == 0) return s;
        s.elapsedMs = t[n - 1] - t[0];
        double[] along = new double[n];
        s.speedAt = new double[Math.max(0, n - 1)];
        s.speed = new double[Math.max(0, n - 1)];
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(spd[i])) s.maxSpeedMps = Math.max(s.maxSpeedMps, spd[i]);
            if (i == 0) continue;
            double d = metres(lat[i - 1], lon[i - 1], lat[i], lon[i]);
            double secs = Math.max(1, (t[i] - t[i - 1]) / 1000.0);
            double v = d / secs;
            boolean moving = d >= MIN_MOVE_M && v >= MIN_MOVE_MPS;
            if (moving) {
                s.distanceM += d;
                s.movingMs += t[i] - t[i - 1];
                s.maxSpeedMps = Math.max(s.maxSpeedMps, v);
            }
            along[i] = s.distanceM;
            s.speedAt[i - 1] = (t[i - 1] + t[i]) / 2.0 - t[0];
            s.speed[i - 1] = moving ? v : 0;
        }

        // Altitude: three-point average, then count only rises beyond the noise step.
        int m = 0;
        for (double a : alt) if (!Double.isNaN(a)) m++;
        s.altAt = new double[m];
        s.alt = new double[m];
        double[] raw = new double[m];
        for (int i = 0, k = 0; i < n; i++) {
            if (Double.isNaN(alt[i])) continue;
            s.altAt[k] = along[i];
            raw[k++] = alt[i];
        }
        for (int k = 0; k < m; k++) {
            int from = Math.max(0, k - 1), to = Math.min(m - 1, k + 1);
            double sum = 0;
            for (int j = from; j <= to; j++) sum += raw[j];
            s.alt[k] = sum / (to - from + 1);
            s.maxAltM = k == 0 ? s.alt[k] : Math.max(s.maxAltM, s.alt[k]);
        }
        if (m > 0) {
            double ref = s.alt[0];
            for (int k = 1; k < m; k++) {
                if (s.alt[k] > ref + CLIMB_STEP_M) {
                    s.gainM += s.alt[k] - ref;
                    ref = s.alt[k];
                } else if (s.alt[k] < ref - CLIMB_STEP_M) {
                    ref = s.alt[k];
                }
            }
        }
        return s;
    }

    /** Great-circle distance. */
    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
