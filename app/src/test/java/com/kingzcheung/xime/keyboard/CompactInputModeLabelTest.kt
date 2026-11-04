package com.kingzcheung.xime.keyboard

import com.kingzcheung.xime.ui.keyboard.compactInputModeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactInputModeLabelTest {
    @Test
    fun `shortens Frost mode names and preserves other schemas`() {
        assertEquals("拼音", compactInputModeLabel("白霜拼音"))
        assertEquals("双拼", compactInputModeLabel("白霜小鹤双拼"))
        assertEquals("混拼", compactInputModeLabel("白霜全拼＋小鹤双拼混输（实验）"))
        assertEquals("五笔", compactInputModeLabel("五笔"))
    }
}
