# Kunai Android

Native Android entrypoint for Kunai, sharing one codebase between the XGIMI
TV layout and Nothing Phone's touch layout. It does not launch a WebView,
Node, Bun, Python, Termux, Moonlight, Mirai, or Philipedia.

## Architecture

- **Kotlin / Compose:** responsive Kunai Ember Dusk interface with native focus
  rings and direct navigation, selected by the Android TV device feature.
- **Media3:** hardware-decoded media, source headers, adaptive HLS/DASH and
  external subtitles. All playback stays on the device.
- **Catalog:** on-device AniList GraphQL and a TMDB-format movie/series
  catalog; thumbnails and real season/episode metadata.
- **Resolvers:** Kotlin port of the existing KickAssAnime provider contract and
  Movy's encrypted source contract. Both fail closed when streams cannot be
  verified, rather than launching a wrong title or pretending to play.
- **Local library:** My List and last-watch position remain on the Android
  device. Storage is currently JSON in private SharedPreferences; migrating to
  Room/DataStore remains planned.
- **Optional integrations:** cross-device sync and projector remote controls
  must stay optional, never a prerequisite to browse or play.

The installed XGIMI hardware is Android 11 / API 30, 32-bit ARM
(`armeabi-v7a`), with ~2 GB RAM. This requires low-memory TV operation.
The Nothing Phone runs the same package with touch navigation.

## Build

From this directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Uses Gradle 9.6.1, AGP 9.1, Kotlin/Compose, Android SDK 37, JDK 17.
The APK is `app/build/outputs/apk/debug/app-debug.apk` and application ID
`dev.kitsunekode.kunai`. Debug APKs are for local device QA only, not a
signed public release.

## Device QA (November 22, 2026)

- Version **0.1.0** successfully installed on the actual XGIMI (Android 11,
  `armeabi-v7a`) without removing Moonlight. Its 1280×720 native Home, Search,
  category filters, actual episode stills, series details and movie browsing
  rendered and were captured from the device.
- The actual XGIMI media session advanced independently through Black Clover
  Season 2 episode 1, Rings of Power season 1 episode 1, and Iron Man (2008).
  Android reported playing position about 25–28 s, buffered media and no
  MediaSession playback error. The local app persisted Black Clover's playback
  position (27.7 s) to its private SharedPreferences. These are **hardware
  decoder and playback-state checks**, not proof of visible projected frames or
  physical loudspeaker audio, which were not independently observed.
- Source metadata, poster art and season information loaded without contacting
  the Mirai or Philipedia Kunai services. Providers still require internet.
- Native **0.1.1** adds fixed Ember Dusk playback controls, a direct Play movie
  action, and narrow filtering of phone-number spam from a trending catalog.
  Both APK builds and 3 Movy cipher parity tests passed; 0.1.1 was installed
  on Nothing Phone without changing its foreground app. The projector went
  offline before 0.1.1 could be installed and tested there, so it retains the
  verified 0.1.0 build at the last check.

## Remaining readiness gates

1. Verify Android 0.1.1 controls and stable D-pad focus **on the projector**.
2. Confirm physical projected video, actual speaker audio, subtitle selection,
   buffering/recovery and seeking. SurfaceView screenshots can show black even
   while the Android media session reports real playback.
3. Verify phone portrait browsing, navigation, rotation and Media3 playback
   without disrupting an active phone session.
4. Test all-devices-off independence by disabling the optional external
   integrations; the code and direct native catalog/player flow need no server,
   but that complete outage test has not been run.
5. Benchmark the projector's 2 GB memory budget, cold start and ANR behavior.
6. Migrate local JSON preferences to Room/DataStore with tests, add optional
   sync, and add network/provider-outage recovery tests.
7. Sign and release a production APK only after the gates above pass. Do not
   replace the existing Moonlight route until the native app is production-ready.

The original Bun CLI remains in `apps/cli` and unchanged. Mirai TV, Moonlight
and the Nothing Phone projector remote remain in place and unaffected. The CI
workflow builds/tests a debug APK when published on GitHub. Local commits are
not themselves a public GitHub push or a release.
