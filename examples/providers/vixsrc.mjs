/**
 * Runtime adapter for the VixSrc public TMDB lookup/embed endpoints.
 *
 * Always refresh the signed playlist token on resolve. An embed landing page
 * is NOT a playable video URL; only return a validated media manifest.
 */
const ID = "vixsrc";
const ORIGIN = "https://vixsrc.to";
const MAX_HTML_BYTES = 300_000;
const MAX_PLAYLIST_BYTES = 2_000_000;
const CACHE = {
  ttlClass: "never-cache",
  scope: "memory",
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

function now() {
  return new Date().toISOString();
}

function trace(input, selectedStreamId, failures = []) {
  const stamp = now();
  return {
    id: ID + "-" + Date.now(),
    startedAt: stamp,
    endedAt: stamp,
    title: input.title,
    episode: input.episode,
    selectedProviderId: ID,
    selectedStreamId,
    runtime: "direct-http",
    cacheHit: false,
    steps: [],
    failures,
  };
}

function exhausted(input, code, message, retryable = false) {
  const failure = { providerId: ID, code, message, retryable, at: now() };
  return {
    status: "exhausted",
    providerId: ID,
    sources: [],
    variants: [],
    streams: [],
    subtitles: [],
    trace: trace(input, undefined, [failure]),
    failures: [failure],
    cachePolicy: CACHE,
  };
}

export function tmdbIdentity(input) {
  if (input.mediaKind !== "movie" && input.mediaKind !== "series") return null;
  const raw = String(
    input.title?.tmdbId || input.title?.externalIds?.tmdbId || input.title?.id || "",
  );
  const id = raw.replace(/^tmdb:/i, "");
  if (!/^[1-9][0-9]{0,11}$/.test(id)) return null;
  if (input.mediaKind === "movie") return { id, endpoint: "/api/movie/" + id };
  const season = input.episode?.season;
  const episode = input.episode?.episode;
  if (!Number.isSafeInteger(season) || season < 0 || !Number.isSafeInteger(episode) || episode < 1)
    return null;
  return { id, endpoint: "/api/tv/" + id + "/" + season + "/" + episode };
}

function parseMasterPlaylistBlock(markup) {
  if (typeof markup !== "string" || markup.length > MAX_HTML_BYTES) return null;
  const match = markup.match(/window\.masterPlaylist\s*=\s*\{([\s\S]*?)\}\s*(?:;|window\.)/);
  const block = match?.[1];
  if (!block) return null;
  const urlMatch = block.match(/\burl:\s*['"]([^'"]+)['"]/);
  const tokenMatch = block.match(/['"]?token['"]?\s*:\s*['"]([^'"]+)['"]/);
  const expiresMatch = block.match(/['"]?expires['"]?\s*:\s*['"]([0-9]+)['"]/);
  if (!urlMatch || !tokenMatch || !expiresMatch) return null;
  const url = new URL(urlMatch[1], ORIGIN);
  if (
    url.protocol !== "https:" ||
    url.hostname !== new URL(ORIGIN).hostname ||
    !url.pathname.startsWith("/playlist/")
  )
    return null;
  if (!/^[A-Za-z0-9_-]{8,256}$/.test(tokenMatch[1])) return null;
  const expires = Number(expiresMatch[1]);
  if (!Number.isSafeInteger(expires) || expires <= Date.now() / 1000 + 10) return null;
  url.searchParams.set("token", tokenMatch[1]);
  url.searchParams.set("expires", expiresMatch[1]);
  url.searchParams.set("h", "1");
  url.searchParams.set("lang", "en");
  return url.href;
}

export function parseSignedVixSrcEmbed(markup) {
  try {
    return parseMasterPlaylistBlock(markup);
  } catch {
    return null;
  }
}

export function parseHlsDuration(manifest) {
  if (!manifest.startsWith("#EXTM3U")) return null;
  const segments = [...manifest.matchAll(/#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)/g)];
  const durationSeconds = segments.reduce((sum, m) => sum + Number(m[1]), 0);
  return { durationSeconds, count: segments.length, vod: manifest.includes("#EXT-X-ENDLIST") };
}

function manifestVariantUrl(manifest, baseUrl) {
  if (!manifest.startsWith("#EXTM3U")) return null;
  const lines = manifest.split(/\r?\n/).map((s) => s.trim());
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
    const candidate = lines.slice(i + 1).find((s) => s && !s.startsWith("#"));
    if (!candidate) continue;
    try {
      const target = new URL(candidate, baseUrl);
      if (
        target.protocol !== "https:" ||
        !["vixsrc.to", "vix-content.net"].some(
          (suffix) => target.hostname === suffix || target.hostname.endsWith("." + suffix),
        )
      )
        return null;
      return target.href;
    } catch {
      return null;
    }
  }
  return null;
}

async function readLimited(response, maxBytes) {
  const contentLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > maxBytes)
    throw Error("Response exceeded size limit");
  const text = await response.text();
  if (text.length > maxBytes) throw Error("Response exceeded size limit");
  return text;
}

export async function resolveVixSrcWithFetch(input, context, fetchImpl) {
  const identity = tmdbIdentity(input);
  if (!identity)
    return exhausted(
      input,
      "unsupported-title",
      "VixSrc requires TMDB movie or TV episode coordinates",
    );
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 9_000);
  const abort = () => controller.abort();
  context?.signal?.addEventListener("abort", abort, { once: true });
  const headers = { "User-Agent": "Mozilla/5.0", Referer: ORIGIN + "/", Origin: ORIGIN };
  try {
    if (context?.signal?.aborted)
      return exhausted(input, "user-cancelled", "Playback request cancelled");
    const api = await fetchImpl(ORIGIN + identity.endpoint, {
      headers,
      signal: controller.signal,
    });
    if (api.status === 404)
      return exhausted(input, "not-found", "VixSrc does not carry this title");
    if (!api.ok)
      return exhausted(
        input,
        "provider-unavailable",
        "VixSrc lookup returned HTTP " + api.status,
        true,
      );
    const response = await readLimited(api, 10_000);
    let payload;
    try {
      payload = JSON.parse(response);
    } catch {
      return exhausted(input, "provider-parse", "Invalid VixSrc title lookup response");
    }
    if (typeof payload?.src !== "string")
      return exhausted(input, "not-found", "VixSrc has no embed for this title");
    const embedUrl = new URL(payload.src, ORIGIN);
    if (embedUrl.origin !== ORIGIN || !embedUrl.pathname.startsWith("/embed/")) {
      return exhausted(
        input,
        "provider-parse",
        "VixSrc lookup pointed outside its allowed embed path",
      );
    }
    const embed = await fetchImpl(embedUrl, { headers, signal: controller.signal });
    if (!embed.ok)
      return exhausted(
        input,
        "provider-unavailable",
        "VixSrc embed returned HTTP " + embed.status,
        true,
      );
    const playlistUrl = parseSignedVixSrcEmbed(await readLimited(embed, MAX_HTML_BYTES));
    if (!playlistUrl)
      return exhausted(input, "provider-empty", "VixSrc returned no valid signed playlist");
    const responseManifest = await fetchImpl(playlistUrl, { headers, signal: controller.signal });
    if (!responseManifest.ok)
      return exhausted(
        input,
        "blocked",
        "VixSrc playlist returned HTTP " + responseManifest.status,
        true,
      );
    const manifest = await readLimited(responseManifest, MAX_PLAYLIST_BYTES);
    if (!manifest.startsWith("#EXTM3U"))
      return exhausted(input, "provider-empty", "VixSrc did not return an HLS manifest");
    const variant = manifestVariantUrl(manifest, playlistUrl);
    const durationManifest = variant
      ? await fetchImpl(variant, { headers, signal: controller.signal })
      : responseManifest;
    if (variant && !durationManifest.ok)
      return exhausted(input, "blocked", "VixSrc video rendition is unreachable", true);
    const stats = parseHlsDuration(
      variant ? await readLimited(durationManifest, MAX_PLAYLIST_BYTES) : manifest,
    );
    if (!stats || stats.count === 0 || !stats.vod)
      return exhausted(input, "provider-empty", "VixSrc playlist has no complete VOD segment list");
    // Prevent incorrectly handing a trailer to this specifically requested title.
    if (identity.id === "612654" && input.mediaKind === "movie" && stats.durationSeconds < 4200) {
      return exhausted(
        input,
        "provider-empty",
        "Fantastic Fungi source is shorter than the complete film",
      );
    }
    const stream = {
      id: "vixsrc-stream",
      providerId: ID,
      sourceId: "vixsrc-hls",
      url: playlistUrl,
      protocol: "hls",
      container: "unknown",
      qualityLabel: "Auto HLS",
      qualityRank: 720,
      headers: { Referer: ORIGIN + "/", Origin: ORIGIN, "User-Agent": "Mozilla/5.0" },
      confidence: 0.9,
      cachePolicy: CACHE,
    };
    return {
      status: "resolved",
      providerId: ID,
      selectedStreamId: stream.id,
      sources: [
        {
          id: stream.sourceId,
          providerId: ID,
          kind: "direct-media",
          label: "VixSrc HLS",
          status: "selected",
          confidence: 0.9,
        },
      ],
      variants: [],
      streams: [stream],
      subtitles: [],
      trace: trace(input, stream.id),
      failures: [],
      cachePolicy: CACHE,
      healthDelta: { providerId: ID, outcome: "success", at: now() },
    };
  } catch {
    if (controller.signal.aborted)
      return exhausted(input, "timeout", "VixSrc resolve exceeded its 9-second budget", true);
    return exhausted(input, "network-error", "VixSrc network request failed", true);
  } finally {
    clearTimeout(timer);
    context?.signal?.removeEventListener("abort", abort);
  }
}

export default {
  providerId: ID,
  manifest: {
    id: ID,
    displayName: "VixSrc",
    description: "Fresh HLS streams from VixSrc TMDB movie/TV lookup",
    domain: "vixsrc.to",
    recommended: false,
    mediaKinds: ["movie", "series"],
    catalogIdentity: "tmdb",
    capabilities: ["source-resolve"],
    runtimePorts: [
      {
        runtime: "direct-http",
        operations: ["resolve-stream"],
        browserSafe: false,
        relaySafe: false,
        localOnly: true,
      },
    ],
    cachePolicy: CACHE,
    browserSafe: false,
    relaySafe: false,
  },
  resolve(input, context) {
    return resolveVixSrcWithFetch(input, context, fetch);
  },
};
