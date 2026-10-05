package dev.vory.android.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import dev.vory.android.VoryApp
import dev.vory.android.data.Gateway
import dev.vory.android.ui.components.EmptyState
import dev.vory.android.ui.components.OneUiCard
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.components.SectionLabel
import dev.vory.android.ui.components.SettingRow
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.SettingsViewModel

/** Registry consumed by MainActivity's NavHost. */
data class SubScreen(
    val route: String,
    val content: @Composable (onBack: () -> Unit, nav: NavHostController) -> Unit,
)

object SettingsSubScreens {
    val entries: List<SubScreen> = listOf(
        SubScreen("settings/gateways") { onBack, nav -> GatewaysScreen(onBack, nav) },
        SubScreen("settings/model") { onBack, _ -> ModelScreen(onBack) },
        SubScreen("settings/config") { onBack, _ -> ConfigScreen(onBack) },
        SubScreen("settings/env") { onBack, _ -> EnvScreen(onBack) },
        SubScreen("settings/tools") { onBack, _ -> ToolsScreen(onBack) },
        SubScreen("settings/skills") { onBack, _ -> SkillsScreen(onBack) },
        SubScreen("settings/mcp") { onBack, _ -> McpScreen(onBack) },
        SubScreen("settings/approvals") { onBack, _ -> ApprovalsScreen(onBack) },
        SubScreen("settings/cron") { onBack, _ -> CronScreen(onBack) },
        SubScreen("settings/sessions") { onBack, _ -> SessionsMgmtScreen(onBack) },
        SubScreen("settings/channels") { onBack, _ -> ChannelsScreen(onBack) },
        SubScreen("settings/system") { onBack, _ -> SystemScreen(onBack) },
        SubScreen("settings/maintenance") { onBack, _ -> MaintenanceScreen(onBack) },
        SubScreen("settings/plugins") { onBack, _ -> PluginsScreen(onBack) },
        SubScreen("settings/appearance") { onBack, _ -> AppearanceScreen(onBack) },
        SubScreen("settings/notifications") { onBack, _ -> NotifSettingsScreen(onBack) },
    )
}

@Composable
private fun settingsVm(): SettingsViewModel {
    val app = LocalContext.current.applicationContext as VoryApp
    return viewModel(factory = SettingsViewModel.Factory(app.repository))
}

@Composable
private fun appVm(): AppViewModel {
    val app = LocalContext.current.applicationContext as VoryApp
    return viewModel(factory = AppViewModel.Factory(app.repository))
}

