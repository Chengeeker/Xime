package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticStepSliderTest {
    @Test
    fun `step index follows slider detents including endpoints`() {
        val range = 0f..100f
        assertEquals(0, sliderStepIndex(0f, range, 19))
        assertEquals(1, sliderStepIndex(5f, range, 19))
        assertEquals(20, sliderStepIndex(100f, range, 19))
    }
}
