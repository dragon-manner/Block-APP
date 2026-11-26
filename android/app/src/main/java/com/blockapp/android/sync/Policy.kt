package com.blockapp.android.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * The synced rules this phone enforces, as the server last sent them (server/enforce.go), and
 * the one function that turns them into "may this app be in front". The desktop app carries
 * the same logic in Rust (desktop/src-tauri/src/model.rs); change one and change the other.
 *
 * The last copy is kept in SharedPreferences and loaded on process start, so a reboot or an
 * OEM kill mid-focus doesn't open everything up until the network comes back. Server time is
 * tracked through [skewMs] so a focus round ends on the same second here as on the laptop.
 */
object Policy {

    data class Phase(val work: Boolean, val start: Long, val end: Long)
    data class Focus(val id: Long, val label: String, val ends: Long, val strict: Boolean, val categories: Set<String>, val phases: List<Phase>)
    data class Limit(val target: String, val minutes: Int, val othersSec: Long)
    data class Quiet(val enabled: Boolean, val from: String, val to: String, val blockReels: Boolean, val categories: Set<String>)

    data class Enforce(
        val date: String,
        val reelLimit: Int?,
        val reelsOthers: Int,
        val limits: List<Limit>,
        val focus: Focus?,
        val lockdownTasks: List<String>,
        val lockdownCategories: Set<String>,
        val quiet: Quiet,
        val passUntil: Long,
        val overrides: Map<String, String>,
    ) {
        val lockdown: Boolean get() = lockdownTasks.isNotEmpty()
    }

    data class Verdict(val reason: String, val message: String, val until: Long)

    private val _state = MutableStateFlow<Enforce?>(null)
    val state: StateFlow<Enforce?> = _state

    @Volatile var skewMs: Long = 0L
        private set

    /** This phone's own seconds per target today, as of [localAt]. Refreshed by KeelSync. */
    @Volatile var localUsage: Map<String, Long> = emptyMap()
    @Volatile var localAt: Long = 0L

