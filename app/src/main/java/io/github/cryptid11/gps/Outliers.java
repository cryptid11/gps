package io.github.cryptid11.gps;

/**
 * Finds fixes that jump away from the path and straight back, as network positions and
 * poor GPS fixes sometimes do. A fix is only judged once the fix after it exists.
 */
final class Outliers {
    /** Bump when the rules change, so stored history gets cleaned again. */
    static final int VERSION = 1;

    /** Faster than this between two fixes is not ground travel. */
    private static final double MAX_SPEED_MPS = 45;
    /** Detours shorter than this are left alone; they look like ordinary GPS scatter. */
    private static final double MIN_DETOUR_M = 300;
    /** Up to this many consecutive fixes can be dropped as one detour. */
    private static final int MAX_RUN = 2;

    private Outliers() {}

    /**
     * Returns which fixes to drop: runs of one or two fixes that sit far from the path on both
     * sides, where the path continues close to where it left, and where reaching them would
     * take an impossible speed or they are no further away than their own stated inaccuracy.
     * The first and last fix are never dropped. {@code acc} entries may be NaN.
     */
    static boolean[] find(long[] t, double[] lat, double[] lon, double[] acc) {
        int n = t.length;
        boolean[] bad = new boolean[n];
        for (int run = 1; run <= MAX_RUN; run++) {
            int before = -1;
            for (int i = 0; i < n; i++) {
                if (bad[i]) continue;
                if (before >= 0) {
                    int[] block = new int[run];
                    int k = 0, j = i;
                    for (; j < n && k < run; j++) {
                        if (!bad[j]) block[k++] = j;
                    }
                    int after = j;
                    while (after < n && bad[after]) after++;
                    if (k == run && after < n && detour(t, lat, lon, acc, before, block, after)) {
                        for (int b : block) bad[b] = true;
                        i = block[run - 1];
                        continue;
                    }
                }
                before = i;
            }
        }
        return bad;
    }

    private static boolean detour(long[] t, double[] lat, double[] lon, double[] acc,
            int before, int[] block, int after) {
        int first = block[0], last = block[block.length - 1];
        double out = metres(lat, lon, before, first), back = metres(lat, lon, last, after);
        double away = Math.min(out, back);
        if (away < MIN_DETOUR_M || metres(lat, lon, before, after) > away / 2) return false;
        double speed = Math.max(out / seconds(t, before, first), back / seconds(t, last, after));
        double worstAccuracy = 0;
        for (int b : block) {
            if (!Double.isNaN(acc[b])) worstAccuracy = Math.max(worstAccuracy, acc[b]);
        }
        return speed > MAX_SPEED_MPS || worstAccuracy > away / 3;
    }

    private static double seconds(long[] t, int a, int b) {
        return Math.max(1, (t[b] - t[a]) / 1000.0);
    }

    private static double metres(double[] lat, double[] lon, int a, int b) {
        return Stats.metres(lat[a], lon[a], lat[b], lon[b]);
    }
}
