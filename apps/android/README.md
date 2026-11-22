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
- **MyAnimeList library:** The native app reads the configured MAL account's
  public list directly over HTTPS, keeps a private offline cache, and shows
  Watching, Plan to Watch, Completed, Subscribed and Watchlist without any
  desktop or home server. The local personalized bootstrap is included only
  in the owner's privately built APK; it is gitignored and must never be
  published to GitHub. The initial personalized recommendations are based on
  MAL ratings and stay cached offline.
- **Local playback:** Local favorites, per-episode progress and backups remain
  separately in private SharedPreferences. They are never overwritten by the
  MAL cache. Editing MAL scores and list membership currently opens the MAL
  web editor; native in-app MAL OAuth write access remains future work.
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

- Native Android package `dev.kitsunekode.kunai` version **0.1.3** was
  installed and verified on the XGIMI MoGo 2 Pro (Android 11, 32-bit ARM),
  Nothing Phone 2 (Android 16), Echo Show 5 (Android 11, 32-bit ARM), and
  Waydroid on both Mirai and Tachibana (x86_64). All installs leave the
  existing apps and their data intact.
- XGIMI on-device Media3 playback was observed advancing for Black Clover,
  Rings of Power, and Iron Man; its saved position survived native updates.
  Real 1280×720 screenshots show the Home UI, film/series artwork, episode
  stills, and Library settings. Media3 reported no playback error. Video
  SurfaceView screenshots are black; **physical projected video and speaker
  output were not separately verified**.
- Nothing Phone 2 was tested using scrcpy's private Android display, leaving
  Instagram on its physical display uninterrupted. Real portrait captures
  show progressive loading of Movies, Anime and Series shelves.
- Tachibana's desktop Kunai launcher/UI remains working on port 18793
  (required assets HTTP 200), with its own private screenshot display. The
  desktop app, existing TV streaming backend and Moonlight are preserved.
- The Echo Show's Home Dashboard/Loom kiosk stays in the foreground; Kunai is
  a separate launchable app.

### Library transfer

The Android library uses private device storage. `Settings → Export` writes
a small, versioned JSON snapshot; `Import & merge` combines saved titles and
watch history without wiping local data, preferring the newer watch timestamp.
On Android TV firmware without the document-picker app, such as this XGIMI,
`Save backup` writes to `Downloads/Kunai` via Android MediaStore and
`Restore latest` imports the newest Kunai backup made on that device.
The TV fallback does **not** discover files from other devices automatically.
This backup process transfers only local playback data. The MAL library
is independently downloaded and cached on each Android device; it is never
mixed into the local backup or silently written back to MAL. MAL reading
requires the account's list to remain publicly readable. If it is private
or unavailable, the last verified offline copy stays visible. Playback
and local favorites never depend on the sync service.

### Source loading

Home resolves Anime, Series and Movies separately with bounded waits, so a
slow provider no longer holds the whole page behind a blocking spinner.
Recent catalog data is kept in a device-local bounded disk cache, with stale
fallback on a provider outage. Resolution and playback still use direct
on-device HTTPS sources, not Mirai or Philipedia.

## Verified 0.1.4 rollout

The same SHA-256-verified 0.1.4 APK was installed on XGIMI, Nothing Phone 2,
Echo Show 5, and the Mirai and Tachibana Waydroid instances. Android's package
manager confirmed versionCode 5 / versionName 0.1.4 on XGIMI, phone and Echo.
Both Waydroid app lists showed Kunai after installing that exact APK.

On the actual XGIMI, the TV Settings screen displayed **4 watched episodes**.
Tapping **Save backup** created a 3,848-byte JSON file in
`/sdcard/Download/Kunai/kunai-library-20261122-180527.json`.
Tapping **Restore latest** returned **Merged: 0 titles, 0 episodes**, a
successful no-op with all four watched entries intact and no Android crash.
This confirms the Android TV document-picker workaround works on real hardware.

## Remaining readiness gates

1. Verify subtitles, real projector speaker output and visible projected
   frames, buffering and remote seek across multiple providers. Video
   SurfaceView screenshots are black even while Media3 state advances.
2. Benchmark memory/ANRs and cold start on XGIMI's approximately 2 GB RAM.
3. Test offline and provider-outage states plus corrupt/import-conflict cases.
4. Implement **optional** authenticated cross-device live sync and a migration
   from JSON preferences to Room/DataStore, if needed. Never make a central
   server mandatory for playback. Desktop profiles and MAL should not be
   silently overwritten by the Android library.
5. Build a signed production artifact after these gates pass. The current
   APKs are locally installed debug builds, not a publicly signed release.
   Keep Moonlight and the desktop player as verified fallbacks.

Other platforms: OSIRIS macOS, iPad/iOS, and Quest 3 were offline during this
run. An Android APK cannot be installed on iOS/macOS; those require an
appropriate dedicated build/desktop frontend. No install or cross-device sync
was claimed for those unavailable devices.

Kunai's Bun CLI remains in `apps/cli`; its published providers and unrelated
working-tree changes are intentionally untouched. The Android GitHub Actions
workflow builds and tests a debug APK when it is pushed to GitHub. A local Git
commit alone does not publish a new release or public changes.


## Calendar and navigation parity (0.1.5)

The native Compose phone and TV surfaces now use the six Kunai navigation
icons and order from `XGIMIRemote/bin/kunai-tv-ui.html`: Home, Discover,
Calendar, Continue, My List and Settings. The Android drawables contain the
same vector path geometry as Kunai's existing web/remote UI, instead of relying
on generic platform icons or an external font at runtime.

The schedule reproduces the remote calendar's fixed-width Monday–Sunday day
row, selected date, localized date labels, previous/next week navigation, and
This week shortcut. It shows cover-first anime release tiles and their episode
numbers/times, using AniList directly on the Android device. Local timezone
boundaries include DST transitions. The app does not require the XGIMI remote,
a TV connection, Mirai, or Philipedia to display the schedule. Only the Anime
airing schedule is supported by this native data source; movie/TV premieres
remain a separate discovery capability and are not misrepresented as anime
episodes. When a request fails, an error appears instead of a false empty
schedule.

This aligns navigation and calendar behaviors but **does not merge the
Java/Web XGIMI Remote app and the Kotlin/Compose standalone app into one
source tree**; their independent remote-control and player capabilities remain
preserved. The Android package keeps the same application ID and upgrades
in place without clearing the local library.
