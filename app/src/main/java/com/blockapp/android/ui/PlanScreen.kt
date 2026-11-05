package com.blockapp.android.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.util.PlanProgress
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The three views under the plan's always-visible top half. */
private enum class PlanTab(val label: String) { STEPS("Steps"), MILESTONES("Milestones"), RULES("Your rules") }

/**
 * The running reel plan, as a programme rather than a setting: where the user is in it, the
 * streak of days they stopped on their own, what it has given back, every week's step against
 * what really happened, milestones, and the user's own reason and alternatives.
 *
 * Laid out as a fixed top half (today against the step, the streak, what it has given back, one
 * insight at a time) and a [SegmentedToggle] over the rest. Every section stacked used to run to
 * ten panels and three paragraphs of explanation, and the one number that matters today was a
 * small stat under a percentage ring.
 *
 * All figures come from PlanProgress over Room (reel_counts + plan_days), so they agree with
 * Home and the reel wall. Editing the reason or the alternatives changes no limit. Reel-free
 * hours go through ReelPlan.setQuietHours (wider now, narrower tomorrow), and rebuilding or
 * stopping keep the rule in ReelPlan's class doc: nothing here loosens today.
 */
@Composable
fun PlanScreen(onBack: () -> Unit, onRebuild: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var refreshes by remember { mutableIntStateOf(0) }
    var confirmingStop by remember { mutableStateOf(false) }
    var editingWhy by remember { mutableStateOf(false) }
    var editingQuiet by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(PlanTab.STEPS) }

    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose {}
    }
    val today = remember(refreshes) { LocalDate.now() }
    val plan = remember(refreshes) { ReelPlan.current(context) }
    val stopPending = remember(refreshes) { ReelPlan.stopPending(context) }
    val limit = remember(refreshes) { ReelLimit.todayLimit(context) }
    val quiet = remember(refreshes) { ReelPlan.quietHours(context) }
    val (pendingQuiet, hasPendingQuiet) = remember(refreshes) { ReelPlan.pendingQuietHours(context) }
    val why = remember(refreshes) { ReelPlan.why(context) }
    var alternatives by remember(refreshes) { mutableStateOf(ReelPlan.alternatives(context).toSet()) }

    var summary by remember { mutableStateOf<PlanProgress.Summary?>(null) }
    LaunchedEffect(plan, today) {
        if (plan == null) return@LaunchedEffect
        app.repository.observePlanProgress(plan, today).collectLatest { summary = it }
    }

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("Stop the plan?") },
            text = {
                Text(
                    "It stops tomorrow, not now. Today's limit and reel-free hours stay as they " +
                        "are until midnight, so stopping can't be done on impulse. Your history and " +
                        "milestones are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = withHaptic {
                    ReelPlan.stopFromTomorrow(context)
                    confirmingStop = false
                    refreshes++
                }) { Text("Stop from tomorrow", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = withHaptic { confirmingStop = false }) { Text("Keep going") } },
        )
    }
    if (editingWhy) {
        WhyDialog(
            initial = why.orEmpty(),
            onDismiss = { editingWhy = false },
            onSave = {
                ReelPlan.setWhy(context, it)
                editingWhy = false
                refreshes++
            },
        )
    }
    if (editingQuiet) {
        QuietDialog(
            initial = quiet ?: ReelPlan.QuietHours(DEFAULT_QUIET_START, DEFAULT_QUIET_END),
            hasQuiet = quiet != null,
            onDismiss = { editingQuiet = false },
            onSave = { chosen ->
                val before = ReelPlan.quietHours(context)
                ReelPlan.setQuietHours(context, chosen)
                val after = ReelPlan.quietHours(context)
                if (after != chosen && before != chosen) {
                    Toast.makeText(context, "That change starts tomorrow.", Toast.LENGTH_SHORT).show()
                }
                editingQuiet = false
                refreshes++
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Your plan",
            onBack = onBack,
            subtitle = summary?.phase?.blurb,
        )
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            if (plan == null) {
                NoPlan(onRebuild)
                return@Column
            }
            val s = summary

            Reveal(0) { PlanHero(plan, s, limit, today, stopPending) }

            if (s != null) {
                Spacer(Modifier.height(12.dp))
                Reveal(1) { StreakPanel(s) }
                Spacer(Modifier.height(12.dp))
                Reveal(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImpactTile(
                            icon = Icons.Outlined.SkipNext,
                            label = "reels skipped",
                            modifier = Modifier.weight(1f),
                        ) {
                            AnimatedCount(s.reelsSkipped, style = MaterialTheme.typography.headlineMedium)
                        }
                        ImpactTile(
                            icon = Icons.Outlined.Schedule,
                            label = "of your time back",
                            modifier = Modifier.weight(1f),
                        ) {
                            AnimatedDuration(s.minutesBack * 60_000L, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Reveal(3) { InsightCard(PlanProgress.insights(s).take(MAX_INSIGHTS)) }
            }

            Spacer(Modifier.height(24.dp))
            Reveal(4) {
                SegmentedToggle(
                    options = PlanTab.entries,
                    selected = tab,
                    label = { it.label },
                    onSelect = { tab = it },
                )
            }
            Spacer(Modifier.height(16.dp))

            // The tabs slide the way the toggle's pill moves, and the height eases between them
            // rather than the page below jumping when a short tab replaces a long one.
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    val slide = spring<IntOffset>(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    (slideInHorizontally(slide) { dir * it / 5 } + fadeIn(tween(220, delayMillis = 40)))
                        .togetherWith(slideOutHorizontally(slide) { -dir * it / 5 } + fadeOut(tween(120)))
                        .using(SizeTransform(clip = false))
                },
                label = "planTab",
            ) { shown ->
                Column {
                    when (shown) {
                        PlanTab.STEPS -> StepsTab(plan, s)
                        PlanTab.MILESTONES -> MilestonesTab(s)
                        PlanTab.RULES -> RulesTab(
                            why = why,
                            alternatives = alternatives,
                            quiet = quiet,
                            pendingQuiet = pendingQuiet,
                            hasPendingQuiet = hasPendingQuiet,
                            stopPending = stopPending,
                            onEditWhy = { editingWhy = true },
                            onEditQuiet = { editingQuiet = true },
                            onToggleAlternative = { alternative ->
                                alternatives = if (alternative in alternatives) {
                                    alternatives - alternative
                                } else {
                                    alternatives + alternative
                                }
                                ReelPlan.setAlternatives(context, alternatives)
                            },
                            onRebuild = onRebuild,
                            onStop = { confirmingStop = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * Where the plan is and today against its step. Today's count is the headline because it's
 * the only figure the user can still change today; the day count and weeks sit above it.
 */
@Composable
private fun PlanHero(
    plan: ReelPlan.Plan,
    summary: PlanProgress.Summary?,
    limit: Int?,
    today: LocalDate,
    stopPending: Boolean,
) {
    val dayNumber = summary?.dayNumber ?: 1
    val totalDays = plan.weeks * 7
    val complete = dayNumber > totalDays
    val ahead = limit != null && limit < plan.scheduledLimit(today)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(summary?.phase?.title ?: plan.goal.title, color = Ember, modifier = Modifier.weight(1f))
            when {
                stopPending -> StatusPill("Ends tomorrow", Ember)
                ahead -> StatusPill("Ahead of schedule", Lime)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (complete) {
                Text("Plan complete", style = MaterialTheme.typography.displaySmall)
            } else {
                Text("Day ", style = MaterialTheme.typography.displaySmall)
                AnimatedCount(dayNumber, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    "of $totalDays",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        WeekTrack(weeks = plan.weeks, current = plan.weekOf(today), complete = complete, dayInWeek = (dayNumber - 1) % 7)
        Spacer(Modifier.height(8.dp))
        Text(
            if (complete) {
                "Holding at ${plan.targetLimit} a day"
            } else {
                "Week ${plan.weekOf(today) + 1} of ${plan.weeks} · goal ${plan.targetLimit} a day by " +
                    plan.startDay.plusWeeks((plan.weeks - 1).toLong()).format(DAY_MONTH)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        TodayGauge(reels = summary?.today?.reels ?: 0, limit = limit)
    }
}

/**
 * One segment per week of the plan: past weeks full, the current one filling by the day, later
 * ones empty. The segments fill left to right on arrival.
 */
@Composable
private fun WeekTrack(weeks: Int, current: Int, complete: Boolean, dayInWeek: Int) {
    val appeared = rememberAppeared()
    val fills = (0 until weeks).map { index ->
        val target = when {
            complete || index < current -> 1f
            index == current -> (dayInWeek + 1) / 7f
            else -> 0f
        }
        animateFloatAsState(
            if (appeared) target else 0f,
            tween(WEEK_FILL_MS, delayMillis = if (appeared) index * WEEK_STAGGER_MS else 0),
            label = "week",
        )
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val gap = 4.dp.toPx()
        val segment = (size.width - gap * (weeks - 1)) / weeks
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        for (index in 0 until weeks) {
            val left = index * (segment + gap)
            drawRoundRect(track, Offset(left, 0f), Size(segment, size.height), radius)
            val fill = fills[index].value
            if (fill > 0f) {
                drawRoundRect(
                    if (index == current && !complete) Lime else Lime.copy(alpha = 0.55f),
                    Offset(left, 0f),
                    Size(segment * fill, size.height),
                    radius,
                )
            }
        }
    }
}

/** "12 of 40 reels today" with a meter that turns Ember as the limit gets close. */
@Composable
private fun TodayGauge(reels: Int, limit: Int?) {
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedCount(reels, style = MaterialTheme.typography.displayMedium, color = Ember)
        Spacer(Modifier.width(10.dp))
        Text(
            when (limit) {
                null -> "reels today · no limit"
                0 -> "reels today · reel-free day"
                else -> "of $limit reels today"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (limit != null && limit > 0) {
        Spacer(Modifier.height(10.dp))
        val fraction = reels.toFloat() / limit
        Meter(
            fraction = fraction,
            color = if (fraction >= GAUGE_WARN_FRACTION) Ember else Lime,
            height = 8.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                reels >= limit -> "Limit reached. Reel viewers close until midnight."
                else -> "${limit - reels} left before the wall"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The streak and the last two weeks. What makes a day clean lives behind the info toggle: it's
 * read once, and spelled out on every visit it was most of this card.
 */
@Composable
private fun StreakPanel(summary: PlanProgress.Summary) {
    var explaining by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                Icons.Outlined.LocalFireDepartment,
                tint = if (summary.streak > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                background = if (summary.streak > 0) Lime else MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 48.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedCount(summary.streak, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (summary.streak == 1) "clean day" else "clean days",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Text(
                    "Best ${summary.bestStreak} · ${summary.cleanDays} of ${summary.scoredDays} clean",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClickLabel = "What counts as clean") { explaining = !explaining },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (explaining) Lime else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        DayStrip(summary.days)
        AnimatedVisibility(
            visible = explaining,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(14.dp))
                OutcomeLegend()
                Spacer(Modifier.height(10.dp))
                Text(
                    "A clean day is one you stopped before the wall had to. Days nothing was " +
                        "counting are skipped, not held against you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ImpactTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Panel(modifier = modifier, contentPadding = PaddingValues(18.dp)) {
        Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(10.dp))
        value()
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One insight at a time, tap for the next. Three stacked lines of advice read as a wall of text;
 * one rolls in with a page dot per line, so there is clearly more without showing it all.
 */
@Composable
private fun InsightCard(insights: List<String>) {
    if (insights.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(0) }
    val shown = index.coerceIn(0, insights.lastIndex)
    Panel(
        color = MaterialTheme.colorScheme.primaryContainer,
        onClick = if (insights.size > 1) ({ index = (shown + 1) % insights.size }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("How it's going", color = Lime, modifier = Modifier.weight(1f))
            if (insights.size > 1) PageDots(count = insights.size, current = shown)
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = shown,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 2 } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically(tween(160)) { -it / 3 } + fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            label = "insight",
        ) { i ->
            Text(
                insights[i],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Small page dots; the current one stretches into a pill. Drawn, so nothing here loops. */
@Composable
private fun PageDots(count: Int, current: Int) {
    val dots = (0 until count).map { i ->
        animateFloatAsState(
            if (i == current) 1f else 0f,
            spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
            label = "dot",
        )
    }
    val dim = Lime.copy(alpha = 0.3f)
    Canvas(Modifier.size(width = (count * 10 + 10).dp, height = 6.dp)) {
        val h = size.height
        val wide = h * 3f
        var x = 0f
        dots.forEachIndexed { i, state ->
            val p = state.value
            val w = h + (wide - h) * p
            drawRoundRect(
                if (p > 0.5f) Lime else dim,
                Offset(x, 0f),
                Size(w, h),
                CornerRadius(h / 2f, h / 2f),
            )
            x += w + h
        }
    }
}

@Composable
private fun StepsTab(plan: ReelPlan.Plan, summary: PlanProgress.Summary?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("From your start to the goal")
                Spacer(Modifier.height(4.dp))
                Text("${plan.baseline} → ${plan.targetLimit} a day", style = MaterialTheme.typography.titleLarge)
            }
            summary?.dropPercent?.takeIf { it > 0 }?.let { StatusPill("−$it%", Lime) }
        }
        if (summary != null) {
            Spacer(Modifier.height(18.dp))
            JourneyChart(weeks = summary.weeks, baseline = plan.baseline)
            Spacer(Modifier.height(14.dp))
            JourneyLegend()
        }
    }
}

@Composable
private fun MilestonesTab(summary: PlanProgress.Summary?) {
    if (summary == null) return
    val reached = summary.milestones.count { it.reached }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
        Text("$reached", style = MaterialTheme.typography.titleLarge, color = Lime)
        Text(
            " of ${summary.milestones.size} reached",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Next up first, then the rest by how close they are, then the ones already done: the
    // list reads as "what to aim for" rather than a trophy shelf of finished badges.
    val ordered = summary.milestones.sortedWith(
        compareBy<PlanProgress.Milestone> { it.reached }.thenByDescending { it.progress },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.chunked(2).forEachIndexed { row, pair ->
            Reveal(row) {
                // Same height across the pair, whichever title wraps to two lines.
                Row(
                    Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { milestone ->
                        MilestoneTile(
                            milestone,
                            milestoneIcon(milestone.title),
                            Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesTab(
    why: String?,
    alternatives: Set<ReelPlan.Alternative>,
    quiet: ReelPlan.QuietHours?,
    pendingQuiet: ReelPlan.QuietHours?,
    hasPendingQuiet: Boolean,
    stopPending: Boolean,
    onEditWhy: () -> Unit,
    onEditQuiet: () -> Unit,
    onToggleAlternative: (ReelPlan.Alternative) -> Unit,
    onRebuild: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(onClick = onEditWhy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Favorite, contentDescription = null, tint = Ember, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Eyebrow("Your reason", modifier = Modifier.weight(1f))
                Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                why?.let { "“$it”" } ?: "Add one in your own words. The reel wall reads it back to you.",
                style = if (why != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                fontStyle = if (why != null) FontStyle.Normal else FontStyle.Italic,
                color = if (why != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Panel(onClick = onEditQuiet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.Bedtime, tint = Ember, background = EmberWash)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow("Reel-free hours")
                    Text(quiet?.label() ?: "None yet", style = MaterialTheme.typography.titleLarge)
                    if (hasPendingQuiet) {
                        Text(
                            "From tomorrow: ${pendingQuiet?.label() ?: "none"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Ember,
                        )
                    }
                }
                Text(
                    if (quiet == null) "Add" else "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Panel {
            Eyebrow("Instead of the next reel")
            Spacer(Modifier.height(4.dp))
            Text(
                "Shown on the reel wall when it stops you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReelPlan.Alternative.entries.forEach { alternative ->
                    ToggleChip(alternative.title, alternative in alternatives) { onToggleAlternative(alternative) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton("Rebuild my plan", onClick = onRebuild, modifier = Modifier.fillMaxWidth())
        if (!stopPending) {
            SecondaryButton("Stop the plan", onClick = onStop, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "A rebuilt plan can only make today stricter. Anything looser starts tomorrow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun milestoneIcon(title: String): ImageVector = when {
    "reel-free" in title -> Icons.Outlined.WbSunny
    "skipped" in title -> Icons.Outlined.SkipNext
    "back" in title -> Icons.Outlined.Schedule
    "goal" in title -> Icons.Outlined.Flag
    "complete" in title -> Icons.Outlined.EmojiEvents
    "week" in title -> Icons.Outlined.CalendarMonth
    "row" in title -> Icons.Outlined.LocalFireDepartment
    else -> Icons.Outlined.Star
}

@Composable
private fun WhyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why you're doing this") },
        text = {
            Column {
                Text(
                    "One line, in your words. It's what the reel wall shows you when it stops you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_WHY_CHARS) text = it },
                    placeholder = { Text("I want my evenings back") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = withHaptic { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = withHaptic(onDismiss)) { Text("Cancel") } },
    )
}

@Composable
private fun QuietDialog(
    initial: ReelPlan.QuietHours,
    hasQuiet: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReelPlan.QuietHours?) -> Unit,
) {
    var quiet by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reel-free hours") },
        text = {
            Column {
                QuietHoursEditor(quiet, onChange = { if (it.start != it.end) quiet = it })
                Spacer(Modifier.height(12.dp))
                Text(
                    "Wider hours start now. Narrower ones, or turning them off, start tomorrow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = withHaptic { onSave(quiet) }) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (hasQuiet) TextButton(onClick = withHaptic { onSave(null) }) { Text("Turn off") }
                TextButton(onClick = withHaptic(onDismiss)) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun NoPlan(onBuild: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        StepsArt(height = 140.dp)
        Spacer(Modifier.height(24.dp))
        Text("No plan yet", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "A few questions, then a limit that steps down each week, follows you down when you " +
                "beat it, and keeps score of the days you stop on your own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Build my plan", onClick = onBuild, modifier = Modifier.fillMaxWidth())
    }
}

/** "1,240" rather than "1240": these totals run into the thousands. */
internal fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())

private const val MAX_INSIGHTS = 3

/** One week segment's fill, and the delay between neighbouring segments starting. */
private const val WEEK_FILL_MS = 700
private const val WEEK_STAGGER_MS = 90

/** Share of today's limit at which the gauge turns Ember: close enough to want a warning. */
private const val GAUGE_WARN_FRACTION = 0.8f
private const val MAX_WHY_CHARS = 120

/** Offered when reel-free hours are first added here: the window most people pick in the quiz. */
private const val DEFAULT_QUIET_START = 23 * 60
private const val DEFAULT_QUIET_END = 7 * 60
