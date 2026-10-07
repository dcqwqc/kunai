import { afterEach, describe, expect, test } from "bun:test";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { loadExternalProviderModules } from "@/container/load-external-providers";

const dirs: string[] = [];

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), "kunai-provider-test-"));
  dirs.push(dir);
  return dir;
}

afterEach(async () => {
  await Promise.all(dirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })));
});

describe("external provider loader", () => {
  test("loads a valid local JavaScript provider module", async () => {
    const dir = await tempDir();
    await writeFile(
      join(dir, "demo.mjs"),
      [
        "export default {",
        '  providerId: "demo",',
        "  manifest: {",
        '    id: "demo", displayName: "Demo", description: "Demo plugin", domain: "local",',
        '    recommended: false, mediaKinds: ["movie"], capabilities: ["source-resolve"],',
        '    runtimePorts: [{ runtime: "direct-http", operations: ["resolve-stream"], browserSafe: false, relaySafe: false, localOnly: true }],',
        '    cachePolicy: { ttlClass: "never-cache", scope: "local", keyParts: ["provider", "demo"] },',
        "    browserSafe: false, relaySafe: false",
        "  },",
        '  async resolve() { throw new Error("not used"); }',
        "};",
      ].join("\n"),
    );

    const result = await loadExternalProviderModules({ directory: dir });
    expect(result.issues).toEqual([]);
    expect(result.modules.map((module) => module.providerId)).toEqual(["demo"]);
  });

  test("isolates malformed plugins and rejects built-in id collisions", async () => {
    const dir = await tempDir();
    await writeFile(join(dir, "broken.mjs"), "export default { nope: true };\n");
    await writeFile(
      join(dir, "collision.mjs"),
      'export default { providerId: "youtube", manifest: { id: "youtube" }, async resolve() {} };\n',
    );

    const result = await loadExternalProviderModules({
      directory: dir,
      reservedProviderIds: new Set(["youtube"]),
    });

    expect(result.modules).toEqual([]);
    expect(result.issues).toHaveLength(2);
    expect(result.issues.some((issue) => issue.message.includes("collides"))).toBe(true);
  });
});
