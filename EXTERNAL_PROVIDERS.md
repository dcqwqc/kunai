# External provider plugins

This fork can load trusted runtime provider modules from:

```text
~/.config/kunai/providers/
```

Set `KUNAI_PROVIDER_DIR` to override that directory for development or isolated profiles.

Supported files are `.js`, `.mjs`, and `.cjs`. A file must export either `default` or `providerModule` with Kunai's provider-module shape:

```js
export default {
  providerId: "example",
  manifest: {
    id: "example",
    displayName: "Example",
    description: "Example external provider",
    domain: "example.com",
    recommended: false,
    mediaKinds: ["movie", "series"],
    catalogIdentity: "provider-native",
    capabilities: ["search", "source-resolve"],
    runtimePorts: [
      {
        runtime: "direct-http",
        operations: ["search", "resolve-stream"],
        browserSafe: false,
        relaySafe: false,
        localOnly: true,
      },
    ],
    cachePolicy: {
      ttlClass: "stream-manifest",
      scope: "local",
      keyParts: ["provider", "example", "title"],
    },
    browserSafe: false,
    relaySafe: false,
  },

  async search(input, context) {
    return [];
  },

  async resolve(input, context) {
    // Return a normal ProviderResolveResult.
  },
};
```

External providers are registered in the same `ProviderEngine` as built-ins. They therefore participate in Kunai's provider registry, search surface, timeouts, health tracking, fallback ordering, source selection, subtitle selection, and playback result adapter.

## Safety boundary

External provider files are executable JavaScript and are trusted local code. Kunai does not sandbox them. Provider IDs may not collide with built-in providers or another loaded plugin.

A malformed plugin is isolated: other plugins still load and the loader returns a per-file issue instead of failing the entire provider bootstrap.

## Included examples

`examples/providers/archive.mjs`
: Search and resolve public video from Internet Archive.

`examples/providers/web-url.mjs`
: Resolve a pasted HTTP(S) video URL using local `yt-dlp`.

Install the examples for the current user:

```bash
mkdir -p ~/.config/kunai/providers
cp examples/providers/archive.mjs ~/.config/kunai/providers/
cp examples/providers/web-url.mjs ~/.config/kunai/providers/
```

They remain ordinary user files after installation, so editing or adding a provider does not require rebuilding Kunai.
