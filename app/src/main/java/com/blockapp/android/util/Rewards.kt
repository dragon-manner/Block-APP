package com.blockapp.android.util

import android.content.Context

/**
 * What following the plan earns: accents, reel-pill glyphs, a line of the user's own on every
 * wall, a level with a name, and one secret. Earned rewards stay earned. A broken streak or a
 * rebuilt plan resets the plan's numbers (PlanProgress), and taking back a colour the user is
 * wearing would turn a reward into a punishment. Also keeps the choices those rewards unlock
 * (accent, glyph, wall line, nickname), so everything that makes the app "theirs" is in one
 * SharedPreferences file, which Auto Backup carries over a reinstall like the plan.
 *
 * Nothing here loosens anything. Every reward is cosmetic or adds friction (the wall line), so
 * there is no reward that can be farmed into an early exit.
 *
 * [sync] is what earns them. It is pure apart from the prefs, and is fed from HomeScreen and
 * RewardsScreen whenever the plan's progress or the lock count changes.
 */
object Rewards {

    const val ACCENT_MINT = "accent_mint"
    const val ACCENT_SKY = "accent_sky"
    const val ACCENT_LILAC = "accent_lilac"
    const val ACCENT_ROSE = "accent_rose"
    const val ACCENT_GOLD = "accent_gold"
    const val GLYPH_DOT = "glyph_dot"
    const val GLYPH_MOON = "glyph_moon"
    const val GLYPH_SPARK = "glyph_spark"
    const val GLYPH_CROWN = "glyph_crown"
    const val WALL_LINE = "wall_line"
    const val SECRET_MONO = "secret_mono"

    enum class Kind(val label: String) { ACCENT("Accent"), GLYPH("Pill glyph"), FEATURE("Feature"), SECRET("Secret") }

    /** What the rules are judged on. Every field only grows within one plan. */
    data class Stats(
        val appsLocked: Int = 0,
        val cleanDays: Int = 0,
        val bestStreak: Int = 0,
        val reelsSkipped: Int = 0,
        val reelFreeDays: Int = 0,
    ) {
        companion object {
            fun from(summary: PlanProgress.Summary?, appsLocked: Int) = Stats(
                appsLocked = appsLocked,
                cleanDays = summary?.cleanDays ?: 0,
                bestStreak = summary?.bestStreak ?: 0,
                reelsSkipped = summary?.reelsSkipped ?: 0,
                reelFreeDays = summary?.days?.count { it.outcome == PlanProgress.Outcome.REEL_FREE } ?: 0,
            )
        }
    }

    data class Reward(
        val id: String,
        val title: String,
        val kind: Kind,
        /** How to earn it, shown on the locked card. A secret's is only a hint. */
        val requirement: String,
        /** 0..1 towards it; secrets report 0 until found. */
        val progress: (Stats) -> Float,
    )

    private fun ratio(have: Int, need: Int) = (have.toFloat() / need).coerceIn(0f, 1f)

    /** In the order they tend to be earned, which is also the order RewardsScreen shows them. */
    val ALL: List<Reward> = listOf(
        Reward(ACCENT_MINT, "Mint", Kind.ACCENT, "Lock your first app") { ratio(it.appsLocked, 1) },
        Reward(ACCENT_SKY, "Sky", Kind.ACCENT, "One clean day on your plan") { ratio(it.cleanDays, 1) },
        Reward(WALL_LINE, "Your line on every wall", Kind.FEATURE, "Two clean days") { ratio(it.cleanDays, 2) },
        Reward(ACCENT_LILAC, "Lilac", Kind.ACCENT, "A 3-day clean streak") { ratio(it.bestStreak, 3) },
        Reward(GLYPH_DOT, "Dot pill", Kind.GLYPH, "Skip 100 reels") { ratio(it.reelsSkipped, 100) },
        Reward(GLYPH_MOON, "Moon pill", Kind.GLYPH, "A whole day with no reels") { ratio(it.reelFreeDays, 1) },
        Reward(ACCENT_ROSE, "Rose", Kind.ACCENT, "A 7-day clean streak") { ratio(it.bestStreak, 7) },
        Reward(GLYPH_SPARK, "Spark pill", Kind.GLYPH, "Skip 500 reels") { ratio(it.reelsSkipped, 500) },
        Reward(ACCENT_GOLD, "Gold", Kind.ACCENT, "14 clean days") { ratio(it.cleanDays, 14) },
        Reward(GLYPH_CROWN, "Crown pill", Kind.GLYPH, "30 clean days") { ratio(it.cleanDays, 30) },
        Reward(SECRET_MONO, "Mono", Kind.SECRET, "Hidden somewhere on this screen. Be persistent.") { 0f },
    )

    fun byId(id: String): Reward? = ALL.firstOrNull { it.id == id }

    fun earnedIds(context: Context): Set<String> = prefs(context).getStringSet(EARNED, emptySet()).orEmpty()

    fun isEarned(context: Context, id: String): Boolean = id in earnedIds(context)

    /**
     * Earns every reward [stats] now satisfies and returns the ones that are new. New ones are
     * also queued as unseen, for Home's "new unlock" card. Also raises the stored best XP.
     */
    fun sync(context: Context, stats: Stats): List<Reward> {
        val earned = earnedIds(context)
        val fresh = ALL.filter { it.kind != Kind.SECRET && it.id !in earned && it.progress(stats) >= 1f }
        val prefs = prefs(context)
        val all = earned + fresh.map { it.id }
        val xp = maxOf(prefs.getInt(XP_BEST, 0), xpFor(stats, all.size))
        prefs.edit()
            .putStringSet(EARNED, all)
            .putStringSet(UNSEEN, unseenIds(context) + fresh.map { it.id })
            .putInt(XP_BEST, xp)
            .apply()
        return fresh
    }

