import { describe, expect, test } from "bun:test";

import { movieShareStartSelection } from "@/app/playback/movie-share-start-selection";
import { startFromEpisodeSelection } from "@/app/playback/playback-start-intent";

describe("explicit movie share start", () => {
  test("bypasses Resume/Restart and starts at a supplied timestamp", () => {
    const selection = movieShareStartSelection(1);
    expect(selection).toEqual({
      season: 1,
      episode: 1,
      startAt: 1,
      suppressResumePrompt: true,
    });
    expect(startFromEpisodeSelection(selection!)).toEqual({
      startAt: 1,
      resumePromptAt: 0,
      suppressResumePrompt: true,
    });
  });

  test("an explicit zero means Restart without prompting", () => {
    expect(movieShareStartSelection(0)?.startAt).toBe(0);
  });

  test("ordinary deep links still allow Resume/Restart", () => {
    expect(movieShareStartSelection(undefined)).toBeNull();
    expect(movieShareStartSelection(Number.NaN)).toBeNull();
    expect(movieShareStartSelection(-2)).toBeNull();
  });
});
