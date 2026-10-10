/**
 * Local, opt-in, user-provided movie/episode sources.
 *
 * Edit ~/.config/kunai/personal-sources.json with the companion CLI. The
 * mapping never calls a scraper and never treats a web page/DRM manifest as an
 * automatically playable video. Source URLs are returned to the normal Kunai
 * stream-health and mpv pipeline.
 */
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

const ID = "personal-sources";
const FILE = join(homedir(), ".config", "kunai", "personal-sources.json");
const CACHE = {
  ttlClass: "never-cache",
  scope: "local",
  keyParts: [
    "provider",
    ID,
    "title",
    "episode",
    "audio",
    "subtitle",
    "quality",
    "startup",
    "source",
    "stream",
  ],
};

function at() {
  return new Date().toISOString();
}

function makeTrace(input, selectedStreamId, failures = []) {
  const stamp = at();
  return {
    id: ID + "-" + Date.now(),
    startedAt: stamp,
    endedAt: stamp,
    title: input.title,
    episode: input.episode,
    selectedProviderId: ID,
    selectedStreamId,
    cacheHit: false,
    runtime: "direct-http",
    steps: [],
    failures,
  };
}

function exhausted(input, code, message) {
  const failure = { providerId: ID, code, message, retryable: false, at: at() };
  return {
    status: "exhausted",
    providerId: ID,
    sources: [],
    variants: [],
    streams: [],
    subtitles: [],
    trace: makeTrace(input, undefined, [failure]),
    failures: [failure],
    cachePolicy: CACHE,
  };
}

function sourceFile() {
  return process.env.KUNAI_PERSONAL_SOURCES_FILE || FILE;
}

async function loadEntries() {
  try {
    const raw = JSON.parse(await readFile(sourceFile(), "utf8"));
    if (
      raw?.version !== 1 ||
      !raw.titles ||
      typeof raw.titles !== "object" ||
      Array.isArray(raw.titles)
    ) {
      throw new Error("Expected version 1 and a titles object");
    }
    return raw.titles;
  } catch (error) {
    if (error?.code === "ENOENT") return {};
    // Do not surface contents of the JSON (including URLs or auth tokens).
    throw new Error("Personal Sources config is invalid or unreadable", { cause: error });
  }
}

function canonicalId(title) {
  const id = String(title?.tmdbId || title?.externalIds?.tmdbId || title?.id || "").replace(
    /^tmdb:/i,
    "",
  );
  return /^[1-9][0-9]*$/.test(id) ? "tmdb:" + id : null;
}

function entryKey(input) {
  const base = canonicalId(input.title);
  if (!base) return null;
  if (input.mediaKind === "series" || input.title?.kind === "series") {
    const season = Number(input.episode?.season);
    const episode = Number(input.episode?.episode);
    if (
      !Number.isSafeInteger(season) ||
      season < 0 ||
      !Number.isSafeInteger(episode) ||
      episode < 1
    ) {
      return null;
    }
    return base + ":s" + season + "e" + episode;
  }
  return base;
}

function safeUrl(value) {
  if (typeof value !== "string" || value.length > 16384) return null;
  try {
    const u = new URL(value);
    if (!["http:", "https:"].includes(u.protocol) || u.username || u.password) return null;
    return u.href;
  } catch {
    return null;
  }
}

function safeHeaders(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const output = {};
  for (const key of ["Referer", "Origin", "User-Agent"]) {
    const v = value[key];
    if (typeof v === "string" && v.length <= 2048 && !/[\r\n]/.test(v)) output[key] = v;
  }
  return Object.keys(output).length ? output : undefined;
}

