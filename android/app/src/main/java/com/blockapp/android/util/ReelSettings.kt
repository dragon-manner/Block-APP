package com.blockapp.android.util

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * The user's settings for the reel engine: which of ReelApps.KNOWN are counted, how the reel
 * pill (ReelBubble) looks and where it sits, and when the "N reels left" warning comes.
 * SharedPreferences like ReelLimit, so they're in the Auto Backup and need no schema bump.
 *
 * **Turning tracking off for an app waits until tomorrow; turning it on is immediate.** An app
 * that isn't tracked can't reach the reel limit or reel-free hours (Tier 3), so an immediate
 * "off" would be the same one-tap way out that ReelLimit's tomorrow rule and invariant 4 exist
 * to close. The pill and the warning are only presentation: those apply at once.
 *
 * AppBlockAccessibilityService reads [trackedPackages] on connect, at midnight, and whenever
 * this file changes ([PREFS] via a listener), and ReelBubble reads the pill settings on show.
 */
object ReelSettings {

    enum class Change { APPLIED_NOW, FROM_TOMORROW, UNCHANGED }

    /** ReelApps.KNOWN minus the apps switched off, with any switch-off whose day has come applied. */
    fun trackedPackages(context: Context): Set<String> = ReelApps.KNOWN - untracked(context)

    fun isTracked(context: Context, packageName: String): Boolean = packageName !in untracked(context)

    /** Apps switched off from tomorrow, still counted today. */
    fun pendingUntrack(context: Context): Set<String> {
        untracked(context) // applies them first if their day has come
        return prefs(context).getStringSet(PENDING, emptySet()).orEmpty()
    }

    fun setTracked(context: Context, packageName: String, tracked: Boolean): Change {
        val off = untracked(context)
        val pending = pendingUntrack(context)
        val prefs = prefs(context)
        return when {
            tracked && (packageName in off || packageName in pending) -> {
                prefs.edit()
                    .putStringSet(UNTRACKED, off - packageName)
                    .putStringSet(PENDING, pending - packageName)
                    .apply()
                Change.APPLIED_NOW
            }
            !tracked && packageName !in off && packageName !in pending -> {
                prefs.edit()
                    .putStringSet(PENDING, pending + packageName)
                    .putString(PENDING_FROM, LocalDate.now().plusDays(1).toString())
                    .apply()
                Change.FROM_TOMORROW
            }
            else -> Change.UNCHANGED
        }
    }

    private fun untracked(context: Context): Set<String> {
        val prefs = prefs(context)
        val off = prefs.getStringSet(UNTRACKED, emptySet()).orEmpty()
        val from = prefs.getString(PENDING_FROM, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (from != null && !LocalDate.now().isBefore(from)) {
            val merged = off + prefs.getStringSet(PENDING, emptySet()).orEmpty()
            prefs.edit().putStringSet(UNTRACKED, merged).remove(PENDING).remove(PENDING_FROM).apply()
            return merged
        }
        return off
    }

    // ── the pill ────────────────────────────────────────────────────────────────────────────

    /** Which side of the front camera the pill sits on. */
    enum class Side(val label: String) { RIGHT("Right of camera"), LEFT("Left of camera") }

    /** What the pill's figure says. */
    enum class Format(val label: String, val example: String) {
        COUNT("Today's count", "12/40"),
        LEFT("Reels left", "28 left"),
        BARE("Just the number", "12"),
    }

    fun pillVisible(context: Context): Boolean = prefs(context).getBoolean(PILL_VISIBLE, true)
    fun setPillVisible(context: Context, visible: Boolean) = prefs(context).edit().putBoolean(PILL_VISIBLE, visible).apply()

    fun pillSide(context: Context): Side =
        Side.entries.firstOrNull { it.name == prefs(context).getString(PILL_SIDE, null) } ?: Side.RIGHT
    fun setPillSide(context: Context, side: Side) = prefs(context).edit().putString(PILL_SIDE, side.name).apply()

    fun pillFormat(context: Context): Format =
        Format.entries.firstOrNull { it.name == prefs(context).getString(PILL_FORMAT, null) } ?: Format.COUNT
    fun setPillFormat(context: Context, format: Format) = prefs(context).edit().putString(PILL_FORMAT, format.name).apply()

    // ── the warning ─────────────────────────────────────────────────────────────────────────

    /** Offered choices for [warnAt]: reels before the limit. 0 is off. */
    val WARN_CHOICES = listOf(0, 3, 5, 10, 20)

    /** How many reels before the limit the "N reels left" warning comes, 0 for never. */
    fun warnAt(context: Context): Int = prefs(context).getInt(WARN_AT, DEFAULT_WARN_AT)
    fun setWarnAt(context: Context, reels: Int) = prefs(context).edit().putInt(WARN_AT, reels.coerceAtLeast(0)).apply()

    /** Whether the warning also vibrates (through util/Haptics, so the app-wide switch still wins). */
    fun warnBuzz(context: Context): Boolean = prefs(context).getBoolean(WARN_BUZZ, true)
    fun setWarnBuzz(context: Context, on: Boolean) = prefs(context).edit().putBoolean(WARN_BUZZ, on).apply()

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The warning's distance before this file existed, kept as the default. */
    private const val DEFAULT_WARN_AT = 10

    private const val PREFS = "reel_settings"
    private const val UNTRACKED = "untracked"
    private const val PENDING = "pending_untrack"
    private const val PENDING_FROM = "pending_from"
    private const val PILL_VISIBLE = "pill_visible"
    private const val PILL_SIDE = "pill_side"
    private const val PILL_FORMAT = "pill_format"
    private const val WARN_AT = "warn_at"
    private const val WARN_BUZZ = "warn_buzz"
}
