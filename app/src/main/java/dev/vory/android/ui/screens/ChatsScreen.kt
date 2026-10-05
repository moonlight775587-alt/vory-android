package dev.vory.android.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.ChatSession
import dev.vory.android.ui.components.EmptyState
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.util.toRelativeTime
import dev.vory.android.vm.AppViewModel
import dev.vory.android.vm.ChatsViewModel

/**
 * Chats tab: stored sessions (GET /api/sessions?order=recent), search, swipe to
 * pin/archive/delete, "Needs you" badge when a card waits, project filter chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpenChat: (sessionId: String, profile: String) -> Unit) {
    val app = LocalContext.current.applicationContext as VoryApp
    val appVm: AppViewModel = viewModel(factory = AppViewModel.Factory(app.repository))
    val vm: ChatsViewModel = viewModel(factory = ChatsViewModel.Factory(app.repository))

    val sessions by vm.sessions.collectAsState()
    val query by vm.query.collectAsState()
    val projects by vm.projects.collectAsState()
    val projectFilter by vm.projectFilter.collectAsState()
    val notice by vm.notice.collectAsState()
    val needsYou by appVm.needsYouSessions.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice!!)
            vm.clearNotice()
        }
    }

    var searchOpen by remember { mutableStateOf(false) }

    OneUiScaffold(
        title = "Chats",
        actions = {
            androidx.compose.material3.IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenChat("", "") }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen) {
                OutlinedTextField(
                    value = query, onValueChange = vm::setQuery,
                    placeholder = { Text("Search chats") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OneUi.ScreenPadding, vertical = 8.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (projects.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = OneUi.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    item {
                        AssistChip(
                            onClick = { vm.setProjectFilter(null) },
                            label = { Text("All") },
                        )
                    }
                    items(projects) { p ->
                        AssistChip(
                            onClick = { vm.setProjectFilter(if (projectFilter == p) null else p) },
                            label = { Text(p) },
                        )
                    }
                }
            }
            if (sessions.isEmpty()) {
                EmptyState("No chats", "Start a new chat with the + button.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = OneUi.ScreenPadding, end = OneUi.ScreenPadding, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Pinned first, then by recency.
                    val sorted = sessions.sortedWith(
                        compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt },
                    )
                    items(sorted, key = { it.id }) { session ->
                        SwipeRow(
                            session = session,
                            needsYou = session.id in needsYou,
                            onPin = { vm.setPinned(session.id, !session.pinned) },
                            onArchive = { vm.setArchived(session.id, !session.archived) },
                            onDelete = { vm.delete(session.id) },
                            onOpen = { onOpenChat(session.id, session.profile) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    session: ChatSession,
    needsYou: Boolean,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onPin()
                    false // keep the row; action applied
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                else -> Color.Transparent
            }
            val icon = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.PushPin
                SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
                else -> null
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(OneUi.CardCorner))
                    .padding(0.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart
                else Alignment.CenterEnd,
            ) {
                Surface(color = color, modifier = Modifier.fillMaxSize()) {}
                if (icon != null) {
                    Icon(
                        icon, contentDescription = null,
                        modifier = Modifier.padding(horizontal = 20.dp),
                        tint = if (direction == SwipeToDismissBoxValue.EndToStart)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) {
        Surface(
            shape = RoundedCornerShape(OneUi.CardCorner),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .clickable(onClick = onOpen)
                    .padding(OneUi.RowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.pinned) {
                            Icon(
                                Icons.Filled.PushPin, contentDescription = "Pinned",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            session.title, maxLines = 1,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.profile.isNotEmpty()) {
                            Text(
                                session.profile,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (session.project.isNotEmpty()) {
                            Text(
                                session.project,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (session.lastPreview.isNotEmpty()) {
                            Text(
                                session.lastPreview, maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        session.updatedAt.toRelativeTime(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (needsYou) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                "Needs you",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        }
                    }
                }
                androidx.compose.material3.IconButton(onClick = onArchive) {
                    Icon(
                        if (session.archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                        contentDescription = if (session.archived) "Unarchive" else "Archive",
                    )
                }
            }
        }
    }
}
