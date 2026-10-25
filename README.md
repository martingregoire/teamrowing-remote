# Teamrowing Remote

An Android phone app for the trainer of a team rowing training session: a remote control for the
Spotify app on the same phone, with big, high-contrast controls.

It's the counterpart of [teamrowing-display](https://github.com/martingregoire/teamrowing-display),
which shows the current and next track with their tempo on a tablet for the participants.

## Features

- **Controls:** the current track, pushed live by the Spotify app, with previous, play/pause and
  next, and a progress bar to seek within the track.
- **Sessions:** each of the trainer's own Spotify playlists is a training session. The chosen
  session is remembered, and a playlist started in the Spotify app is picked up automatically.
- **Tempo:** each track's tempo (strokes per minute) next to the current track and in the session
  list, as prepared in the display app. Tracks marked "No tempo" show none, unknown ones "–".
- **Tempo correction:** tapping a tempo opens a bottom sheet to type a new value, step it by ±1,
  mark the track as "No tempo", or reset it to the detected value. Corrections are synced to the
  display app, right away or at the next connection. BPM lookups only happen in the display app.
- **Session list:** all tracks of the session with their tempo and duration, the current one
  highlighted and kept in the middle of the list; it scrolls back to it 8 seconds after scrolling
  by hand.
  Tapping a track plays the session from there, in playlist order (shuffle is turned off). Play
  starts the session from the beginning if something else was playing.
- **Sync with the display app:** the tempos come from
  [teamrowing-display](https://github.com/martingregoire/teamrowing-display) on the tablet, over
  Bluetooth while both apps are open. The phone keeps a copy, which it sends back on each connection,
  so it can restore the tablet's data. The connection status is shown below the session name.
- **Instant display updates:** while connected, the phone sends every track change, pause and seek
  to the display app, which shows it right away instead of at its next poll.
- The screen stays on while the app is open.

## How it works

- The [Spotify App Remote SDK](https://developer.spotify.com/documentation/android) controls the
  Spotify app on the same phone and pushes its player state, so no polling is needed.
- The [Spotify Web API](https://developer.spotify.com/documentation/web-api) provides the
  playlists. The app logs in with the Authorization Code flow with PKCE, so no client secret is
  needed.
- The tempos are synced with the display app over Bluetooth (RFCOMM): the remote connects to the
  display app on a paired computer, tablet or phone, starting with the last connected one, and
  retries every 10 seconds. It also sends the SDK's playback state on every change. The protocol
  and the BPM entry format (`sync/SyncSession.kt`, `bpm/BpmEntry.kt`) are kept identical in both
  apps; see the display app's README.
- The login also grants the SDK's `app-remote-control` permission. The SDK can't ask for it
  itself on Android 15: the Spotify app tries to open its permission dialog from the background,
  which Android blocks.

## Requirements

- A Spotify Premium account and the Spotify app on the phone.
- A Spotify app registered in the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard),
  separate from the display app's:
  - APIs: **Web API** and **Android**
  - Redirect URI: `teamrowing-remote://callback`
  - Android package `de.gregoire.teamrowingremote`, with the SHA-1 fingerprints of the debug key
    and the release key
- An Android phone with Android 8.0 (API 26) or newer.
- For the tempos: the phone paired with the display tablet in Android's Bluetooth settings, and the
  "Nearby devices" permission allowed when the app asks for it.
- On LineageOS with restricted network access for new apps, allow network access for the app
  once: App info → Mobile data & Wi-Fi → Network access.

## Development setup

- [mise](https://mise.jdx.dev) provides the JDK (`mise.toml`) and puts the Android SDK tools on the
  `PATH`. Run `mise install` once in the project directory.
- [Android Studio](https://developer.android.com/studio) installs the Android SDK to `~/Android/Sdk`.
- The Spotify App Remote SDK is only distributed as an AAR on
  [GitHub](https://github.com/spotify/android-sdk/releases) and is included in `app/libs`.
- The Spotify client ID goes into `~/.gradle/gradle.properties`, outside the repository:

  ```properties
  teamrowingRemote.spotifyClientId=<client ID from the Spotify dashboard>
  ```

The Spotify app isn't available on the emulator, so the app is developed against a real phone,
e.g. connected with wireless debugging. Build and install a debug version:

```sh
./gradlew installDebug
```

Get the debug key's SHA-1 fingerprint for the Spotify dashboard with:

```sh
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
```

## Release build

Release builds are signed with the same key as the display app, configured in
`~/.gradle/gradle.properties` as described in the display app's README
(`teamrowingDisplay.releaseKeystore`, `teamrowingDisplay.releaseKeystorePassword`,
`teamrowingDisplay.releaseKeyAlias`). Without it, Gradle builds an unsigned APK.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

For an update, increase `versionCode` (and `versionName`) in `app/build.gradle.kts` before
building.
