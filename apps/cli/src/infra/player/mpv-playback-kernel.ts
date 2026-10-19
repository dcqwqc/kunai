import type { PlayerStatsSample } from "./mpv-stats";

/** Clock movement (including audio-only playback or a resume seek) is not video readiness. */
export function hasDecodedVideo(sample: PlayerStatsSample | null | undefined): boolean {
  return sample?.voConfigured === true && Boolean(sample.videoOutParams);
}

/** A native video track needs output evidence; explicit audio-only inputs stay playable. */
export function hasPlayableOutput(sample: PlayerStatsSample | null | undefined): boolean {
  if (hasDecodedVideo(sample)) return true;
  // Compatibility shims can expose a clock without decoder properties. Keep
  // their existing playback usable, but report that evidence as unverified.
  if (sample?.videoOutputSupported === false && sample.positionSeconds > 0) return true;
  return Boolean(
    sample &&
    Array.isArray(sample.trackList) &&
    sample.trackList.some((row) => row?.type === "audio") &&
    !sample.trackList.some((row) => row?.type === "video"),
  );
}

export function playbackOutputReadiness(
  sample: PlayerStatsSample | null | undefined,
): "decoded-video" | "audio-only" | "backend-unverified" {
  return hasDecodedVideo(sample)
    ? "decoded-video"
    : sample?.videoOutputSupported === false
      ? "backend-unverified"
      : "audio-only";
}

export type PlaybackProgressSample = {
  readonly positionSeconds: number;
  readonly durationSeconds: number;
};

export type PlaybackProgressThrottleState = {
  lastPlaybackProgressEventAtMs: number;
  lastPlaybackProgressPositionSeconds: number;
  lastPlaybackProgressDurationSeconds: number;
};

export function createPlaybackProgressThrottleState(): PlaybackProgressThrottleState {
  return {
    lastPlaybackProgressEventAtMs: 0,
    lastPlaybackProgressPositionSeconds: -1,
    lastPlaybackProgressDurationSeconds: 0,
  };
}

/** Shared mpv progress emission policy for one-shot and persistent sessions. */
export function shouldEmitPlaybackProgress(
  state: PlaybackProgressThrottleState,
  sample: PlaybackProgressSample | null | undefined,
  observedAtMs: number,
): boolean {
  if (!sample || sample.positionSeconds <= 0) return false;
  const durationChanged =
    sample.durationSeconds > 0 &&
    Math.abs(sample.durationSeconds - state.lastPlaybackProgressDurationSeconds) >= 1;
  const positionChanged =
    Math.abs(sample.positionSeconds - state.lastPlaybackProgressPositionSeconds) >= 15;
  if (
    state.lastPlaybackProgressEventAtMs > 0 &&
    !durationChanged &&
    !positionChanged &&
    observedAtMs - state.lastPlaybackProgressEventAtMs < 15_000
  ) {
    return false;
  }
  state.lastPlaybackProgressEventAtMs = observedAtMs;
  state.lastPlaybackProgressPositionSeconds = sample.positionSeconds;
  state.lastPlaybackProgressDurationSeconds = sample.durationSeconds;
  return true;
}
