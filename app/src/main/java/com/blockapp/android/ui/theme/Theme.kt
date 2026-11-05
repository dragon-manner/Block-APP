package com.blockapp.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.HapticRipple
import com.blockapp.android.ui.VibratorHapticFeedback

// surface == background so Material's default top bars sit flush with the page instead of
// drawing a band. Cards use the surfaceContainer tiers, and the older screens that still draw
// cards in surfaceVariant get the same card colour.
private val Colors = darkColorScheme(
    primary = AccentColor.LIME.main,
    onPrimary = AccentColor.LIME.ink,
    primaryContainer = AccentColor.LIME.wash,
    onPrimaryContainer = AccentColor.LIME.main,
    inversePrimary = AccentColor.LIME.ink,
    secondary = BoneMuted,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Bone,
    tertiary = Ember,
    onTertiary = Ink950,
    tertiaryContainer = EmberWash,
    onTertiaryContainer = Ember,
    error = StatusColors.Danger,
    onError = Ink950,
    errorContainer = Color(0xFF3A1717),
    onErrorContainer = StatusColors.Danger,
    background = Ink950,
    onBackground = Bone,
    surface = Ink950,
    onSurface = Bone,
    surfaceVariant = Ink850,
    onSurfaceVariant = BoneMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Bone,
    inverseOnSurface = Ink950,
    outline = Ink700,
    outlineVariant = Ink800,
    scrim = Color.Black,
    surfaceBright = Ink800,
    surfaceDim = Ink950,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink850,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink800,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The [darkTheme] parameter is kept so call sites (BlockOverlayActivity passes `true`) don't
 * have to change, but there is one palette: see Color.kt for why the app is dark-only.
 */
@Composable
fun BlockAppTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val indication = remember(context) { HapticRipple(context) }
    val haptics = remember(context) { VibratorHapticFeedback(context) }
    AccentState.ensureLoaded(context)
    val accent = AccentState.current
    val colors = remember(accent) {
        Colors.copy(
            primary = accent.main,
            onPrimary = accent.ink,
            primaryContainer = accent.wash,
            onPrimaryContainer = accent.main,
            inversePrimary = accent.ink,
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        // Inside MaterialTheme, which sets its own plain ripple as LocalIndication: provided
        // outside it, this would be overridden and no tap would vibrate. See ui/Haptic.kt.
        CompositionLocalProvider(
            LocalIndication provides indication,
            LocalHapticFeedback provides haptics,
            content = content,
        )
    }
}
