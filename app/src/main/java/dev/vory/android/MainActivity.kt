package dev.vory.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import dev.vory.android.data.HermesSocketClient
import dev.vory.android.ui.components.LocalVoryUiPrefs
import dev.vory.android.ui.components.VoryUiPrefs
import dev.vory.android.ui.screens.BotsScreen
import dev.vory.android.ui.screens.ChatScreen
import dev.vory.android.ui.screens.ChatsScreen
import dev.vory.android.ui.screens.FilesScreen
import dev.vory.android.ui.screens.HomeScreen
import dev.vory.android.ui.screens.SettingsScreen
import dev.vory.android.ui.screens.SetupScreen
import dev.vory.android.ui.screens.settings.SettingsSubScreens
import dev.vory.android.ui.theme.VoryTheme
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.SetupViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)
        setContent {
            val app = application as VoryApp
            val repo = app.repository
            val store = app.store
            val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(repo))

            val darkMode by store.darkModeFlow.collectAsState(initial = "system")
            val accentArgb by store.accentFlow.collectAsState(initial = null)
            val faceMotion by store.faceMotionFlow.collectAsState(initial = "lively")
            val showToolCards by store.showToolCardsFlow.collectAsState(initial = true)
            val showStats by store.showStatsFlow.collectAsState(initial = true)
            val showReasoning by store.showReasoningFlow.collectAsState(initial = true)
            val darkTheme = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            VoryTheme(darkTheme = darkTheme, accentArgb = accentArgb) {
                CompositionLocalProvider(
                    LocalVoryUiPrefs provides VoryUiPrefs(
                        accentArgb, darkMode, faceMotion, showToolCards, showStats, showReasoning,
                    ),
                ) {
                    VoryNav(appVm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** vory://oauth/callback?code=… → hand to the wizard's token exchange. */
    private fun handleDeepLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme == "vory" && uri.host == "oauth") {
            val code = uri.getQueryParameter("code")
            OauthCallback.code = code
        }
    }

    /** Delivered to whichever SetupViewModel is alive; consumed once. */
    object OauthCallback {
        var code: String? = null
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("chats", "Chats", Icons.Filled.ChatBubbleOutline),
    Tab("bots", "Bots", Icons.Filled.SmartToy),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun VoryNav(appVm: AppViewModel) {
    val nav = rememberNavController()
    val gateways by appVm.gateways.collectAsState()
    val needsRestart by appVm.needsRestart.collectAsState()
    val startTab by (nav.context.applicationContext as VoryApp).store.startTabFlow
        .collectAsState(initial = "home")
    val start = if (gateways.isEmpty()) "setup" else "main"
    val snackbar = remember { SnackbarHostState() }

    NavHost(navController = nav, startDestination = start) {
        composable("setup") {
            val repo = (nav.context.applicationContext as VoryApp).repository
            val vm: SetupViewModel = viewModel(factory = SetupViewModel.Factory(repo))
            // OIDC callback hand-off.
            var oauthCode by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                MainActivity.OauthCallback.code?.let {
                    MainActivity.OauthCallback.code = null
                    oauthCode = it
                }
            }
            SetupScreen(
                vm = vm,
                oauthCode = oauthCode,
                onSaved = { nav.navigate("main") { popUpTo("setup") { inclusive = true } } },
            )
        }
        composable("main") {
            MainTabs(nav = nav, appVm = appVm, startTab = startTab, needsRestart = needsRestart, snackbar = snackbar)
        }
        composable(
            route = "chat?sessionId={sessionId}&profile={profile}&draft={draft}",
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("profile") { type = NavType.StringType; defaultValue = "" },
                navArgument("draft") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "vory://chat/{sessionId}" }),
        ) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId")
            val profile = backStack.arguments?.getString("profile").orEmpty()
            val draft = backStack.arguments?.getString("draft").orEmpty()
            // Profile may also arrive as a query param on the deep link.
            val queryProfile = backStack.arguments?.getString("profile").orEmpty()
            ChatScreen(
                sessionId = sessionId,
                profile = profile.ifEmpty { queryProfile },
                draft = draft,
                onBack = { nav.popBackStack() },
            )
        }
        composable("files") {
            FilesScreen(onBack = { nav.popBackStack() })
        }
        // Settings sub-screens (mirror the API map).
        SettingsSubScreens.entries.forEach { entry ->
            composable(entry.route) {
                entry.content({ nav.popBackStack() }, nav)
            }
        }
    }
}

@Composable
private fun MainTabs(
    nav: androidx.navigation.NavHostController,
    appVm: AppViewModel,
    startTab: String,
    needsRestart: Boolean,
    snackbar: SnackbarHostState,
) {
    val tabsNav = rememberNavController()
    val backStack by tabsNav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: startTab
    val connState by appVm.connState.collectAsState()

    // Enter on the user's chosen start tab.
    LaunchedEffect(startTab) {
        if ((backStack?.destination?.route) == null) {
            tabsNav.navigate(startTab) {
                popUpTo(tabsNav.graph.startDestinationId) { inclusive = true }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (needsRestart) {
                    RestartBanner(
                        onUpdate = { appVm.updateHermes {} },
                        onRestart = { appVm.restartGateway {} },
                        onDismiss = { appVm.dismissRestartBanner() },
                    )
                }
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                tabsNav.navigate(tab.route) {
                                    popUpTo(tabsNav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = tabsNav, startDestination = "home") {
                composable("home") { HomeScreen(onOpenChat = { id, profile ->
                    nav.navigate("chat?sessionId=$id&profile=$profile")
                }, onOpenFiles = { nav.navigate("files") }) }
                composable("chats") { ChatsScreen(onOpenChat = { id, profile ->
                    nav.navigate("chat?sessionId=$id&profile=$profile")
                }) }
                composable("bots") { BotsScreen() }
                composable("settings") {
                    SettingsScreen(
                        onOpenGatewaySettings = { /* in Settings list */ },
                        onOpenSub = { route -> nav.navigate(route) },
                        connState = connState,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestartBanner(onUpdate: () -> Unit, onRestart: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                "Gateway needs a restart (dashboard older than its checkout).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onUpdate) { Text("Update Hermes") }
                TextButton(onClick = onRestart) { Text("Restart gateway") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}