    /** The easter egg: earns [SECRET_MONO]. Returns false if it was already found. */
    fun findSecret(context: Context): Boolean {
        val earned = earnedIds(context)
        if (SECRET_MONO in earned) return false
        prefs(context).edit()
            .putStringSet(EARNED, earned + SECRET_MONO)
            .putInt(XP_BEST, prefs(context).getInt(XP_BEST, 0) + SECRET_XP)
            .apply()
        return true
    }

    private fun unseenIds(context: Context): Set<String> =
        prefs(context).getStringSet(UNSEEN, emptySet()).orEmpty()

    fun unseen(context: Context): List<Reward> = unseenIds(context).mapNotNull(::byId)

    fun markSeen(context: Context) {
        prefs(context).edit().remove(UNSEEN).apply()
    }

    // ── levels ──────────────────────────────────────────────────────────────────────────────

    data class Level(val index: Int, val title: String, val xp: Int, val floor: Int, val next: Int?) {
        /** 0..1 through this level; 1 at the top level. */
        val fraction: Float get() = next?.let { ((xp - floor).toFloat() / (it - floor)).coerceIn(0f, 1f) } ?: 1f
    }

    private val LEVELS = listOf(
        0 to "Scroller",
        60 to "Noticer",
        160 to "Thumb Tamer",
        360 to "Feed Breaker",
        700 to "Focus Keeper",
        1200 to "Unhooked",
    )

    /**
     * Points for what the user did: clean days count most, then streaks, then reels skipped.
     * Each earned reward adds a little, so the bar moves on the day something unlocks.
     */
    private fun xpFor(stats: Stats, earnedCount: Int): Int =
        stats.cleanDays * XP_PER_CLEAN_DAY +
            stats.bestStreak * XP_PER_STREAK_DAY +
            stats.reelsSkipped / REELS_PER_XP +
            stats.appsLocked * XP_PER_APP_LOCKED +
            earnedCount * XP_PER_REWARD

    /** The level for the best XP ever reached, so rebuilding the plan never demotes anyone. */
    fun level(context: Context): Level {
        val xp = prefs(context).getInt(XP_BEST, 0)
        val index = LEVELS.indexOfLast { xp >= it.first }.coerceAtLeast(0)
        return Level(
            index = index,
            title = LEVELS[index].second,
            xp = xp,
            floor = LEVELS[index].first,
            next = LEVELS.getOrNull(index + 1)?.first,
        )
    }

    // ── the choices rewards unlock ──────────────────────────────────────────────────────────

    fun accentId(context: Context): String? = prefs(context).getString(ACCENT, null)

    fun setAccentId(context: Context, id: String) {
        prefs(context).edit().putString(ACCENT, id).apply()
    }

    /** The glyph at the front of the reel pill. Read by ReelBubble on every render. */
    enum class PillGlyph(val id: String, val label: String, val text: String, val rewardId: String?) {
        // Each with the text-presentation selector: bare, several render as colour emoji.
        PLAY("play", "Play", "▶︎", null),
        DOT("dot", "Dot", "●︎", GLYPH_DOT),
        MOON("moon", "Moon", "☾︎", GLYPH_MOON),
        SPARK("spark", "Spark", "✦︎", GLYPH_SPARK),
        CROWN("crown", "Crown", "♛︎", GLYPH_CROWN),
    }

    fun glyph(context: Context): PillGlyph {
        val id = prefs(context).getString(GLYPH, null)
        val glyph = PillGlyph.entries.firstOrNull { it.id == id } ?: PillGlyph.PLAY
        // A backup restored onto a fresh install keeps both files, but don't trust a pick whose
        // reward is missing.
        return if (glyph.rewardId == null || isEarned(context, glyph.rewardId)) glyph else PillGlyph.PLAY
    }

    fun setGlyph(context: Context, glyph: PillGlyph): Boolean {
        if (glyph.rewardId != null && !isEarned(context, glyph.rewardId)) return false
        prefs(context).edit().putString(GLYPH, glyph.id).apply()
        return true
    }

    /** The user's own line for the block walls, or null. Only once [WALL_LINE] is earned. */
    fun wallLine(context: Context): String? {
        if (!isEarned(context, WALL_LINE)) return null
        return prefs(context).getString(WALL, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setWallLine(context: Context, line: String?) {
        prefs(context).edit().putString(WALL, line?.take(MAX_WALL_LINE)?.trim()).apply()
    }

    /** What Home's greeting calls the user. Free, not a reward: it's their app. */
    fun nickname(context: Context): String? =
        prefs(context).getString(NICKNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setNickname(context: Context, name: String?) {
        prefs(context).edit().putString(NICKNAME, name?.take(MAX_NICKNAME)?.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val MAX_WALL_LINE = 90
    const val MAX_NICKNAME = 20

    /** Taps on RewardsScreen's level badge that find the secret. */
    const val SECRET_TAPS = 7

    private const val XP_PER_CLEAN_DAY = 20
    private const val XP_PER_STREAK_DAY = 10
    private const val REELS_PER_XP = 5
    private const val XP_PER_APP_LOCKED = 10
    private const val XP_PER_REWARD = 15
    private const val SECRET_XP = 50

    private const val PREFS = "rewards"
    private const val EARNED = "earned"
    private const val UNSEEN = "unseen"
    private const val XP_BEST = "xp_best"
    private const val ACCENT = "accent"
    private const val GLYPH = "glyph"
    private const val WALL = "wall_line"
    private const val NICKNAME = "nickname"
}
