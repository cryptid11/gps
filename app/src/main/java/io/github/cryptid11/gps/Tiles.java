package io.github.cryptid11.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * OpenStreetMap raster tiles, kept in the app's cache directory so a map that was fetched once
 * keeps working offline. Tiles the user is looking at are downloaded on any connection; the
 * background pre-download around recent positions runs on Wi-Fi only.
 */
final class Tiles {
    static final int SIZE = 256;
    static final int MAX_ZOOM = 19;

    private static final String SERVER = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final int MEMORY_BYTES = 16 * 1024 * 1024;
    /** After this many failed downloads in a row, stop trying for a while. */
    private static final int MAX_FAILURES = 3;
    private static final long BACKOFF_MS = 60_000;

    /** Zoom levels fetched ahead of time around recent positions: city overview to street level. */
    private static final int PREFETCH_MIN_ZOOM = 11;
    private static final int PREFETCH_MAX_ZOOM = 16;
    private static final int PREFETCH_MAX_TILES = 200;
    private static final long PREFETCH_EVERY_MS = 10 * 60_000L;
    private static final long PREFETCH_BUDGET_MS = 60_000;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    // Holds the application context only, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private static Tiles instance;

    private final Context context;
    private final File dir;
    private final String agent;
    private final ConnectivityManager connectivity;
    private final PowerManager.WakeLock wakeLock;
    // Two workers: the tile server's usage policy allows at most two connections.
    private final ThreadPoolExecutor pool =
            new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private final LruCache<Long, Bitmap> memory = new LruCache<Long, Bitmap>(MEMORY_BYTES) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<Long> loading = ConcurrentHashMap.newKeySet();
    private int failures;
    private long blockedUntil;
    private long lastPrefetch;
    private boolean prefetching;

    static synchronized Tiles get(Context c) {
        if (instance == null) instance = new Tiles(c.getApplicationContext());
        return instance;
    }

    private Tiles(Context c) {
        context = c;
        dir = new File(c.getCacheDir(), "tiles");
        String version = "dev";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        agent = "GPSLog/" + version + " (+https://github.com/cryptid11/gps)";
        connectivity = c.getSystemService(ConnectivityManager.class);
        wakeLock = c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpslog:tiles");
        wakeLock.setReferenceCounted(false);
        pool.allowCoreThreadTimeOut(true);
    }

    static double worldX(double lon) {
        return (lon + 180) / 360;
    }

    /** Web Mercator: 0 at the top of the map (85°N), 1 at the bottom. */
    static double worldY(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-85.05, Math.min(85.05, lat))));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    static double latitude(double worldY) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * worldY))));
    }

    private static long key(int z, int x, int y) {
        return ((long) z << 50) | ((long) x << 25) | y;
    }

    private File file(int z, int x, int y) {
        return new File(dir, z + "/" + x + "/" + y + ".png");
    }

    /** The decoded tile if it is in memory, else null; see {@link #load}. */
    Bitmap cached(int z, int x, int y) {
        return memory.get(key(z, x, y));
    }

    /** Brings a tile into memory from disk, or else from the network, then runs {@code onLoaded}. */
    void load(int z, int x, int y, Runnable onLoaded) {
        long key = key(z, x, y);
        if (!loading.add(key)) return;
        pool.execute(() -> {
            try {
                File f = file(z, x, y);
                if (!f.exists() && !download(z, x, y, f)) return;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeFile(f.getPath(), options);
                if (bitmap == null) {
                    f.delete();
                    return;
                }
                memory.put(key, bitmap);
                onLoaded.run();
            } finally {
                loading.remove(key);
            }
        });
    }

    boolean onWifi() {
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private boolean download(int z, int x, int y, File target) {
        synchronized (this) {
            if (SystemClock.elapsedRealtime() < blockedUntil) return false;
        }
        boolean ok = false;
        HttpURLConnection c = null;
        File partial = new File(target.getPath() + ".part");
        try {
            target.getParentFile().mkdirs();
            c = (HttpURLConnection) new URL(String.format(Locale.US, SERVER, z, x, y)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", agent);
            if (c.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[16 * 1024];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                }
                ok = partial.renameTo(target);
            }
        } catch (IOException | RuntimeException e) {
            // Counted as a failure below.
        } finally {
            if (c != null) c.disconnect();
            if (!ok) partial.delete();
        }
        synchronized (this) {
            failures = ok ? 0 : failures + 1;
            if (failures >= MAX_FAILURES) {
                failures = 0;
                blockedUntil = SystemClock.elapsedRealtime() + BACKOFF_MS;
            }
        }
        return ok;
    }

    /**
     * Downloads the map around the last day's positions, so the track has a map under it even
     * for places that were never looked at. Runs on Wi-Fi only, to spare mobile data and
     * battery, and only fetches tiles that are not on disk yet.
     */
    void prefetchRecent() {
        synchronized (this) {
            long now = SystemClock.elapsedRealtime();
            if (prefetching || (lastPrefetch != 0 && now - lastPrefetch < PREFETCH_EVERY_MS)) return;
            if (!onWifi()) return;
            prefetching = true;
            lastPrefetch = now;
        }
        wakeLock.acquire(PREFETCH_BUDGET_MS + 10_000);
        pool.execute(() -> {
            try {
                long deadline = SystemClock.elapsedRealtime() + PREFETCH_BUDGET_MS;
                for (long key : missingAroundRecent()) {
                    int z = (int) (key >>> 50), x = (int) (key >>> 25) & 0x1FFFFFF, y = (int) key & 0x1FFFFFF;
                    boolean ok = download(z, x, y, file(z, x, y));
                    boolean blocked;
                    synchronized (this) {
                        blocked = SystemClock.elapsedRealtime() < blockedUntil;
                    }
                    if ((!ok && blocked) || SystemClock.elapsedRealtime() > deadline || !onWifi()) break;
                }
            } catch (IOException | RuntimeException ignored) {
            } finally {
                synchronized (this) {
                    prefetching = false;
                }
                if (wakeLock.isHeld()) wakeLock.release();
            }
        });
    }

    /** Tiles under and next to each recent fix, overview zooms first, that are not cached yet. */
    private Set<Long> missingAroundRecent() throws IOException {
        Set<Long> wanted = new LinkedHashSet<>();
        Set<Long> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (int zoom = PREFETCH_MIN_ZOOM; zoom <= PREFETCH_MAX_ZOOM; zoom++) {
            int z = zoom, n = 1 << z;
            Db.get(context).range(now - DAY_MS, now + 1, (t, lat, lon, acc, alt, spd, src, tz) -> {
                int tx = (int) Math.floor(worldX(lon) * n), ty = (int) Math.floor(worldY(lat) * n);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = Math.floorMod(tx + dx, n), y = ty + dy;
                        if (y < 0 || y >= n || wanted.size() >= PREFETCH_MAX_TILES) continue;
                        if (seen.add(key(z, x, y)) && !file(z, x, y).exists()) wanted.add(key(z, x, y));
                    }
                }
            });
        }
        return wanted;
    }
}
