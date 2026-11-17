# Ninni

**English** · [Türkçe](README.tr.md)

Control the lullaby phone in the baby's room from another room, over Bluetooth Low Energy.

An old Android phone sits next to the sleeping child and plays lullabies from Spotify, offline
and with Wi-Fi off so no notification wakes anyone up. **Ninni Player** runs on that phone.
**Ninni Remote** runs on the parents' phones and controls it from the next room: no Wi-Fi, no
internet, no account and no server.

- See what is playing, with its progress, and the player phone's battery
- Change the volume (the remote's own volume keys work too), play/pause, previous/next track
- Jump to a position, go 15 s back or forward, toggle shuffle and repeat
- **Volume limit**: the music never gets louder than the limit, even from the phone's own keys
- **Sleep timer** (10–60 min): optionally fades the music out over the last 10 minutes, then
  pauses it and puts the volume back for next time
- Pairing with a 4-digit code shown on the player; several remotes can be connected at once

## Requirements

- **Player:** an Android 10+ phone that can act as a BLE peripheral (the app checks this:
  *BLE broadcast support* ✓), with Spotify or another music app.
- **Remote:** any Android 10+ phone with Bluetooth.
- Range is that of Bluetooth: one or two rooms away, depending on the walls.

## Build and install

There are no prebuilt APKs; build them from this repository. You need a JDK 17 (Gradle 8.10.2
does not run on newer JDKs) and the Android SDK.

```sh
cd android
export JAVA_HOME=/path/to/jdk-17
./gradlew :protocol:testDebugUnitTest :player:assembleDebug :manager:assembleDebug
adb install -r player/build/outputs/apk/debug/player-debug.apk    # the phone next to the baby
adb install -r manager/build/outputs/apk/debug/manager-debug.apk  # the parents' phones
```

## First setup on the player phone

1. Open **Ninni Player** and tap **Start**; allow the Bluetooth permission (Android 12+).
2. **Notification access → Open** and enable Ninni Player. It is needed for the track name,
   position, seek, shuffle and repeat; volume and play/pause work without it. On Android 13+ a
   sideloaded app may first need *App info › ⋮ › Allow restricted settings*.
3. **Battery optimization → Open** and allow, so Android never stops the service.
4. Check that **BLE broadcast support** is ✓. If it is ✗, this phone cannot act as a BLE
   peripheral and cannot be the player.
5. Open **Ninni Remote** on a parent's phone and type the 4-digit code shown on the player once.

Wi-Fi can stay off. Airplane mode turns Bluetooth off; turn Bluetooth back on if you use it.
Spotify asks to go online at least once every 30 days to keep its downloads, so turn Wi-Fi on
now and then.

On Android 10–11 the remote needs the location permission **and location turned on** to scan
(an Android rule for BLE scanning); Android 12+ does not.

## How it works

| Part | File | What it does |
|---|---|---|
| Protocol | `android/protocol/.../Protocol.kt` | UUIDs, state JSON, commands, shared by both apps (+ unit tests). Described in [PROTOCOL.md](PROTOCOL.md) |
| Player screen | `android/player/.../MainActivity.kt` | Pairing code, start/stop, now playing, setup checklist. Dark on purpose |
| Player service | `android/player/.../PlayerService.kt` | Foreground service: collects the state, runs commands, survives screen-off, restarts after a reboot or update (`BootReceiver.kt`) |
| Player Bluetooth | `android/player/.../BleServer.kt` | Advertising + GATT server, per-connection code check, notification queue, long reads |
| Player media | `android/player/.../MediaWatcher.kt`, `MediaListenerService.kt` | Follows the music app's media session: title, position, seek, shuffle/repeat; falls back to media keys |
| Sleep timer | `android/player/.../SleepTimer.kt` | Fades the volume out step by step, pauses, then puts the volume back once the music has stopped |
| Remote screen | `android/manager/.../MainActivity.kt` | Big buttons, progress, volume and limit, sleep timer, battery; the phone's volume keys control the player while the app is open |
| Remote Bluetooth | `android/manager/.../PlayerConnection.kt` | Scan by service UUID, GATT operation queue, pairing code, reconnect with backoff |

No third-party libraries: only `androidx.core`, plus `androidx.media` on the player for
shuffle/repeat.

## Testing the player without a remote

With **nRF Connect**: connect, enable notifications on STATE, and write text to COMMAND:
`auth:1234`, `vol-`, `toggle` …

Or over adb, without Bluetooth or a code (`dumpsys` is only open to adb and the system):

```sh
adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService                # state
adb shell dumpsys activity service com.ender.ninniplayer/.PlayerService cmd sleep:120:60
```

---

Not affiliated with Spotify. The apps only use Android's standard media controls.
