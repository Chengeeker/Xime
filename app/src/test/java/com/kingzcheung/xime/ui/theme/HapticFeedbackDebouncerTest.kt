package com.kingzcheung.xime.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticFeedbackDebouncerTest {
    private val debouncer = HapticFeedbackDebouncer()

    @Test
    fun duplicateFeedbackForOneViewIsSuppressedForOneTapWindow() {
        val view = Any()

        assertTrue(debouncer.acquire(view, 100L))
        assertFalse(debouncer.acquire(view, 120L))
        assertTrue(debouncer.acquire(view, 135L))
    }

    @Test
    fun independentViewsKeepIndependentFeedback() {
        assertTrue(debouncer.acquire(Any(), 100L))
        assertTrue(debouncer.acquire(Any(), 100L))
    }
}
