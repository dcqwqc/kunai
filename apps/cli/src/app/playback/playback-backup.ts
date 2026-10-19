import type { StreamInfo } from "@/domain/types";
import { StreamHealthService } from "@/services/playback/StreamHealthService";
import { providerResolveResultToStreamInfo } from "@/services/providers/provider-result-adapter";
import { compareStreamQuality } from "@kunai/core";

import type { EpisodePrefetchBundle, EpisodePrefetchTarget } from "./episode-prefetch";

/** Reuse one alternate source already discovered for this exact playback intent. No resolve or player. */
export function selectPlaybackBackup(
  stream: StreamInfo,
  target: EpisodePrefetchTarget,
  blockedUrls: readonly string[],
): EpisodePrefetchBundle | null {
  const inventory = stream.providerResolveResult;
  const current = inventory?.streams.find((row) => row.id === inventory.selectedStreamId);
  if (!inventory || !current) return null;
  const failedSources = new Set(
    inventory.sources
      ?.filter(
        (row) => row.status === "failed" || row.status === "exhausted" || row.status === "skipped",
      )
      .map((row) => row.id),
  );
  const candidate = [...inventory.streams]
    .filter(
      (row) =>
        row.url &&
        row.url !== stream.url &&
        row.sourceId &&
        row.sourceId !== current.sourceId &&
        !failedSources.has(row.sourceId) &&
        !row.requiresYtdl &&
        !row.deferredLocator &&
        !blockedUrls.includes(row.url) &&
        row.presentation === current.presentation &&
        row.hardSubLanguage === current.hardSubLanguage &&
        (!current.audioLanguages?.length ||
          current.audioLanguages.some((lang) => row.audioLanguages?.includes(lang))) &&
        (!row.expiresAt || Date.parse(row.expiresAt) > Date.now() + 30_000),
    )
    .sort(compareStreamQuality)[0];
  if (!candidate) return null;
  const backup = providerResolveResultToStreamInfo({
    result: inventory,
    title: stream.title ?? "",
    subtitlePreference: target.subtitlePreference ?? "none",
    selectedStreamId: candidate.id,
    blockedStreamUrls: blockedUrls,
  });
  if (!backup) return null;
  return {
    target: { ...target, sourceId: candidate.sourceId, streamId: undefined },
    stream: backup,
    prepared: false,
    resolvedProviderId: target.providerId,
  };
}

/** HTTP readiness only. The active player must still supply decoded-video evidence. */
export async function checkPlaybackBackup(
  bundle: EpisodePrefetchBundle,
  signal: AbortSignal,
  healthService = new StreamHealthService({ timeoutMs: 2_000 }),
): Promise<EpisodePrefetchBundle | null> {
  const health = await healthService.check(bundle.stream, {
    force: true,
    signal,
    phase: "cache-revalidate",
  });
  return !signal.aborted && health.reachable ? bundle : null;
}

export function isPlaybackBackupFresh(bundle: EpisodePrefetchBundle, now = Date.now()): boolean {
  const selected = bundle.stream.providerResolveResult?.streams.find(
    (row) => row.id === bundle.stream.providerResolveResult?.selectedStreamId,
  );
  return (
    now - bundle.stream.timestamp < 60_000 &&
    (!selected?.expiresAt || Date.parse(selected.expiresAt) > now + 5_000)
  );
}
