package dev.vory.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.vory.android.VoryApp
import dev.vory.android.data.RemoteFile
import dev.vory.android.ui.components.EmptyState
import dev.vory.android.ui.components.OneUiScaffold
import dev.vory.android.ui.theme.OneUi
import dev.vory.android.util.formatBytes
import dev.vory.android.vm.FilesViewModel

/**
 * Files browser: the gateway's folders (GET /api/files, paged), downloads
 * (GET /api/files/download) and uploads (POST /api/files/upload-stream).
 * Dot files hide behind the eye button.
 */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as VoryApp
    val vm: FilesViewModel = viewModel(factory = FilesViewModel.Factory(app.repository))

    val path by vm.path.collectAsState()
    val files by vm.files.collectAsState()
    val loading by vm.loading.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val showHidden by vm.showHidden.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            vm.upload(context, uri, name)
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice!!)
            vm.clearNotice()
        }
    }

    OneUiScaffold(
        title = "Files",
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = vm::toggleHidden, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (showHidden) "Hide dot files" else "Show dot files",
                )
            }
            IconButton(onClick = { pickUpload.launch("*/*") }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Upload, contentDescription = "Upload")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Breadcrumb bar.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OneUi.ScreenPadding, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (path != "/") {
                    IconButton(onClick = vm::goUp, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
                    }
                }
                Text(
                    path, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, modifier = Modifier.weight(1f),
                )
            }
            if (files.isEmpty() && !loading) {
                EmptyState("Empty folder", "Upload a file with the upload button.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = OneUi.ScreenPadding, end = OneUi.ScreenPadding, bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(files, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            busy = busy == file.path,
                            onOpen = {
                                if (file.isDir) vm.browse(file.path)
                                else vm.download(context, file) { local ->
                                    if (local != null) {
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", local,
                                        )
                                        val open = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent.createChooser(open, "Open with")
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                }
                            },
                            onDownload = {
                                vm.download(context, file) { /* cached; open via onOpen */ }
                            },
                        )
                    }
                    if (hasMore) {
                        item {
                            if (loading) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            } else {
                                Text(
                                    "Load more", modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.loadMore() }
                                        .padding(16.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (file.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name, maxLines = 1,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                )
                if (!file.isDir) {
                    Text(
                        formatBytes(file.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (!file.isDir) {
                IconButton(onClick = onDownload, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            }
        }
    }
}
