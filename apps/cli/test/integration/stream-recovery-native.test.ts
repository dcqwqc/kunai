import { expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { bundledKunaiMpvBridgePath } from "@/infra/player/kunai-mpv-bridge";
import type { PersistentMpvSessionRuntime } from "@/infra/player/persistent-mpv-runtime";
import { PersistentMpvSession } from "@/infra/player/PersistentMpvSession";
import type { PlayerPlaybackEvent } from "@/infra/player/PlayerService";
import { DEFAULT_CONFIG } from "@/services/persistence/ConfigService";

// Opt-in: headless native mpv only. Never invoke a user's desktop player shim.
const native =
  process.env.KUNAI_NATIVE_STREAM_TEST === "1" && Bun.which("mpv") && Bun.which("ffmpeg");
(native ? test : test.skip)(
  "native decoder rejects invalid media then plays video at a failover timestamp in one process",
  async () => {
    const dir = await mkdtemp(join(tmpdir(), "kunai-stream-native-"));
    let session: PersistentMpvSession | null = null;
    const children: Array<ReturnType<typeof Bun.spawn>> = [];
    try {
      const video = join(dir, "video.mp4");
      const invalid = join(dir, "invalid.mp4");
      const subtitle = join(dir, "sub.vtt");
      const generated = Bun.spawn(
        [
          "ffmpeg",
          "-hide_banner",
          "-loglevel",
          "error",
          "-f",
          "lavfi",
          "-i",
          "testsrc2=size=320x180:rate=24",
          "-f",
          "lavfi",
          "-i",
          "sine=frequency=440",
          "-t",
          "3",
          "-c:v",
          "mpeg4",
          "-c:a",
          "aac",
          "-y",
          video,
        ],
        { stdout: "ignore", stderr: "pipe" },
      );
      expect(await generated.exited, await new Response(generated.stderr).text()).toBe(0);
      await Bun.write(invalid, "HTTP 200 is not a playable video");
      await Bun.write(subtitle, "WEBVTT\n\n00:00.000 --> 00:03.000\nRecovery subtitle\n");
      const commands: unknown[][] = [];
      const events: PlayerPlaybackEvent[] = [];
      const runtime: PersistentMpvSessionRuntime = {
        which: (name) => Bun.which(name),
        spawn(command, options) {
          const separator = command.indexOf("--");
          const headless = [
            "--no-config",
            "--vo=null",
            "--ao=null",
            "--force-window=no",
            "--really-quiet",
          ];
          const child = Bun.spawn(
            [...command.slice(0, separator), ...headless, ...command.slice(separator)],
            { ...options, stderr: "inherit" },
          );
          children.push(child);
          return child;
        },
        async waitForIpcEndpoint(...args) {
          return (await import("@/infra/player/mpv-ipc")).waitForMpvIpcEndpoint(...args);
        },
        async openIpcSession(options) {
          const ipc = await (await import("@/infra/player/mpv-ipc")).openMpvIpcSession(options);
          return {
            ...ipc,
            send: async (command, timeout) => {
              commands.push([...command]);
              return ipc.send(command, timeout);
            },
          };
        },
      };
      session = await PersistentMpvSession.create({
        stream: { url: video, headers: {}, timestamp: Date.now() },
        options: {
          displayTitle: "Same episode",
          urlKind: "local",
          primarySubtitle: null,
          onPlaybackEvent: (event) => events.push(event),
        },
        kitsuneConfig: {
          ...DEFAULT_CONFIG,
          mpvKunaiScriptPath: bundledKunaiMpvBridgePath(),
          mpvInProcessStreamReconnect: false,
        },
        mpv: { clean: true },
        onControlReady: () => {},
        runtime,
      });
      const initial = session.waitForCurrentPlayback();
      await initial;
      events.length = 0;
      const rejected = await session.play(
        { url: invalid, headers: {}, timestamp: Date.now() },
        {
          displayTitle: "Same episode",
          urlKind: "local",
          primarySubtitle: null,
          onPlaybackEvent: (event) => events.push(event),
        },
      );
      expect(rejected.endReason).toBe("error");
      expect(events.some((event) => event.type === "playback-started")).toBe(false);
      events.length = 0;
      const recovered = await session.play(
        { url: video, headers: {}, timestamp: Date.now() },
        {
          displayTitle: "Same episode",
          urlKind: "local",
          primarySubtitle: subtitle,
          subtitleUrlKind: "local",
          startAt: 1,
          audioPreference: "original",
          subtitlePreference: "en",
          onPlaybackEvent: (event) => events.push(event),
        },
      );
      expect(recovered.endReason).toBe("eof");
      expect(events.some((event) => event.type === "playback-started")).toBe(true);
      expect(
        events.some((event) => event.type === "playback-progress" && event.positionSeconds >= 1),
      ).toBe(true);
      expect(children).toHaveLength(1);
      expect(commands.filter((command) => command[0] === "loadfile")).toHaveLength(2);
      expect(commands.some((command) => command[0] === "sub-add" && command[1] === subtitle)).toBe(
        true,
      );
    } finally {
      await session?.close();
      for (const child of children) if (child.exitCode === null) child.kill();
      await rm(dir, { recursive: true, force: true });
    }
  },
  20_000,
);
