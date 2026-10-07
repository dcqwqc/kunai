import { DEFAULT_CONFIG } from "@kunai/config";

import { createProviderPrioritySnapshot } from "../services/providers/provider-priority";
import { loadProductionProviderModules } from "./bootstrap-providers";
import { loadExternalProviderModules } from "./load-external-providers";

export type ProviderPluginReport = {
  readonly directory: string;
  readonly builtInCount: number;
  readonly externalProviders: readonly {
    readonly id: string;
    readonly displayName: string;
    readonly mediaKinds: readonly string[];
  }[];
  readonly issues: readonly {
    readonly file: string;
    readonly message: string;
  }[];
};

export async function buildProviderPluginReport(): Promise<ProviderPluginReport> {
  const builtIns = await loadProductionProviderModules(
    createProviderPrioritySnapshot(DEFAULT_CONFIG),
  );
  const result = await loadExternalProviderModules({
    reservedProviderIds: new Set(builtIns.map((module) => module.providerId)),
  });

  return {
    directory: result.directory,
    builtInCount: builtIns.length,
    externalProviders: result.modules.map((module) => ({
      id: module.providerId,
      displayName: module.manifest.displayName,
      mediaKinds: module.manifest.mediaKinds,
    })),
    issues: result.issues,
  };
}

export async function runProviderPluginsCommand(
  options: {
    readonly json?: boolean;
  } = {},
): Promise<number> {
  const report = await buildProviderPluginReport();

  if (options.json) {
    console.log(JSON.stringify(report));
    return report.issues.length > 0 ? 1 : 0;
  }

  console.log("Kunai providers");
  console.log("  plugin directory: " + report.directory);
  console.log("  built-in providers: " + report.builtInCount);

  if (report.externalProviders.length === 0) {
    console.log("  external providers: none");
  } else {
    console.log("  external providers:");
    for (const provider of report.externalProviders) {
      console.log(
        "    " +
          provider.id +
          "  " +
          provider.displayName +
          "  [" +
          provider.mediaKinds.join(", ") +
          "]",
      );
    }
  }

  if (report.issues.length > 0) {
    console.log("  rejected plugins:");
    for (const issue of report.issues) {
      console.log("    " + issue.file + ": " + issue.message);
    }
  }

  return report.issues.length > 0 ? 1 : 0;
}
