package dev.vory.android.ui.components

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vory.android.ui.theme.OneUi

/** UI preferences piped from MainActivity (accent, dark override, motion, chat display). */
data class VoryUiPrefs(
    val accentArgb: Int?,
    val darkMode: String,
    val faceMotion: String,
    val showToolCards: Boolean,
    val showStats: Boolean,
    val showReasoning: Boolean,
)

val LocalVoryUiPrefs = compositionLocalOf {
    VoryUiPrefs(null, "system", "lively", showToolCards = true, showStats = true, showReasoning = true)
}

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            ctx.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
}

/**
 * One UI-style screen scaffold: large title that collapses on scroll
 * (like Samsung Settings), 20dp screen padding, edge-to-edge safe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneUiScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.displaySmall,
                        maxLines = 1,
                    )
                },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding -> content(padding) }
}

/** 24dp rounded card on the One UI surface. */
@Composable
fun OneUiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val cardMod = modifier.clip(RoundedCornerShape(OneUi.CardCorner))
    if (onClick != null) {
        Card(
            modifier = cardMod,
            onClick = onClick,
            shape = RoundedCornerShape(OneUi.CardCorner),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { Box(Modifier.padding(OneUi.RowPadding)) { content() } }
    } else {
        Surface(
            modifier = cardMod,
            shape = RoundedCornerShape(OneUi.CardCorner),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) { Box(Modifier.padding(OneUi.RowPadding)) { content() } }
    }
}

/** Small section label, One UI style. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A settings-style row: icon, title, subtitle, trailing control. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val mod = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 12.dp, horizontal = 4.dp)
    Row(mod, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Connection pill used in headers: green READY / amber CONNECTING / grey OFFLINE. */
@Composable
fun ConnPill(ready: Boolean, connecting: Boolean, modifier: Modifier = Modifier) {
    val (text, color) = when {
        ready -> "Ready" to Color(0xFF34C759)
        connecting -> "Connecting" to Color(0xFFFF9F0A)
        else -> "Offline" to Color(0xFF8E8E93)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                color = color,
            )
        }
    }
}
