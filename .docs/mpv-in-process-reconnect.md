---
status: current
lastReviewed: "2026-09-12"
---

# mpv in-process stream reconnect (persistent session)

> Agent-facing (L3). Never linked from published docs. Users: see `docs/users/`.

This applies only to the **persistent mpv** path (`PersistentMpvSession`, autoplay chain). One-shot `launchMpv` does not run this logic.

## What it does

When playback hits certain failure signals, Kunai **reloads the same stream URL** inside the existing mpv process via IPC (`loadfile … replace`), then:

- **VOD** (mpv reports a positive `duration`): **seeks** back to the last trusted position (capped near the end to avoid overshoot).
- **Live / unknown duration** (`duration` ≤ 0): **reload only** — no seek-back (same rule as normal “live” handling).

After a successful reload, **external subtitles are re-attached** from the current cycle options (same as a fresh file load path).

Provider TLS compatibility options are file-local on startup and replay. The
mp4upload exception follows the current URL; replacing it with another host restores
the user's prior mpv TLS setting. Native transition coverage includes exceptional
startup, same-URL replay, normal replacement, and both enabled/disabled baselines in
`apps/cli/test/integration/persistent-mpv-local-transition-native.test.ts`.

## When it runs

1. **`network-read-dead`** (from `playback-watchdog`): demuxer reports network + underrun + `raw-input-rate === 0` while paused-for-cache, sustained for ~8s. Fires at most once per stall incident from the watchdog; **reconnect attempts** are still capped per cycle.

2. **Premature EOF** (playback-stats guard): `end-file` with `eof` was **demoted** to `unknown` because trusted progress was inconsistent with a full watch (`eofDemotedByPrematureGuard`).

3. **`end-file` with `error`** while **`demuxer-via-network`** was true: treat as a reconnectable network demuxer error before surfacing a terminal failure.

If reconnect **succeeds**, the current `play()` promise **does not resolve** yet; playback continues until a normal end, quit, or exhaustion of retries.

If reconnect **fails** or limits are hit, the cycle ends and the usual `PlaybackResult` is returned.

## Limits and backoff

- **`mpvInProcessStreamReconnectMaxAttempts`** (default `1`, and `1` is also the
  cap): counts **started** reloads per playback cycle (new episode resets the
  counter). `ConfigServiceImpl` clamps whatever is persisted down to
  `MPV_IN_PROCESS_RECONNECT_MAX_ATTEMPTS` in `packages/config/src/defaults.ts`,
  so a hand-edited `config.json` asking for more gets one attempt. A dead stream
  answers a reload as fast as a live one, so a larger budget buys a retry loop
  against a URL that is not coming back. (`PersistentMpvSession.create` bounds a
  raw injected config at a higher structural ceiling — that path is for the test
  harness and the compiled smoke, not for user configuration.)
- **Backoff** after a failed `loadfile`: exponential from a base delay, capped (see `PersistentMpvSession` constants). Prevents hammering a dead CDN.
- **`reconnectInFlight`**: serializes overlapping reconnect work.

## Configuration (`~/.config/kunai/config.json`)

| Field                                    | Default | Meaning                                                                                                       |
| ---------------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------- |
| `mpvInProcessStreamReconnect`            | `true`  | Master switch. `false` disables automatic same-URL reloads (manual **Ctrl+r** / shell refresh still work).    |
| `mpvInProcessStreamReconnectMaxAttempts` | `1`     | Max reload attempts **per episode play**, and also the cap. Set `0` to disable (same as turning off retries). |

## Relationship to other recovery

- **Provider refresh** (cache bust, new URL) and **Ctrl+r** in mpv remain the way to get a **fresh** resolve when the URL or lease is bad.
- **Libavformat reconnect** flags (`--demuxer-lavf-o=…`) are complementary; they only help when the backend supports them.
- **`keep-open=always`** is **not** used: it can suppress `end-file` and break autoplay/session hand-off. Reconnect is explicit IPC instead.

## Generations and stale work

Every mpv process and playback cycle carries a monotonic generation
(`{ process, cycle }`, `apps/cli/src/domain/playback/playback-generation.ts`).
Endpoint waits, IPC opens, property callbacks, `file-loaded`, end-file,
reconnect completions, and recovery work all capture the generation that created
them and compare it against the active one before mutating session state.

