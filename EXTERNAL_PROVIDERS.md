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
: Resolve pasted HTTP(S) video URLs in YouTube/video mode via local `yt-dlp`.

`examples/providers/web-media-url.mjs`
: Resolve pasted HTTP(S) media URLs in Movies & Series mode using local `yt-dlp`.

`examples/providers/vixsrc.mjs`
: Resolve freshly signed HLS streams for TMDB movies and episodes. Validates that the media playlist is complete; rejects short previews of Fantastic Fungi rather than treating them as the movie.

Install the examples for the current user:

```bash
mkdir -p ~/.config/kunai/providers
cp examples/providers/archive.mjs ~/.config/kunai/providers/
cp examples/providers/web-url.mjs ~/.config/kunai/providers/
cp examples/providers/web-media-url.mjs ~/.config/kunai/providers/
cp examples/providers/vixsrc.mjs ~/.config/kunai/providers/
```

They remain ordinary user files after installation, so editing or adding a provider does not require rebuilding Kunai.

## Personal Sources: attach an authorized direct stream to a TMDB title

Install the `personal-sources.mjs` example, then attach a direct HTTP(S) MP4,
HLS (`.m3u8`), or DASH (`.mpd`) media URL. **A watch-page URL or DRM-protected
service URL is not interchangeable with a playable media stream.** No provider
can manufacture rights/access from a catalog ID.

```bash
cp examples/providers/personal-sources.mjs ~/.config/kunai/providers/
python3 scripts/kunai-personal-source.py add 612654 \
  'https://your-authorized-video-host.example/full-film.m3u8' \
  --title 'Fantastic Fungi' --label 'Authorized full film'
kunai providers
kunai --open 'kunai://play?cat=tmdb%3A612654&kind=movie&src=personal-sources'
```

The add command records your URL in `~/.config/kunai/personal-sources.json`
(mode 0600), not the public Git repository. The provider rereads that file on
each resolution without needing a new app build. `list` displays source
labels rather than bearer URLs; `remove 612654` removes that association.
To map a TV episode, add `--kind series --season 1 --episode 2`.

The provider is local-only and deliberately does not bypass DRM or sign in to
third-party services. An expired, geoblocked, short or unauthorized URL still
cannot yield full-film playback. Verify duration against the actual film.

## VixSrc media resolution and verification

The `vixsrc` plugin requests `/api/movie/{tmdb_id}` (or the TV episode route),
reads the embed page's signed HLS playlist, and checks its VOD segment
inventory before returning a playback URL. Its stream cache is disabled
so Kunai requests a new signature on subsequent resolutions.

The plugin is independent of Kunai's built-in VidLink/VidRock/Cineby
providers and requires no browser WebView or resident background service.
Titles and regional playback access depend on the external service, and
Kunai neither hosts nor redistributes any movie. Use third-party sources
only where you have the appropriate viewing rights.

`kunai --open 'kunai://play?cat=tmdb%3A612654&kind=movie&src=vixsrc'`

For Fantastic Fungi, an October 2026 live check on Mirai resolved a VOD
playlist of about 80 minutes and confirmed advancing playback in Showtime.
That result is evidence for this title at that time, not a guarantee that
other titles, later signatures, or regions will keep working.
