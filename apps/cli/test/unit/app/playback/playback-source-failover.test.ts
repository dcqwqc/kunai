import { describe, expect, test } from "bun:test";

import {
  listOrderedPlaybackSourceIds,
  pickNextCatalogSourceId,
  planStartupFailover,
  pickLowerQualityRecoveryStream,
  shouldUseProviderPlaybackRecovery,
  STARTUP_STALL_TIMEOUT_MS,
} from "@/app/playback/playback-source-failover";
import type { ProviderResolveResult } from "@kunai/types";

describe("playback-source-failover", () => {
  test("verified local playback never enters provider stream recovery", () => {
    expect(shouldUseProviderPlaybackRecovery("local")).toBe(false);
    expect(shouldUseProviderPlaybackRecovery("fresh")).toBe(true);
    expect(shouldUseProviderPlaybackRecovery("cache")).toBe(true);
  });

  test("STARTUP_STALL_TIMEOUT_MS leaves headroom past 20s for slow CDNs", () => {
    expect(STARTUP_STALL_TIMEOUT_MS).toBeGreaterThanOrEqual(45_000);
  });

  test("pickNextCatalogSourceId returns the next untried source in order", () => {
    expect(
      pickNextCatalogSourceId(
        ["source:a", "source:b", "source:c"],
        "source:a",
        new Set(["source:a"]),
      ),
    ).toBe("source:b");
    expect(
      pickNextCatalogSourceId(
        ["source:a", "source:b", "source:c"],
        "source:b",
        new Set(["source:a", "source:b"]),
      ),
    ).toBe("source:c");
    expect(
      pickNextCatalogSourceId(
        ["source:a", "source:b"],
        "source:b",
        new Set(["source:a", "source:b"]),
      ),
    ).toBeNull();
  });

  test("planStartupFailover prefers next source then provider hop then give-up", () => {
    expect(
      planStartupFailover({
        sourceIds: ["s1", "s2"],
        currentSourceId: "s1",
        triedSourceIds: new Set(["s1"]),
        hasFallbackProvider: true,
        failoverAttempts: 0,
      }),
    ).toEqual({ kind: "advance-source", sourceId: "s2" });

    expect(
      planStartupFailover({
        sourceIds: ["s1", "s2"],
        currentSourceId: "s2",
        triedSourceIds: new Set(["s1", "s2"]),
        hasFallbackProvider: true,
        failoverAttempts: 2,
        providerHopUsed: false,
      }),
    ).toEqual({ kind: "fallback-provider" });

    expect(
      planStartupFailover({
        sourceIds: ["s1"],
        currentSourceId: "s1",
        triedSourceIds: new Set(["s1"]),
        hasFallbackProvider: false,
        failoverAttempts: 1,
        providerHopUsed: true,
      }),
    ).toEqual({ kind: "give-up" });

    expect(
      planStartupFailover({
        sourceIds: ["s1", "s2"],
        currentSourceId: "s1",
        triedSourceIds: new Set(["s1"]),
        hasFallbackProvider: true,
        failoverAttempts: 4,
      }),
    ).toEqual({ kind: "give-up" });
  });

  test("listOrderedPlaybackSourceIds prefers sources inventory order", () => {
    const result = {
      providerId: "videasy",
      status: "ok",
      sources: [
        {
          id: "source:yoru",
          providerId: "videasy",
          kind: "provider-api",
          label: "Yoru",
          host: "x",
          status: "selected",
          confidence: 1,
        },
        {
          id: "source:neon",
          providerId: "videasy",
          kind: "provider-api",
          label: "Neon",
          host: "x",
          status: "ready",
          confidence: 1,
        },
      ],
      streams: [],
      subtitles: [],
      selectedStreamId: null,
      failures: [],
      trace: { title: { kind: "movie" }, events: [] },
      cachePolicy: { ttlClass: "stream-manifest", scope: "local", keyParts: [] },
    } as unknown as ProviderResolveResult;

    expect(listOrderedPlaybackSourceIds(result)).toEqual(["source:yoru", "source:neon"]);
  });
});

test("source failover wraps to an earlier untried source without revisiting failed sources", () => {
  expect(pickNextCatalogSourceId(["a", "b", "c"], "c", new Set(["c", "a"]))).toBe("b");
});

test("known exhausted sources never become automatic fallback targets", () => {
  const result = {
    sources: [
      { id: "dead", status: "failed" },
      { id: "exhausted", status: "exhausted" },
      { id: "ready", status: "available" },
    ],
    streams: [],
  } as never;
  expect(listOrderedPlaybackSourceIds(result)).toEqual(["ready"]);
});

