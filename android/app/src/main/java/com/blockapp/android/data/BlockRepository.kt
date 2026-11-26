package com.blockapp.android.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.blockapp.android.usage.DayUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Single read/write entry point for what this phone records itself: reel counts and the
 * screen-time archive. Limits, focus and the rest of enforcement come from the server through
 * sync/Policy. The lock and nonce tables from 1.x are still in the schema (dropping them is a
 * migration for no gain, invariant 7) but nothing reads or writes them any more.
 */
class BlockRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val dao: BlockDao = database.blockDao()

    /** Per-app reel counts for [day], most-watched first. */
    fun observeReelCounts(day: LocalDate): Flow<List<ReelCountEntity>> =
        dao.observeReelCounts(day.toString())

    /** Today's reels across every app, for the floating ReelBubble. */
    fun observeReelTotal(day: LocalDate): Flow<Int> =
        observeReelCounts(day).map { counts -> counts.sumOf { it.reels } }

    /** Reel totals per day from [fromDay] on, oldest first. Days with no reels have no row. */
    fun observeReelHistory(fromDay: LocalDate): Flow<List<ReelDayTotal>> =
        dao.observeReelHistory(fromDay.toString())

    /**
     * Adds [count] reels and [watchMs] of reel-viewer time to today's tally for [packageName].
     * Either may be zero: a session ending adds its last stretch of time with no new reel.
     * Called from the accessibility service's main thread, so the write is launched rather than
     * awaited.
     *
     * A failed write is dropped rather than rethrown. [scope] has no exception handler, so an
     * uncaught SQLiteException here (disk full, DB locked) would take the whole process down —
     * and with it the accessibility service and every running lock — over a statistic.
     * Losing a few counted reels is the right trade (CLAUDE.md invariant 9).
     */
    fun addReelsWatched(packageName: String, count: Int, watchMs: Long) {
        if (count <= 0 && watchMs <= 0L) return
        val day = LocalDate.now().toString()
        scope.launch {
            try {
                dao.insertReelCountIfAbsent(ReelCountEntity(day, packageName, reels = 0))
                dao.incrementReelCount(day, packageName, count, watchMs)
            } catch (e: SQLiteException) {
                // Dropped on purpose; see KDoc.
            }
        }
    }

    // ── history ─────────────────────────────────────────────────────────────────────────────

    /** Per-day reel totals in [from]..[to] inclusive. Days with no reels have no row. */
    fun observeReelDays(from: LocalDate, to: LocalDate): Flow<List<ReelDayTotal>> =
        dao.observeReelDays(from.toString(), to.toString())

    /** Per-app reel totals over [from]..[to], most first. */
    fun observeReelApps(from: LocalDate, to: LocalDate): Flow<List<ReelAppTotal>> =
        dao.observeReelApps(from.toString(), to.toString())

    fun observeUsageDays(from: LocalDate, to: LocalDate): Flow<List<UsageDayEntity>> =
        dao.observeUsageDays(from.toString(), to.toString())

    fun observeUsageApps(day: LocalDate): Flow<List<UsageAppEntity>> = dao.observeUsageApps(day.toString())

    fun observeUsageAppTotals(from: LocalDate, to: LocalDate): Flow<List<UsageAppTotal>> =
        dao.observeUsageAppTotals(from.toString(), to.toString())

    /** The first day with any recorded screen time or reels, or null on a fresh install. */
    suspend fun earliestRecordedDay(): LocalDate? =
        dao.earliestRecordedDay()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /**
     * Copies [days] of screen time into the archive. Two rules keep a replay from making the
     * archive worse:
     * - an empty day is never written, because a day older than the system's event window
     *   replays as empty, and writing that would erase a day the archive still had;
     * - a day is only replaced by one at least as large. The oldest day of a replay can come out
     *   short (its lookback misses a session that started earlier), and the fuller copy wins.
     *
     * A failed write is dropped, like [addReelsWatched]: this runs from a receiver and from
     * screens, and a statistic must not take the process down (CLAUDE.md invariant 9).
     */
    suspend fun archiveUsage(days: List<DayUsage>) {
        try {
            for (usage in days) {
                if (usage.totalMillis <= 0L && usage.unlocks == 0) continue
                val key = usage.day.toString()
                val existing = dao.usageDay(key)
                if (existing != null && existing.totalMs > usage.totalMillis) continue
                database.withTransaction {
                    dao.upsertUsageDay(
                        UsageDayEntity(
                            day = key,
                            totalMs = usage.totalMillis,
                            opens = usage.totalOpens,
                            unlocks = usage.unlocks,
                            hourly = usage.hourly.joinToString(","),
                        ),
                    )
                    dao.clearUsageApps(key)
                    dao.insertUsageApps(
                        usage.apps.map {
                            UsageAppEntity(key, it.packageName, it.label, it.foregroundMillis, it.opens)
                        },
                    )
                }
            }
        } catch (e: SQLiteException) {
            // Dropped on purpose; see KDoc.
        }
    }
}
