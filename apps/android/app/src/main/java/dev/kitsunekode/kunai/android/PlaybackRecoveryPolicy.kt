package dev.kitsunekode.kunai.android

/**
 * Bounded self-healing policy for the on-device Media3 player.
 * State is scoped to one activity/episode; no device restarts or network relay.
 * Time values use elapsedRealtime rather than wall-clock time.
 */
internal class PlaybackRecoveryPolicy {
    private var playbackStartedAt = 0L
    private var bufferingSince = 0L
    private var lastFrameAt = 0L
    private val recentAttempts = ArrayDeque<Long>()

    fun framePresented(nowMs: Long) {
        lastFrameAt = nowMs
    }

    fun check(
        nowMs: Long,
        foreground: Boolean,
        playRequested: Boolean,
        buffering: Boolean,
        ready: Boolean
    ): Boolean {
        if (!foreground || !playRequested) {
            playbackStartedAt = 0
            bufferingSince = 0
            return false
        }
        if (playbackStartedAt == 0L) playbackStartedAt = nowMs
        if (buffering && bufferingSince == 0L) bufferingSince = nowMs
        if (!buffering) bufferingSince = 0L

        val bufferStalled = bufferingSince > 0 && nowMs - bufferingSince >= 18_000
        val videoStalled = ready && (
            if (lastFrameAt > 0) nowMs - lastFrameAt >= 12_000
            else nowMs - playbackStartedAt >= 20_000
        )
        return (bufferStalled || videoStalled) && canAttempt(nowMs)
    }

    fun canAttempt(nowMs: Long): Boolean {
        while (recentAttempts.isNotEmpty() &&
            nowMs - recentAttempts.first() >= 600_000) recentAttempts.removeFirst()
        return recentAttempts.size < 2 &&
            (recentAttempts.isEmpty() || nowMs - recentAttempts.last() >= 20_000)
    }

    fun attempted(nowMs: Long) {
        recentAttempts.addLast(nowMs)
        bufferingSince = nowMs
        playbackStartedAt = nowMs
        lastFrameAt = 0L
    }

    fun resetForUserSeek(nowMs: Long) {
        bufferingSince = 0L
        playbackStartedAt = nowMs
        lastFrameAt = nowMs
    }
}