    fun now(): Long = System.currentTimeMillis() + skewMs

    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        runCatching { _state.value = parse(JSONObject(raw)) }.onFailure { Log.w(TAG, "stored policy unreadable", it) }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        _state.value = null
    }

    fun update(context: Context, json: JSONObject, sentAt: Long) {
        val e = try {
            parse(json)
        } catch (e: Exception) {
            Log.w(TAG, "bad enforce payload", e)
            return
        }
        val serverNow = json.optLong("now", 0L)
        if (serverNow > 0) skewMs = serverNow - (sentAt + (System.currentTimeMillis() - sentAt) / 2)
        prefs(context).edit().putString(KEY, json.toString()).apply()
        _state.value = e
    }

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()

    private fun parse(j: JSONObject): Enforce {
        val reels = j.optJSONObject("reels") ?: JSONObject()
        val limits = j.optJSONArray("limits") ?: JSONArray()
        val focus = j.optJSONObject("focus")
        val lockdown = j.optJSONObject("lockdown") ?: JSONObject()
        val quiet = j.optJSONObject("quiet") ?: JSONObject()
        val overrides = j.optJSONObject("categoryOverrides")
        return Enforce(
            date = j.optString("date"),
            reelLimit = if (reels.isNull("limit") || !reels.has("limit")) null else reels.getInt("limit"),
            reelsOthers = reels.optInt("others", 0),
            limits = (0 until limits.length()).map { i ->
                val l = limits.getJSONObject(i)
                Limit(l.getString("target"), l.optInt("minutes"), l.optLong("others"))
            },
            focus = focus?.let { f ->
                val ph = f.optJSONArray("phases") ?: JSONArray()
                Focus(
                    id = f.getLong("id"),
                    label = f.optString("label"),
                    ends = f.optLong("ends"),
                    strict = f.optBoolean("strict", true),
                    categories = strings(f.optJSONArray("categories")),
                    phases = (0 until ph.length()).map { i ->
                        val p = ph.getJSONObject(i)
                        Phase(p.optString("kind") == "work", p.getLong("start"), p.getLong("end"))
                    },
                )
            },
            lockdownTasks = if (lockdown.optBoolean("active")) strings(lockdown.optJSONArray("tasks")).toList() else emptyList(),
            lockdownCategories = strings(lockdown.optJSONArray("categories")),
            quiet = Quiet(
                enabled = quiet.optBoolean("enabled"),
                from = quiet.optString("from"),
                to = quiet.optString("to"),
                blockReels = quiet.optBoolean("blockReels"),
                categories = strings(quiet.optJSONArray("categories")),
            ),
            passUntil = j.optLong("passUntil"),
            overrides = overrides?.keys()?.asSequence()?.associateWith { overrides.getString(it) } ?: emptyMap(),
        )
    }

    fun workPhase(e: Enforce, now: Long = now()): Phase? =
        e.focus?.phases?.firstOrNull { it.work && now >= it.start && now < it.end }

    private fun hhmm(s: String): Int? {
        val p = s.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun quietActive(q: Quiet): Boolean {
        if (!q.enabled) return false
        val f = hhmm(q.from) ?: return false
        val t = hhmm(q.to) ?: return false
        if (f == t) return false
        val n = LocalTime.now().let { it.hour * 60 + it.minute }
        return if (f < t) n in f until t else n >= f || n < t
    }

    /** True while something time-bound is locking things down: no way out but waiting it out. */
    fun hardLocked(): Boolean {
        val e = _state.value ?: return false
        return workPhase(e) != null && e.focus?.strict == true || e.lockdown
    }

    /** Anything at all for the watchdog to enforce. */
    fun armed(): Boolean {
        val e = _state.value ?: return false
        return e.focus != null || e.lockdown || e.quiet.enabled || e.limits.isNotEmpty()
    }

    /**
     * Whether [target] (in [category]) may be in front, given [localSec] seconds of it on this
     * phone today. Null means open.
     */
    fun verdict(target: String, name: String, category: String, localSec: Long): Verdict? {
        val e = _state.value ?: return null
        val now = now()
        val phase = workPhase(e, now)
        val f = e.focus
        if (f != null && phase != null && f.strict && category in f.categories) {
            val on = if (f.label.isBlank()) "" else " on “${f.label}”"
            return Verdict("focus", "$name is blocked while you focus$on.", phase.end)
        }
        if (e.lockdown && category in e.lockdownCategories) {
            return Verdict("lockdown", "“${e.lockdownTasks.first()}” is overdue. Finish it, or work it off with 25 focused minutes.", 0L)
        }
        if (quietActive(e.quiet) && category in e.quiet.categories) {
            return Verdict("quiet", "Quiet hours until ${e.quiet.to}.", 0L)
        }
        val l = e.limits.firstOrNull { it.target == target }
        if (l != null && e.passUntil <= now && l.othersSec + localSec >= l.minutes * 60L) {
            return Verdict("limit", "You've used today's ${l.minutes} min of $name across your devices.", 0L)
        }
        return null
    }

    /** Whether one more reel may play, given this phone's own count today. */
    fun reelVerdict(localReels: Int): Verdict? {
        val e = _state.value ?: return null
        if (workPhase(e) != null) return Verdict("focus", "Reels are closed during a focus round.", workPhase(e)?.end ?: 0L)
        if (e.lockdown) return Verdict("lockdown", "Reels are locked until your overdue task is done.", 0L)
        if (e.quiet.blockReels && quietActive(e.quiet)) return Verdict("quiet", "Reel-free hours until ${e.quiet.to}.", 0L)
        val limit = e.reelLimit ?: return null
        if (e.reelsOthers + localReels >= limit) {
            return Verdict("reels", "That's today's $limit reels, counted across your phone and browsers.", 0L)
        }
        return null
    }

    /** Today's effective reel limit and other devices' count, for the bubble. */
    fun reelLimit(): Int? = _state.value?.reelLimit
    fun reelsElsewhere(): Int = _state.value?.reelsOthers ?: 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("keel_policy", Context.MODE_PRIVATE)

    private const val KEY = "enforce"
    private const val TAG = "KeelPolicy"
}
