import { describe, expect, test } from "bun:test";

import {
  acceptResolvedProviderForPlayback,
  resolvePlaybackResolvePolicy,
} from "@/app/playback/playback-resolve-policy";

describe("resolvePlaybackResolvePolicy", () => {
  test("manual provider switch forces fresh state without disabling guided fallback", () => {
    const policy = resolvePlaybackResolvePolicy({
      recomputeSources: false,
      pendingUserProviderSwitch: true,
      sourceRefreshDecision: null,
      configuredRecoveryMode: "guided",
    });

    expect(policy).toMatchObject({
      honorExplicitProviderOnly: false,
      preferFreshStream: true,
      forceHealthCheck: true,
      shouldInvalidateSuspectResolveState: true,
      ignoreProviderHealth: false,
      ignoreTitleHealthSuggestion: false,
      recoveryMode: "guided",
      resolveIntent: "play",
    });
  });

  test("manual recovery mode keeps explicit provider switch provider-only", () => {
    const policy = resolvePlaybackResolvePolicy({
      recomputeSources: false,
      pendingUserProviderSwitch: true,
      sourceRefreshDecision: null,
      configuredRecoveryMode: "manual",
    });

    expect(policy).toMatchObject({
      honorExplicitProviderOnly: true,
      preferFreshStream: true,
      shouldInvalidateSuspectResolveState: true,
      ignoreProviderHealth: true,
      ignoreTitleHealthSuggestion: true,
      recoveryMode: "manual",
    });
  });

  test("recover invalidates suspect cache and refresh preserves playable cache on miss", () => {
    expect(
      resolvePlaybackResolvePolicy({
        recomputeSources: false,
        pendingUserProviderSwitch: false,
        sourceRefreshDecision: {
          kind: "recover",
          bypassCache: true,
          invalidateSuspectCache: true,
        },
        configuredRecoveryMode: "guided",
      }),
    ).toMatchObject({
      preferFreshStream: true,
      forceHealthCheck: true,
      preserveCachedStreamOnFreshFailure: false,
      shouldInvalidateSuspectResolveState: true,
    });

    expect(
      resolvePlaybackResolvePolicy({
        recomputeSources: false,
        pendingUserProviderSwitch: false,
        sourceRefreshDecision: {
          kind: "refresh",
          bypassCache: true,
          invalidateSuspectCache: false,
        },
        configuredRecoveryMode: "guided",
      }),
    ).toMatchObject({
      preferFreshStream: true,
      forceHealthCheck: false,
      preserveCachedStreamOnFreshFailure: true,
      shouldInvalidateSuspectResolveState: false,
    });
  });
});


describe("provider fallback preference contract", () => {
  test("a saved provider preference never overrides a successful guided fallback", () => {
    const policy = resolvePlaybackResolvePolicy({
      recomputeSources: false,
      pendingUserProviderSwitch: true,
      sourceRefreshDecision: null,
      configuredRecoveryMode: "guided",
    });
    expect(acceptResolvedProviderForPlayback({
      policy,
      requestedProviderId: "vidlink",
      resolvedProviderId: "vidrock",
    })).toBe(true);
  });

  test("fallback-first is automatic, while manual and recompute remain provider-only", () => {
    for (const mode of ["fallback-first", "manual"] as const) {
      const policy = resolvePlaybackResolvePolicy({
        recomputeSources: false,
        pendingUserProviderSwitch: true,
        sourceRefreshDecision: null,
        configuredRecoveryMode: mode,
      });
      expect(acceptResolvedProviderForPlayback({
        policy,
        requestedProviderId: "vidlink",
        resolvedProviderId: "vidrock",
      })).toBe(mode === "fallback-first");
    }
    const recompute = resolvePlaybackResolvePolicy({
      recomputeSources: true,
      pendingUserProviderSwitch: false,
      sourceRefreshDecision: null,
      configuredRecoveryMode: "guided",
    });
    expect(acceptResolvedProviderForPlayback({
      policy: recompute,
      requestedProviderId: "vidlink",
      resolvedProviderId: "vidrock",
    })).toBe(false);
    expect(acceptResolvedProviderForPlayback({
      policy: recompute,
      requestedProviderId: "vidlink",
      resolvedProviderId: "vidlink",
    })).toBe(true);
  });
});
