package com.blockapp.android.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.ui.theme.AccentColor
import com.blockapp.android.ui.theme.AccentState
import com.blockapp.android.util.Haptics
import com.blockapp.android.util.PlanProgress
import com.blockapp.android.util.ReelPlan
import com.blockapp.android.util.Rewards
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate

/**
 * "Make it yours": the level the user has reached, the rewards the reel plan has earned them
 * (util/Rewards), and the choices those unlock: accent, reel-pill glyph, their own line on the
 * walls, and what Home calls them. Locked rewards are shown with how to earn them and how far
 * along the user is, so the next one is always in view.
 *
 * Purely cosmetic: nothing reachable from here loosens a lock, the limit or reel tracking.
 * The easter egg lives here too: [Rewards.SECRET_TAPS] quick taps on the level badge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RewardsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    // Bumped after anything here changes what's earned or chosen, to re-read the prefs.
    var version by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf(Rewards.Stats()) }
    var summary by remember { mutableStateOf<PlanProgress.Summary?>(null) }
    var appsLocked by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        Rewards.markSeen(context)
        app.repository.observeAppsEverLocked().collectLatest { appsLocked = it }
    }
    LaunchedEffect(Unit) {
        val plan = ReelPlan.current(context) ?: return@LaunchedEffect
        app.repository.observePlanProgress(plan, LocalDate.now()).collectLatest { summary = it }
    }
    LaunchedEffect(summary, appsLocked) {
        stats = Rewards.Stats.from(summary, appsLocked)
        if (Rewards.sync(context, stats).isNotEmpty()) {
            Rewards.markSeen(context)
            version++
        }
    }

    val earned = remember(version, stats) { Rewards.earnedIds(context) }
    val level = remember(version, stats) { Rewards.level(context) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Make it yours",
            onBack = onBack,
            subtitle = "Clean days unlock colours, pill styles and more",
        )
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            Reveal(0) {
                LevelCard(
                    level = level,
                    earnedCount = earned.size,
                    total = Rewards.ALL.size,
                    onSecret = {
                        if (Rewards.findSecret(context)) {
                            Haptics.confirm(context)
                            Toast.makeText(context, "You found Mono. It's in your accents now.", Toast.LENGTH_LONG).show()
                            version++
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            Eyebrow("What we call you", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            NicknameField()

            Spacer(Modifier.height(24.dp))
            Eyebrow("Accent", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            Panel {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AccentColor.entries.forEach { accent ->
                        val unlocked = accent.rewardId == null || accent.rewardId in earned
                        // Mono stays out of sight until found: it's the easter egg.
                        if (accent == AccentColor.MONO && !unlocked) return@forEach
                        AccentSwatch(
                            accent = accent,
                            selected = AccentState.current == accent,
                            unlocked = unlocked,
                            onClick = {
                                if (AccentState.choose(context, accent)) {
                                    Haptics.confirm(context)
                                } else {
                                    Toast.makeText(context, requirementFor(accent.rewardId), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Eyebrow("Reel pill", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            Panel {
                val chosen = remember(version) { Rewards.glyph(context) }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Rewards.PillGlyph.entries.forEach { glyph ->
                        val unlocked = glyph.rewardId == null || glyph.rewardId in earned
                        GlyphChip(
                            glyph = glyph,
                            selected = chosen == glyph,
                            unlocked = unlocked,
                            onClick = {
                                if (Rewards.setGlyph(context, glyph)) {
                                    Haptics.confirm(context)
                                    version++
                                } else {
                                    Toast.makeText(context, requirementFor(glyph.rewardId), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Shown at the front of the counter that floats beside your camera while you watch reels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            Eyebrow("Your line on every wall", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            WallLineCard(unlocked = Rewards.WALL_LINE in earned)

            Spacer(Modifier.height(24.dp))
            Eyebrow("All rewards", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                Rewards.ALL.forEach { reward ->
                    RewardRow(reward = reward, earned = reward.id in earned, stats = stats)
                }
            }
            if (summary == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Most rewards come from clean days on a reel plan. Build one from Settings → Reel plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun requirementFor(rewardId: String?): String =
    rewardId?.let { Rewards.byId(it) }?.let { "Locked: ${it.requirement.replaceFirstChar(Char::lowercase)}" }
        ?: "Locked"

/**
 * The level, its progress bar, and the easter egg: [Rewards.SECRET_TAPS] taps on the badge, each
 * within [SECRET_TAP_WINDOW_MS] of the last, find the secret accent. Each tap gives the badge a
 * finite spring (fine under Motion.kt's rule), so persistence feels like it's doing something.
 */
