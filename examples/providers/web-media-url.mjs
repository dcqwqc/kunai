const ID = "web-media-url";

const cachePolicy = {
  ttlClass: "stream-manifest",
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

function isHttpUrl(value) {
  try {
    const url = new URL(value);
    return (
      (url.protocol === "http:" || url.protocol === "https:") && !url.username && !url.password
    );
  } catch {
    return false;
  }
}

function isDirectMediaUrl(value) {
  try {
    return /\.(?:mp4|webm|m3u8|mpd)$/i.test(new URL(value).pathname);
  } catch {
    return false;
  }
}

function trace(input, selectedStreamId, failures = []) {
  const at = now();
  return {
    id: ID + "-" + Date.now(),
    startedAt: at,
    endedAt: at,
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

function exhausted(input, code, message, retryable = false) {
  const item = { providerId: ID, code, message, retryable, at: now() };
  return {
    status: "exhausted",
    providerId: ID,
    sources: [],
    variants: [],
    streams: [],
    subtitles: [],
    trace: trace(input, undefined, [item]),
    failures: [item],
  };
}

export default {
  providerId: ID,
  manifest: {
    id: ID,
    displayName: "Web Media URL",
    description: "Resolve direct supported web-video URLs through yt-dlp",
    domain: "web",
    recommended: false,
    mediaKinds: ["movie", "series"],
    catalogIdentity: "provider-native",
    capabilities: ["search", "source-resolve", "subtitle-resolve", "quality-ranked"],
    runtimePorts: [
      {
        runtime: "direct-http",
        operations: ["search", "resolve-stream", "resolve-subtitles"],
        browserSafe: false,
        relaySafe: false,
        localOnly: true,
      },
    ],
    cachePolicy,
    browserSafe: false,
    relaySafe: false,
  },

  async search(input) {
    const query = input.query.trim();
    if (!isHttpUrl(query)) return [];
    return [
      {
        id: query,
        type: "movie",
        title: query,
        metadataSource: "yt-dlp URL",
        externalIds: { providerNativeIds: { [ID]: query } },
      },
    ];
  },

  async resolve(input, context) {
    const url = String(input.title.externalIds?.providerNativeIds?.[ID] || input.title.id || "");
    if (!isHttpUrl(url)) return exhausted(input, "missing-input", "Paste a full http(s) URL");

    let data;
    if (isDirectMediaUrl(url)) {
      // No extractor is needed for direct media. Routing through yt-dlp can
      // unnecessarily reject playable CDN URLs with a 403 on the page probe.
      data = { url, formats: [] };
    } else {
      const executable = Bun.which("yt-dlp", { PATH: process.env.PATH });
      if (!executable) return exhausted(input, "runtime-missing", "yt-dlp is not installed");

      const proc = Bun.spawn(
        [executable, "--dump-single-json", "--no-playlist", "--no-warnings", url],
        {
          stdout: "pipe",
          stderr: "pipe",
          signal: context.signal,
        },
      );
      const [stdout, stderr, exitCode] = await Promise.all([
        new Response(proc.stdout).text(),
        new Response(proc.stderr).text(),
        proc.exited,
      ]);
      if (exitCode !== 0)
        return exhausted(
          input,
          "provider-unavailable",
          stderr.trim().replace(/https?:\/\/[^\s"'<>]+/g, "[media URL]") || "yt-dlp failed",
          true,
        );

      try {
        data = JSON.parse(stdout);
      } catch {
        return exhausted(input, "parse-failed", "yt-dlp returned invalid JSON");
      }
    }

    const formats = (Array.isArray(data.formats) ? data.formats : [])
      .filter((format) => format?.url && format?.vcodec !== "none" && format?.acodec !== "none")
      .sort((a, b) => Number(b.height || b.tbr || 0) - Number(a.height || a.tbr || 0));
    const picked = formats[0];
    const directUrl = picked?.url || data.url;
    if (!directUrl) return exhausted(input, "provider-empty", "yt-dlp returned no playable URL");

    const subtitles = [];
    for (const [language, tracks] of Object.entries(data.subtitles || {})) {
      const track = Array.isArray(tracks)
        ? tracks.find((item) => item.ext === "vtt") || tracks[0]
        : null;
      if (!track?.url) continue;
      subtitles.push({
        id: "web-sub-" + subtitles.length,
        providerId: ID,
        sourceId: "yt-dlp",
        url: track.url,
        language,
        label: language,
        format: track.ext === "srt" ? "srt" : track.ext === "vtt" ? "vtt" : "unknown",
        source: "provider",
        confidence: 0.9,
        cachePolicy: { ...cachePolicy, ttlClass: "subtitle-list" },
      });
    }

    const stream = {
      id: "web-stream",
      providerId: ID,
      sourceId: "yt-dlp",
      url: directUrl,
      protocol:
        picked?.protocol === "m3u8_native" ||
        new URL(directUrl).pathname.toLowerCase().endsWith(".m3u8")
          ? "hls"
          : new URL(directUrl).pathname.toLowerCase().endsWith(".mpd")
            ? "dash"
            : "mp4",
      container: picked?.ext === "webm" ? "webm" : picked?.ext === "mp4" ? "mp4" : "unknown",
      qualityLabel: picked?.height ? String(picked.height) + "p" : "best",
      qualityRank: Number(picked?.height || picked?.tbr || 1),
      headers: data.http_headers || undefined,
      confidence: 0.95,
      cachePolicy,
    };

    return {
      status: "resolved",
      providerId: ID,
      selectedStreamId: stream.id,
      sources: [
        {
          id: "yt-dlp",
          providerId: ID,
          kind: "direct-media",
          label: "yt-dlp",
          status: "selected",
          confidence: 0.95,
        },
      ],
      variants: [],
      streams: [stream],
      subtitles,
      trace: trace(input, stream.id),
      failures: [],
      cachePolicy,
      healthDelta: { providerId: ID, outcome: "success", at: now() },
    };
  },
};
