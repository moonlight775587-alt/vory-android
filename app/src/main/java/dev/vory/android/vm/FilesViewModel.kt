package dev.vory.android.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.RemoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class FilesViewModel(private val repo: AppRepository) : ViewModel() {

    private val _path = MutableStateFlow("/")
    val path: StateFlow<String> = _path.asStateFlow()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _page = MutableStateFlow(0)
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _showHidden = MutableStateFlow(false)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null) // path currently downloading/uploading
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init { browse("/") }

    fun toggleHidden() {
        _showHidden.value = !_showHidden.value
        browse(_path.value)
    }

    fun browse(path: String) {
        _path.value = path
        _page.value = 0
        _files.value = emptyList()
        _hasMore.value = true
        loadPage()
    }

    fun goUp() {
        val p = _path.value.trimEnd('/')
        if (p.isEmpty() || p == "/") return
        browse(p.substringBeforeLast("/").ifEmpty { "/" })
    }

    fun loadMore() = loadPage()

    private fun loadPage() {
        if (_loading.value || !_hasMore.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                val r = repo.rest ?: throw Exception("No gateway selected")
                val o = r.get(
                    "/api/files", profile = repo.activeProfile.value,
                    extra = mapOf("path" to _path.value, "page" to _page.value.toString(), "limit" to "100"),
                )
                val arr = o.optJSONArray("files") ?: o.optJSONArray("items")
                val list = mutableListOf<RemoteFile>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        val name = f.optString("name", "")
                        if (name.startsWith(".") && !_showHidden.value) continue
                        val isDir = f.optString("type", "").equals("dir", true) || f.optBoolean("is_dir", false)
                        list += RemoteFile(
                            path = f.optString("path", _path.value.trimEnd('/') + "/" + name),
                            name = name,
                            isDir = isDir,
                            size = f.optLong("size", 0),
                            modifiedAt = f.optLong("mtime", f.optLong("modified_at", 0)),
                        )
                    }
                }
                // Directories first, then alphabetical — like a file manager.
                val sorted = list.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                _files.value = _files.value + sorted
                _hasMore.value = o.optBoolean("has_more", o.optBoolean("hasMore", arr?.length() == 100))
                _page.value = _page.value + 1
            } catch (e: Exception) {
                _notice.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    /** Download to the app cache dir; returns the local file. */
    fun download(context: Context, file: RemoteFile, onDone: (File?) -> Unit) {
        viewModelScope.launch {
            _busy.value = file.path
            try {
                val r = repo.rest ?: throw Exception("No gateway selected")
                val dest = File(context.cacheDir, "vory-dl-" + file.name)
                r.download(
                    "/api/files/download", repo.activeProfile.value,
                    mapOf("path" to file.path), dest,
                )
                onDone(dest)
            } catch (e: Exception) {
                _notice.value = e.message
                onDone(null)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Upload a local file into the current remote directory. */
    fun upload(context: Context, uri: Uri, displayName: String) {
        viewModelScope.launch {
            _busy.value = displayName
            try {
                val r = repo.rest ?: throw Exception("No gateway selected")
                val tmp = File(context.cacheDir, "vory-up-" + displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                r.upload("/api/files/upload-stream", repo.activeProfile.value, _path.value, tmp)
                tmp.delete()
                browse(_path.value)
                _notice.value = "Uploaded $displayName"
            } catch (e: Exception) {
                _notice.value = e.message
            } finally {
                _busy.value = null
            }
        }
    }

    fun clearNotice() { _notice.value = null }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(repo) as T
    }
}