@Composable
private fun LevelCard(level: Rewards.Level, earnedCount: Int, total: Int, onSecret: () -> Unit) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        bounce.snapTo(1f + BADGE_BOUNCE * (taps.toFloat() / Rewards.SECRET_TAPS))
        bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
    }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        delay(SECRET_TAP_WINDOW_MS)
        taps = 0
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = bounce.value
                        scaleY = bounce.value
                    }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        Haptics.tick(context)
                        val next = taps + 1
                        if (next >= Rewards.SECRET_TAPS) {
                            taps = 0
                            onSecret()
                        } else {
                            taps = next
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${level.index + 1}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Level ${level.index + 1}")
                Text(level.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "$earnedCount of $total rewards",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Meter(level.fraction, MaterialTheme.colorScheme.primary, height = 8.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            level.next?.let { "${level.xp} / $it XP to the next level" } ?: "${level.xp} XP · top level",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NicknameField() {
    val context = LocalContext.current
    var name by remember { mutableStateOf(Rewards.nickname(context).orEmpty()) }
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it.take(Rewards.MAX_NICKNAME)
            Rewards.setNickname(context, name)
        },
        placeholder = { Text("Your name, for Home's greeting") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AccentSwatch(accent: AccentColor, selected: Boolean, unlocked: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (unlocked) accent.main else accent.main.copy(alpha = LOCKED_ALPHA))
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Filled.Check, contentDescription = "Selected", tint = accent.ink)
                !unlocked -> Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Locked",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            accent.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GlyphChip(glyph: Rewards.PillGlyph, selected: Boolean, unlocked: Boolean, onClick: () -> Unit) {
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            glyph.text,
            style = MaterialTheme.typography.titleMedium,
            color = if (unlocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            glyph.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (unlocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!unlocked) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Locked",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun WallLineCard(unlocked: Boolean) {
    val context = LocalContext.current
    Panel {
        if (!unlocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Unlocks after two clean days. Then write one line to yourself, and every block " +
                        "wall shows it back to you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Panel
        }
        var line by remember { mutableStateOf(Rewards.wallLine(context).orEmpty()) }
        OutlinedTextField(
            value = line,
            onValueChange = {
                line = it.take(Rewards.MAX_WALL_LINE)
                Rewards.setWallLine(context, line)
            },
            placeholder = { Text("e.g. You wanted your evenings back.") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Shown on every lock wall, and on reel walls when your plan has no reason saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (line.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "“${line.trim()}”",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RewardRow(reward: Rewards.Reward, earned: Boolean, stats: Rewards.Stats) {
    val secret = reward.kind == Rewards.Kind.SECRET
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (earned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (earned) Icons.Filled.Check else Icons.Filled.Lock,
                contentDescription = if (earned) "Earned" else "Locked",
                tint = if (earned) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (secret && !earned) "???" else reward.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${reward.kind.label} · ${reward.requirement}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!earned && !secret) {
                Spacer(Modifier.height(6.dp))
                Meter(reward.progress(stats), MaterialTheme.colorScheme.primary, height = 4.dp)
            }
        }
    }
}

/** How much one tap on the badge swells it, at the last tap before the secret. */
private const val BADGE_BOUNCE = 0.18f

/** Taps further apart than this start the count over, so the egg takes intent to find. */
private const val SECRET_TAP_WINDOW_MS = 1_500L

/** Locked swatches show their colour faintly: you see what you're working towards. */
private const val LOCKED_ALPHA = 0.28f
