package com.blockapp.android

import android.app.Application
import android.util.Log
import com.blockapp.android.alarm.AlarmScheduler
import com.blockapp.android.data.AppDatabase
import com.blockapp.android.data.BlockRepository
import com.blockapp.android.service.BlockGuardService
import com.blockapp.android.sync.KeelSync
import com.blockapp.android.sync.Policy
import com.blockapp.android.usage.UsageArchive
import com.blockapp.android.util.CrashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class BlockApplication : Application() {

    /**
     * The handler is the backstop for invariant 9. Without one, any exception that escaped a
     * coroutine here (a Room write on a full disk, a system service throwing on an OEM build)
     * was an uncaught crash, and on this app a crash also takes down the accessibility service,
     * so enforcement stops until the system rebinds it. The call sites still catch what they
     * expect; this only catches what they didn't, and records it so it can be found later.
     */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            CrashLog.recordNonFatal(this, "applicationScope", error)
        },
    )

    /** Set when Android refused to start BlockGuardService; see [onAppForeground]. */
    @Volatile private var guardStartRefused = false

    lateinit var repository: BlockRepository
        private set

    lateinit var sync: KeelSync
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        repository = BlockRepository(
            context = applicationContext,
            database = AppDatabase.getInstance(this),
            scope = applicationScope,
        )
        // Loads the last stored enforcement before anything else runs, so a process restarted
        // mid-focus (an OEM kill, a reboot) enforces it from the first event, not from the
        // first successful network call.
        sync = KeelSync(this, repository, applicationScope)
        sync.start()

        applicationScope.launch { AlarmScheduler.scheduleDailyArchive(this@BlockApplication) }

        // The history archive replays a week of usage events, so it waits until the launch has
        // settled rather than competing with it for the CPU on a cold start.
        applicationScope.launch {
            delay(DEFERRED_STARTUP_WORK_MS)
            try {
                UsageArchive.archiveRecent(this@BlockApplication, repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "deferred startup work failed", e)
            }
        }

        // Keep the process (and the bound accessibility service) at foreground priority while
        // there's anything to enforce — see BlockGuardService's doc for why.
        //
        // Debounced (invariant 6): the policy can flip several times in a burst (the stored copy
        // loading, then the first network answer), and starting and stopping the foreground
        // service faster than Android's start-up handshake crashes the process with
        // ForegroundServiceDidNotStartInTimeException.
        Policy.state
            .map { Policy.armed() }
            .distinctUntilChanged()
            .debounce(SERVICE_TOGGLE_DEBOUNCE_MS)
            .onEach { armed ->
                if (armed) {
                    guardStartRefused = !BlockGuardService.start(this)
                } else {
                    guardStartRefused = false
                    BlockGuardService.stop(this)
                }
            }
            .launchIn(applicationScope)
    }

    /**
     * Called by MainActivity and BlockOverlayActivity when they resume. A start refused while
     * the app was in the background (see BlockGuardService.start) is retried here, the first
     * moment Android allows it. Still only this class starts the service, from the same policy
     * state the observer above uses, so the two can't disagree.
     */
    fun onAppForeground() {
        sync.nudge()
        if (!guardStartRefused) return
        if (!Policy.armed()) {
            guardStartRefused = false
            return
        }
        guardStartRefused = !BlockGuardService.start(this)
    }

    private companion object {
        const val TAG = "BlockApplication"
        const val SERVICE_TOGGLE_DEBOUNCE_MS = 300L

        /** Long enough to be past a cold start's first frames, short enough to beat a quick exit. */
        const val DEFERRED_STARTUP_WORK_MS = 4_000L
    }
}
