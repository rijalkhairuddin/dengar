# Dengar – song finder for Android

Tap to identify a song (AudD), then read the lyrics in sync (LRCLIB).

- Home-screen widget showing the last song, with a Listen button
- Quick Settings tile and long-press app shortcut to start listening
- History, offline queue, share

## Build

GitHub Actions builds the APK on every push (Actions tab → "Build Dengar APK" → artifact **Dengar-APK**).
Locally: install Gradle 8.7 and JDK 17, then run `gradle assembleDebug`.

Add your own AudD key in the app's Settings (get one at dashboard.audd.io); without it the free test key allows about 10 songs a day.
