package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackManagerHapticPhaseTest {
    @Test
    fun hapticIsEmittedOnExactlyTheConfiguredKeyPhase() {
        assertTrue(shouldEmitKeyHaptic(keyUp = false, hapticOnKeyUp = false))
        assertFalse(shouldEmitKeyHaptic(keyUp = true, hapticOnKeyUp = false))
        assertFalse(shouldEmitKeyHaptic(keyUp = false, hapticOnKeyUp = true))
        assertTrue(shouldEmitKeyHaptic(keyUp = true, hapticOnKeyUp = true))
    }
}