Stale work closes any handle it just created and returns without touching
status, presence, diagnostics, or the shell. Replacing or stopping a session
increments the generation **before** cleanup, so a late IPC event from the
previous cycle cannot revive a session the user already replaced or stopped.
Reconnect budgets and backoff above are unchanged by this.

Reconnect backoff and post-`file-loaded` completion carry the initiating
generation explicitly. They re-check it after every awaited seek, unpause, and
subtitle operation; a replacement during any one of those waits prevents every
remaining command and state mutation from the retired cycle.

## Status recovery contract

One authoritative transition policy owns current playback status
(`apps/cli/src/app/playback/playback-status-policy.ts`):

- Fresh `playback-progress` is recovery evidence **only** from `buffering`,
  `stalled`, or `seeking`, and returns that session to `playing`.
- It never overrides `paused`, `stopped`, `finished`, a replaced session, or a
  terminal fallback/failure state that has not accepted a new stream.
  `playback-resumed` is the only paused-to-playing transition; stop stays
  authoritative.
- Genuine stall and provider-fallback evidence stays in the diagnostics record
  after status recovers, so the UI can still explain what happened.

Header, loading shell, diagnostics, and presence consume that one status. They
format it differently but never infer a second state machine. Note the two
distinct predicates in `SessionState.ts`: `isPlaybackSessionActive()` means "a
session exists" and includes the bootstrap states `loading`/`ready`, while
`isPlaybackTransportStarted()` means "bootstrap is over" and does not. Surfaces
deciding whether to keep a loading presentation need the latter.

## Playback stats and UI

- Diagnostics / shell may show **`mpv-in-process-reconnect`** events (`started` | `complete` | `failed`) with attempt number and a short `detail` (trigger + error when failed).
- Seek policy lives in `apps/cli/src/infra/player/mpv-in-process-reconnect.ts` (`computeInProcessReconnectSeek`).
- Presence updates from a superseded generation are dropped rather than sent.

## Source failover and output evidence

`automaticStreamFailover` defaults to `true`. It controls automatic source and
provider recovery; explicit refresh, recovery and provider picks remain available
when it is disabled. Same-URL retries still use the separate reconnect settings
above. A persistent stall gets a further bounded recovery window before the file
is stopped and the existing source/provider recovery loop resumes at the reliable
position. That loop has a four-attempt episode budget and one provider hop.
Automatic provider handoff suppresses the resume prompt. Reported audio and
subtitle language choices, external subtitle selection, and subtitles-off carry
within the same episode where the new source supports them; track IDs do not.

Native mpv readiness requires video output dimensions and configured output,
plus positive playback progress. A demuxed clock, successful HTTP probe or
`file-loaded` alone is insufficient. Output/track properties are queried again
after a replacement because observations may have arrived while callbacks were
gated or may not change between files. Explicit audio-only track inventories
remain playable. This verifies decoder/output evidence, not the visual contents
of every frame; intentionally black footage is not classified as a failure.

Compatibility backends that expose no native mpv version/output properties keep
their clock-based playback path, explicitly reported as `backend-unverified`.
The currently inspected external Showtime shim hardcodes output readiness and
does not expose decoded frames. Kunai cannot prove video decode or detect
advancing-audio black video in that backend until it supplies real output
telemetry. It can handle its reported `loading_failed`/access-denied errors and
clock stalls. These deterministic backend failures skip same-URL reconnect.
No external shim, installation or live profile is changed by this feature.

`prepareBackupStream` defaults to `true`. Once playback starts, one alternate
source already in the current inventory may receive a bounded HTTP health check.
It uses `EpisodePrefetchHandle` cancellation and identity checks, never another
player or provider resolve. Presentation, audio and hard-sub context must match;
expired, blocked, deferred and yt-dlp sources are excluded. A ready backup is
preferred during failover, expires after one minute, and is discarded on intent
changes, normal playback end or cancellation. HTTP readiness is not decoder proof;
the active player still establishes output readiness after handoff. Media stays
direct or on the existing local materializer/loopback path.
