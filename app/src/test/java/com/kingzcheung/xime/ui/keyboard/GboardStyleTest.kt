package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import com.kingzcheung.xime.ui.theme.GboardStyle
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import org.junit.Assert.*
import org.junit.Test

class GboardStyleTest {
    @Test
    fun `新主题可查找且不替换已有主题`() {
        assertEquals(GboardStyle.THEME_ID, KeyboardThemes.getThemeById(GboardStyle.THEME_ID).id)
        assertEquals("lavender_purple", KeyboardThemes.getThemeById("lavender_purple").id)
        assertEquals("dynamic", GboardStyle.DYNAMIC_THEME_ID)
        assertEquals("Gboard Inspired", GboardStyle.theme.name)
        assertEquals("dynamic", GboardStyle.canonicalThemeId(GboardStyle.LEGACY_DYNAMIC_THEME_ID))
        assertTrue(GboardStyle.matches(GboardStyle.DYNAMIC_THEME_ID))
        assertTrue(GboardStyle.matches(GboardStyle.LEGACY_DYNAMIC_THEME_ID))
    }

    @Test
    fun `浅深色独立键面与按压状态保持不透明`() {
        val theme = GboardStyle.theme
        for ((background, text) in listOf(theme.keyBgLight to theme.keyTextColorLight,
            theme.keyBgDark to theme.keyTextColorDark)) {
            val pressed = GboardStyle.pressedSurface(background, text)
            assertEquals(1f, pressed.alpha, 0f)
            assertNotEquals(background, pressed)
        }
        assertEquals(Color(0xFFE3F1F1), theme.keyboardBgLight)
        assertEquals(Color(0xFF253535), theme.keyBgDark)
    }

}
