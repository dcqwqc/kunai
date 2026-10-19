import type { StreamCandidate } from "@kunai/types";

/** Actual source dimensions outrank display hints; bitrate only breaks resolution ties. */
export function compareStreamQuality(left: StreamCandidate, right: StreamCandidate): number {
  const height = (stream: StreamCandidate) => stream.height ?? stream.qualityRank ?? 0;
  return (
    Number(Boolean(right.height)) - Number(Boolean(left.height)) ||
    height(right) - height(left) ||
    (right.bitrate ?? 0) - (left.bitrate ?? 0)
  );
}
