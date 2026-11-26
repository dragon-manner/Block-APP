package com.blockapp.android.ui

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blockapp.android.util.ReelApps
import com.blockapp.android.util.ReelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reel engine's controls (util/ReelSettings): which installed short-video apps are counted,
 * the floating pill's visibility, side and wording, and the "N reels left" warning. Links to the
 * daily limit and the plan, which keep their own screens.
 *
 * Switching an app's counting off waits until tomorrow, and says so on the row: an uncounted app
 * can't hit the limit, so an immediate off would be an in-app way round it.
 */
@Composable
fun ReelEngineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }

    // Only the tracked apps that are installed: the list has 26 entries, most of them regional.
    val installed by produceState<List<String>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            ReelApps.KNOWN.filter { pkg ->
                try {
                    context.packageManager.getApplicationInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }.sortedBy { com.blockapp.android.util.InstalledAppsProvider.label(context, it).lowercase() }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Reel engine",
            onBack = onBack,
            subtitle = "What gets counted, and how the counter shows up",
        )
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            Reveal(0) {
                Column {
                    Eyebrow("Counted apps", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Panel(contentPadding = PaddingValues(vertical = 6.dp)) {
                        val apps = installed
                        when {
                            apps == null -> LoadingRows(count = 3)
                            apps.isEmpty() -> Text(
                                "None of the short-video apps the counter knows are installed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                            else -> {
                                val pending = remember(version) { ReelSettings.pendingUntrack(context) }
                                apps.forEachIndexed { index, pkg ->
                                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    val tracked = remember(version, pkg) { ReelSettings.isTracked(context, pkg) }
                                    val stopping = pkg in pending
                                    TrackedAppRow(
                                        packageName = pkg,
                                        on = tracked && !stopping,
                                        subtitle = when {
                                            stopping -> "Still counted today · off from tomorrow"
                                            tracked -> "Counted"
                                            else -> "Not counted"
                                        },
                                        onToggle = { on ->
                                            val change = ReelSettings.setTracked(context, pkg, on)
                                            if (change == ReelSettings.Change.FROM_TOMORROW) {
                                                Toast.makeText(
                                                    context,
                                                    "Counting stops tomorrow. Turning it off can't skip today's limit.",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                            version++
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Reveal(1) {
                Column {
                    Eyebrow("Floating counter", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Panel {
                        var visible by remember { mutableStateOf(ReelSettings.pillVisible(context)) }
                        SwitchLine(
                            title = "Show the pill",
                            subtitle = "A small count beside your camera while a reel is on screen",
                            checked = visible,
                            onChange = {
                                visible = it
                                ReelSettings.setPillVisible(context, it)
                            },
                        )
                        if (visible) {
                            Spacer(Modifier.height(16.dp))
                            var side by remember { mutableStateOf(ReelSettings.pillSide(context)) }
                            SegmentedToggle(
                                options = ReelSettings.Side.entries,
                                selected = side,
                                label = { it.label },
                                onSelect = {
                                    side = it
                                    ReelSettings.setPillSide(context, it)
                                },
                            )
                            Spacer(Modifier.height(16.dp))
                            var format by remember { mutableStateOf(ReelSettings.pillFormat(context)) }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReelSettings.Format.entries.forEach { option ->
                                    ChoiceCard(
                                        title = option.label,
                                        subtitle = option.example,
                                        selected = format == option,
                                        onClick = {
                                            format = option
                                            ReelSettings.setPillFormat(context, option)
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Changes show the next time the pill appears.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Reveal(2) {
                Column {
                    Eyebrow("Heads-up before the limit", modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Panel {
                        var warnAt by remember { mutableIntStateOf(ReelSettings.warnAt(context)) }
                        SegmentedToggle(
                            options = ReelSettings.WARN_CHOICES,
                            selected = warnAt,
                            label = { if (it == 0) "Off" else "$it" },
                            onSelect = {
                                warnAt = it
                                ReelSettings.setWarnAt(context, it)
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (warnAt == 0) {
                                "No warning: the wall is the first you'll hear of it."
                            } else {
                                "A \"reels left\" note when you're $warnAt away from today's limit."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (warnAt > 0) {
                            Spacer(Modifier.height(12.dp))
                            var buzz by remember { mutableStateOf(ReelSettings.warnBuzz(context)) }
                            SwitchLine(
                                title = "Vibrate with it",
                                subtitle = "So you feel it mid-scroll",
                                checked = buzz,
                                onChange = {
                                    buzz = it
                                    ReelSettings.setWarnBuzz(context, it)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TrackedAppRow(packageName: String, on: Boolean, subtitle: String, onToggle: (Boolean) -> Unit) {
    val identity = rememberAppIdentity(packageName)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = on,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Switch,
                onValueChange = withHapticToggle(onToggle),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, 36)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(identity.label, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Switch,
                onValueChange = withHapticToggle(onChange),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}
