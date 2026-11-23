package dev.kitsunekode.kunai.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test fun normalFramesNeverRestart() {
        val p = PlaybackRecoveryPolicy()
        assertFalse(p.check(1000, true, true, true, false))
        p.framePresented(3000)
        assertFalse(p.check(8000, true, true, false, true))
        p.framePresented(10500)
        assertFalse(p.check(18000, true, true, false, true))
    }

    @Test fun pausedOrBackgroundNeverRestarts() {
        val p = PlaybackRecoveryPolicy()
        assertFalse(p.check(1000, true, true, true, false))
        assertFalse(p.check(50000, true, false, true, false))
        assertFalse(p.check(80000, false, true, true, false))
    }

    @Test fun sustainedBufferingRecoversOnceWithCooldown() {
        val p = PlaybackRecoveryPolicy()
        assertFalse(p.check(1000, true, true, true, false))
        assertFalse(p.check(18000, true, true, true, false))
        assertTrue(p.check(19001, true, true, true, false))
        p.attempted(19001)
        assertFalse(p.check(22000, true, true, true, false))
        assertTrue(p.check(39100, true, true, true, false))
        p.attempted(39100)
        assertFalse(p.check(300000, true, true, true, false))
        assertTrue(p.check(620000, true, true, true, false))
    }

    @Test fun videoTimestampFreezeDetectedEvenWhenPlayerReady() {
        val p = PlaybackRecoveryPolicy()
        assertFalse(p.check(1000, true, true, false, true))
        p.framePresented(2000)
        assertFalse(p.check(13000, true, true, false, true))
        assertTrue(p.check(15001, true, true, false, true))
    }

    @Test fun userSeekingResetsWatchdog() {
        val p = PlaybackRecoveryPolicy()
        p.framePresented(1000)
        p.resetForUserSeek(25000)
        assertFalse(p.check(27000, true, true, false, true))
        assertTrue(p.check(40000, true, true, false, true))
    }
}
