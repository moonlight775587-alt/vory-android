package dev.vory.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.BotMood
import dev.vory.android.data.BotProfile
import dev.vory.android.ui.components.BotFace
import dev.vory.android.ui.components.LocalVoryUiPrefs
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.components.SectionLabel
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.BotsViewModel

/**
 * Bots tab: the gateway's profiles (GET /api/profiles), each with its code-drawn
 * face, live status and model. Tap → profile sheet: colour, description editor
 * (PUT …/description), default model (PUT …/model), SOUL.md editor (PUT …/soul).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsScreen() {
    val app = LocalContext.current.applicationContext as VoryApp
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(app.repository))
    val vm: BotsViewModel = viewModel(factory = BotsViewModel.Factory(app.repository))

    val profiles by appVm.profiles.collectAsState()
    val moods by appVm.botMoods.collectAsState()
    val botColors by appVm.botColors.collectAsState()
    val activeProfile by appVm.activeProfile.collectAsState()
    val selected by vm.selected.collectAsState()
    val groupRooms by vm.groupRooms.collectAsState()
    val notice by vm.notice.collectAsState()
    val prefs = LocalVoryUiPrefs.current
    val snackbar = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice!!)
            vm.clearNotice()
        }
    }

    OneUiScaffold(
        title = "Bots",
        actions = {
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New bot")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = OneUi.ScreenPadding, end = OneUi.ScreenPadding,
                top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles, key = { it.name }) { bot ->
                BotRow(
                    bot = bot,
                    color = botColors[bot.name]?.let { Color(it) } ?: OneUi.SamsungBlue,
                    mood = moods[bot.name] ?: BotMood.IDLE,
                    faceMotion = prefs.faceMotion,
                    isActive = bot.name == activeProfile,
                    onClick = {
                        appVm.setActiveProfile(bot.name)
                        vm.select(bot)
                    },
                )
            }
            if (groupRooms.isNotEmpty()) {
                item {
                    SectionLabel("Group rooms")
                }
                items(groupRooms) { room ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(room, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    selected?.let { bot ->
        BotSheet(
            bot = bot,
            color = botColors[bot.name]?.let { Color(it) } ?: OneUi.SamsungBlue,
            vm = vm,
            appVm = appVm,
            onDismiss = vm::clearSelection,
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New bot") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Profile name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        vm.createBot(name.trim())
                        showCreate = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BotRow(
    bot: BotProfile,
    color: Color,
    mood: BotMood,
    faceMotion: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(OneUi.CardCorner),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(OneUi.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotFace(seed = bot.faceSeed, color = color, mood = mood, size = 56.dp, motion = faceMotion)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bot.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                "active", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text(
                    bot.model.ifEmpty { "default model" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    moodLabel(mood),
                    style = MaterialTheme.typography.bodyMedium,
                    color = moodColor(mood),
                )
            }
        }
    }
}

private fun moodLabel(m: BotMood) = when (m) {
    BotMood.IDLE -> "idle"
    BotMood.WORKING -> "working…"
    BotMood.WAITING -> "waiting on you"
    BotMood.ERROR -> "error"
}

private fun moodColor(m: BotMood) = when (m) {
    BotMood.IDLE -> Color(0xFF8E8E93)
    BotMood.WORKING -> Color(0xFF0381FE)
    BotMood.WAITING -> Color(0xFFFF9F0A)
    BotMood.ERROR -> Color(0xFFFF6B6B)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotSheet(
    bot: BotProfile,
    color: Color,
    vm: BotsViewModel,
    appVm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val soul by vm.soul.collectAsState()
    val soulLoading by vm.soulLoading.collectAsState()
    var description by remember(bot.name) { mutableStateOf(bot.description) }
    var model by remember(bot.name) { mutableStateOf(bot.model) }
    var soulText by remember { mutableStateOf("") }
    var soulTouched by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Sync the editor once the soul loads.
    LaunchedEffect(soul) {
        if (!soulTouched && soul.isNotEmpty()) soulText = soul
    }

    val palette = listOf(
        0xFF0381FE, 0xFF34C759, 0xFFFF9F0A, 0xFFFF3B30,
        0xFFAF52DE, 0xFFFF2D92, 0xFF64D2FF, 0xFFAC8E68,
    ).map { Color(it) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotFace(seed = bot.faceSeed, color = color, mood = BotMood.IDLE, size = 64.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(bot.name, style = MaterialTheme.typography.titleLarge)
                    Text("profile", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Text("Colour (this device only)", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { c ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = c,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { appVm.setBotColor(bot.name, c.toArgb()) },
                    ) {}
                }
            }

            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
            Button(onClick = { vm.saveDescription(description) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save description")
            }

            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Default model") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Button(onClick = { vm.saveDefaultModel(model) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save default model")
            }

            Text("SOUL.md", style = MaterialTheme.typography.titleMedium)
            if (soulLoading) {
                CircularProgressIndicator()
            } else {
                OutlinedTextField(
                    value = soulText,
                    onValueChange = { soulText = it; soulTouched = true },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
                Button(
                    onClick = { vm.saveSoul(soulText) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save SOUL.md") }
            }

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Text("Delete bot", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${bot.name}?") },
            text = { Text("This deletes the profile from the gateway.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBot(bot.name)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
