const ID = "archive";

const cachePolicy = {
  ttlClass: "direct-media-url",
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
  allowStale: true,
};

function now() {
  return new Date().toISOString();
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

function failure(input, code, message, retryable = false) {
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

function extension(name) {
  const match = String(name || "")
    .toLowerCase()
    .match(/\.([a-z0-9]+)$/);
  return match ? match[1] : "";
}

function mediaProtocol(name) {
  const ext = extension(name);
  if (ext === "mp4") return ["mp4", "mp4"];
  if (ext === "webm") return ["mp4", "webm"];
  return ["unknown", "unknown"];
}

export default {
  providerId: ID,
  manifest: {
    id: ID,
    displayName: "Internet Archive",
    description: "Public-domain and user-published video from archive.org",
    domain: "archive.org",
    recommended: false,
    mediaKinds: ["movie"],
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
    notes: ["External runtime plugin loaded from the user's Kunai provider directory."],
  },

  async search(input, context) {
    const query = input.query.trim();
    if (!query) return [];
    const params = new URLSearchParams();
    params.set("q", "title:(" + query + ") AND mediatype:(movies)");
    for (const field of ["identifier", "title", "year", "description"])
      params.append("fl[]", field);
    params.set("rows", "24");
    params.set("page", "1");
    params.set("output", "json");

    const response = await fetch("https://archive.org/advancedsearch.php?" + params, {
      signal: context.signal,
      headers: { "User-Agent": "Kunai external archive provider" },
    });
    if (!response.ok) throw new Error("Internet Archive search returned HTTP " + response.status);
    const payload = await response.json();
    return (payload?.response?.docs || []).map((item) => ({
      id: String(item.identifier),
      type: "movie",
      title: String(item.title || item.identifier),
      year: item.year == null ? undefined : String(item.year),
      overview: typeof item.description === "string" ? item.description : undefined,
      metadataSource: "Internet Archive",
      externalIds: { providerNativeIds: { [ID]: String(item.identifier) } },
    }));
  },

  async resolve(input, context) {
    const identifier = String(
      input.title.externalIds?.providerNativeIds?.[ID] || input.title.id || "",
    ).trim();
    if (!identifier) return failure(input, "missing-input", "Internet Archive identifier missing");

    let response;
    try {
      response = await fetch("https://archive.org/metadata/" + encodeURIComponent(identifier), {
        signal: context.signal,
        headers: { "User-Agent": "Kunai external archive provider" },
      });
    } catch (error) {
      return failure(input, "network-error", String(error), true);
    }
    if (response.status === 404) return failure(input, "not-found", "Archive item not found");
    if (!response.ok)
      return failure(input, "network-error", "Archive metadata HTTP " + response.status, true);

    const payload = await response.json();
    const files = Array.isArray(payload?.files) ? payload.files : [];
    const videos = files
      .filter((file) => ["mp4", "webm"].includes(extension(file?.name)))
      .filter((file) => Number(file?.size || 0) > 0)
      .sort((a, b) => Number(b.size || 0) - Number(a.size || 0));
    if (!videos.length) return failure(input, "provider-empty", "No playable video files found");

    const base = "https://archive.org/download/" + encodeURIComponent(identifier) + "/";
    const streams = videos.slice(0, 8).map((file, index) => {
      const [protocol, container] = mediaProtocol(file.name);
      return {
        id: "archive-stream-" + index,
        providerId: ID,
        sourceId: "archive-files",
        url: base + encodeURIComponent(String(file.name)),
        protocol,
        container,
        qualityLabel: file?.height ? String(file.height) + "p" : String(file?.format || "Archive"),
        qualityRank: Number(file?.height || videos.length - index),
        confidence: 0.95,
        cachePolicy,
      };
    });

    const subtitles = files
      .filter((file) => ["vtt", "srt"].includes(extension(file?.name)))
      .slice(0, 24)
      .map((file, index) => ({
        id: "archive-sub-" + index,
        providerId: ID,
        sourceId: "archive-files",
        url: base + encodeURIComponent(String(file.name)),
        label: String(file.name),
        format: extension(file.name),
        source: "provider",
        confidence: 0.8,
        cachePolicy: { ...cachePolicy, ttlClass: "subtitle-list" },
      }));

    const selectedStreamId = streams[0].id;
    return {
      status: "resolved",
      providerId: ID,
      selectedStreamId,
      sources: [
        {
          id: "archive-files",
          providerId: ID,
          kind: "direct-media",
          label: "Archive files",
          host: "archive.org",
          status: "selected",
          confidence: 0.95,
        },
      ],
      variants: [],
      streams,
      subtitles,
      trace: trace(input, selectedStreamId),
      failures: [],
      cachePolicy,
      healthDelta: { providerId: ID, outcome: "success", at: now() },
    };
  },
};
