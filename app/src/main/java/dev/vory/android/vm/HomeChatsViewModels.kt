package dev.vory.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.ChatSession
import dev.vory.android.data.UsageOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

fun parseOverview(o: JSONObject): UsageOverview {
    val weeks = mutableListOf<List<Int>>()
    val warr = o.optJSONArray("weeks")
    if (warr != null) {
        for (i in 0 until warr.length()) {
            val w = warr.optJSONArray(i) ?: continue
            weeks += List(w.length()) { j -> w.optInt(j, 0).coerceIn(0, 4) }
        }
    }
    return UsageOverview(
        sessions = o.optLong("sessions", 0),
        messages = o.optLong("messages", 0),
        tokens = o.optLong("tokens", 0),
        activeDays = o.optLong("active_days", o.optLong("activeDays", 0)),
        peakHour = o.optString("peak_hour", o.optString("peakHour", "")),
        topModel = o.optString("top_model", o.optString("topModel", "")),
        costEstimate = o.optString("cost_estimate", o.optString("costEstimate", "")),
        weeks = weeks,
    )
}

fun parseSession(o: JSONObject): ChatSession {
    val rawTs = o.optLong("updated_at", o.optLong("updatedAt", 0))
    // Tolerate seconds vs millis epoch.
    val tsMillis = if (rawTs in 1..9999999999L) rawTs * 1000 else rawTs
    return ChatSession(
        id = o.optString("id", o.optString("session_id", "")),
        title = o.optString("title", "Untitled"),
        profile = o.optString("profile", ""),
        project = o.optString("project", ""),
        updatedAt = tsMillis,
        pinned = o.optBoolean("pinned", false),
        archived = o.optBoolean("archived", false),
        lastPreview = o.optString("preview", o.optString("last_message", o.optString("lastMessage", ""))),
    )
}

class HomeViewModel(private val repo: AppRepository) : ViewModel() {
    private val _days = MutableStateFlow(30)
    val days: StateFlow<Int> = _days.asStateFlow()
    private val _overview = MutableStateFlow<UsageOverview?>(null)
    val overview: StateFlow<UsageOverview?> = _overview.asStateFlow()
    private val _recent = MutableStateFlow<List<ChatSession>>(emptyList())
    val recent: StateFlow<List<ChatSession>> = _recent.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    val layout: StateFlow<List<String>> = repo.store.homeLayoutFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf("overview", "pickup", "bots", "activity"))

    init { refresh() }

    fun setDays(d: Int) {
        _days.value = d
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val r = repo.rest ?: throw Exception("No gateway selected")
                val o = r.get("/api/analytics/usage", profile = "", extra = mapOf("days" to _days.value.toString()))
                _overview.value = parseOverview(o)
                val arr = r.getArray("/api/sessions", profile = "", extra = mapOf("order" to "recent", "limit" to "10"))
                _recent.value = List(arr.length()) { parseSession(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun saveLayout(cards: List<String>) = viewModelScope.launch { repo.store.setHomeLayout(cards) }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(repo) as T
    }
}

class ChatsViewModel(private val repo: AppRepository) : ViewModel() {
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _projectFilter = MutableStateFlow<String?>(null)
    val projectFilter: StateFlow<String?> = _projectFilter.asStateFlow()
    private val _projects = MutableStateFlow<List<String>>(emptyList())
    val projects: StateFlow<List<String>> = _projects.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        refresh()
        loadProjects()
    }

    fun setQuery(q: String) {
        _query.value = q
        viewModelScope.launch {
            val r = repo.rest ?: return@launch
            _sessions.value = try {
                if (q.isBlank()) {
                    val arr = r.getArray("/api/sessions", profile = "", extra = mapOf("order" to "recent"))
                    List(arr.length()) { parseSession(arr.getJSONObject(it)) }
                } else {
                    val arr = r.getArray("/api/sessions/search", profile = "", extra = mapOf("q" to q))
                    List(arr.length()) { parseSession(arr.getJSONObject(it)) }
                }
            } catch (e: Exception) {
                _notice.value = e.message
                _sessions.value
            }
        }
    }

    fun setProjectFilter(p: String?) {
        _projectFilter.value = p
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val r = repo.rest ?: throw Exception("No gateway selected")
                val extra = mutableMapOf("order" to "recent")
                _projectFilter.value?.let { extra["project"] = it }
                val arr = r.getArray("/api/sessions", profile = "", extra = extra)
                _sessions.value = List(arr.length()) { parseSession(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                _notice.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            _projects.value = try {
                val res = repo.rpc("projects.list")
                val arr = res.optJSONArray("projects") ?: res.optJSONArray("items")
                if (arr != null) List(arr.length()) {
                    val o = arr.optJSONObject(it)
                    o?.optString("name", "") ?: arr.optString(it, "")
                }.filter { it.isNotEmpty() } else emptyList()
            } catch (e: dev.vory.android.data.RpcException) {
                if (e.featureMissing) emptyList() else throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repo.rest?.delete("/api/sessions/$id", profile = "")
                _sessions.value = _sessions.value.filterNot { it.id == id }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    /** Best-effort pin/archive via JSON-RPC; older gateways answer -32601. */
    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            try {
                repo.rpc("session.pin", JSONObject().put("session_id", id).put("pinned", pinned))
                refresh()
            } catch (e: dev.vory.android.data.RpcException) {
                _notice.value = if (e.featureMissing) "Pin not supported by this gateway" else e.message
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch {
            try {
                repo.rpc("session.archive", JSONObject().put("session_id", id).put("archived", archived))
                refresh()
            } catch (e: dev.vory.android.data.RpcException) {
                _notice.value = if (e.featureMissing) "Archive not supported by this gateway" else e.message
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    fun clearNotice() { _notice.value = null }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatsViewModel(repo) as T
    }
}
