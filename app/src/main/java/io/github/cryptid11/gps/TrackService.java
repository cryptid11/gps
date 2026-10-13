package io.github.cryptid11.gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Stays in the foreground doing nothing between fixes. Each alarm tick turns the location
 * providers on just long enough to get one usable position, saves it, and turns them off again.
 */
public final class TrackService extends Service implements LocationListener {
    static final String ACTION_START = "start";
    static final String ACTION_TICK = "tick";

    private static final String CHANNEL = "tracking";
    private static final int NOTIFICATION_ID = 1;

    /** Longest a single attempt keeps the radios on. */
    private static final long GPS_TIMEOUT_MS = 45_000;
    private static final long NETWORK_TIMEOUT_MS = 20_000;
    /** After the first GPS fix, how long to wait for the accuracy to tighten. */
    private static final long SETTLE_MS = 6_000;
    /** A fix this accurate ends the attempt at once. */
    private static final float GOOD_ACCURACY_M = 20f;
    /** A position another app obtained this recently is reused instead of powering anything on. */
    private static final long REUSE_AGE_MS = 30_000;
    private static final float REUSE_ACCURACY_M = 50f;
    /** While stationary, a fix is still attempted this often. */
    private static final long STILL_CHECK_MS = 60 * 60_000L;
    private static final float MOVING_SPEED_MPS = 1f;
    private static final float MOVING_DISTANCE_M = 100f;

