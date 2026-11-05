package com.blockapp.android.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Every vibration in the app goes through here: taps (via the theme's indication, see
 * ui/HapticIndication), the hold-to-commit fill, a lock landing, and the block walls.
 *
 * Drives the [Vibrator] directly instead of `View.performHapticFeedback`. Compose's
 * `TextHandleMove`, which the app used before, maps on a Pixel 8 to a texture tick so faint it
 * read as "the app doesn't vibrate", and Compose 1.7 has no stronger tap type. The direct route
 * picks the best effect each phone can play:
 *
 * - API 30+ with composition primitives (Pixel, recent Samsung): crisp PRIMITIVE_CLICK/TICK.
 * - API 29+: the predefined click/tick effects, which every OEM maps to its own tuned buzz.
 * - API 26–28: a short one-shot, at a reduced amplitude where the motor can do amplitude.
 *
 * Nothing is forced past the system: on Android 13+ these are classed as touch feedback, so the
 * phone's own "Touch feedback" / "Vibration" setting still mutes them, as it should. The in-app
 * switch ([setEnabled], in Settings) mutes only this app.
 *
 * Never throws. A missing vibrator, a missing VIBRATE permission or an OEM vibrator service that
 * throws must not crash a tap handler, and the block wall calls this from the accessibility
 * service's process, where a crash takes enforcement down with it.
 */
object Haptics {

    enum class Kind {
        /** Selection changes: a segment, a chip, a stepper, a tick of the hold fill. */
        TICK,

        /** A plain tap on a button or card. */
        CLICK,

        /** Something committed: a lock started, a plan held to the end. */
        CONFIRM,

        /** A wall closed something. Firmer, so it's felt with the phone face-up on a table. */
        REJECT,
    }

    fun tick(context: Context) = perform(context, Kind.TICK)
    fun click(context: Context) = perform(context, Kind.CLICK)
    fun confirm(context: Context) = perform(context, Kind.CONFIRM)
    fun reject(context: Context) = perform(context, Kind.REJECT)

    fun perform(context: Context, kind: Kind) {
        try {
            if (!isEnabled(context)) return
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            val effect = effectFor(vibrator, kind) ?: return
            vibrator.vibrate(effect)
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }

    @Volatile
    private var cached: Vibrator? = null

    private fun vibrator(context: Context): Vibrator? {
        cached?.let { return it }
        val app = context.applicationContext
        // API 31 deprecated the single Vibrator service in favour of VibratorManager; on some
        // Android 12+ builds the old lookup returns a vibrator that plays nothing.
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
        cached = found
        return found
    }

    private fun effectFor(vibrator: Vibrator, kind: Kind): VibrationEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composed(vibrator, kind)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return when (kind) {
                Kind.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Kind.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Kind.CONFIRM -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Kind.REJECT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
        }
        // Without amplitude control a motor plays everything at full strength, so the only
        // lever left is length: shorter for a tick, a two-pulse waveform for the bigger moments.
        val amp = vibrator.hasAmplitudeControl()
        return when (kind) {
            Kind.TICK -> VibrationEffect.createOneShot(LEGACY_TICK_MS, if (amp) LEGACY_TICK_AMP else VibrationEffect.DEFAULT_AMPLITUDE)
            Kind.CLICK -> VibrationEffect.createOneShot(LEGACY_CLICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            Kind.CONFIRM -> VibrationEffect.createWaveform(longArrayOf(0, 18, 70, 28), -1)
            Kind.REJECT -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
        }
    }

    /**
     * Composition primitives are what the Pixel's own UI plays. Returns null when the phone
     * doesn't support them, so the caller falls back to the predefined effects: a composition
     * of unsupported primitives plays nothing at all.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun composed(vibrator: Vibrator, kind: Kind): VibrationEffect? {
        val click = VibrationEffect.Composition.PRIMITIVE_CLICK
        val tick = VibrationEffect.Composition.PRIMITIVE_TICK
        if (!vibrator.areAllPrimitivesSupported(click, tick)) return null
        val composition = VibrationEffect.startComposition()
        when (kind) {
            Kind.TICK -> composition.addPrimitive(tick, TICK_SCALE)
            Kind.CLICK -> composition.addPrimitive(click, CLICK_SCALE)
            Kind.CONFIRM -> composition
                .addPrimitive(click, CONFIRM_FIRST_SCALE)
                .addPrimitive(click, 1f, CONFIRM_GAP_MS)
            Kind.REJECT -> composition
                .addPrimitive(click, 1f)
                .addPrimitive(click, 1f, REJECT_GAP_MS)
                .addPrimitive(tick, 1f, REJECT_GAP_MS)
        }
        return composition.compose()
    }

    private const val TAG = "Haptics"
    private const val PREFS = "haptics"
    private const val ENABLED = "enabled"

    /**
     * Primitive strengths. A full-strength click on every tap felt like a typewriter on a
     * Pixel 8; 0.7 keeps it clearly felt without that. Ticks run full scale because the tick
     * primitive is already much lighter than a click.
     */
    private const val TICK_SCALE = 1f
    private const val CLICK_SCALE = 0.7f
    private const val CONFIRM_FIRST_SCALE = 0.5f
    private const val CONFIRM_GAP_MS = 70
    private const val REJECT_GAP_MS = 90

    /** Pre-Android 10 fallback lengths: under ~10ms many older motors don't spin up at all. */
    private const val LEGACY_TICK_MS = 12L
    private const val LEGACY_TICK_AMP = 90
    private const val LEGACY_CLICK_MS = 22L
}
