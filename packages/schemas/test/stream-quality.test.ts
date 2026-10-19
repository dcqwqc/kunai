import { expect, test } from "bun:test";

import { streamCandidateSchema } from "../src/index";

test("source resolution and bitrate survive the inventory boundary without trusting labels", () => {
  const candidate = {
    id: "stream",
    providerId: "vidlink",
    protocol: "hls",
    qualityLabel: "4K",
    height: 720,
    bitrate: 2_000_000,
    confidence: 1,
    cachePolicy: { ttlClass: "stream-manifest", scope: "local", keyParts: [] },
  };
  expect(streamCandidateSchema.parse(candidate)).toMatchObject({ height: 720, bitrate: 2_000_000 });
  expect(streamCandidateSchema.safeParse({ ...candidate, height: -1 }).success).toBe(false);
});