/** Sub-screen scaffold: back arrow + large title + snackbar plumbing. */
@Composable
private fun SubScaffold(
    title: String,
    onBack: () -> Unit,
    notice: String?,
    onNoticeShown: () -> Unit,
    busy: Boolean = false,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            onNoticeShown()
        }
    }
    OneUiScaffold(
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 48dp touch target via the wrapping Row.
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------------ //
// Gateways
// ------------------------------------------------------------------ //

@Composable
private fun GatewaysScreen(onBack: () -> Unit, nav: NavHostController) {
    val vm = appVm()
    val gateways by vm.gateways.collectAsState()
    val active by vm.activeGateway.collectAsState()

    SubScaffold(title = "Gateways", onBack = onBack, notice = null, onNoticeShown = {},
        actions = {
            IconButton(onClick = { nav.navigate("setup") }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add gateway")
            }
        }) {
        LazyColumn(
            contentPadding = PaddingValues(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(gateways, key = { it.id }) { gw: Gateway ->
                GatewayRow(
                    gw = gw,
                    isActive = gw.id == active?.id,
                    onSelect = { vm.selectGateway(gw) },
                    onDelete = { vm.deleteGateway(gw) },
                )
            }
            if (gateways.isEmpty()) {
                item { EmptyState("No gateways", "Add your first gateway with the + button.") }
            }
        }
    }
}

@Composable
private fun GatewayRow(gw: Gateway, isActive: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(OneUi.CardCorner),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onSelect)
                .padding(OneUi.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(gw.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
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
                    gw.baseUrl, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    when (gw.authMode.name) {
                        "SESSION_TOKEN" -> "Session token"
                        "USERNAME_PASSWORD" -> "Username & password"
                        else -> "Browser OIDC"
                    } + if (gw.hasCloudflareAccess) " · Cloudflare Access" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { confirm = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${gw.name}?") },
            text = { Text("Removes the gateway and its stored credentials from this device.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ //
// Model
// ------------------------------------------------------------------ //

@Composable
private fun ModelScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val options by vm.modelOptions.collectAsState()
    val auxiliary by vm.auxiliary.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadModel() }

    SubScaffold(title = "Model", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(
            contentPadding = PaddingValues(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val grouped = options.groupBy { it.provider.ifEmpty { "other" } }
            grouped.forEach { (provider, models) ->
                item(key = "h-$provider") { SectionLabel(provider) }
                items(models, key = { "m-${it.provider}-${it.model}" }) { opt ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            opt.model,
                            modifier = Modifier
                                .clickable { vm.setModel(opt.model, opt.provider) }
                                .padding(16.dp),
                        )
                    }
                }
            }
            if (auxiliary.isNotEmpty()) {
                item(key = "aux") {
                    SectionLabel("Auxiliary")
                    OneUiCard {
                        Text(
                            auxiliary, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ //
// Config
// ------------------------------------------------------------------ //

@Composable
private fun ConfigScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val configText by vm.configText.collectAsState()
    val schema by vm.configSchema.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    var edited by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    var showSchema by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadConfig() }
    LaunchedEffect(configText) { if (!touched) edited = configText }

    SubScaffold(title = "Config", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Saves with PUT /api/config {\"config\": …} — deep-merged on the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = edited,
                onValueChange = { edited = it; touched = true },
                modifier = Modifier.fillMaxWidth().height(320.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                ),
            )
            Button(
                onClick = { vm.saveConfigMerge(edited.ifBlank { "{}" }) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save (deep-merge)") }
            TextButton(onClick = { showSchema = !showSchema }) {
                Text(if (showSchema) "Hide schema" else "Show schema")
            }
            if (showSchema && schema.isNotEmpty()) {
                OneUiCard {
                    Text(
                        schema, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ //
// Env / API keys
// ------------------------------------------------------------------ //

@Composable
private fun EnvScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val keys by vm.envKeys.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadEnv() }

    SubScaffold(
        title = "API keys & env", onBack = onBack, notice = notice,
        onNoticeShown = vm::clearNotice, busy = busy,
        actions = {
            IconButton(onClick = { showAdd = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(keys, key = { it }) { key ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            key, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        var confirm by remember { mutableStateOf(false) }
                        IconButton(onClick = { confirm = true }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                        if (confirm) {
                            AlertDialog(
                                onDismissRequest = { confirm = false },
                                title = { Text("Delete $key?") },
                                confirmButton = {
                                    TextButton(onClick = { vm.deleteEnv(key); confirm = false }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var key by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Set variable") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (key.isNotBlank()) {
                        vm.putEnv(key.trim(), value)
                        showAdd = false
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ //
// Tools / Skills
// ------------------------------------------------------------------ //

@Composable
private fun ToolsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val toolsets by vm.toolsets.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadToolsets() }

    SubScaffold(title = "Tools", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(contentPadding = PaddingValues(OneUi.ScreenPadding)) {
            items(toolsets, key = { it.name }) { t ->
                ToggleRow(
                    title = t.name, subtitle = null, checked = t.enabled,
                    onChange = { vm.setToolset(t.name, it) },
                )
            }
        }
    }
}

@Composable
private fun SkillsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val skills by vm.skills.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadSkills() }

    SubScaffold(title = "Skills", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(contentPadding = PaddingValues(OneUi.ScreenPadding)) {
            items(skills, key = { it.name }) { s ->
                ToggleRow(
                    title = s.name, subtitle = s.detail.ifEmpty { null }, checked = s.enabled,
                    onChange = { vm.toggleSkill(s.name, it) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ //
// MCP
// ------------------------------------------------------------------ //

@Composable
private fun McpScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val servers by vm.mcp.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadMcp() }

    SubScaffold(title = "MCP servers", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(
            contentPadding = PaddingValues(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(servers, key = { it.name }) { s ->
                OneUiCard {
                    Column {
                        ToggleRow(
                            title = s.name, subtitle = s.status.ifEmpty { null },
                            checked = s.enabled,
                            onChange = { vm.setMcpEnabled(s.name, it) },
                        )
                        Row {
                            TextButton(onClick = { vm.testMcp(s.name) }) { Text("Test") }
                            Spacer(Modifier.width(8.dp))
                            var confirm by remember { mutableStateOf(false) }
                            TextButton(onClick = { confirm = true }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                            if (confirm) {
                                AlertDialog(
                                    onDismissRequest = { confirm = false },
                                    title = { Text("Delete ${s.name}?") },
                                    confirmButton = {
                                        TextButton(onClick = { vm.deleteMcp(s.name); confirm = false }) {
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                                )
                            }
                        }
                    }
                }
            }
            if (servers.isEmpty() && !busy) {
                item { EmptyState("No MCP servers", "Add servers from the gateway dashboard.") }
            }
        }
    }
}

// ------------------------------------------------------------------ //
// Approvals
// ------------------------------------------------------------------ //

@Composable
private fun ApprovalsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val mode by vm.approvalsMode.collectAsState()
    val timeout by vm.approvalsTimeout.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    var modeEdit by remember { mutableStateOf(mode) }
    var timeoutEdit by remember { mutableStateOf(timeout) }
    LaunchedEffect(Unit) { vm.loadApprovals() }
    LaunchedEffect(mode, timeout) {
        modeEdit = mode
        timeoutEdit = timeout
    }

    SubScaffold(title = "Approvals", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        Column(
            Modifier.padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            listOf("prompt", "auto", "yolo").forEach { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { modeEdit = m }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = modeEdit == m, onClick = { modeEdit = m })
                    Spacer(Modifier.width(8.dp))
                    Text(m, style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedTextField(
                value = timeoutEdit, onValueChange = { timeoutEdit = it },
                label = { Text("Timeout (seconds)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.saveApprovals(modeEdit, timeoutEdit) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save") }
            Text(
                "Saved via PUT /api/config (deep-merge of approvals.mode / approvals.timeout).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ //
// Cron
// ------------------------------------------------------------------ //

@Composable
private fun CronScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val jobs by vm.cron.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadCron() }

    SubScaffold(
        title = "Cron jobs", onBack = onBack, notice = notice,
        onNoticeShown = vm::clearNotice, busy = busy,
        actions = {
            IconButton(onClick = { showCreate = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New job")
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                OneUiCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                                Text(
                                    job.schedule + if (job.lastRun.isNotEmpty()) " · last ${job.lastRun}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (job.enabled) "on" else "paused",
                                color = if (job.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                vm.cronAction(job.id, if (job.enabled) "pause" else "resume")
                            }) { Text(if (job.enabled) "Pause" else "Resume") }
                            TextButton(onClick = { vm.cronAction(job.id, "trigger") }) { Text("Trigger") }
                            Spacer(Modifier.weight(1f))
                            var confirm by remember { mutableStateOf(false) }
                            TextButton(onClick = { confirm = true }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                            if (confirm) {
                                AlertDialog(
                                    onDismissRequest = { confirm = false },
                                    title = { Text("Delete ${job.name}?") },
                                    confirmButton = {
                                        TextButton(onClick = { vm.deleteCron(job.id); confirm = false }) {
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                                )
                            }
                        }
                    }
                }
            }
            if (jobs.isEmpty() && !busy) {
                item { EmptyState("No cron jobs", "Create one with the + button.") }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var schedule by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New cron job") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = schedule, onValueChange = { schedule = it }, label = { Text("Schedule (cron)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && schedule.isNotBlank()) {
                        vm.createCron(name.trim(), schedule.trim(), prompt)
                        showCreate = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ //
// Sessions management
// ------------------------------------------------------------------ //

@Composable
private fun SessionsMgmtScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val stats by vm.sessionStats.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadSessionStats() }

    SubScaffold(title = "Sessions", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Stats", style = MaterialTheme.typography.titleMedium)
            OneUiCard {
                Text(
                    stats.ifEmpty { "Loading…" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                )
            }
            Text(
                "Delete individual chats from the Chats tab (swipe left).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ //
// Channels / Plugins (read-only)
// ------------------------------------------------------------------ //

@Composable
private fun ChannelsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val channels by vm.channels.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadChannels() }

    SubScaffold(title = "Channels", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(contentPadding = PaddingValues(OneUi.ScreenPadding)) {
            items(channels, key = { it.name }) { c ->
                SettingRow(
                    title = c.name,
                    subtitle = c.detail.ifEmpty { null },
                    trailing = {
                        Text(
                            if (c.enabled) "connected" else "off",
                            color = if (c.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            if (channels.isEmpty() && !busy) {
                item { EmptyState("No channels", "Configure messaging platforms on the gateway.") }
            }
        }
    }
}

@Composable
private fun PluginsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val plugins by vm.plugins.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadPlugins() }

    SubScaffold(title = "Plugins", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        LazyColumn(contentPadding = PaddingValues(OneUi.ScreenPadding)) {
            items(plugins, key = { it.name }) { p ->
                SettingRow(title = p.name, subtitle = p.detail.ifEmpty { null })
            }
            if (plugins.isEmpty() && !busy) {
                item { EmptyState("No plugins", "The hub list is empty on this gateway.") }
            }
        }
    }
}

// ------------------------------------------------------------------ //
// System
// ------------------------------------------------------------------ //

@Composable
private fun SystemScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val status by vm.statusText.collectAsState()
    val logs by vm.logsText.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.loadSystem() }

    SubScaffold(title = "System", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            OneUiCard {
                Text(
                    status.ifEmpty { "Loading…" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                )
            }
            OutlinedButton(onClick = vm::runDoctor, modifier = Modifier.fillMaxWidth()) {
                Text("Run doctor (POST /api/ops/doctor)")
            }
            Text("Logs", style = MaterialTheme.typography.titleMedium)
            OneUiCard {
                Text(
                    logs.ifEmpty { "(no logs)" }.take(6000),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ //
// Maintenance
// ------------------------------------------------------------------ //

@Composable
private fun MaintenanceScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val updateInfo by vm.updateInfo.collectAsState()
    val tail by vm.actionTail.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    var confirmRestart by remember { mutableStateOf(false) }

    SubScaffold(title = "Maintenance", onBack = onBack, notice = notice, onNoticeShown = vm::clearNotice, busy = busy) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = vm::checkUpdate, modifier = Modifier.fillMaxWidth()) {
                Text("Check for Hermes updates")
            }
            if (updateInfo.isNotEmpty()) {
                OneUiCard {
                    Text(
                        updateInfo, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    )
                }
            }
            Button(
                onClick = vm::updateHermes,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Update Hermes (POST /api/hermes/update)") }
            OutlinedButton(
                onClick = { confirmRestart = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restart gateway", color = MaterialTheme.colorScheme.error) }
            if (tail.isNotEmpty()) {
                Text("Action status", style = MaterialTheme.typography.titleMedium)
                OneUiCard {
                    Text(
                        tail, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    )
                }
            }
        }
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart gateway?") },
            text = { Text("The socket will disconnect and reconnect afterwards.") },
            confirmButton = {
                TextButton(onClick = { vm.restartGateway(); confirmRestart = false }) {
                    Text("Restart", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ //
// Appearance
// ------------------------------------------------------------------ //

@Composable
private fun AppearanceScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val app = LocalContext.current.applicationContext as VoryApp
    val store = app.store
    val accent by store.accentFlow.collectAsState(initial = null)
    val darkMode by store.darkModeFlow.collectAsState(initial = "system")
    val faceMotion by store.faceMotionFlow.collectAsState(initial = "lively")
    val startTab by store.startTabFlow.collectAsState(initial = "home")
    val showToolCards by store.showToolCardsFlow.collectAsState(initial = true)
    val showStats by store.showStatsFlow.collectAsState(initial = true)
    val showReasoning by store.showReasoningFlow.collectAsState(initial = true)

    val palette = listOf(
        null to "Samsung Blue (default)",
        0xFF0381FE.toInt() to "Blue",
        0xFF34C759.toInt() to "Green",
        0xFFFF9F0A.toInt() to "Orange",
        0xFFFF3B30.toInt() to "Red",
        0xFFAF52DE.toInt() to "Purple",
        0xFFFF2D92.toInt() to "Pink",
        0xFF00C7BE.toInt() to "Teal",
    )

    SubScaffold(title = "Appearance", onBack = onBack, notice = null, onNoticeShown = {}) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Accent colour")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                palette.forEach { (argb, _) ->
                    val c = argb?.let { Color(it) } ?: OneUi.SamsungBlue
                    val selected = accent == argb
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = c,
                        border = if (selected) androidx.compose.foundation.BorderStroke(
                            3.dp, MaterialTheme.colorScheme.onBackground,
                        ) else null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { vm.setAccent(argb) },
                    ) {}
                }
            }

            SectionLabel("Theme")
            listOf("system" to "System", "dark" to "Dark (true black)", "light" to "Light").forEach { (v, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setDarkMode(v) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = darkMode == v, onClick = { vm.setDarkMode(v) })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            SectionLabel("Bot faces")
            listOf("lively" to "Lively", "calm" to "Calm", "still" to "Still").forEach { (v, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setFaceMotion(v) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = faceMotion == v, onClick = { vm.setFaceMotion(v) })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                "System reduced-motion is always honoured: still bodies, blinking eyes only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Chat display")
            ToggleRow("Tool cards", "Show tool.start / tool.complete cards", showToolCards, vm::setShowToolCards)
            ToggleRow("Per-turn stats", "tokens · tok/s · seconds under replies", showStats, vm::setShowStats)
            ToggleRow("Reasoning", "Show reasoning blocks when present", showReasoning, vm::setShowReasoning)

            SectionLabel("Start tab")
            listOf("home" to "Home", "chats" to "Chats", "bots" to "Bots", "settings" to "Settings").forEach { (v, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.setStartTab(v) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = startTab == v, onClick = { vm.setStartTab(v) })
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ //
// Notifications settings
// ------------------------------------------------------------------ //

@Composable
private fun NotifSettingsScreen(onBack: () -> Unit) {
    val vm = settingsVm()
    val app = LocalContext.current.applicationContext as VoryApp
    val turnDone by app.store.notifTurnDoneFlow.collectAsState(initial = true)
    val approvals by app.store.notifApprovalsFlow.collectAsState(initial = true)

    SubScaffold(title = "Notifications", onBack = onBack, notice = null, onNoticeShown = {}) {
        Column(Modifier.padding(OneUi.ScreenPadding)) {
            Text(
                "Local notifications, gateway-direct — no push service. Approvals carry " +
                    "Approve once / Deny actions; finished turns carry an inline Reply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                "Approvals & prompts", "Approval, clarify, sudo and secret cards while backgrounded",
                approvals, vm::setNotifApprovals,
            )
            ToggleRow(
                "Finished turns", "Notify when a turn completes while backgrounded",
                turnDone, vm::setNotifTurnDone,
            )
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = {
                // Open the system notification settings for this app.
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, app.packageName)
                app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }, label = { Text("System notification settings") })
        }
    }
}
