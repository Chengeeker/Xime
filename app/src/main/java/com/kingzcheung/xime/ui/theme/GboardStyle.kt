package com.kingzcheung.xime.ui.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 公开截图的测量记录见 design-reference/gboard/README.md。 */
object GboardStyle {
    const val THEME_ID = "gboard_inspired"
    const val DYNAMIC_THEME_ID = DynamicThemes.THEME_ID
    const val LEGACY_DYNAMIC_THEME_ID = "gboard_inspired_dynamic"
    val KeyRadius = 6.dp
    val LetterSize = 22.sp
    val PopupRadius = 16.dp
    val FunctionRadius = 100.dp
    val ToolRadius = 28.dp

    fun canonicalThemeId(id: String): String =
        if (id == LEGACY_DYNAMIC_THEME_ID) DYNAMIC_THEME_ID else id

    fun matches(id: String): Boolean =
        id == THEME_ID || canonicalThemeId(id) == DYNAMIC_THEME_ID

    fun pressedSurface(background: Color, text: Color): Color = lerp(background, text, 0.12f)

    val theme = KeyboardColorScheme(
        id = THEME_ID,
        name = "Gboard Inspired",
        specialKeyLight = Color(0xFFC0EAF8),
        specialKeyDark = Color(0xFF1C4A57),
        accentLight = Color(0xFF245363),
        accentDark = Color(0xFFB6E8F7),
        keyboardBgLight = Color(0xFFE3F1F1),
        keyboardBgDark = Color(0xFF0D1D1D),
        keyBgLight = Color.White,
        keyBgDark = Color(0xFF253535),
        candidateBarBgLight = Color(0xFFE3F1F1),
        candidateBarBgDark = Color(0xFF0D1D1D),
        keyTextColorLight = Color(0xFF101C1D),
        keyTextColorDark = Color(0xFFDCE8E8),
        candidateTextColorLight = Color(0xFF101C1D),
        candidateTextColorDark = Color(0xFFDCE8E8),
        candidateSelectedTextColorLight = Color(0xFF245363),
        candidateSelectedTextColorDark = Color(0xFFB6E8F7),
        dividerColorLight = Color(0xFFBACBCB),
        dividerColorDark = Color(0xFF435353),
    )

    fun dynamicTheme(context: Context): KeyboardColorScheme? =
        DynamicThemes.create(context)?.let { dynamic ->
            dynamic.copy(
                id = DYNAMIC_THEME_ID,
                name = "动态取色",
                keyboardBgLight = lerp(dynamic.surfaceLight, dynamic.specialKeyLight, 0.35f),
                keyboardBgDark = dynamic.surfaceDark,
                candidateTextColorLight = dynamic.keyTextColorLight,
                candidateTextColorDark = dynamic.keyTextColorDark,
            )
        }
}

val LocalGboardStyle = staticCompositionLocalOf { false }
