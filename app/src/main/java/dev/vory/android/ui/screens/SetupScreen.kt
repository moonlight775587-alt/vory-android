package dev.vory.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.vory.android.data.AuthMode
import dev.vory.android.data.TestLeg
import dev.vory.android.ui.components.OneUiCard
import dev.vory.android.ui.components.SectionLabel
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.vm.SetupViewModel

/**
 * Gateway setup wizard: name, URL (trailing slash + /api/* stripped), auth mode,
 * optional Cloudflare Access, and the 3-leg Test Connection (status JSON,
 * credential accepted, wss awaiting gateway.ready). Save enables only when all pass.
 */
@Composable
fun SetupScreen(
    vm: SetupViewModel,
    oauthCode: String?,
    onSaved: () -> Unit,
) {
    val name by vm.name.collectAsState()
    val url by vm.url.collectAsState()
    val authMode by vm.authMode.collectAsState()
    val sessionToken by vm.sessionToken.collectAsState()
    val username by vm.username.collectAsState()
    val password by vm.password.collectAsState()
    val cfId by vm.cfId.collectAsState()
    val cfSecret by vm.cfSecret.collectAsState()
    val showAdvanced by vm.showAdvanced.collectAsState()
    val legs by vm.legs.collectAsState()
    val testing by vm.testing.collectAsState()
    val canSave by vm.canSave.collectAsState()
    val error by vm.error.collectAsState()
    val plainHttpWarning by vm.plainHttpWarning.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // OIDC callback hand-off from the browser.
    LaunchedEffect(oauthCode) {
        if (oauthCode != null) {
            vm.setAuthMode(AuthMode.BROWSER_OIDC)
            vm.exchangeOidcCode(oauthCode!!) { ok, msg ->
                if (ok) vm.reportError("Signed in — now run Test Connection.")
                else vm.reportError(msg ?: "Browser sign-in failed.")
            }
        }
    }
    LaunchedEffect(error) {
        if (error != null) snackbar.showSnackbar(error!!)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(OneUi.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Connect to your gateway", style = MaterialTheme.typography.displaySmall)
            Text(
                "Vory is a remote — your Hermes agent runs on your own machine. " +
                    "Enter the dashboard URL you reach it at.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OneUiCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = vm::setName,
                        label = { Text("Name") }, placeholder = { Text("Home") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = url, onValueChange = vm::setUrl,
                        label = { Text("Gateway URL") },
                        placeholder = { Text("https://hermes.example.com") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    if (plainHttpWarning) {
                        Text(
                            "Plain http:// to a non-private host — anyone on the path can read your traffic.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            SectionLabel("Authentication")
            OneUiCard {
                Column(Modifier.selectableGroup()) {
                    AuthOption(
                        selected = authMode == AuthMode.SESSION_TOKEN,
                        title = "Session token",
                        subtitle = "Paste HERMES_DASHBOARD_SESSION_TOKEN",
                        onClick = { vm.setAuthMode(AuthMode.SESSION_TOKEN) },
                    )
                    AuthOption(
                        selected = authMode == AuthMode.USERNAME_PASSWORD,
                        title = "Username & password",
                        subtitle = "Native sign-in flow; password is never stored",
                        onClick = { vm.setAuthMode(AuthMode.USERNAME_PASSWORD) },
                    )
                    AuthOption(
                        selected = authMode == AuthMode.BROWSER_OIDC,
                        title = "Sign in with browser",
                        subtitle = "Nous Portal / OIDC via the system browser",
                        onClick = { vm.setAuthMode(AuthMode.BROWSER_OIDC) },
                    )
                }
            }

            when (authMode) {
                AuthMode.SESSION_TOKEN -> {
                    var visible by remember { mutableStateOf(false) }
                    OneUiCard {
                        OutlinedTextField(
                            value = sessionToken, onValueChange = vm::setSessionToken,
                            label = { Text("Session token") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (visible) "Hide" else "Show",
                                    )
                                }
                            },
                        )
                    }
                }
                AuthMode.USERNAME_PASSWORD -> {
                    OneUiCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = username, onValueChange = vm::setUsername,
                                label = { Text("Username") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = password, onValueChange = vm::setPassword,
                                label = { Text("Password") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                        }
                    }
                }
                AuthMode.BROWSER_OIDC -> {
                    OneUiCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Opens the system browser for the OIDC sign-in, then returns here to finish.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedButton(onClick = { vm.startBrowserSignIn(context) }) {
                                Text("Sign in with browser")
                            }
                        }
                    }
                }
            }

            TextButton(onClick = vm::toggleAdvanced, modifier = Modifier.align(Alignment.End)) {
                Text(if (showAdvanced) "Hide advanced" else "Cloudflare Access (advanced)")
            }
            if (showAdvanced) {
                OneUiCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = cfId, onValueChange = vm::setCfId,
                            label = { Text("CF-Access-Client-Id") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        var visible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = cfSecret, onValueChange = vm::setCfSecret,
                            label = { Text("CF-Access-Client-Secret") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (visible) "Hide" else "Show",
                                    )
                                }
                            },
                        )
                        Text(
                            "Sent on every HTTP request and the WebSocket upgrade. Leave both empty when you have no Access policy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::testConnection,
                enabled = !testing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Testing…")
                } else {
                    Text("Test Connection")
                }
            }

            legs.forEach { leg ->
                LegRow(
                    name = when (leg.leg) {
                        TestLeg.STATUS -> "Dashboard reachable (GET /api/status)"
                        TestLeg.AUTH -> "Credential accepted (GET /api/auth/me)"
                        TestLeg.SOCKET -> "Live socket (wss /api/ws → gateway.ready)"
                    },
                    ok = leg.ok,
                    detail = leg.detail,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save(onSaved) },
                enabled = canSave && !testing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Save gateway")
            }
            if (!canSave && legs.isNotEmpty()) {
                Text(
                    "All three legs must pass before Save is enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AuthOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegRow(name: String, ok: Boolean, detail: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
