import { readdir } from "node:fs/promises";
import { extname, join } from "node:path";
import { pathToFileURL } from "node:url";

import { assertProviderModuleMatchesManifest, type CoreProviderModule } from "@kunai/core";
import { getKunaiPaths } from "@kunai/storage";

const SUPPORTED_EXTENSIONS = new Set([".js", ".mjs", ".cjs"]);

export type ExternalProviderLoadIssue = {
  readonly file: string;
  readonly message: string;
};

export type ExternalProviderLoadResult = {
  readonly modules: readonly CoreProviderModule[];
  readonly issues: readonly ExternalProviderLoadIssue[];
  readonly directory: string;
};

type PluginNamespace = {
  readonly default?: unknown;
  readonly providerModule?: unknown;
};

function isProviderModule(value: unknown): value is CoreProviderModule {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<CoreProviderModule>;
  return (
    typeof candidate.providerId === "string" &&
    typeof candidate.resolve === "function" &&
    !!candidate.manifest &&
    typeof candidate.manifest === "object" &&
    typeof candidate.manifest.id === "string"
  );
}

function pluginDirectory(explicitDirectory?: string): string {
  if (explicitDirectory?.trim()) return explicitDirectory;
  const envDirectory = process.env.KUNAI_PROVIDER_DIR?.trim();
  if (envDirectory) return envDirectory;
  return join(getKunaiPaths().configDir, "providers");
}

export async function loadExternalProviderModules(
  options: {
    readonly directory?: string;
    readonly reservedProviderIds?: ReadonlySet<string>;
  } = {},
): Promise<ExternalProviderLoadResult> {
  const directory = pluginDirectory(options.directory);
  const modules: CoreProviderModule[] = [];
  const issues: ExternalProviderLoadIssue[] = [];
  const seen = new Set(options.reservedProviderIds ?? []);

  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === "ENOENT") return { modules, issues, directory };
    return {
      modules,
      issues: [
        { file: directory, message: error instanceof Error ? error.message : String(error) },
      ],
      directory,
    };
  }

  const candidates = entries
    .filter((entry) => entry.isFile() && SUPPORTED_EXTENSIONS.has(extname(entry.name)))
    .sort((left, right) => left.name.localeCompare(right.name));

  for (const entry of candidates) {
    const file = join(directory, entry.name);
    try {
      const namespace = (await import(pathToFileURL(file).href)) as PluginNamespace;
      const candidate = namespace.providerModule ?? namespace.default;
      if (!isProviderModule(candidate)) {
        throw new Error(
          "plugin must export default or providerModule with providerId, manifest, and resolve()",
        );
      }
      assertProviderModuleMatchesManifest(candidate);
      if (seen.has(candidate.providerId)) {
        throw new Error(
          'provider id "' + candidate.providerId + '" collides with an already registered provider',
        );
      }
      seen.add(candidate.providerId);
      modules.push(candidate);
    } catch (error) {
      issues.push({
        file,
        message: error instanceof Error ? error.message : String(error),
      });
    }
  }

  return { modules, issues, directory };
}
