package dev.vory.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.BotMood
import dev.vory.android.data.BotProfile
import dev.vory.android.data.ChatSession
import dev.vory.android.data.UsageOverview
import dev.vory.android.ui.components.BotFace
import dev.vory.android.ui.components.ConnPill
import dev.vory.android.ui.components.LocalVoryUiPrefs
import dev.vory.android.ui.components.OneUiCard
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.util.formatTokens
import dev.vory.android.util.toRelativeTime
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.HomeViewModel
import java.util.Calendar

/**
 * Home tab: greeting, Overview (analytics), Bots row, Pick up where you left off.
 * Cards are reorderable/hideable; layout persists in DataStore.
 */
@Composable
fun HomeScreen(
    onOpenChat: (sessionId: String, profile: String) -> Unit,
    onOpenFiles: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VoryApp
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(app.repository))
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(app.repository))

    val profiles by appVm.profiles.collectAsState()
    val moods by appVm.botMoods.collectAsState()
    val botColors by appVm.botColors.collectAsState()
    val activeProfile by appVm.activeProfile.collectAsState()
    val connState by appVm.connState.collectAsState()
    val overview by vm.overview.collectAsState()
    val recent by vm.recent.collectAsState()
    val days by vm.days.collectAsState()
    val layout by vm.layout.collectAsState()
    val loading by vm.loading.collectAsState()
    val prefs = LocalVoryUiPrefs.current

    var editLayout by remember { mutableStateOf(false) }

    OneUiScaffold(
        title = greeting(),
        actions = {
            IconButton(onClick = { editLayout = !editLayout }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit layout")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = OneUi.ScreenPadding, end = OneUi.ScreenPadding,
                top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnPill(
                        ready = connState == dev.vory.android.data.HermesSocketClient.ConnState.READY,
                        connecting = connState == dev.vory.android.data.HermesSocketClient.ConnState.CONNECTING,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activeProfile.ifEmpty { "No profile" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenFiles) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Files")
                    }
                }
            }

            val allCards = listOf("overview", "pickup", "bots", "activity")
            val visible = layout.filter { it in allCards }
            if (editLayout) {
                item {
                    OneUiCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Edit cards", style = MaterialTheme.typography.titleMedium)
                            allCards.forEach { id ->
                                val shown = id in layout
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        cardTitle(id),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    IconButton(
                                        onClick = {
                                            val i = layout.indexOf(id)
                                            if (i > 0) vm.saveLayout(layout.toMutableList().also {
                                                it[i] = it[i - 1]; it[i - 1] = id
                                            })
                                        },
                                        enabled = shown,
                                    ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
                                    IconButton(
                                        onClick = {
                                            val i = layout.indexOf(id)
                                            if (i in 0 until layout.size - 1) vm.saveLayout(layout.toMutableList().also {
                                                it[i] = it[i + 1]; it[i + 1] = id
                                            })
                                        },
                                        enabled = shown,
                                    ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down") }
                                    IconButton(onClick = {
                                        vm.saveLayout(
                                            if (shown) layout - id else layout + id,
                                        )
                                    }) {
                                        Icon(
                                            if (shown) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                            contentDescription = if (shown) "Hide" else "Show",
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { editLayout = false }, modifier = Modifier.align(Alignment.End)) {
                                Text("Done")
                            }
                        }
                    }
                }
            }

            visible.forEach { id ->
                item(key = "card-$id") {
                    when (id) {
                        "overview" -> OverviewCard(
                            overview = overview, days = days,
                            onDays = vm::setDays, loading = loading,
                        )
                        "pickup" -> PickupCard(
                            sessions = recent,
                            onOpen = onOpenChat,
                        )
                        "bots" -> BotsRowCard(
                            profiles = profiles, moods = moods, botColors = botColors,
                            faceMotion = prefs.faceMotion,
                            onSelect = { appVm.setActiveProfile(it.name) },
                            activeProfile = activeProfile,
                        )
                        "activity" -> ActivityCard(overview = overview)
                    }
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Up late"
    }
}

private fun cardTitle(id: String) = when (id) {
    "overview" -> "Overview"
    "pickup" -> "Pick up where you left off"
    "bots" -> "Bots"
    "activity" -> "Activity"
    else -> id
}

@Composable
private fun OverviewCard(overview: UsageOverview?, days: Int, onDays: (Int) -> Unit, loading: Boolean) {
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Overview", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                listOf(7, 30, 90).forEach { d ->
                    AssistChip(
                        onClick = { onDays(d) },
                        label = { Text("${d}d") },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (loading && overview == null) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (overview != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Sessions", formatTokens(overview.sessions))
                    Stat("Messages", formatTokens(overview.messages))
                    Stat("Tokens", formatTokens(overview.tokens))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Active days", overview.activeDays.toString())
                    Stat("Peak hour", overview.peakHour.ifEmpty { "—" })
                    Stat("Top model", overview.topModel.ifEmpty { "—" })
                }
                if (overview.costEstimate.isNotEmpty()) {
                    Text(
                        "Est. cost ${overview.costEstimate}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PickupCard(sessions: List<ChatSession>, onOpen: (String, String) -> Unit) {
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pick up where you left off", style = MaterialTheme.typography.titleLarge)
            if (sessions.isEmpty()) {
                Text("No recent chats.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                sessions.take(5).forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpen(s.id, s.profile) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            if (s.lastPreview.isNotEmpty()) {
                                Text(
                                    s.lastPreview, maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            s.updatedAt.toRelativeTime(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotsRowCard(
    profiles: List<BotProfile>,
    moods: Map<String, BotMood>,
    botColors: Map<String, Int>,
    faceMotion: String,
    activeProfile: String,
    onSelect: (BotProfile) -> Unit,
) {
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bots", style = MaterialTheme.typography.titleLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(profiles, key = { it.name }) { bot ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(bot) }
                            .padding(8.dp),
                    ) {
                        BotFace(
                            seed = bot.faceSeed,
                            color = botColors[bot.name]?.let { Color(it) } ?: OneUi.SamsungBlue,
                            mood = moods[bot.name] ?: BotMood.IDLE,
                            size = 64.dp,
                            motion = faceMotion,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bot.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (bot.name == activeProfile) FontWeight.Bold else FontWeight.Normal,
                            color = if (bot.name == activeProfile) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(overview: UsageOverview?) {
    OneUiCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleLarge)
            val weeks = overview?.weeks ?: emptyList()
            if (weeks.isEmpty()) {
                Text("No activity data yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // 13 weeks of activity blocks, newest last.
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    weeks.takeLast(13).forEach { week ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            week.take(7).forEach { level ->
                                Box(
                                    Modifier
                                        .size(11.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            when (level) {
                                                0 -> MaterialTheme.colorScheme.surfaceVariant
                                                1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                                3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                                else -> MaterialTheme.colorScheme.primary
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