function toCandidate(item, index) {
  const url = safeUrl(item?.url);
  if (!url) return null;
  const label =
    typeof item.label === "string" && item.label.trim()
      ? item.label.trim().slice(0, 80)
      : "Personal source " + (index + 1);
  const pathname = new URL(url).pathname.toLowerCase();
  const protocol = pathname.endsWith(".m3u8") ? "hls" : pathname.endsWith(".mpd") ? "dash" : "mp4";
  const container = pathname.endsWith(".webm")
    ? "webm"
    : pathname.endsWith(".mp4")
      ? "mp4"
      : "unknown";
  const sourceId = "personal:" + (index + 1);
  return {
    sourceId,
    label,
    stream: {
      id: "personal-stream:" + (index + 1),
      providerId: ID,
      sourceId,
      url,
      protocol,
      container,
      qualityLabel: typeof item.quality === "string" ? item.quality.slice(0, 48) : "User source",
      qualityRank: Number.isFinite(item.qualityRank) ? item.qualityRank : 1,
      headers: safeHeaders(item.headers),
      confidence: 0.95,
      cachePolicy: CACHE,
    },
  };
}

export default {
  providerId: ID,
  manifest: {
    id: ID,
    displayName: "Personal Sources",
    description: "Your own playable HTTP(S) streams associated with a movie or episode",
    domain: "local",
    recommended: false,
    mediaKinds: ["movie", "series"],
    catalogIdentity: "tmdb",
    capabilities: ["search", "source-resolve", "multi-source"],
    runtimePorts: [
      {
        runtime: "direct-http",
        operations: ["search", "resolve-stream"],
        browserSafe: false,
        relaySafe: false,
        localOnly: true,
      },
    ],
    cachePolicy: CACHE,
    browserSafe: false,
    relaySafe: false,
  },

  async search(input) {
    const q = String(input.query || "")
      .trim()
      .toLowerCase();
    if (!q) return [];
    const entries = await loadEntries();
    return Object.entries(entries).flatMap(([key, value]) => {
      if (
        !/^tmdb:[1-9][0-9]*$/.test(key) ||
        !value ||
        value.kind !== "movie" ||
        typeof value.title !== "string"
      )
        return [];
      if (!value.title.toLowerCase().includes(q) && !key.includes(q)) return [];
      const id = key.slice(5);
      return [
        {
          id,
          type: "movie",
          title: value.title,
          metadataSource: "Your sources",
          externalIds: { tmdbId: id },
        },
      ];
    });
  },

  async resolve(input) {
    const key = entryKey(input);
    if (!key)
      return exhausted(
        input,
        "missing-input",
        "A TMDB ID and valid movie/episode coordinates are required",
      );
    let entries;
    try {
      entries = await loadEntries();
    } catch {
      return exhausted(
        input,
        "provider-unavailable",
        "Personal Sources configuration is invalid or unreadable",
      );
    }
    const entry = entries[key];
    if (!entry || !Array.isArray(entry.sources) || !entry.sources.length) {
      return exhausted(input, "not-found", "No personal source configured for this title");
    }
    if (entry.kind !== input.mediaKind && entry.kind !== input.title.kind) {
      return exhausted(input, "unsupported-title", "Personal source media kind mismatch");
    }
    const candidates = entry.sources.map(toCandidate).filter(Boolean);
    if (!candidates.length)
      return exhausted(input, "missing-input", "No valid HTTP(S) video URL configured");
    const preferred = candidates.find(
      (c) => c.sourceId === input.preferredSourceId || c.stream.id === input.preferredStreamId,
    );
    const selected = preferred || candidates[0];
    return {
      status: "resolved",
      providerId: ID,
      selectedStreamId: selected.stream.id,
      sources: candidates.map((candidate) => ({
        id: candidate.sourceId,
        providerId: ID,
        kind: "direct-media",
        label: candidate.label,
        status: candidate === selected ? "selected" : "available",
        confidence: 0.95,
      })),
      variants: [],
      streams: candidates.map((c) => c.stream),
      subtitles: [],
      trace: makeTrace(input, selected.stream.id),
      failures: [],
      cachePolicy: CACHE,
    };
  },
};
