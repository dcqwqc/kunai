import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { loadExternalProviderModules } from "@/container/load-external-providers";

const pluginPath = join(import.meta.dir, "../../../../../examples/providers/personal-sources.mjs");
const plugin = (await import(pathToFileURL(pluginPath).href)).default;
const webMediaPluginPath = join(
  import.meta.dir,
  "../../../../../examples/providers/web-media-url.mjs",
);
const webMediaPlugin = (await import(pathToFileURL(webMediaPluginPath).href)).default;
const earlier = process.env.KUNAI_PERSONAL_SOURCES_FILE;
const created: string[] = [];

async function setConfig(titles: Record<string, unknown>) {
  const dir = await mkdtemp(join(tmpdir(), "kunai-personal-test-"));
  created.push(dir);
  process.env.KUNAI_PERSONAL_SOURCES_FILE = join(dir, "personal-sources.json");
  await writeFile(process.env.KUNAI_PERSONAL_SOURCES_FILE, JSON.stringify({ version: 1, titles }));
}

afterEach(async () => {
  if (earlier === undefined) delete process.env.KUNAI_PERSONAL_SOURCES_FILE;
  else process.env.KUNAI_PERSONAL_SOURCES_FILE = earlier;
  await Promise.all(created.splice(0).map((path) => rm(path, { recursive: true, force: true })));
});

const movieInput = {
  title: {
    id: "tmdb:612654",
    kind: "movie",
    title: "Fantastic Fungi",
    tmdbId: "612654",
  },
  mediaKind: "movie",
};

describe("personal-sources external provider", () => {
  test("loader recognizes its valid provider manifest", async () => {
    const directory = join(import.meta.dir, "../../../../../examples/providers");
    const loaded = await loadExternalProviderModules({ directory });
    expect(loaded.issues).toEqual([]);
    expect(loaded.modules.some((provider) => provider.providerId === "personal-sources")).toBe(
      true,
    );
    expect(loaded.modules.some((provider) => provider.providerId === "web-media-url")).toBe(true);
  });

  test("returns exhausted quickly when a movie has no configured source", async () => {
    await setConfig({});
    const resolved = await plugin.resolve(movieInput);
    expect(resolved.status).toBe("exhausted");
    expect(resolved.streams).toEqual([]);
    expect(resolved.failures[0].code).toBe("not-found");
  });

  test("maps TMDB movie to valid sources and honors explicit selected server", async () => {
    await setConfig({
      "tmdb:612654": {
        kind: "movie",
        title: "Fantastic Fungi",
        sources: [
          { url: "https://media.example.test/a.m3u8", label: "Primary" },
          {
            url: "https://media.example.test/b.mp4",
            label: "Backup",
            headers: { Referer: "https://media.example.test/" },
          },
        ],
      },
    });
    const resolved = await plugin.resolve({ ...movieInput, preferredSourceId: "personal:2" });
    expect(resolved.status).toBe("resolved");
    expect(resolved.streams).toHaveLength(2);
    expect(resolved.streams[0].protocol).toBe("hls");
    expect(resolved.streams[1].protocol).toBe("mp4");
    expect(resolved.streams[1].headers.Referer).toBe("https://media.example.test/");
    expect(resolved.selectedStreamId).toBe("personal-stream:2");
    expect(resolved.sources.map((source: { status: string }) => source.status)).toEqual([
      "available",
      "selected",
    ]);
    expect(JSON.stringify(resolved.trace)).not.toContain("https://media.example.test");
    const search = await plugin.search({ query: "fungi" });
    expect(search[0].externalIds.tmdbId).toBe("612654");
  });

  test("does not map one TV episode URL onto another", async () => {
    await setConfig({
      "tmdb:1399:s2e3": {
        kind: "series",
        title: "Test series",
        sources: [{ url: "https://media.example.test/ep3.m3u8" }],
      },
    });
    const title = { id: "1399", kind: "series", title: "Test series", tmdbId: "1399" };
    const match = await plugin.resolve({
      title,
      mediaKind: "series",
      episode: { season: 2, episode: 3 },
    });
    const other = await plugin.resolve({
      title,
      mediaKind: "series",
      episode: { season: 2, episode: 4 },
    });
    expect(match.status).toBe("resolved");
    expect(other.status).toBe("exhausted");
  });

  test("rejects unsafe protocols, embedded credentials, and newline headers", async () => {
    await setConfig({
      "tmdb:612654": {
        kind: "movie",
        sources: [
          { url: "file:///etc/shadow" },
          { url: "https://name:secret@media.example.test/master.m3u8" },
          { url: "https://media.example.test/safe.mp4", headers: { Referer: "good\r\nBad: yes" } },
        ],
      },
    });
    const resolved = await plugin.resolve(movieInput);
    expect(resolved.status).toBe("resolved");
    expect(resolved.streams).toHaveLength(1);
    expect(resolved.streams[0].headers).toBeUndefined();
  });

  test("pasted direct MP4 or HLS URL resolves without a yt-dlp subprocess", async () => {
    for (const [url, protocol] of [
      ["https://media.w3.org/2010/05/sintel/trailer.mp4", "mp4"],
      ["https://example.test/master.m3u8?token=redacted", "hls"],
    ]) {
      const resolved = await webMediaPlugin.resolve(
        {
          title: { id: url, kind: "movie", title: "Licensed test video" },
        },
        { signal: AbortSignal.timeout(3000) },
      );
      expect(resolved.status).toBe("resolved");
      expect(resolved.streams[0].url).toBe(url);
      expect(resolved.streams[0].protocol).toBe(protocol);
    }
  });

  test("malformed JSON fails closed without exposing its contents", async () => {
    await setConfig({});
    await writeFile(process.env.KUNAI_PERSONAL_SOURCES_FILE!, "{invalid secret");
    const resolved = await plugin.resolve(movieInput);
    expect(resolved.status).toBe("exhausted");
    expect(JSON.stringify(resolved)).not.toContain("secret");
  });
});
