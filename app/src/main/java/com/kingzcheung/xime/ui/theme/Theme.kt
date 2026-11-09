package com.kingzcheung.xime.ui.theme

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences

private fun hclColor(hue: Float, chroma: Float, lightness: Float): Color {
    val s = (chroma / 100f).coerceIn(0f, 1f)
    val l = (lightness / 100f).coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, s, l)))
}

private fun deriveSecondaryHue(hue: Float): Float {
    return (hue + 30f) % 360f
}

private fun deriveTertiaryHue(hue: Float): Float {
    return (hue + 60f) % 360f
}

private fun hslOf(color: Color): FloatArray {
    val argb = color.toArgb()
    val h = FloatArray(3)
    ColorUtils.RGBToHSL(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        h
    )
    return h
}

private fun generateColorScheme(seed: Color, seedContainer: Color, dark: Boolean): ColorScheme {
    val hsl = hslOf(seed)
    val hue = hsl[0]
    val sat = hsl[1]
    val p = sat.coerceAtMost(0.6f)

    if (dark) {
        return darkColorScheme(
            primary = seed,
            onPrimary = hclColor(hue, 0f, 20f),
            primaryContainer = seedContainer,
            onPrimaryContainer = hclColor(hue, p * 20f, 90f),
            secondary = hclColor(deriveSecondaryHue(hue), p * 40f, 80f),
            onSecondary = hclColor(deriveSecondaryHue(hue), p * 0f, 20f),
            secondaryContainer = hclColor(deriveSecondaryHue(hue), p * 30f, 30f),
            onSecondaryContainer = hclColor(deriveSecondaryHue(hue), p * 20f, 90f),
            tertiary = hclColor(deriveTertiaryHue(hue), p * 30f, 80f),
            onTertiary = hclColor(deriveTertiaryHue(hue), p * 0f, 20f),
            tertiaryContainer = hclColor(deriveTertiaryHue(hue), p * 20f, 30f),
            onTertiaryContainer = hclColor(deriveTertiaryHue(hue), p * 0f, 90f),
            background = hclColor(hue, 0f, 6f),
            surface = hclColor(hue, p * 5f, 6f),
            surfaceDim = hclColor(hue, p * 5f, 4f),
            surfaceBright = hclColor(hue, p * 5f, 14f),
            surfaceContainerLowest = hclColor(hue, p * 5f, 2f),
            surfaceContainerLow = hclColor(hue, p * 5f, 18f),
            surfaceContainer = hclColor(hue, p * 5f, 22f),
            surfaceContainerHigh = hclColor(hue, p * 5f, 26f),
            surfaceContainerHighest = hclColor(hue, p * 5f, 30f),
            onBackground = hclColor(hue, 0f, 90f),
            onSurface = hclColor(hue, 0f, 90f),
            surfaceVariant = hclColor(hue, p * 10f, 30f),
            onSurfaceVariant = hclColor(hue, 0f, 80f),
            outline = hclColor(hue, 0f, 60f),
            outlineVariant = hclColor(hue, 0f, 30f),
            inverseSurface = hclColor(hue, 0f, 90f),
            inverseOnSurface = hclColor(hue, 0f, 10f),
            inversePrimary = hclColor(hue, p * 80f, 40f),
            surfaceTint = seed
        )
    } else {
        return lightColorScheme(
            primary = seed,
            onPrimary = hclColor(hue, 0f, 100f),
            primaryContainer = seedContainer,
            onPrimaryContainer = hclColor(hue, p * 20f, 10f),
            secondary = hclColor(deriveSecondaryHue(hue), p * 40f, 40f),
            onSecondary = hclColor(deriveSecondaryHue(hue), p * 0f, 100f),
            secondaryContainer = hclColor(deriveSecondaryHue(hue), p * 30f, 90f),
            onSecondaryContainer = hclColor(deriveSecondaryHue(hue), p * 20f, 10f),
            tertiary = hclColor(deriveTertiaryHue(hue), p * 30f, 40f),
            onTertiary = hclColor(deriveTertiaryHue(hue), p * 0f, 100f),
            tertiaryContainer = hclColor(deriveTertiaryHue(hue), p * 20f, 90f),
            onTertiaryContainer = hclColor(deriveTertiaryHue(hue), p * 0f, 10f),
            background = hclColor(hue, 0f, 96f),
            onBackground = hclColor(hue, 0f, 10f),
            surface = hclColor(hue, p * 5f, 96f),
            surfaceDim = hclColor(hue, p * 5f, 92f),
            surfaceBright = hclColor(hue, p * 5f, 98f),
            surfaceContainerLowest = hclColor(hue, p * 5f, 99f),
            surfaceContainerLow = hclColor(hue, p * 5f, 94f),
            surfaceContainer = hclColor(hue, p * 5f, 92f),
            surfaceContainerHigh = hclColor(hue, p * 5f, 90f),
            surfaceContainerHighest = hclColor(hue, p * 5f, 86f),
            onSurface = hclColor(hue, 0f, 10f),
            surfaceVariant = hclColor(hue, p * 10f, 90f),
            onSurfaceVariant = hclColor(hue, 0f, 35f),
            outline = hclColor(hue, 0f, 50f),
            outlineVariant = hclColor(hue, 0f, 80f),
            inverseSurface = hclColor(hue, 0f, 20f),
            inverseOnSurface = hclColor(hue, 0f, 95f),
            inversePrimary = hclColor(hue, p * 80f, 80f),
            surfaceTint = seed
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun XimeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var currentThemeId by remember { mutableStateOf(themeId ?: SettingsPreferences.getKeyboardTheme(context)) }
    var configDarkThemeId by remember { mutableStateOf(KeysConfigHelper.loadThemeIdForMode(context, true)) }

    DisposableEffect(context, themeId) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "keyboard_theme" && themeId == null) {
                currentThemeId = SettingsPreferences.getKeyboardTheme(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val effectiveThemeId = if (themeId != null) {
        themeId
    } else if (darkTheme) {
        configDarkThemeId.takeIf { it.isNotEmpty() } ?: currentThemeId
    } else {
        currentThemeId
    }
    val scheme = KeyboardThemes.getThemeById(effectiveThemeId)
    val generatedColorScheme = if (scheme.isDynamic && DynamicThemes.isSupported()) {
        // Material You 动态配色：由系统壁纸调色板生成（官方实现），随壁纸自动更新
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val seed = if (darkTheme) scheme.primaryDark else scheme.primaryLight
        val container = if (darkTheme) scheme.primaryContainerDark else scheme.primaryContainerLight
        generateColorScheme(seed, container, darkTheme)
    }
    val colorScheme = if (darkTheme) generatedColorScheme.copy(
        background = Color(0xFF111215),
        surface = Color(0xFF111215),
        surfaceContainerLow = Color(0xFF1B1C20),
        surfaceContainer = Color(0xFF16171B),
    ) else generatedColorScheme

    val typography = remember {
        val baseTypography = Typography()
        baseTypography.copy(
        displayLarge = baseTypography.displayLarge.copy(letterSpacing = 0.sp),
        displayMedium = baseTypography.displayMedium.copy(letterSpacing = 0.sp),
        displaySmall = baseTypography.displaySmall.copy(letterSpacing = 0.sp),
        headlineLarge = baseTypography.headlineLarge.copy(letterSpacing = 0.sp),
        headlineMedium = baseTypography.headlineMedium.copy(letterSpacing = 0.sp),
        headlineSmall = baseTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleLarge = baseTypography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = baseTypography.bodyLarge.copy(letterSpacing = 0.sp),
        bodyMedium = baseTypography.bodyMedium.copy(letterSpacing = 0.sp),
        bodySmall = baseTypography.bodySmall.copy(letterSpacing = 0.sp),
        labelLarge = baseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = baseTypography.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = baseTypography.labelSmall.copy(letterSpacing = 0.sp),
        )
    }
    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        val view = LocalView.current
        val window = (context as? android.app.Activity)?.window
        if (!view.isInEditMode && window != null) {
            DisposableEffect(window, darkTheme) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                onDispose { }
            }
        }
        CompositionLocalProvider(
            LocalIndication provides remember(view) { XimeHapticIndication(view) },
            LocalRippleConfiguration provides null,
            content = content,
        )
    }
}
