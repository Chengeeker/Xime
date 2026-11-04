package com.kingzcheung.xime.ui.theme

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.os.SystemClock
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.node.DelegatableNode
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun View.performXimeHapticFeedback(effect: Int): Boolean {
    val mode = SettingsPreferences.getHapticMode(context)
    if (mode == "disabled") return false
    if (mode != "enabled") {
        val enabled = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
        }.getOrDefault(true)
        if (!enabled) return false
    }
    if (!XimeHapticFeedbackGate.acquire(this)) return false
    if (mode != "enabled") return performHapticFeedback(
        if (effect == HapticFeedbackConstants.CONTEXT_CLICK) HapticFeedbackConstants.CLOCK_TICK else effect,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
    )
    // 新版 Android 会忽略强制系统触觉标记，显式启用时直接请求轻振动。
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
    if (!vibrator.hasVibrator()) return false
    val vibration = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(8, 40)
    vibrator.vibrate(vibration)
    return true
}

internal class HapticFeedbackDebouncer(private val intervalMs: Long = 35L) {
    private val lastTimes = java.util.WeakHashMap<Any, Long>()

    fun acquire(target: Any, now: Long): Boolean = synchronized(lastTimes) {
        val last = lastTimes[target]
        if (last != null && now - last < intervalMs) {
            false
        } else {
            lastTimes[target] = now
            true
        }
    }
}

internal object XimeHapticFeedbackGate {
    private val debouncer = HapticFeedbackDebouncer()

    fun acquire(view: View): Boolean = debouncer.acquire(view, SystemClock.uptimeMillis())
}

internal fun Modifier.hapticTapFeedback(): Modifier = composed {
    val view = LocalView.current
    pointerInput(view) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var travel = 0f
            var multiplePointers = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.count { it.pressed } > 1) multiplePointers = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                travel += change.positionChangeIgnoreConsumed().getDistance()
                if (change.changedToUpIgnoreConsumed() && !multiplePointers && travel <= viewConfiguration.touchSlop &&
                    change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()) {
                    view.performXimeHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
internal fun hapticAction(action: () -> Unit): () -> Unit {
    val current = rememberUpdatedState(action)
    // XimeTheme's shared indication owns click feedback; this wrapper only keeps the latest callback.
    return { current.value() }
}

@Composable
internal fun hapticToggle(action: ((Boolean) -> Unit)?): ((Boolean) -> Unit)? {
    val current = rememberUpdatedState(action)
    return if (action == null) null else { value -> current.value?.invoke(value) }
}

internal class XimeHapticIndication(private val view: View) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        XimeHapticNode(interactionSource, view)

    override fun equals(other: Any?): Boolean =
        other is XimeHapticIndication && view == other.view

    override fun hashCode(): Int = view.hashCode()
}

private class XimeHapticNode(
    private val interactionSource: InteractionSource,
    private val view: View,
) : Modifier.Node() {

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    view.performXimeHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        }
    }
}
