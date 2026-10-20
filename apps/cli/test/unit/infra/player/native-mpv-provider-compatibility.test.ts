import { expect, test } from "bun:test";

import { prefersNativeMpvCompatibilityFallback } from "@/infra/player/PlayerServiceImpl";

test("HiAnime uses native mpv compatibility path without changing Showtime for other providers", () => {
  const titleStream = (providerId: string) =>
    ({
      providerResolveResult: { providerId },
    }) as never;
  expect(prefersNativeMpvCompatibilityFallback(titleStream("hianime"))).toBe(true);
  expect(prefersNativeMpvCompatibilityFallback(titleStream("anidb"))).toBe(false);
  expect(prefersNativeMpvCompatibilityFallback(titleStream("movy"))).toBe(false);
  expect(prefersNativeMpvCompatibilityFallback({ providerResolveResult: null } as never)).toBe(
    false,
  );
});
