import { describe, expect, test } from "bun:test";

import {
  hasDecodedVideo,
  hasPlayableOutput,
  playbackOutputReadiness,
  createPlaybackProgressThrottleState,
  shouldEmitPlaybackProgress,
} from "@/infra/player/mpv-playback-kernel";

describe("mpv playback progress kernel", () => {
  test("throttles frequent progress samples", () => {
    const state = createPlaybackProgressThrottleState();
    const sample = { positionSeconds: 30, durationSeconds: 1200 };
    expect(shouldEmitPlaybackProgress(state, sample, 1_000)).toBe(true);
    expect(shouldEmitPlaybackProgress(state, sample, 2_000)).toBe(false);
    expect(
      shouldEmitPlaybackProgress(state, { positionSeconds: 50, durationSeconds: 1200 }, 3_000),
    ).toBe(true);
  });
});

test("a demuxed clock, HTTP success, or configured VO alone never proves decoded video", () => {
  const sample = {
    source: "ipc" as const,
    observedAt: 1,
    positionSeconds: 120,
    durationSeconds: 600,
  };
  expect(hasDecodedVideo(sample)).toBe(false);
  expect(hasDecodedVideo({ ...sample, voConfigured: true })).toBe(false);
  expect(
    hasDecodedVideo({ ...sample, voConfigured: true, videoOutParams: { w: 1920, h: 1080 } }),
  ).toBe(true);
});

test("a compatibility clock remains explicitly unverified rather than claiming decoded video", () => {
  const sample = {
    source: "ipc" as const,
    observedAt: 1,
    positionSeconds: 120,
    durationSeconds: 600,
    videoOutputSupported: false,
  };
  expect(hasDecodedVideo(sample)).toBe(false);
  expect(hasPlayableOutput(sample)).toBe(true);
  expect(playbackOutputReadiness(sample)).toBe("backend-unverified");
});
