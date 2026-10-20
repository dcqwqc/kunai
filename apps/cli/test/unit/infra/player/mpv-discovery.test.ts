import { describe, expect, test } from "bun:test";

import { discoverMpvInvocation } from "@/infra/player/mpv-discovery";

const HOME = "/home/tester";

function deps(overrides: {
  which?: (command: string) => string | null;
  exists?: (path: string) => boolean;
  platform?: NodeJS.Platform;
  homeDir?: string;
  preferSystemMpv?: boolean;
}) {
  return {
    which: overrides.which ?? (() => null),
    exists: overrides.exists ?? (() => false),
    platform: overrides.platform ?? "linux",
    homeDir: overrides.homeDir ?? HOME,
    preferSystemMpv: overrides.preferSystemMpv ?? false,
  };
}

describe("discoverMpvInvocation", () => {
  test("prefers a PATH mpv over every fallback", () => {
    const found = discoverMpvInvocation(deps({ which: () => "/usr/bin/mpv", exists: () => true }));
    expect(found).toEqual({ argv: ["mpv"], via: "path" });
  });

  test("returns null when neither PATH nor Flatpak provides mpv", () => {
    expect(discoverMpvInvocation(deps({}))).toBeNull();
  });

  test("discovers the flatpak when only the app dir exists", () => {
    const found = discoverMpvInvocation(
      deps({
        which: (command) => (command === "flatpak" ? "/usr/bin/flatpak" : null),
        exists: (path) => path === `/var/lib/flatpak/app/io.mpv.Mpv`,
      }),
    );
    expect(found).toEqual({ argv: ["flatpak", "run", "io.mpv.Mpv"], via: "flatpak" });
  });

  test("checks the user-scope flatpak install dir", () => {
    const found = discoverMpvInvocation(
      deps({
        which: (command) => (command === "flatpak" ? "/usr/bin/flatpak" : null),
        exists: (path) => path === `${HOME}/.local/share/flatpak/app/io.mpv.Mpv`,
      }),
    );
    expect(found?.via).toBe("flatpak");
  });

  test("ignores a flatpak binary with no mpv app installed", () => {
    const found = discoverMpvInvocation(
      deps({ which: (command) => (command === "flatpak" ? "/usr/bin/flatpak" : null) }),
    );
    expect(found).toBeNull();
  });

  test("skips the flatpak leg off-Linux", () => {
    const found = discoverMpvInvocation(
      deps({
        platform: "darwin",
        which: (command) => (command === "flatpak" ? "/usr/bin/flatpak" : null),
        exists: () => true,
      }),
    );
    expect(found).toBeNull();
  });
});

test("uses native system mpv only for an explicitly requested compatibility fallback", () => {
  const options = deps({
    which: (command) => (command === "mpv" ? "/home/tester/.local/bin/mpv" : null),
    exists: (path) => path === "/usr/bin/mpv",
    preferSystemMpv: true,
  });
  expect(discoverMpvInvocation(options)).toEqual({
    argv: ["/usr/bin/mpv"],
    via: "path",
  });
  expect(discoverMpvInvocation({ ...options, preferSystemMpv: false })).toEqual({
    argv: ["mpv"],
    via: "path",
  });
});
