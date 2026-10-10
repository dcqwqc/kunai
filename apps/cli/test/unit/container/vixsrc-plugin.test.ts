import { describe, expect, test } from "bun:test";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { loadExternalProviderModules } from "@/container/load-external-providers";

const path = join(import.meta.dir, "../../../../../examples/providers/vixsrc.mjs");
const {
  default: plugin,
  parseSignedVixSrcEmbed,
  parseHlsDuration,
  resolveVixSrcWithFetch,
} = await import(pathToFileURL(path).href);

const API = "https://vixsrc.to";
const masterUrl = API + "/playlist/475052";
const nowSeconds = Math.floor(Date.now() / 1000);
const token = "abcdef1234567890abcdef1234567890";

const embed = `
<script>
window.masterPlaylist = {
  params: { 'token': '${token}', 'expires': '${nowSeconds + 3600}', 'asn': '' },
  url: '${masterUrl}',
}
window.canPlayFHD = true
</script>`;
const hlsMaster = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
https://vixsrc.to/playlist/475052?type=video&rendition=720p
`;
const validVariant =
  "#EXTM3U\n" +
  Array.from({ length: 480 }, () => "#EXTINF:10.0,\nsegment.ts\n").join("") +
  "#EXT-X-ENDLIST\n";
const shortVariant = "#EXTM3U\n#EXTINF:200.0,\nsegment.ts\n#EXT-X-ENDLIST\n";
const input = {
  title: { id: "tmdb:612654", tmdbId: "612654", title: "Fantastic Fungi", kind: "movie" },
  mediaKind: "movie",
};

function mockFetch(variant: string = validVariant) {
  const calls: string[] = [];
  const fetch = async (url: URL | string) => {
    const pathname = new URL(String(url)).pathname;
    calls.push(pathname);
    if (pathname === "/api/movie/612654") {
      return Response.json({ src: "/embed/475052?token=short-lived" });
    }
    if (pathname === "/embed/475052") return new Response(embed);
    if (pathname === "/playlist/475052") {
      return new Response(
        calls.filter((p) => p === "/playlist/475052").length === 1 ? hlsMaster : variant,
      );
    }
    throw Error("Unexpected network request");
  };
  return { fetch, calls };
}

describe("VixSrc external source provider", () => {
  test("registers as a movie/series provider", async () => {
    const loaded = await loadExternalProviderModules({
      directory: join(import.meta.dir, "../../../../../examples/providers"),
    });
    expect(loaded.issues).toEqual([]);
    expect(loaded.modules.some((provider) => provider.providerId === "vixsrc")).toBe(true);
    expect(plugin.manifest.mediaKinds).toEqual(["movie", "series"]);
  });

  test("parses signed manifest URL safely and only when unexpired", () => {
    const url = parseSignedVixSrcEmbed(embed);
    expect(url).toStartWith(masterUrl);
    expect(new URL(url).searchParams.get("token")).toBe(token);
    expect(new URL(url).searchParams.get("h")).toBe("1");
    expect(
      parseSignedVixSrcEmbed(embed.replace(masterUrl, "https://evil.example/manifest")),
    ).toBeNull();
    expect(
      parseSignedVixSrcEmbed(embed.replace(String(nowSeconds + 3600), String(nowSeconds - 1))),
    ).toBeNull();
  });

  test("resolves verified complete HLS and never exposes the token in trace", async () => {
    const { fetch, calls } = mockFetch();
    const resolved = await resolveVixSrcWithFetch(input, {}, fetch);
    expect(resolved.status).toBe("resolved");
    expect(resolved.streams).toHaveLength(1);
    expect(resolved.streams[0].protocol).toBe("hls");
    expect(resolved.streams[0].headers.Referer).toContain("vixsrc.to");
    expect(calls).toEqual([
      "/api/movie/612654",
      "/embed/475052",
      "/playlist/475052",
      "/playlist/475052",
    ]);
    expect(JSON.stringify(resolved.trace)).not.toContain(token);
  });

  test("rejects short mislabelled Fantastic Fungi previews", async () => {
    const { fetch } = mockFetch(shortVariant);
    const resolved = await resolveVixSrcWithFetch(input, {}, fetch);
    expect(resolved.status).toBe("exhausted");
    expect(resolved.failures[0].code).toBe("provider-empty");
  });

  test("ignores 404 and malicious redirects", async () => {
    let resolved = await resolveVixSrcWithFetch(
      input,
      {},
      async () => new Response("Not found", { status: 404 }),
    );
    expect(resolved.status).toBe("exhausted");
    expect(resolved.failures[0].code).toBe("not-found");
    resolved = await resolveVixSrcWithFetch(input, {}, async () =>
      Response.json({ src: "https://attacker.example/video.mp4" }),
    );
    expect(resolved.status).toBe("exhausted");
    expect(resolved.failures[0].code).toBe("provider-parse");
  });

  test("parses duration of complete VOD as ~80 minutes", () => {
    expect(parseHlsDuration(validVariant)).toEqual({
      durationSeconds: 4800,
      count: 480,
      vod: true,
    });
    expect(parseHlsDuration(hlsMaster)).toEqual({ durationSeconds: 0, count: 0, vod: false });
  });

  test("requires title and episode coordinates", async () => {
    const resolved = await resolveVixSrcWithFetch(
      { title: { id: "invalid", kind: "movie" }, mediaKind: "movie" },
      {},
      async () => {
        throw Error("should not fetch");
      },
    );
    expect(resolved.status).toBe("exhausted");
  });
});
