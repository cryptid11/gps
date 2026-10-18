package io.github.cryptid11.gps;

/**
 * Guesses how each stretch between two fixes was travelled, from speed alone. It cannot tell
 * a car from a bus, or running from cycling, and with fixes minutes apart a stretch that mixes
 * walking and driving gets their average; it is a hint, not a record.
 */
final class Modes {
    static final int STILL = 0;
    static final int WALK = 1;
    static final int BIKE = 2;
    static final int CAR = 3;
    static final int TRAIN = 4;
    static final int PLANE = 5;
    static final int COUNT = 6;

    private static final int[] COLORS = {0xFF9E9E9E, 0xFF43A047, 0xFFFB8C00, 0xFF1E88E5, 0xFF8E24AA, 0xFFE53935};
    private static final String[] ICONS = {"", "🚶", "🚲", "🚗", "🚄", "✈️"};

    /** Upper speed limits of walking, bike or run, road, and train, in km/h. */
    private static final double[] LIMITS_KMH = {7.5, 25, 140, 330};
    /** Hops shorter or slower than this are standing still, as in {@link Stats}... */
    private static final double MIN_MOVE_M = 15;
    private static final double MIN_MOVE_MPS = 0.5;
    /** ...unless they cover this much ground beyond both fixes' inaccuracy: a slow stroll with stops. */
    private static final double CLEAR_MOVE_M = 100;

    private Modes() {}

    static int color(int mode) {
        return COLORS[mode];
    }

    static String icon(int mode) {
        return ICONS[mode];
    }

    static int of(double mps) {
        double kmh = mps * 3.6;
        for (int i = 0; i < LIMITS_KMH.length; i++) {
            if (kmh < LIMITS_KMH[i]) return WALK + i;
        }
        return PLANE;
    }

    /**
     * Speed of each stretch between consecutive fixes in m/s, 0 when standing still. With
     * fixes far apart the average underrates the speed of travel (traffic lights, waiting),
     * so when both ends carry a GPS speed, the lower of the two is taken if it is higher.
     * A stretch counts as moving when it is fast enough, or when it covers clearly more
     * ground than GPS scatter could ({@code acc} may hold NaN).
     */
    static double[] speeds(long[] t, double[] lat, double[] lon, double[] spd, double[] acc) {
        int n = Math.max(0, t.length - 1);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            double d = Stats.metres(lat[i], lon[i], lat[i + 1], lon[i + 1]);
            double avg = d / Math.max(1, (t[i + 1] - t[i]) / 1000.0);
            double scatter = (Double.isNaN(acc[i]) ? 0 : acc[i]) + (Double.isNaN(acc[i + 1]) ? 0 : acc[i + 1]);
            boolean clearlyMoved = d >= Math.max(CLEAR_MOVE_M, scatter);
            if (d < MIN_MOVE_M || (avg < MIN_MOVE_MPS && !clearlyMoved)) continue;
            double atEnds = Double.isNaN(spd[i]) || Double.isNaN(spd[i + 1]) ? 0 : Math.min(spd[i], spd[i + 1]);
            v[i] = Math.max(avg, atEnds);
        }
        return v;
    }

    /** Mode of each stretch: speeds smoothed with their moving neighbours (median of three), then {@link #of}. */
    static int[] classify(double[] speeds) {
        int n = speeds.length;
        int[] modes = new int[n];
        for (int i = 0; i < n; i++) {
            if (speeds[i] == 0) continue;
            double before = i > 0 && speeds[i - 1] > 0 ? speeds[i - 1] : speeds[i];
            double after = i + 1 < n && speeds[i + 1] > 0 ? speeds[i + 1] : speeds[i];
            modes[i] = of(median(before, speeds[i], after));
        }
        return modes;
    }

    /** Distance covered in each mode, indexed by mode. */
    static double[] distances(double[] lat, double[] lon, int[] modes) {
        double[] metres = new double[COUNT];
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] != STILL) metres[modes[i]] += Stats.metres(lat[i], lon[i], lat[i + 1], lon[i + 1]);
        }
        return metres;
    }

    private static double median(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