    private static volatile boolean running;
    private static PowerManager.WakeLock wakeLock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::finishFix;
    private final TriggerEventListener motionListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            // One-shot sensor: it disarms itself when it fires.
            motionArmed = false;
            moved = true;
        }
    };

    private LocationManager locations;
    private SensorManager sensors;
    private Sensor motionSensor;

    private boolean foreground;
    private boolean fixing;
    private boolean gpsSeen;
    private Location best;

    private Location lastSaved;
    private String lastSavedClock;
    private long lastAttempt;
    private boolean lastAttemptStill;
    private boolean motionArmed;
    private boolean moved = true;
    private boolean pausedNoted;

    static boolean isRunning() {
        return running;
    }

    static boolean hasLocationPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether a start from a receiver can lead to fixes. Android 11+ only lets a service
     * started from the background use location with the "all the time" permission; an
     * already running service keeps the access it got when the user started it.
     */
    static boolean canStartInBackground(Context c) {
        return running || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    static void start(Context c, String action) {
        if (!hasLocationPermission(c)) return;
        Intent i = new Intent(c, TrackService.class).setAction(action);
        // Bridges the gap until onStartCommand; the alarm's own wake lock ends with onReceive.
        acquireWake(c, 10_000);
        try {
            try {
                c.startService(i);
            } catch (IllegalStateException e) {
                // App is in the background with no service running yet.
                c.startForegroundService(i);
            }
        } catch (RuntimeException e) {
            // Start refused by the system; keep the alarm chain alive so a later tick retries.
            releaseWake();
            schedule(c, Prefs.intervalMinutes(c) * 60_000L);
        }
    }

    static void stop(Context c) {
        c.getSystemService(AlarmManager.class).cancel(tickIntent(c));
        c.stopService(new Intent(c, TrackService.class));
    }

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, Receiver.class).setAction(Receiver.TICK);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(Context c, long delayMs) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + delayMs;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent(c));
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, tickIntent(c));
        }
    }

    private static synchronized void acquireWake(Context c, long ms) {
        if (wakeLock == null) {
            wakeLock = c.getApplicationContext().getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpslog:fix");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(ms);
    }

    private static synchronized void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        locations = getSystemService(LocationManager.class);
        sensors = getSystemService(SensorManager.class);
        motionSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = Prefs.get(this);
        if (!foreground) foreground = enterForeground();
        if (!foreground || !prefs.getBoolean(Prefs.ENABLED, false)) {
            if (!foreground) prefs.edit().putString(Prefs.NOTE, getString(R.string.note_blocked)).apply();
            releaseWake();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Re-arm first so the chain survives whatever happens to this attempt.
        long intervalMs = Prefs.intervalMinutes(this) * 60_000L;
        schedule(this, intervalMs);

        boolean tick = intent != null && ACTION_TICK.equals(intent.getAction());
        if (fixing) return START_STICKY;
        if (tick && canSkip(prefs)) {
            if (!pausedNoted) {
                pausedNoted = true;
                note(prefs, getString(R.string.note_still, clock()));
            }
            releaseWake();
            return START_STICKY;
        }
        startFix(prefs);
        return START_STICKY;
    }

    private boolean enterForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
            return true;
        } catch (RuntimeException e) {
            // Android 12+ refuses some background starts, 14+ needs the location permission.
            return false;
        }
    }

    /**
     * True when the phone has demonstrably stayed put since the last attempt. Either the
     * system is in deep Doze, which it only enters when the device is motionless (and where
     * it turns GPS off anyway), or the motion sensor has not fired and the last fix showed
     * neither speed nor displacement (which covers smooth rides, such as trains, that the
     * motion sensor can miss).
     */
    private boolean canSkip(SharedPreferences prefs) {
        if (!prefs.getBoolean(Prefs.STILL, true)
                || SystemClock.elapsedRealtime() - lastAttempt >= STILL_CHECK_MS) {
            return false;
        }
        return getSystemService(PowerManager.class).isDeviceIdleMode()
                || (motionArmed && !moved && lastAttemptStill);
    }

    private void startFix(SharedPreferences prefs) {
        boolean useGps = prefs.getBoolean(Prefs.GPS, true);
        long limit = useGps ? GPS_TIMEOUT_MS : NETWORK_TIMEOUT_MS;
        acquireWake(this, limit + 10_000);
        fixing = true;
        gpsSeen = false;
        best = null;

        // Watch for motion from here on, so movement during the attempt is not lost.
        moved = false;
        if (motionSensor != null && !motionArmed) {
            motionArmed = sensors.requestTriggerSensor(motionListener, motionSensor);
        }

        best = recentFix();
        if (best != null) {
            finishFix();
            return;
        }
        int requested = 0;
        if (useGps) requested += request(LocationManager.GPS_PROVIDER, 1000);
        // One network fix is enough; it is only the fallback when GPS finds nothing.
        requested += request(LocationManager.NETWORK_PROVIDER, 60_000);
        if (requested == 0) {
            finishFix();
            return;
        }
        handler.postDelayed(timeout, limit);
    }

    @SuppressLint("MissingPermission")
    private int request(String provider, long minTimeMs) {
        try {
            if (!locations.isProviderEnabled(provider)) return 0;
            locations.requestLocationUpdates(provider, minTimeMs, 0f, this, Looper.getMainLooper());
            return 1;
        } catch (RuntimeException e) {
            // Provider missing on this device, or only approximate location was granted.
            return 0;
        }
    }

    @SuppressLint("MissingPermission")
    private Location recentFix() {
        Location found = null;
        String[] providers = {
                LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String provider : providers) {
            Location l;
            try {
                l = locations.getLastKnownLocation(provider);
            } catch (RuntimeException e) {
                continue;
            }
            if (l != null && ageMs(l) < REUSE_AGE_MS && l.hasAccuracy()
                    && l.getAccuracy() <= REUSE_ACCURACY_M && better(l, found)) {
                found = l;
            }
        }
        return found;
    }

    private static boolean better(Location a, Location b) {
        if (b == null) return true;
        if (!a.hasAccuracy()) return false;
        return !b.hasAccuracy() || a.getAccuracy() < b.getAccuracy();
    }

    private static long ageMs(Location l) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!fixing) return;
        if (better(l, best)) best = l;
        if (l.hasAccuracy() && l.getAccuracy() <= GOOD_ACCURACY_M) {
            finishFix();
        } else if (!gpsSeen && LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            gpsSeen = true;
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, SETTLE_MS);
        }
    }

    private void finishFix() {
        if (!fixing) return;
        fixing = false;
        handler.removeCallbacks(timeout);
        try {
            locations.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
        Location l = best;
        best = null;
        lastAttempt = SystemClock.elapsedRealtime();
        pausedNoted = false;

        SharedPreferences prefs = Prefs.get(this);
        if (l == null) {
            // Nothing in reach; retrying before the phone moves would find the same nothing.
            lastAttemptStill = true;
            note(prefs, getString(R.string.note_no_fix, clock()));
        } else {
            Db.get(this).add(System.currentTimeMillis() - ageMs(l), l);
            lastAttemptStill = lastSaved != null
                    && !(l.hasSpeed() && l.getSpeed() >= MOVING_SPEED_MPS)
                    && lastSaved.distanceTo(l) < Math.max(MOVING_DISTANCE_M, lastSaved.getAccuracy() + l.getAccuracy());
            lastSaved = l;
            lastSavedClock = clock();
            note(prefs, null);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        releaseWake();
    }

    private static void note(SharedPreferences prefs, String text) {
        prefs.edit().putString(Prefs.NOTE, text).putLong(Prefs.TICK, System.currentTimeMillis()).apply();
    }

    private static String clock() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private Notification notification() {
        int minutes = Prefs.intervalMinutes(this);
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pin)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(lastSavedClock == null
                        ? getString(R.string.notif_waiting, minutes)
                        : getString(R.string.notif_last, minutes, lastSavedClock))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        fixing = false;
        handler.removeCallbacks(timeout);
        try {
            locations.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
        if (motionSensor != null) sensors.cancelTriggerSensor(motionListener, motionSensor);
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Abstract on API levels below 30.
    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {}
}
