package dev.vory.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.HermesSocketClient
import dev.vory.android.ui.components.BotFace
import dev.vory.android.ui.components.ConnPill
import dev.vory.android.ui.components.LocalVoryUiPrefs
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.components.SectionLabel
import dev.vory.android.ui.components.SettingRow
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.vm.AppViewModel

private data class SettingsEntry(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val ENTRIES = listOf(
    SettingsEntry("settings/model", "Model", "Providers and model options", Icons.Filled.SmartToy),
    SettingsEntry("settings/config", "Config", "Gateway config (deep-merge)", Icons.Filled.SmartToy),
    SettingsEntry("settings/env", "API keys & env", "Environment variables", Icons.Filled.SmartToy),
    SettingsEntry("settings/tools", "Tools", "Toolsets on/off", Icons.Filled.SmartToy),
    SettingsEntry("settings/skills", "Skills", "Skill toggles", Icons.Filled.SmartToy),
    SettingsEntry("settings/mcp", "MCP servers", "Servers, test, delete", Icons.Filled.SmartToy),
    SettingsEntry("settings/approvals", "Approvals", "Mode and timeout", Icons.Filled.SmartToy),
    SettingsEntry("settings/cron", "Cron jobs", "Pause, resume, trigger, delete", Icons.Filled.SmartToy),
    SettingsEntry("settings/sessions", "Sessions", "Stats, search, delete", Icons.Filled.SmartToy),
    SettingsEntry("settings/channels", "Channels", "Messaging platforms (read-only)", Icons.Filled.SmartToy),
    SettingsEntry("settings/system", "System", "Status, logs, doctor", Icons.Filled.SmartToy),
    SettingsEntry("settings/maintenance", "Maintenance", "Updates and restart", Icons.Filled.SmartToy),
    SettingsEntry("settings/plugins", "Plugins", "Dashboard plugin hub (read-only)", Icons.Filled.SmartToy),
)

private val APP_ENTRIES = listOf(
    SettingsEntry("settings/gateways", "Gateways", "Saved gateways", Icons.Filled.SmartToy),
    SettingsEntry("settings/appearance", "Appearance", "Accent, theme, faces, chat", Icons.Filled.SmartToy),
    SettingsEntry("settings/notifications", "Notifications", "Approvals and turns", Icons.Filled.SmartToy),
)

/**
 * Settings hub: profile switcher + connection status on top, then the API-map
 * sections (every request scoped with ?profile=), then app settings.
 */
@Composable
fun SettingsScreen(
    onOpenGatewaySettings: () -> Unit,
    onOpenSub: (String) -> Unit,
    connState: HermesSocketClient.ConnState,
) {
    val app = LocalContext.current.applicationContext as VoryApp
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(app.repository))
    val profiles by appVm.profiles.collectAsState()
    val activeProfile by appVm.activeProfile.collectAsState()
    val moods by appVm.botMoods.collectAsState()
    val botColors by appVm.botColors.collectAsState()
    val prefs = LocalVoryUiPrefs.current
    var profileMenu by remember { mutableStateOf(false) }

    OneUiScaffold(title = "Settings") { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = OneUi.ScreenPadding, end = OneUi.ScreenPadding,
                top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                // Profile switcher + connection status.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(OneUi.CardCorner),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(OneUi.RowPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bot = profiles.firstOrNull { it.name == activeProfile }
                        BotFace(
                            seed = bot?.faceSeed ?: activeProfile.hashCode(),
                            color = botColors[activeProfile]?.let { Color(it) } ?: OneUi.SamsungBlue,
                            mood = moods[activeProfile] ?: dev.vory.android.data.BotMood.IDLE,
                            size = 48.dp,
                            motion = prefs.faceMotion,
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            Column {
                                Text("Profile", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.material3.TextButton(onClick = { profileMenu = true }) {
                                    Text(activeProfile.ifEmpty { "Select profile" }, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            appVm.setActiveProfile(p.name)
                                            profileMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        ConnPill(
                            ready = connState == HermesSocketClient.ConnState.READY,
                            connecting = connState == HermesSocketClient.ConnState.CONNECTING,
                        )
                    }
                }
            }

            item { SectionLabel("Gateway (?profile= scoped)") }
            items(ENTRIES) { e ->
                SettingsRow(entry = e, onClick = { onOpenSub(e.route) })
            }
            item { SectionLabel("This device") }
            items(APP_ENTRIES) { e ->
                SettingsRow(entry = e, onClick = { onOpenSub(e.route) })
            }
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, onClick: () -> Unit) {
    SettingRow(
        title = entry.title,
        subtitle = entry.subtitle,
        trailing = {
            Icon(
                Icons.Filled.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}
