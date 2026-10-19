import { expect, test } from "bun:test";

import { EpisodePrefetchHandle } from "@/app/playback/episode-prefetch";
import {
  selectPlaybackBackup,
  isPlaybackBackupFresh,
  checkPlaybackBackup,
} from "@/app/playback/playback-backup";
import type { StreamInfo } from "@/domain/types";
import { StreamHealthService } from "@/services/playback/StreamHealthService";

const target = {
  titleId: "movie",
  providerId: "vidlink",
  episode: { season: 1, episode: 1 },
  audioPreference: "en",
  subtitlePreference: "none",
};
function stream(): StreamInfo {
  return {
    url: "https://cdn.example/current.mp4",
    headers: { Referer: "https://provider.example/" },
    timestamp: Date.now(),
    providerResolveResult: {
      providerId: "vidlink",
      status: "resolved",
      selectedStreamId: "current",
      streams: [
        {
          id: "current",
          sourceId: "a",
          url: "https://cdn.example/current.mp4",
          audioLanguages: ["en"],
        },
        {
          id: "wrong-language",
          sourceId: "b",
          url: "https://cdn.example/de.mp4",
          audioLanguages: ["de"],
          height: 2160,
        },
        {
          id: "backup",
          sourceId: "c",
          url: "https://cdn.example/backup.mp4",
          audioLanguages: ["en"],
          height: 1080,
          headers: { Referer: "https://provider.example/" },
        },
      ],
      subtitles: [],
      failures: [],
      trace: { steps: [], failures: [] },
    } as never,
  };
}

test("backup reuses one alternate inventory source and preserves language and request headers", () => {
  const bundle = selectPlaybackBackup(stream(), target, []);
  expect(bundle?.stream.url).toBe("https://cdn.example/backup.mp4");
  expect(bundle?.stream.headers).toEqual({ Referer: "https://provider.example/" });
  expect(bundle?.target.episode).toEqual(target.episode);
  expect(bundle?.target.sourceId).toBe("c");
  expect(selectPlaybackBackup(stream(), target, ["https://cdn.example/backup.mp4"])).toBeNull();
});

test("backup expires and cannot be consumed for a new episode or manual stream choice", async () => {
  const bundle = selectPlaybackBackup(stream(), target, [])!;
  expect(isPlaybackBackupFresh(bundle, bundle.stream.timestamp + 60_001)).toBe(false);
  const handle = new EpisodePrefetchHandle();
  handle.putbackReady(bundle);
  expect(handle.takeReadyFor({ ...bundle.target, episode: { season: 1, episode: 2 } })).toBeNull();
  expect(handle.takeReadyFor({ ...bundle.target, streamId: "manual" })).toBeNull();
  expect(handle.takeReadyFor(bundle.target)?.stream.url).toBe(bundle.stream.url);
  expect(handle.takeReadyFor(bundle.target)).toBeNull();
});

test("cancellation rejects late backup completion and performs no playback", async () => {
  const handle = new EpisodePrefetchHandle();
  const bundle = selectPlaybackBackup(stream(), target, [])!;
  let complete!: (value: typeof bundle) => void;
  let signal!: AbortSignal;
  handle.schedule(bundle.target, (value) => {
    signal = value;
    return new Promise((resolve) => {
      complete = resolve;
    });
  });
  handle.cancel("episode changed");
  expect(signal.aborted).toBe(true);
  complete(bundle);
  await Promise.resolve();
  expect(handle.hasReadyFor(bundle.target)).toBe(false);
});

test("backup HTTP check rejects denial and cancellation and does not attest decode", async () => {
  const bundle = selectPlaybackBackup(stream(), target, [])!;
  const signal = new AbortController();
  const service = new StreamHealthService({
    fetchImpl: async () => new Response(null, { status: 403 }),
  });
  expect(await checkPlaybackBackup(bundle, signal.signal, service)).toBeNull();
  signal.abort();
  const ready = new StreamHealthService({
    fetchImpl: async () =>
      new Response(null, { headers: { "content-type": "video/mp4", "content-length": "1000000" } }),
  });
  expect(await checkPlaybackBackup(bundle, signal.signal, ready)).toBeNull();
});