test("untried sources remain eligible after a selected stream fails", () => {
  const result = {
    sources: [
      { id: "movy:denver", status: "failed" },
      { id: "movy:portland", status: "selected" },
      { id: "movy:seattle", status: "skipped" },
      { id: "movy:miami", status: "skipped" },
      { id: "movy:phoenix", status: "exhausted" },
    ],
    streams: [],
  } as never;
  const ordered = listOrderedPlaybackSourceIds(result);
  expect(ordered).toEqual(["movy:portland", "movy:seattle", "movy:miami"]);
  expect(pickNextCatalogSourceId(ordered, "movy:portland", new Set(["movy:portland"]))).toBe(
    "movy:seattle",
  );
});

test("auto recovery reserves a provider hop instead of cycling an entire dead mirror inventory", () => {
  const inputs = {
    sourceIds: ["lane:portland", "lane:seattle", "lane:miami", "lane:austin"],
    currentSourceId: "lane:miami",
    triedSourceIds: new Set(["lane:portland", "lane:seattle", "lane:miami"]),
    hasFallbackProvider: true,
    failoverAttempts: 3,
    maxFailoverAttempts: 4,
  };
  expect(planStartupFailover(inputs)).toEqual({ kind: "fallback-provider" });
  expect(planStartupFailover({ ...inputs, hasFallbackProvider: false })).toEqual({
    kind: "advance-source",
    sourceId: "lane:austin",
  });
});

test("4K stall first retries a distinct 1080p rung, then chooses another source", () => {
  const result = {
    selectedStreamId: "miami:2160",
    sources: [
      { id: "miami", status: "selected" },
      { id: "seattle", status: "skipped" },
      { id: "paris", status: "skipped" },
    ],
    streams: [
      { id: "miami:2160", sourceId: "miami", url: "https://cdn.test/4k", qualityRank: 2160 },
      { id: "miami:1080", sourceId: "miami", url: "https://cdn.test/hd", qualityRank: 1080 },
      { id: "miami:720", sourceId: "miami", url: "https://cdn.test/sd", qualityRank: 720 },
    ],
  } as unknown as ProviderResolveResult;
  expect(pickLowerQualityRecoveryStream(result, new Set(["miami:2160"]))).toEqual({
    id: "miami:1080",
    sourceId: "miami",
  });
  // After the one quality downgrade, prefer Seattle rather than trying an
  // endless sequence of poorer variants on the same unstable server.
  expect(pickLowerQualityRecoveryStream(result, new Set(["miami:2160", "miami:1080"]))).toBeNull();
  expect(
    pickLowerQualityRecoveryStream(result, new Set(["miami:2160", "miami:1080", "miami:720"])),
  ).toBeNull();
  // A failed 4K session cannot restart itself forever. If the preferred
  // backup resolves to a different source, the attempted target still counts.
  const tried = new Set(["miami"]);
  const first = planStartupFailover({
    sourceIds: ["miami", "seattle", "paris"],
    currentSourceId: "miami",
    triedSourceIds: tried,
    hasFallbackProvider: true,
    failoverAttempts: 1,
  });
  expect(first).toEqual({ kind: "advance-source", sourceId: "seattle" });
  if (first.kind === "advance-source") tried.add(first.sourceId);
  const second = planStartupFailover({
    sourceIds: ["miami", "seattle", "paris"],
    currentSourceId: "miami", // provider returned Miami instead of Seattle
    triedSourceIds: tried,
    hasFallbackProvider: true,
    failoverAttempts: 2,
  });
  expect(second).toEqual({ kind: "advance-source", sourceId: "paris" });
  if (second.kind === "advance-source") tried.add(second.sourceId);
  expect(
    planStartupFailover({
      sourceIds: ["miami", "seattle", "paris"],
      currentSourceId: "miami",
      triedSourceIds: tried,
      hasFallbackProvider: true,
      failoverAttempts: 3,
    }),
  ).toEqual({ kind: "fallback-provider" });
});

test("quality downshift cannot retry identical URLs or unknown ranks", () => {
  const result = {
    selectedStreamId: "4k",
    streams: [
      { id: "4k", sourceId: "miami", url: "https://a", qualityRank: 2160 },
      { id: "same", sourceId: "miami", url: "https://a", qualityRank: 1080 },
      { id: "other", sourceId: "seattle", url: "https://b", qualityRank: 720 },
      { id: "unknown", sourceId: "miami", url: "https://c" },
    ],
  } as unknown as ProviderResolveResult;
  expect(pickLowerQualityRecoveryStream(result, new Set())).toBeNull();
});
