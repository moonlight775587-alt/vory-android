package dev.vory.android.vm

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.ChatItem
import dev.vory.android.data.ModelOption
import dev.vory.android.data.PendingCard
import dev.vory.android.data.RpcException
import dev.vory.android.data.SessionBusEvent
import dev.vory.android.data.SocketEvent
import dev.vory.android.data.TextMessage
import dev.vory.android.data.TodoEntry
import dev.vory.android.data.ToolCardItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * One chat thread. Speaks the §6 chat flow exactly:
 * session.resume / session.create → prompt.submit → message.delta streaming →
 * tool.start/tool.complete cards → message.complete → session.usage stats.
 */
class ChatViewModel(
    private val repo: AppRepository,
    initialSessionId: String?,
    private val profile: String,
    initialDraft: String = "",
) : ViewModel() {

    private val _sessionId = MutableStateFlow(initialSessionId)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    /** The live tail bubble — separate from [items] so only it recomposes while streaming. */
    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _title = MutableStateFlow("New chat")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _replyTo = MutableStateFlow<TextMessage?>(null)
    val replyTo: StateFlow<TextMessage?> = _replyTo.asStateFlow()

    private val _modelOptions = MutableStateFlow<List<ModelOption>>(emptyList())
    val modelOptions: StateFlow<List<ModelOption>> = _modelOptions.asStateFlow()

    private val _slashCatalog = MutableStateFlow<List<String>>(emptyList())
    val slashCatalog: StateFlow<List<String>> = _slashCatalog.asStateFlow()

    private val _pendingRefs = MutableStateFlow<List<String>>(emptyList())
    val pendingRefs: StateFlow<List<String>> = _pendingRefs.asStateFlow()

    private val _draft = MutableStateFlow(initialDraft)
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** Approval/clarify/secret cards waiting in this session (plus global ones). */
    val sessionCards: StateFlow<List<PendingCard>> = repo.pendingCards
        .combine(_sessionId) { cards, sid ->
            cards.filter { it.sessionId.isEmpty() || it.sessionId == sid }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Per-turn usage for the stats line.
    private var usageBefore: Long = -1
    private var turnStartMs: Long = 0L

    init {
        if (initialSessionId != null) openSession(initialSessionId)
        viewModelScope.launch {
            repo.sessionBus.collect { ev ->
                if (ev is SessionBusEvent.Rpc && ev.sessionId == _sessionId.value) onRpcEvent(ev.event)
            }
        }
        loadSlashCatalog()
    }

    // ------------------------------------------------------------------ //
    // Session lifecycle
    // ------------------------------------------------------------------ //

    private fun openSession(id: String) {
        viewModelScope.launch {
            _sending.value = true
            try {
                repo.rpc("session.resume", JSONObject().put("session_id", id))
                loadHistory(id)
            } catch (e: Exception) {
                _notice.value = "resume failed: ${e.message}"
            } finally {
                _sending.value = false
            }
        }
    }

    private suspend fun ensureSession(): String {
        _sessionId.value?.let { return it }
        val res = repo.rpc("session.create", JSONObject().put("profile", profile))
        val id = res.optString("session_id", res.optString("id", ""))
        if (id.isEmpty()) throw RpcException(-32000, "session.create returned no id")
        _sessionId.value = id
        return id
    }

    private suspend fun loadHistory(id: String) {
        val r = repo.rest ?: return
        val arr = r.getArray("/api/sessions/$id/messages", profile = profile)
        val list = mutableListOf<ChatItem>()
        for (i in 0 until arr.length()) {
            parseHistoryMessage(arr.getJSONObject(i))?.let { list += it }
        }
        _items.value = list
        _title.value = list.firstOrNull()
            .let { (it as? TextMessage)?.text?.take(40) ?: "Chat" }
    }

    private fun parseHistoryMessage(o: JSONObject): ChatItem? {
        val role = o.optString("role", "assistant")
        val text = o.optString("text", o.optString("content", ""))
        if (text.isEmpty()) return null
        return asChatItem(
            id = o.optString("id", UUID.randomUUID().toString()),
            role = role,
            text = text,
            at = o.optLong("created_at", o.optLong("at", 0)),
        )
    }

    /** Bot-to-bot traffic renders as notices, never shell transcripts. */
    private fun asChatItem(id: String, role: String, text: String, at: Long): ChatItem {
        val t = text.trim()
        val bot2bot = t.startsWith("Messaging ", ignoreCase = true) ||
            t.startsWith("Messaged ", ignoreCase = true) ||
            t.startsWith("Message from ", ignoreCase = true)
        return TextMessage(id = id, role = if (bot2bot) "notice" else role, text = text, at = at)
    }

    // ------------------------------------------------------------------ //
    // Sending
    // ------------------------------------------------------------------ //

    fun setReplyTo(msg: TextMessage?) { _replyTo.value = msg }
    fun setDraft(v: String) { _draft.value = v }
    fun clearNotice() { _notice.value = null }

    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _sending.value) return
        if (text.startsWith("/")) {
            handleSlash(text)
            return
        }
        viewModelScope.launch {
            _sending.value = true
            turnStartMs = System.currentTimeMillis()
            usageBefore = -1
            try {
                val sid = ensureSession()
                val refs = _pendingRefs.value
                _pendingRefs.value = emptyList()
                val reply = _replyTo.value
                _replyTo.value = null
                val full = buildString {
                    reply?.let { append("> ").append(it.text.take(200)).append("\n\n") }
                    append(text)
                    refs.forEach { append("\n").append(it) }
                }
                _items.value = _items.value + TextMessage(
                    id = "u-${UUID.randomUUID()}",
                    role = "user",
                    text = full,
                    at = System.currentTimeMillis(),
                    replyTo = reply?.text?.take(120),
                )
                _streamText.value = ""
                repo.rpc("prompt.submit", JSONObject().put("session_id", sid).put("text", full))
                // message.complete finalises; a watchdog only fires on transport death.
            } catch (e: Exception) {
                _sending.value = false
                _items.value = _items.value +
                    TextMessage("e-${UUID.randomUUID()}", "system", "Send failed: ${e.message}", System.currentTimeMillis())
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            _sessionId.value?.let {
                runCatching { repo.rpc("session.stop", JSONObject().put("session_id", it)) }
            }
            finalizeStream(interrupted = true)
        }
    }

    // ------------------------------------------------------------------ //
    // Socket events
    // ------------------------------------------------------------------ //

    private fun onRpcEvent(ev: SocketEvent.RpcEvent) {
        when (ev.type) {
            "turn.start" -> {
                _sending.value = true
                turnStartMs = System.currentTimeMillis()
            }
            "message.delta" -> {
                _streamText.value += ev.payload.optString("text", ev.payload.optString("delta", ""))
            }
            "tool.start" -> {
                val p = ev.payload
                val tool = p.optString("tool", p.optString("name", "tool"))
                // message_agent traffic becomes notices, not shell transcripts.
                if (tool == "message_agent") {
                    val target = p.optString("target", p.optString("to", ""))
                    _items.value = _items.value +
                        TextMessage("n-${UUID.randomUUID()}", "notice", "Messaging $target…", System.currentTimeMillis())
                } else {
                    _items.value = _items.value + ToolCardItem(
                        id = p.optString("id", "t-${UUID.randomUUID()}"),
                        tool = tool,
                        command = p.optString("command", p.optString("input", "")).take(400),
                    )
                }
            }
            "tool.complete" -> {
                val p = ev.payload
                val id = p.optString("id", "")
                val output = p.optString("output", p.optString("result", "")).take(4000)
                val tool = p.optString("tool", "")
                _items.value = _items.value.map { item ->
                    if (item is ToolCardItem && (item.id == id || id.isEmpty())) {
                        val todos = if (tool == "todo" || item.tool == "todo") parseTodos(output) else item.todos
                        // Fold message_agent completion into a notice.
                        if (item.tool == "message_agent") {
                            TextMessage(item.id, "notice", "Messaged ${p.optString("target", "")}", System.currentTimeMillis())
                        } else {
                            item.copy(done = true, output = output, todos = todos)
                        }
                    } else item
                }
            }
            "message.complete" -> finalizeStream(interrupted = false)
            "session.usage" -> {
                val p = ev.payload
                usageBefore = p.optLong("output_tokens", p.optLong("outputTokens", -1))
                val stats = statsLine(p)
                // Attach stats to the last assistant bubble if already finalised.
                _items.value = _items.value.mapIndexed { i, item ->
                    if (item is TextMessage && item.role == "assistant" && i == _items.value.lastIndex) {
                        item.copy(stats = stats)
                    } else item
                }
            }
            "turn.error", "message.error" ->
                finalizeStream(interrupted = true, error = ev.payload.optString("message", "turn failed"))
            "card.pending" -> Unit // repo.pendingCards flow drives the UI
            else -> Unit // unknown types ignored, never crash
        }
    }

    private fun finalizeStream(interrupted: Boolean, error: String? = null) {
        val text = _streamText.value
        _streamText.value = ""
        _sending.value = false
        if (text.isNotEmpty() || error != null) {
            val stats = if (!interrupted) estimatedStats(text) else null
            _items.value = _items.value + asChatItem(
                id = "a-${UUID.randomUUID()}",
                role = "assistant",
                text = if (error != null) "⚠ $error" else text,
                at = System.currentTimeMillis(),
            ).let { if (it is TextMessage) it.copy(stats = stats) else it }
        }
    }

    /** "tokens · tok/s · seconds" — exact when session.usage gave an output count. */
    private fun statsLine(p: JSONObject): String {
        val out = p.optLong("output_tokens", p.optLong("outputTokens", -1))
        val tps = p.optDouble("tokens_per_second", p.optDouble("tokensPerSecond", Double.NaN))
        val secs = p.optDouble(
            "duration_seconds",
            p.optLong("duration_ms", -1).let { if (it >= 0) it / 1000.0 else Double.NaN },
        )
        val tok = if (out >= 0) formatTok(out) else "~${formatTok(0)}"
        val rate = if (!tps.isNaN()) "%.0f tok/s".format(tps) else "—"
        val dur = if (!secs.isNaN()) "%.1fs".format(secs) else "—"
        return "$tok · $rate · $dur"
    }

    private fun estimatedStats(text: String): String {
        val est = text.length / 4
        val secs = (System.currentTimeMillis() - turnStartMs) / 1000.0
        val rate = if (secs > 0) est / secs else 0.0
        return "~${formatTok(est.toLong())} · %.0f tok/s · %.1fs".format(rate, secs)
    }

    private fun formatTok(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> "$n"
    }

    /** `todo` tool output → checklist entries (best-effort parse). */
    private fun parseTodos(output: String): List<TodoEntry> {
        return output.lines().mapNotNull { line ->
            val t = line.trim()
            when {
                t.startsWith("[x]", ignoreCase = true) -> TodoEntry(t.drop(3).trim(), true)
                t.startsWith("[ ]") -> TodoEntry(t.drop(3).trim(), false)
                t.startsWith("- [x]", ignoreCase = true) -> TodoEntry(t.drop(5).trim(), true)
                t.startsWith("- [ ]") -> TodoEntry(t.drop(5).trim(), false)
                else -> null
            }
        }.take(50)
    }

    // ------------------------------------------------------------------ //
    // Slash commands
    // ------------------------------------------------------------------ //

    private fun loadSlashCatalog() {
        viewModelScope.launch {
            _slashCatalog.value = try {
                val res = repo.rpc("commands.catalog")
                val arr = res.optJSONArray("commands") ?: res.optJSONArray("items")
                if (arr != null) List(arr.length()) {
                    val o = arr.optJSONObject(it)
                    (o?.optString("name", "") ?: arr.optString(it, "")).trim()
                }.filter { it.isNotEmpty() } else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun slashSuggestions(word: String): List<String> {
        if (!word.startsWith("/")) return emptyList()
        val q = word.drop(1)
        val local = listOf("approve", "deny", "stop", "new", "title", "model", "reasoning")
        return (local + _slashCatalog.value.map { it.removePrefix("/") })
            .filter { it.startsWith(q) }.distinct().take(8).map { "/$it" }
    }

    private fun handleSlash(text: String) {
        val parts = text.split(" ", limit = 2)
        val cmd = parts[0].removePrefix("/")
        val arg = parts.getOrElse(1) { "" }
        when (cmd) {
            "stop" -> stop()
            "new" -> newChat()
            "approve", "deny" -> answerLatestCard(if (cmd == "approve") "once" else "deny")
            "title" -> setTitle(arg)
            "model" -> _notice.value = "model-picker" // UI opens the picker sheet
            "reasoning" -> setReasoning(arg)
            else -> runCatalogCommand(text)
        }
    }

    private fun newChat() {
        _sessionId.value = null
        _items.value = emptyList()
        _streamText.value = ""
        _title.value = "New chat"
        _notice.value = null
    }

    private fun answerLatestCard(choice: String) {
        val card = sessionCards.value.lastOrNull()
        if (card == null) {
            _notice.value = "No pending approval in this chat."
            return
        }
        viewModelScope.launch {
            repo.answerCard(card, JSONObject().put("choice", choice))
        }
    }

    private fun setTitle(arg: String) {
        val sid = _sessionId.value ?: return
        viewModelScope.launch {
            try {
                repo.rpc("session.title", JSONObject().put("session_id", sid).put("title", arg))
                _title.value = arg.ifEmpty { _title.value }
            } catch (e: RpcException) {
                _notice.value = if (e.featureMissing) "/title not supported by this gateway" else e.message
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    private fun setReasoning(arg: String) {
        viewModelScope.launch {
            try {
                repo.rpc("config.set", JSONObject().put("reasoning", arg.ifEmpty { "medium" }))
                _notice.value = "Reasoning effort: ${arg.ifEmpty { "medium" }}"
            } catch (e: RpcException) {
                _notice.value = if (e.featureMissing) "/reasoning not supported by this gateway" else e.message
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    private fun runCatalogCommand(text: String) {
        viewModelScope.launch {
            try {
                val sid = ensureSession()
                val params = JSONObject().put("command", text).put("session_id", sid)
                try {
                    repo.rpc("slash.exec", params)
                } catch (e: RpcException) {
                    if (e.featureMissing) repo.rpc("command.dispatch", params)
                    else throw e
                }
            } catch (e: Exception) {
                _notice.value = e.message
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Model picker
    // ------------------------------------------------------------------ //

    fun loadModelOptions() {
        viewModelScope.launch {
            _modelOptions.value = try {
                val o = repo.rest?.get("/api/model/options", profile = profile) ?: JSONObject()
                parseModelOptions(o)
            } catch (e: Exception) {
                _notice.value = e.message
                emptyList()
            }
        }
    }

    private fun parseModelOptions(o: JSONObject): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        val providers = o.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val p = providers.getJSONObject(i)
                val slug = p.optString("slug", p.optString("name", ""))
                val models = p.optJSONArray("models") ?: continue
                for (j in 0 until models.length()) {
                    out += ModelOption(slug, models.optJSONObject(j)?.optString("id", "") ?: models.optString(j, ""))
                }
            }
            return out.filter { it.model.isNotEmpty() }
        }
        val models = o.optJSONArray("models")
        if (models != null) {
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i)
                if (m != null) out += ModelOption(m.optString("provider", ""), m.optString("id", m.optString("name", "")))
                else out += ModelOption("", models.optString(i, ""))
            }
        }
        return out.filter { it.model.isNotEmpty() }
    }

    /** Mid-chat model change: session-scoped config.set, never model.default. */
    fun selectModel(option: ModelOption) {
        viewModelScope.launch {
            try {
                val sid = ensureSession()
                repo.rpc(
                    "config.set",
                    JSONObject().put("model", "${option.model} --provider ${option.provider} --session"),
                )
                _notice.value = "Model: ${option.model} (this chat)"
            } catch (e: Exception) {
                _notice.value = e.message
            }
            Unit
        }
    }

    // ------------------------------------------------------------------ //
    // Attachments → @file: refs
    // ------------------------------------------------------------------ //

    fun attach(context: Context, uri: Uri, mime: String?) {
        viewModelScope.launch {
            _sending.value = true
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("cannot read file")
                if (bytes.size > 12 * 1024 * 1024) throw Exception("file too large (12 MB max)")
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val dataUrl = "data:${mime ?: "application/octet-stream"};base64,$b64"
                val method = when {
                    (mime ?: "").startsWith("image/") -> "image.attach_bytes"
                    mime == "application/pdf" -> "pdf.attach"
                    else -> "file.attach"
                }
                val res = repo.rpc(method, JSONObject().put("data_url", dataUrl))
                val ref = res.optString("ref", res.optString("file_ref", res.optString("reference", "")))
                if (ref.isEmpty()) throw Exception("gateway returned no @file: reference")
                _pendingRefs.value = _pendingRefs.value + ref
                _notice.value = "Attached $ref"
            } catch (e: Exception) {
                _notice.value = "Attach failed: ${e.message}"
            } finally {
                _sending.value = false
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Card answers from the UI
    // ------------------------------------------------------------------ //

    fun answerCard(card: PendingCard, payload: JSONObject) {
        viewModelScope.launch { repo.answerCard(card, payload) }
    }

    fun cardShape(card: PendingCard) = repo.answerShape(card)

    class Factory(
        private val repo: AppRepository,
        private val sessionId: String?,
        private val profile: String,
        private val draft: String = "",
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repo, sessionId, profile, draft) as T
    }
}
