package io.github.cryptid11.gps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes the service for each scheduled fix and restarts it after a reboot or an app update. */
public final class Receiver extends BroadcastReceiver {
    static final String TICK = "io.github.cryptid11.gps.TICK";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Prefs.get(c).getBoolean(Prefs.ENABLED, false)) return;
        String action = intent.getAction();
        if (!TrackService.canStartInBackground(c)) {
            Prefs.get(c).edit().putString(Prefs.NOTE, c.getString(R.string.note_blocked)).apply();
            return;
        }
        if (TICK.equals(action)) {
            TrackService.start(c, TrackService.ACTION_TICK);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            TrackService.start(c, TrackService.ACTION_START);
        }
    }
}
