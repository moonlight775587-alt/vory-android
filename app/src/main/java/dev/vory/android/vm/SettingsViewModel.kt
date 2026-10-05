package dev.vory.android.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.CronJob
import dev.vory.android.data.ModelOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class NamedToggle(val name: String, val enabled: Boolean, val detail: String = "")
data class McpServer(val name: String, val enabled: Boolean, val status: String = "")

/**
 * Settings mirrors the dashboard API map (PROTOCOL.md §4). Every request
 * carries ?profile= via the rest client; writes re-GET afterwards and 4xx
 * bodies are shown verbatim (RpcException carries the body).
 */
class SettingsViewModel(private val repo: AppRepository) : ViewModel() {

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun profile() = repo.activeProfile.value
    private fun <T> run(op: suspend () -> T, after: (T) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                after(op())
            } catch (e: Exception) {
                _notice.value = e.message
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearNotice() { _notice.value = null }

    // ------------------------------------------------------------------ //
    // Model
    // ------------------------------------------------------------------ //
    private val _modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelOptions: StateFlow<List<ModelOption>> = _modelOptions.asStateFlow()
    private val _auxiliary = MutableStateFlow("")
    val auxiliary: StateFlow<String> = _auxiliary.asStateFlow()

    fun loadModel() = run({
        val r = repo.rest ?: throw Exception("No gateway selected")
        val o = r.get("/api/model/options", profile())
        _auxiliary.value = runCatching { r.get("/api/model/auxiliary", profile()).toString(2) }.getOrDefault("")
        parseModelOptionsLocal(o)
    }, { _modelOptions.value = it })

    fun setModel(model: String, provider: String) = run({
        repo.rest?.post(
            "/api/model/set", profile(),
            JSONObject().put("model", model).put("provider", provider),
        )
        "Model set to $model"
    }, { _notice.value = it })

    // ------------------------------------------------------------------ //
    // Config (schema + deep-merge PUT)
    // ------------------------------------------------------------------ //
    private val _configText = MutableStateFlow("")
    val configText: StateFlow<String> = _configText.asStateFlow()
    private val _configSchema = MutableStateFlow("")
    val configSchema: StateFlow<String> = _configSchema.asStateFlow()

    fun loadConfig() = run({
        val r = repo.rest ?: throw Exception("No gateway selected")
        _configSchema.value = runCatching { r.get("/api/config/schema", profile()).toString(2) }.getOrDefault("")
        r.get("/api/config", profile()).toString(2)
    }, { _configText.value = it })

    /** PUT /api/config {"config": patch} — deep-merge on the server. */
    fun saveConfigMerge(patchJson: String) = run({
        val patch = JSONObject(patchJson)
        repo.rest?.put("/api/config", profile(), JSONObject().put("config", patch))
        "Config merged"
    }, {
        _notice.value = it
        loadConfig()
    })

    // ------------------------------------------------------------------ //
    // Env / API keys
    // ------------------------------------------------------------------ //
    private val _envKeys = MutableStateFlow<List<String>>(emptyList())
    val envKeys: StateFlow<List<String>> = _envKeys.asStateFlow()

    fun loadEnv() = run({
        val o = repo.rest?.get("/api/env", profile()) ?: JSONObject()
        val keys = mutableListOf<String>()
        o.keys().forEach { keys += it }
        keys.sorted()
    }, { _envKeys.value = it })

    fun putEnv(key: String, value: String) = run({
        repo.rest?.put("/api/env", profile(), JSONObject().put("key", key).put("value", value))
        "Saved $key"
    }, { _notice.value = it; loadEnv() })

    fun deleteEnv(key: String) = run({
        repo.rest?.delete("/api/env", profile(), JSONObject().put("key", key))
        "Deleted $key"
    }, { _notice.value = it; loadEnv() })

    // ------------------------------------------------------------------ //
    // Tools / Skills
    // ------------------------------------------------------------------ //
    private val _toolsets = MutableStateFlow<List<NamedToggle>>(emptyList())
    val toolsets: StateFlow<List<NamedToggle>> = _toolsets.asStateFlow()

    fun loadToolsets() = run({
        val o = repo.rest?.get("/api/tools/toolsets", profile()) ?: JSONObject()
        val arr = o.optJSONArray("toolsets") ?: JSONArray()
        List(arr.length()) {
            val t = arr.getJSONObject(it)
            NamedToggle(t.optString("name", ""), t.optBoolean("enabled", true))
        }
    }, { _toolsets.value = it })

    fun setToolset(name: String, enabled: Boolean) = run({
        repo.rest?.put("/api/tools/toolsets/$name", profile(), JSONObject().put("enabled", enabled))
        loadToolsets()
    }, {})

    private val _skills = MutableStateFlow<List<NamedToggle>>(emptyList())
    val skills: StateFlow<List<NamedToggle>> = _skills.asStateFlow()

    fun loadSkills() = run({
        val o = repo.rest?.get("/api/skills", profile()) ?: JSONObject()
        val arr = o.optJSONArray("skills") ?: JSONArray()
        List(arr.length()) {
            val s = arr.getJSONObject(it)
            NamedToggle(s.optString("name", ""), s.optBoolean("enabled", true), s.optString("description", ""))
        }
    }, { _skills.value = it })

    fun toggleSkill(name: String, enabled: Boolean) = run({
        repo.rest?.put("/api/skills/toggle", profile(), JSONObject().put("name", name).put("enabled", enabled))
        loadSkills()
    }, {})

    // ------------------------------------------------------------------ //
    // MCP servers
    // ------------------------------------------------------------------ //
    private val _mcp = MutableStateFlow<List<McpServer>>(emptyList())
    val mcp: StateFlow<List<McpServer>> = _mcp.asStateFlow()

    fun loadMcp() = run({
        val o = repo.rest?.get("/api/mcp/servers", profile()) ?: JSONObject()
        val arr = o.optJSONArray("servers") ?: JSONArray()
        List(arr.length()) {
            val s = arr.getJSONObject(it)
            McpServer(s.optString("name", ""), s.optBoolean("enabled", true), s.optString("status", ""))
        }
    }, { _mcp.value = it })

    fun setMcpEnabled(name: String, enabled: Boolean) = run({
        repo.rest?.put("/api/mcp/servers/$name/enabled", profile(), JSONObject().put("enabled", enabled))
        loadMcp()
    }, {})

    fun testMcp(name: String) = run({
        val o = repo.rest?.post("/api/mcp/servers/$name/test", profile())
        "Test: ${o?.optString("status", o.toString())}"
    }, { _notice.value = it })

    fun deleteMcp(name: String) = run({
        repo.rest?.delete("/api/mcp/servers/$name", profile())
        loadMcp()
    }, {})

    // ------------------------------------------------------------------ //
    // Approvals (via PUT /api/config deep-merge)
    // ------------------------------------------------------------------ //
    private val _approvalsMode = MutableStateFlow("prompt")
    val approvalsMode: StateFlow<String> = _approvalsMode.asStateFlow()
    private val _approvalsTimeout = MutableStateFlow("60")
    val approvalsTimeout: StateFlow<String> = _approvalsTimeout.asStateFlow()

    fun loadApprovals() = run({
        val o = repo.rest?.get("/api/config", profile()) ?: JSONObject()
        val a = o.optJSONObject("approvals") ?: JSONObject()
        _approvalsMode.value = a.optString("mode", "prompt")
        _approvalsTimeout.value = a.optString("timeout", "60")
        Unit
    }, {})

    fun saveApprovals(mode: String, timeout: String) = run({
        val patch = JSONObject().put("approvals", JSONObject().put("mode", mode).put("timeout", timeout))
        repo.rest?.put("/api/config", profile(), JSONObject().put("config", patch))
        "Approvals updated"
    }, { _notice.value = it; loadApprovals() })

    // ------------------------------------------------------------------ //
    // Cron
    // ------------------------------------------------------------------ //
    private val _cron = MutableStateFlow<List<CronJob>>(emptyList())
    val cron: StateFlow<List<CronJob>> = _cron.asStateFlow()

    fun loadCron() = run({
        val o = repo.rest?.get("/api/cron/jobs", profile()) ?: JSONObject()
        val arr = o.optJSONArray("jobs") ?: JSONArray()
        List(arr.length()) {
            val j = arr.getJSONObject(it)
            CronJob(
                id = j.optString("id", ""),
                name = j.optString("name", ""),
                schedule = j.optString("schedule", j.optString("cron", "")),
                enabled = j.optBoolean("enabled", true),
                lastRun = j.optString("last_run", ""),
            )
        }
    }, { _cron.value = it })

    fun cronAction(id: String, action: String) = run({
        // POST /api/cron/jobs/{id}/pause|resume|trigger
        repo.rest?.post("/api/cron/jobs/$id/$action", profile())
        loadCron()
    }, {})

    fun deleteCron(id: String) = run({
        repo.rest?.delete("/api/cron/jobs/$id", profile())
        loadCron()
    }, {})

    fun createCron(name: String, schedule: String, prompt: String) = run({
        repo.rest?.post(
            "/api/cron/jobs", profile(),
            JSONObject().put("name", name).put("schedule", schedule).put("prompt", prompt),
        )
        loadCron()
    }, {})

    // ------------------------------------------------------------------ //
    // Sessions management
    // ------------------------------------------------------------------ //
    private val _sessionStats = MutableStateFlow("")
    val sessionStats: StateFlow<String> = _sessionStats.asStateFlow()

    fun loadSessionStats() = run({
        (repo.rest?.get("/api/sessions/stats", profile()) ?: JSONObject()).toString(2)
    }, { _sessionStats.value = it })

    fun deleteSession(id: String, after: () -> Unit = {}) = run({
        repo.rest?.delete("/api/sessions/$id", profile())
        after()
    }, {})

    // ------------------------------------------------------------------ //
    // Channels (read-only) / Plugins (read-only hub list)
    // ------------------------------------------------------------------ //
    private val _channels = MutableStateFlow<List<NamedToggle>>(emptyList())
    val channels: StateFlow<List<NamedToggle>> = _channels.asStateFlow()

    fun loadChannels() = run({
        val o = repo.rest?.get("/api/messaging/platforms", profile()) ?: JSONObject()
        val arr = o.optJSONArray("platforms") ?: JSONArray()
        List(arr.length()) {
            val p = arr.getJSONObject(it)
            NamedToggle(p.optString("name", ""), p.optBoolean("connected", p.optBoolean("enabled", false)), p.optString("status", ""))
        }
    }, { _channels.value = it })

    private val _plugins = MutableStateFlow<List<NamedToggle>>(emptyList())
    val plugins: StateFlow<List<NamedToggle>> = _plugins.asStateFlow()

    fun loadPlugins() = run({
        val o = repo.rest?.get("/api/dashboard/plugins/hub") ?: JSONObject()
        val arr = o.optJSONArray("plugins") ?: JSONArray()
        List(arr.length()) {
            val p = arr.getJSONObject(it)
            NamedToggle(p.optString("name", ""), p.optBoolean("enabled", true), p.optString("description", p.optString("version", "")))
        }
    }, { _plugins.value = it })

    // ------------------------------------------------------------------ //
    // System: status, logs, doctor
    // ------------------------------------------------------------------ //
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    private val _logsText = MutableStateFlow("")
    val logsText: StateFlow<String> = _logsText.asStateFlow()

    fun loadSystem() = run({
        val r = repo.rest ?: throw Exception("No gateway selected")
        _statusText.value = r.get("/api/status").toString(2)
        _logsText.value = runCatching { r.getText("/api/logs", profile()) }.getOrDefault("(no logs)")
        Unit
    }, {})

    fun runDoctor() = run({
        val o = repo.rest?.post("/api/ops/doctor", profile()) ?: JSONObject()
        "Doctor: ${o.optString("summary", o.toString(2).take(500))}"
    }, { _notice.value = it })

    // ------------------------------------------------------------------ //
    // Maintenance: update check / hermes update / gateway restart + tail
    // ------------------------------------------------------------------ //
    private val _updateInfo = MutableStateFlow("")
    val updateInfo: StateFlow<String> = _updateInfo.asStateFlow()
    private val _actionTail = MutableStateFlow("")
    val actionTail: StateFlow<String> = _actionTail.asStateFlow()

    fun checkUpdate() = run({
        (repo.rest?.get("/api/hermes/update/check") ?: JSONObject()).toString(2)
    }, { _updateInfo.value = it })

    fun updateHermes() = run({
        repo.rest?.post("/api/hermes/update")
        tailAction("hermes-update")
        "Update started — tailing status"
    }, { _notice.value = it })

    fun restartGateway() = run({
        repo.rest?.post("/api/gateway/restart")
        tailAction("gateway-restart")
        "Restart requested — tailing status"
    }, { _notice.value = it })

    private suspend fun tailAction(action: String) {
        repeat(10) {
            kotlinx.coroutines.delay(2000)
            val o = runCatching {
                repo.rest?.get("/api/actions/$action/status")
            }.getOrNull() ?: return
            _actionTail.value = o.toString(2)
            val state = o.optString("status", o.optString("state", ""))
            if (state.equals("done", true) || state.equals("failed", true)) return
        }
    }

    // ------------------------------------------------------------------ //
    // Appearance / notifications (device-only)
    // ------------------------------------------------------------------ //

    fun setAccent(argb: Int?) = viewModelScope.launch { repo.store.setAccent(argb) }
    fun setDarkMode(v: String) = viewModelScope.launch { repo.store.setDarkMode(v) }
    fun setFaceMotion(v: String) = viewModelScope.launch { repo.store.setFaceMotion(v) }
    fun setStartTab(v: String) = viewModelScope.launch { repo.store.setStartTab(v) }
    fun setShowToolCards(v: Boolean) = viewModelScope.launch { repo.store.setShowToolCards(v) }
    fun setShowStats(v: Boolean) = viewModelScope.launch { repo.store.setShowStats(v) }
    fun setShowReasoning(v: Boolean) = viewModelScope.launch { repo.store.setShowReasoning(v) }
    fun setNotifTurnDone(v: Boolean) = viewModelScope.launch { repo.store.setNotifTurnDone(v) }
    fun setNotifApprovals(v: Boolean) = viewModelScope.launch { repo.store.setNotifApprovals(v) }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repo) as T
    }
}

private fun parseModelOptionsLocal(o: JSONObject): List<ModelOption> {
    val out = mutableListOf<ModelOption>()
    val providers = o.optJSONArray("providers")
    if (providers != null) {
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val slug = p.optString("slug", p.optString("name", ""))
            val models = p.optJSONArray("models") ?: continue
            for (j in 0 until models.length()) {
                val m = models.optJSONObject(j)?.optString("id", "") ?: models.optString(j, "")
                if (m.isNotEmpty()) out += ModelOption(slug, m)
            }
        }
        return out
    }
    return out
}
