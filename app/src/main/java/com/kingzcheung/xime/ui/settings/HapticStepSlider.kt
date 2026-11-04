package com.kingzcheung.xime.ui.settings

import android.view.HapticFeedbackConstants
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.theme.performXimeHapticFeedback
import kotlin.math.roundToInt

@Composable
internal fun HapticStepSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    val view = LocalView.current
    var lastStep by remember(value, valueRange, steps) {
        mutableIntStateOf(sliderStepIndex(value, valueRange, steps))
    }
    Slider(
        value = value,
        onValueChange = { nextValue ->
            val nextStep = sliderStepIndex(nextValue, valueRange, steps)
            if (nextStep != lastStep) {
                view.performXimeHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lastStep = nextStep
            }
            onValueChange(nextValue)
        },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
    )
}

internal fun sliderStepIndex(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    val intervalCount = (steps + 1).coerceAtLeast(1)
    val intervalSize = (valueRange.endInclusive - valueRange.start) / intervalCount
    if (intervalSize <= 0f) return 0
    return ((value - valueRange.start) / intervalSize).roundToInt().coerceIn(0, intervalCount)
}
