package com.blockapp.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.MainActivity
import com.blockapp.android.sync.Catalog
import com.blockapp.android.sync.Policy
import com.blockapp.android.ui.theme.BlockAppTheme
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Ink950
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.util.Haptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * The wall: what a blocked app or a closed reel viewer is replaced with, and why. Started by
 * AppBlockAccessibilityService (Tier 2 and Tier 3) shortly after its HOME/BACK, with the
 * policy's verdict. It finishes on its own once the verdict no longer holds (a focus round
 * ending, a lockdown worked off on the laptop), so it never outlives the reason it was shown.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_REASON = "reason"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_UNTIL = "until"
        const val EXTRA_REELS = "reels"

        fun intent(context: Context, packageName: String, verdict: Policy.Verdict, reels: Boolean = false): Intent =
            Intent(context, BlockOverlayActivity::class.java).apply {
                // NO_ANIMATION: without it the launcher's window animation and the wall's play
                // back to back, which reads as a glitch rather than one deliberate bounce.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_REASON, verdict.reason)
                putExtra(EXTRA_MESSAGE, verdict.message)
                putExtra(EXTRA_UNTIL, verdict.until)
                putExtra(EXTRA_REELS, reels)
            }
    }

    private var packageNameState by mutableStateOf("")
    private var reasonState by mutableStateOf("")
    private var messageState by mutableStateOf("")
    private var untilState by mutableLongStateOf(0L)
    private var reelsState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        // Fresh creates only: the watchdog re-delivers the intent every poll via onNewIntent,
        // and buzzing on each would vibrate continuously.
        if (savedInstanceState == null) Haptics.reject(this)
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            BlockAppTheme(darkTheme = true) {
                Surface(color = Ink950) {
                    Wall(
                        packageName = packageNameState,
                        reason = reasonState,
                        message = messageState,
                        until = untilState,
                        reels = reelsState,
                        onHome = ::goHome,
                        onOpenKeel = ::openKeel,
                        onLifted = ::finish,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BlockApplication).onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
    }

    private fun readExtras(intent: Intent) {
        packageNameState = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        reasonState = intent.getStringExtra(EXTRA_REASON).orEmpty()
        messageState = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        untilState = intent.getLongExtra(EXTRA_UNTIL, 0L)
        reelsState = intent.getBooleanExtra(EXTRA_REELS, false)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun openKeel() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

private fun titleFor(reason: String, reels: Boolean, app: String) = when (reason) {
    "focus" -> "Back to focus"
    "lockdown" -> "Lockdown"
    "quiet" -> if (reels) "Reel-free hours" else "Quiet hours"
    "limit" -> "$app is done for today"
    "reels" -> "Reel limit reached"
    else -> "Blocked"
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Wall(
    packageName: String,
    reason: String,
    message: String,
    until: Long,
    reels: Boolean,
    onHome: () -> Unit,
    onOpenKeel: () -> Unit,
    onLifted: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val identity = if (packageName.isEmpty()) null else rememberAppIdentity(packageName)
    val ember = reason == "lockdown" || reason == "limit" || reason == "reels"
    var now by remember { mutableLongStateOf(Policy.now()) }

    // Finish once the reason is gone. Checked against the live policy rather than a timer,
    // because what ends a block (a focus round ending, a task finished on the laptop, a bought
    // breather) mostly happens elsewhere.
    LaunchedEffect(packageName, reels) {
        while (true) {
            delay(1000L)
            now = Policy.now()
            val still = if (reels) {
                val local = app.repository.observeReelTotal(LocalDate.now()).first()
                Policy.reelVerdict(local) != null
            } else {
                val target = Catalog.targetFor(context, packageName)
                val e = Policy.state.value
                val cat = Catalog.category(context, target, e?.overrides ?: emptyMap())
                val local = Policy.localUsage[target] ?: 0L
                Policy.verdict(target, "", cat, local) != null
            }
            if (!still) {
                onLifted()
                return@LaunchedEffect
            }
        }
    }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(packageName, reason) {
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes { durationMillis = 380; -14f at 60; 12f at 130; -8f at 200; 5f at 270 })
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (identity != null) {
            Box(Modifier.graphicsLayer { translationX = shake.value }) {
                AppIcon(identity, 72)
            }
            Spacer(Modifier.height(24.dp))
        }
        Text(
            titleFor(reason, reels, identity?.label ?: "This app"),
            style = MaterialTheme.typography.headlineLarge,
            color = if (ember) Ember else Lime,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (until > now) {
            Spacer(Modifier.height(28.dp))
            Text(clock(until - now), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(if (reason == "focus") "left in this round" else "left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(40.dp))
        PrimaryButton("Go home", onClick = onHome, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton(if (reason == "lockdown") "See the overdue task" else "Open Keel", onClick = onOpenKeel, modifier = Modifier.fillMaxWidth())
    }
}
