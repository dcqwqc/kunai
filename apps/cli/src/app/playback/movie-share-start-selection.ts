import type { EpisodeSelection } from "@/session-flow";

/**
 * An explicit shared movie timestamp is a playback instruction, not a request
 * for another Resume/Restart dialog. Even t=0 means restart from the start.
 * Ordinary launches still ask when saved movie progress exists.
 */
export function movieShareStartSelection(
  startSeconds: number | undefined,
): EpisodeSelection | null {
  if (startSeconds === undefined || !Number.isFinite(startSeconds) || startSeconds < 0) {
    return null;
  }
  return { season: 1, episode: 1, startAt: startSeconds, suppressResumePrompt: true };
}
