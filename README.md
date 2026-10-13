# GPS Log

A tiny Android app that saves your position every few minutes and exports it in the
Google Maps Timeline format. It needs no Google services, so it works where Timeline does not.

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/cryptid11/gps)

Tap the badge on your phone to add the app to [Obtainium](https://obtainium.imranr.dev/); it then
updates itself from this repo's [releases](https://github.com/cryptid11/gps/releases). You can also
install the APK from the latest release by hand.

## Options

| Option | Default | What it does |
| --- | --- | --- |
| Tracking | off | Starts and stops logging. |
| Save a position every (minutes) | 10 | Time between fixes, 1 to 1440. |
| Use GPS | on | Off uses network location only: least battery, much less precise. |
| Pause while stationary | on | Skips fixes while the phone has not moved, with one check per hour. |

**Last 24 hours** draws the day's track: newest points are the most solid, the ringed dot is
where you are now, and places you stayed at for 20 minutes or more show the time range of each
visit. Drag to pan, pinch to zoom, double-tap to fit, tap a point for its time and a link to
open it in a maps app. The arrows page back a day at a time. There is no map underneath, so the
app still needs no network access.

**Export Timeline.json** writes every saved point to a file you pick. **Delete all points** empties the log.

## Battery

The app does nothing between fixes: no wake lock, no polling, no network. For each fix it

1. reuses a position another app obtained in the last 30 seconds, if there is one;
2. otherwise turns GPS on, stops as soon as the fix is accurate to 20 m or 6 seconds after the
   first fix, and gives up after 45 seconds (indoors, the network position is saved instead);
3. skips the attempt entirely while the phone has not moved, going by the motion sensor and
   by Android's own deep-sleep (Doze) state.

## Setup on the phone

Grant location as **Allow all the time**, and accept the battery prompt. Without "all the time",
logging works until the next reboot or app update, then waits for you to open the app.

Chinese Android skins (MIUI/HyperOS, ColorOS, EMUI, OriginOS) kill background apps regardless.
On those, also enable **Autostart** for GPS Log, set its battery mode to **No restrictions**, and
lock it in the recents screen.

## Export format

The file matches the on-device Timeline export from Google Maps on Android:

```json
{
  "semanticSegments": [
    {
      "startTime": "2026-10-13T14:00:00.000+08:00",
      "endTime": "2026-10-13T16:00:00.000+08:00",
      "timelinePath": [
        { "point": "31.2310001°, 121.4741234°", "time": "2026-10-13T14:00:00.123+08:00" }
      ]
    }
  ],
  "rawSignals": [
    {
      "position": {
        "LatLng": "31.2310001°, 121.4741234°",
        "accuracyMeters": 12,
        "altitudeMeters": 15.3,
        "source": "GPS",
        "timestamp": "2026-10-13T14:00:00.123+08:00",
        "speedMetersPerSecond": 0.07
      }
    }
  ],
  "userLocationProfile": { "frequentPlaces": [] }
}
```

Every point appears twice, as Google does it: in a two-hour `timelinePath` block and as a
`rawSignals` position. Visits, activities and frequent places are Google's own guesses about
your day and are not generated.

Coordinates are stored as the phone reports them. GPS fixes are WGS-84, like Google's. Network
fixes on some Chinese phones are GCJ-02 and can sit a few hundred metres off; they are marked
`"source": "UNKNOWN"` in `rawSignals`.

## Build

```sh
./gradlew assembleRelease   # needs JDK 17 and the Android SDK
```

Every push to `main` builds and publishes a release through GitHub Actions.

Releases are signed with the keystore in `signing/`, which is public like the rest of the repo.
That keeps every build on the same key so updates install in place, but it means anyone can sign
an APK that Android accepts as an update to this app, so install it only from this repo. To use
a private key, set `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the
build environment. Switching keys requires uninstalling first: export your data before you do.
