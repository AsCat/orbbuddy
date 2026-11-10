package dev.orbbuddy.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {
    @Test
    fun codeExpiresExactlyAtSixtySeconds() {
        val start = 10_000L
        assertEquals(60L, PairingCode.secondsLeft(start, start))
        assertEquals(60L, PairingCode.secondsLeft(start, start + 999L))
        assertEquals(59L, PairingCode.secondsLeft(start, start + 1_000L))
        assertEquals(1L, PairingCode.secondsLeft(start, start + 59_999L))
        assertNull(PairingCode.secondsLeft(start, start + 60_000L))
    }

    @Test
    fun invalidElapsedClockCannotReviveCode() {
        assertNull(PairingCode.secondsLeft(-1L, 5_000L))
        assertNull(PairingCode.secondsLeft(10_000L, 9_999L))
    }
}
