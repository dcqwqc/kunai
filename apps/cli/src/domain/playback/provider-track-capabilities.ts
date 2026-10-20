import { providerMetadataMatchesLane } from "@/domain/provider-lane";
import type { ProviderLane, ProviderMetadata, ShellMode } from "@/domain/types";

import type { TrackCapability, TrackCapabilityGroup } from "./track-capabilities";

export type ProviderHealthHint = {
  readonly errorClass?: string;
  readonly consecutiveFailures?: number;
  readonly suggestedProviderId?: string;
};

export type BuildProviderTrackCapabilitiesInput = {
  readonly providers: readonly ProviderMetadata[];
  readonly mode: ShellMode;
  readonly currentProviderId: string;
  readonly healthByProviderId?: Readonly<Record<string, ProviderHealthHint>>;
  /**
   * Lanes the active title can actually resolve through (same eligibility the
   * provider picker uses). When set, replaces the hard mode filter so a
   * cross-lane title lists the providers it can really switch to.
   */
  readonly lanes?: readonly ProviderLane[];
};

function healthDetail(providerId: string, health?: ProviderHealthHint): string | undefined {
  if (!health) return undefined;
  const parts: string[] = [];
  if (health.errorClass) {
    parts.push(`last failure: ${health.errorClass}`);
  }
  if (health.consecutiveFailures && health.consecutiveFailures > 0) {
    parts.push(`${health.consecutiveFailures} recent failures`);
  }
  if (health.suggestedProviderId && health.suggestedProviderId !== providerId) {
    parts.push(`try ${health.suggestedProviderId} instead`);
  }
  return parts.length > 0 ? parts.join(" · ") : undefined;
}

/**
 * Build the Provider section for the unified Tracks panel. Provider switching is
 * intentionally separate from source/quality/audio inventory rows.
 */
export function buildProviderTrackCapabilities(
  input: BuildProviderTrackCapabilitiesInput,
): TrackCapabilityGroup {
  const eligible = input.providers.filter((provider) => {
    if (input.lanes) {
      return input.lanes.some((lane) => providerMetadataMatchesLane(provider, lane));
    }
    if (input.mode === "youtube") return provider.isYoutubeProvider;
    if (input.mode === "anime") return provider.isAnimeProvider;
    return !provider.isAnimeProvider && !provider.isYoutubeProvider;
  });
  // The playing provider always lists — filtering it out renders "No
  // compatible providers for this mode" above its own live source list.
  const listed = eligible.some((provider) => provider.id === input.currentProviderId)
    ? eligible
    : [
        ...eligible,
        ...input.providers.filter((provider) => provider.id === input.currentProviderId),
      ];
  // The most relevant lane comes first. Cross-lane adapters are still
  // selectable, but should not crowd out actual anime providers (the old
  // inventory placed VidLink ahead of HiAnime/Miruro for anime titles).
  const ordered = [...listed].sort((left, right) => {
    const leftNative =
      input.mode === "anime" ? left.isAnimeProvider === true : left.isAnimeProvider !== true;
    const rightNative =
      input.mode === "anime" ? right.isAnimeProvider === true : right.isAnimeProvider !== true;
    if (leftNative !== rightNative) return leftNative ? -1 : 1;
    return 0;
  });
  const rows: TrackCapability[] = ordered.map((provider) => {
    const selected = provider.id === input.currentProviderId;
    const secondaryLane = input.mode === "anime" && !provider.isAnimeProvider;
    const health = input.healthByProviderId?.[provider.id];
    return {
      section: "provider",
      label: provider.name,
      value: provider.id,
      selected,
      enabled: !selected,
      detail:
        [provider.description, healthDetail(provider.id, health)].filter(Boolean).join(" · ") ||
        undefined,
      risk: health?.errorClass ? "failed" : secondaryLane ? "fallback" : "normal",
      reason: selected ? "Current provider" : undefined,
    };
  });

  return {
    section: "provider",
    title: "Provider",
    rows,
    selectable: rows.some((row) => row.enabled),
    emptyReason: rows.length === 0 ? "No compatible providers for this mode" : undefined,
  };
}
