package com.blockapp.android.ui

import android.content.Context
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.platform.LocalContext
import com.blockapp.android.util.Haptics
import kotlinx.coroutines.launch

// How a tap gets felt. BlockAppTheme installs the two pieces below for the whole app, so every
// `Modifier.clickable` / `selectable` / `toggleable` that uses the default indication vibrates
// without each screen opting in. Material3's own Button, IconButton, TextButton, Switch and
// Checkbox draw their ripple with `ripple()` directly and never see LocalIndication, so those
// call sites use [rememberHaptics] in their onClick instead. A clickable must not do both: it
// would buzz twice per tap.

/**
 * The theme's indication: Material's ripple plus a click on release. Release, not press, so a
 * finger that lands on a card and then scrolls the list (press then cancel) doesn't buzz.
 */
@Immutable
internal class HapticRipple(private val context: Context) : IndicationNodeFactory {
    private val ripple = ripple()

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HapticIndicationNode(interactionSource, context, ripple.create(interactionSource))

    override fun equals(other: Any?): Boolean = other is HapticRipple && other.context == context
    override fun hashCode(): Int = context.hashCode()
}

private class HapticIndicationNode(
    private val interactionSource: InteractionSource,
    private val context: Context,
    ripple: DelegatableNode,
) : DelegatingNode() {
    init {
        delegate(ripple)
    }

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect {
                if (it is PressInteraction.Release) Haptics.click(context)
            }
        }
    }
}

/**
 * Replaces Compose's LocalHapticFeedback, so text selection and any library code asking for
 * feedback get the same felt buzz as the rest of the app rather than the faint system tick.
 */
internal class VibratorHapticFeedback(private val context: Context) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        Haptics.perform(
            context,
            if (hapticFeedbackType == HapticFeedbackType.LongPress) Haptics.Kind.CLICK else Haptics.Kind.TICK,
        )
    }
}

/** For onClick lambdas of Material components, which bypass [HapticRipple]; see the file note. */
@Immutable
internal class AppHaptics(private val context: Context) {
    fun tick() = Haptics.tick(context)
    fun click() = Haptics.click(context)
    fun confirm() = Haptics.confirm(context)
    fun reject() = Haptics.reject(context)
}

@Composable
internal fun rememberHaptics(): AppHaptics {
    val context = LocalContext.current.applicationContext
    return remember(context) { AppHaptics(context) }
}

/** [onClick] with a click buzz first, for Material buttons. */
@Composable
internal fun withHaptic(onClick: () -> Unit): () -> Unit {
    val haptics = rememberHaptics()
    return {
        haptics.click()
        onClick()
    }
}

/** [onChange] with a tick first, for Material's Switch and Checkbox. */
@Composable
internal fun withHapticToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = rememberHaptics()
    return {
        haptics.tick()
        onChange(it)
    }
}
