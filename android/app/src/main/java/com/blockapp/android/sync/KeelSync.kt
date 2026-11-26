package com.blockapp.android.sync

import android.content.Context
import android.util.Log
import com.blockapp.android.BlockApplication
import com.blockapp.android.data.BlockRepository
import com.blockapp.android.usage.UsageStatsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * This phone's link to the Keel server. Every [REPORT_MS] it sends today's absolute usage
 * (absolute, so a retry rewrites rather than double counts) and gets fresh enforcement back;
 * a reel counted here is pushed within half a second so the laptop's browser blocks reel 31
 * as soon as the phone watched reel 30; and a WebSocket brings changes made elsewhere (a focus
 * started on the laptop, a limit lowered on the web) in about a second.
 *
 * Owned by [BlockApplication]; nothing else starts it. Every network failure degrades to the
 * last stored [Policy], never to a crash (invariant 9).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class KeelSync(private val app: BlockApplication, private val repository: BlockRepository, private val scope: CoroutineScope) {

    data class Session(val server: String, val token: String, val deviceId: Long, val email: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val refreshCh = Channel<Unit>(Channel.CONFLATED)
    private var socket: WebSocket? = null
    private var loops: Job? = null

    @Volatile var lastSync: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    fun session(): Session? {
        val p = prefs()
        val token = p.getString("token", null) ?: return null
        return Session(p.getString("server", "")!!, token, p.getLong("deviceId", 0L), p.getString("email", null))
    }

    fun sessionJson(): String {
        val s = session() ?: return ""
        return JSONObject().put("server", s.server).put("token", s.token).put("deviceId", s.deviceId).put("email", s.email).toString()
    }

    /** From the UI's sign in / sign out (KeelBridge). */
    fun setSession(raw: String) {
        if (raw.isBlank()) {
            prefs().edit().clear().apply()
            Policy.clear(app)
        } else {
            val j = JSONObject(raw)
            prefs().edit()
                .putString("server", j.getString("server").trimEnd('/'))
                .putString("token", j.getString("token"))
                .putLong("deviceId", j.optLong("deviceId"))
                .putString("email", j.optString("email").ifBlank { null })
                .apply()
        }
        restart()
    }

    fun start() {
        Policy.load(app)
        restart()
    }

    private fun restart() {
        loops?.cancel()
        socket?.cancel()
        socket = null
        if (session() == null) return
        loops = scope.launch {
            launch { reportLoop() }
            launch { reelLoop() }
            launch { refreshLoop() }
            launch { socketLoop() }
        }
    }

    /** Ask for an immediate report, e.g. when the UI opens. */
    fun nudge() {
        kick.trySend(Unit)
    }

    // ---- HTTP -----------------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        val s = session() ?: return@withContext null
        val req = Request.Builder()
            .url(s.server + "/api" + path)
            .header("Authorization", "Bearer " + s.token)
            .method(method, body?.toString()?.toRequestBody(json))
            .build()
        try {
            http.newCall(req).execute().use { res: Response ->
                val text = res.body?.string().orEmpty()
                if (res.code == 401) {
                    // Signed out remotely (device removed): stop enforcing as that account.
                    prefs().edit().clear().apply()
                    Policy.clear(app)
                    lastError = "Signed out"
                    return@withContext null
                }
                if (!res.isSuccessful) {
                    lastError = runCatching { JSONObject(text).optString("error") }.getOrNull() ?: "HTTP ${res.code}"
                    return@withContext null
                }
                lastError = null
                lastSync = System.currentTimeMillis()
                runCatching { JSONObject(text) }.getOrNull()
            }
        } catch (e: java.io.IOException) {
            lastError = "Offline"
            null
        }
    }

    private suspend fun applyEnforce(method: String, path: String, body: JSONObject? = null) {
        val sent = System.currentTimeMillis()
        call(method, path, body)?.let { Policy.update(app, it, sent) }
    }

    // ---- Loops ----------------------------------------------------------------------------

    private suspend fun reportLoop() {
        var n = 0
        while (true) {
            guarded("report") {
                applyEnforce("POST", "/usage", usagePayload())
                if (n % STATUS_EVERY == 0) call("POST", "/device/status", statusPayload())
                n++
            }
            withTimeoutOrNull(REPORT_MS) { kick.receive() }
        }
    }

    private suspend fun reelLoop() {
        todayTicker()
            .flatMapLatest { day -> repository.observeReelTotal(day) }
            .distinctUntilChanged()
            .debounce(REEL_PUSH_DEBOUNCE_MS)
            .collect { total ->
                guarded("reels") {
                    applyEnforce("POST", "/reels", JSONObject().put("date", LocalDate.now().toString()).put("count", total))
                }
            }
    }

    private suspend fun refreshLoop() {
        for (x in refreshCh) {
            delay(150)
            guarded("refresh") { applyEnforce("GET", "/enforce") }
        }
    }

    private suspend fun socketLoop() {
        var backoff = 1_000L
        while (true) {
            val s = session() ?: return
            val closed = Channel<Unit>(Channel.CONFLATED)
            val url = s.server.replaceFirst("http", "ws") + "/api/ws?token=" + java.net.URLEncoder.encode(s.token, "UTF-8")
            socket = http.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        backoff = 1_000L
                        refreshCh.trySend(Unit)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if ("changed" in text) refreshCh.trySend(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.trySend(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.trySend(Unit)
                    }
                },
            )
            closed.receive()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    fun violation(focusId: Long, target: String) {
        scope.launch {
            guarded("violation") {
                call("POST", "/focus/$focusId/violation", JSONObject().put("target", target))
                applyEnforce("GET", "/enforce")
            }
        }
    }

    // ---- Payloads -------------------------------------------------------------------------

    private suspend fun usagePayload(): JSONObject = withContext(Dispatchers.Default) {
        val today = LocalDate.now()
        val usage = if (UsageStatsProvider.hasUsageAccess(app)) {
            UsageStatsProvider.history(app, 1).lastOrNull()
        } else {
            null
        }
        val reelRows = repository.observeReelCounts(today).first()
        val byTarget = linkedMapOf<String, JSONObject>()
        val local = HashMap<String, Long>()
        usage?.apps?.forEach { a ->
            if (a.packageName == app.packageName) return@forEach
            val target = Catalog.targetFor(app, a.packageName)
            val row = byTarget.getOrPut(target) {
                JSONObject().put("target", target).put("name", Catalog.name(app, target) ?: a.label)
                    .put("seconds", 0).put("opens", 0).put("reels", 0)
            }
            row.put("seconds", row.getLong("seconds") + a.foregroundMillis / 1000)
            row.put("opens", row.getInt("opens") + a.opens)
            local[target] = (local[target] ?: 0L) + a.foregroundMillis / 1000
        }
        var reels = 0
        reelRows.forEach { r ->
            reels += r.reels
            val target = Catalog.targetFor(app, r.packageName)
            val row = byTarget.getOrPut(target) {
                JSONObject().put("target", target).put("name", Catalog.name(app, target) ?: target)
                    .put("seconds", 0).put("opens", 0).put("reels", 0)
            }
            row.put("reels", row.getInt("reels") + r.reels)
        }
        Policy.localUsage = local
        Policy.localAt = System.currentTimeMillis()
        val hours = JSONArray()
        val hourly = usage?.hourly ?: LongArray(24)
        for (h in 0 until 24) hours.put(hourly.getOrElse(h) { 0L } / 1000)
        JSONObject()
            .put("date", today.toString())
            .put("apps", JSONArray(byTarget.values.toList()))
            .put("hours", hours)
            .put("pickups", usage?.unlocks ?: 0)
            .put("reels", reels)
    }

    private fun statusPayload(): JSONObject = JSONObject(KeelBridge.statusJson(app))

    // ---- Helpers --------------------------------------------------------------------------

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One failed report is worth a log line, not the process (invariant 9).
            Log.w(TAG, "$what failed", e)
        }
    }

    private fun todayTicker() = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000L)
        }
    }.distinctUntilChanged()

    private fun prefs() = app.getSharedPreferences("keel_session", Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "KeelSync"
        /** Each report also returns fresh enforcement; the socket makes the usual case ~1s. */
        const val REPORT_MS = 30_000L
        /** Device status (permissions) rides along every this many reports: ~5 minutes. */
        const val STATUS_EVERY = 10
        /** Coalesces a fast swipe streak into one request without delaying the other devices. */
        const val REEL_PUSH_DEBOUNCE_MS = 400L
    }
}
