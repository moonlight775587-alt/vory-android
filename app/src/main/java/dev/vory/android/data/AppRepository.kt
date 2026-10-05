package dev.vory.android.data

import android.content.Context
import dev.vory.android.notifications.VoryNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Session-scoped socket events forwarded to whichever chat screen is open. */
sealed interface SessionBusEvent {
    data class Rpc(val sessionId: String, val event: SocketEvent.RpcEvent) : SessionBusEvent
}

/** How a PendingCard expects its answer. */
enum class AnswerShape { CHOICE, TEXT, SECRET }

/**
 * App-scoped repository: owns the active gateway's REST + socket clients,
 * profile selection, the pending-card registry, and the 503 restart probe.
 *
 * Chat screens observe [sessionBus]; the card registry drives approval cards
 * and background notifications.
 */
class AppRepository(
    private val context: Context,
    val store: GatewayStore,
    val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications = VoryNotifications(context)

    private val _activeGateway = MutableStateFlow<Gateway?>(null)
    val activeGateway: StateFlow<Gateway?> = _activeGateway.asStateFlow()

    private val _profiles = MutableStateFlow<List<BotProfile>>(emptyList())
    val profiles: StateFlow<List<BotProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow("")
    val activeProfile: StateFlow<String> = _activeProfile.asStateFlow()

    private val _connState = MutableStateFlow(HermesSocketClient.ConnState.DISCONNECTED)
    val connState: StateFlow<HermesSocketClient.ConnState> = _connState.asStateFlow()

    /** True when /api/model/options answers 503 "Restart required". */
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart.asStateFlow()

    private val _pendingCards = MutableStateFlow<List<PendingCard>>(emptyList())
    val pendingCards: StateFlow<List<PendingCard>> = _pendingCards.asStateFlow()

    private val _sessionBus = MutableSharedFlow<SessionBusEvent>(extraBufferCapacity = 512)
    val sessionBus: SharedFlow<SessionBusEvent> = _sessionBus.asSharedFlow()

    private val _botMoods = MutableStateFlow<Map<String, BotMood>>(emptyMap())
    val botMoods: StateFlow<Map<String, BotMood>> = _botMoods.asStateFlow()

    var rest: HermesRestClient? = null
        private set
    var socket: HermesSocketClient? = null
        private set

    /** app backgrounded? set by VoryApp via ProcessLifecycleOwner. */
    var backgrounded: Boolean = false
        set(value) {
            field = value
            if (value) socket?.appBackgrounded() else socket?.appForeground()
        }

    init {
        scope.launch {
            val id = store.activeGatewayIdFlow.first()
            val gw = id?.let { store.gateways().firstOrNull { g -> g.id == it } }
            if (gw != null) selectGateway(gw, reconnect = true)
            _activeProfile.value = store.activeProfileFlow.first()
        }
    }

    // ------------------------------------------------------------------ //
    // Gateway lifecycle
    // ------------------------------------------------------------------ //

    fun selectGateway(gw: Gateway, reconnect: Boolean = true) {
        socket?.release()
        _activeGateway.value = gw
        rest = HermesRestClient(http, gw.baseUrl, store, gw.id, gw.authMode)
        scope.launch {
            store.setActiveGateway(gw.id)
            loadProfiles()
            probeRestartRequired()
            if (reconnect) connectSocket()
        }
    }

    fun connectSocket() {
        val gw = _activeGateway.value ?: return
        val r = rest ?: return
        socket?.release()
        val s = HermesSocketClient(http, r, store, gw)
        socket = s
        scope.launch { s.connectionState.collect { _connState.value = it } }
        scope.launch {
            s.events.collect { ev ->
                when (ev) {
                    is SocketEvent.GatewayReady -> {
                        _connState.value = HermesSocketClient.ConnState.READY
                        probeRestartRequired()
                    }
                    is SocketEvent.RpcEvent -> {
                        _sessionBus.emit(SessionBusEvent.Rpc(ev.sessionId, ev))
                        updateMoodFromEvent(ev)
                        maybeNotifyBackground(ev)
                    }
                    is SocketEvent.ConnectionLost ->
                        _connState.value = HermesSocketClient.ConnState.DISCONNECTED
                    SocketEvent.Reconnected -> Unit
                }
            }
        }
        scope.launch {
            s.serverRequests.collect { req -> onServerRequest(req) }
        }
        s.connect()
    }

    fun disconnectSocket() {
        socket?.release()
        socket = null
        _connState.value = HermesSocketClient.ConnState.DISCONNECTED
    }

    // ------------------------------------------------------------------ //
    // Profiles
    // ------------------------------------------------------------------ //

    suspend fun loadProfiles() {
        val r = rest ?: return
        runCatching {
            val arr = r.getArray("/api/profiles")
            val colors = store.botColorsFlow.first()
            _profiles.value = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "bot")
                BotProfile(
                    name = name,
                    description = o.optString("description", ""),
                    model = o.optString("model", ""),
                    colorArgb = colors[name],
                    faceSeed = name.hashCode(),
                )
            }
            val active = runCatching { r.get("/api/profiles/active").optString("name", "") }.getOrDefault("")
            if (active.isNotEmpty() && _activeProfile.value.isEmpty()) setActiveProfile(active)
            else if (_activeProfile.value.isEmpty() && _profiles.value.isNotEmpty()) {
                setActiveProfile(_profiles.value.first().name)
            }
        }
    }

    suspend fun setActiveProfile(name: String) {
        _activeProfile.value = name
        store.setActiveProfile(name)
        probeRestartRequired()
    }

    private fun profileParam(): String = _activeProfile.value

    // ------------------------------------------------------------------ //
    // Pending cards (approval / clarify / sudo / secret / vault)
    // ------------------------------------------------------------------ //

    private fun onServerRequest(req: ServerRequest) {
        val kind = when {
            req.method.startsWith("approval") -> CardKind.APPROVAL
            req.method.startsWith("clarify") -> CardKind.CLARIFY
            req.method.startsWith("sudo") -> CardKind.SUDO
            req.method.startsWith("secret") || req.method.startsWith("vault") -> CardKind.SECRET
            else -> return // unknown server request: ignore, never crash
        }
        val p = req.params
        val card = PendingCard(
            requestId = req.id,
            kind = kind,
            sessionId = p.optString("session_id", ""),
            title = p.optString("title", p.optString("command", req.method)),
            detail = p.optString("detail", p.optString("command", p.optString("prompt", ""))),
            secret = kind == CardKind.SECRET,
        )
        _pendingCards.value = _pendingCards.value + card
        updateMood(card.sessionId.ifEmpty { profileParam() }, BotMood.WAITING)
        scope.launch {
            if (backgrounded && store.notifApprovalsFlow.first()) {
                notifications.showApproval(card)
            }
        }
        scope.launch {
            _sessionBus.emit(
                SessionBusEvent.Rpc(
                    card.sessionId,
                    SocketEvent.RpcEvent("card.pending", card.sessionId, JSONObject(), 0L),
                ),
            )
        }
    }

    /** Answer a card. [payload] is the full result object (never logged for secrets). */
    suspend fun answerCard(card: PendingCard, payload: JSONObject) {
        val s = socket
        if (s != null && s.isReady) {
            runCatching { s.respond(card.requestId, payload) }
                .onFailure {
                    // Fallback for queued approvals: approval.respond.
                    if (card.kind == CardKind.APPROVAL) {
                        val choice = payload.optString("choice", "deny")
                        runCatching { s.approvalRespond(card.requestId, choice) }
                    }
                }
        }
        _pendingCards.value = _pendingCards.value.filterNot { it.requestId == card.requestId }
        updateMood(card.sessionId.ifEmpty { profileParam() }, BotMood.WORKING)
        notifications.dismissApproval(card.requestId.hashCode())
    }

    fun answerShape(card: PendingCard): AnswerShape = when (card.kind) {
        CardKind.APPROVAL, CardKind.SUDO -> AnswerShape.CHOICE
        CardKind.CLARIFY -> AnswerShape.TEXT
        CardKind.SECRET, CardKind.VAULT -> AnswerShape.SECRET
    }

    fun pendingCard(requestId: String): PendingCard? =
        _pendingCards.value.firstOrNull { it.requestId == requestId }

    // ------------------------------------------------------------------ //
    // Bot moods from turn/phase events
    // ------------------------------------------------------------------ //

    private fun updateMood(key: String, mood: BotMood) {
        if (key.isEmpty()) return
        _botMoods.value = _botMoods.value + (key to mood)
    }

    private fun updateMoodFromEvent(ev: SocketEvent.RpcEvent) {
        val key = ev.sessionId.ifEmpty { return }
        when (ev.type) {
            "message.delta", "tool.start", "turn.start" -> updateMood(key, BotMood.WORKING)
            "message.complete", "turn.complete" -> updateMood(key, BotMood.IDLE)
            "turn.error", "message.error" -> updateMood(key, BotMood.ERROR)
        }
    }

    // ------------------------------------------------------------------ //
    // Background notifications for chat activity
    // ------------------------------------------------------------------ //

    private suspend fun maybeNotifyBackground(ev: SocketEvent.RpcEvent) {
        if (!backgrounded) return
        when (ev.type) {
            "message.complete" -> {
                if (store.notifTurnDoneFlow.first()) {
                    notifications.showTurnDone(ev.sessionId, ev.payload.optString("text", "").take(120))
                }
                updateMood(ev.sessionId, BotMood.IDLE)
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 503 "Restart required" probe (PROTOCOL.md §4)
    // ------------------------------------------------------------------ //

    /** Probe GET /api/model/options; 503 → show the restart banner. */
    suspend fun probeRestartRequired() {
        val r = rest ?: return
        _needsRestart.value = try {
            r.get("/api/model/options", profileParam())
            false
        } catch (e: RpcException) {
            e.code == 503 || e.message?.contains("Restart required", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun updateHermes(): JSONObject? {
        val r = rest ?: return null
        return runCatching { r.post("/api/hermes/update", profileParam()) }.getOrNull()
    }

    suspend fun restartGateway(): JSONObject? {
        val r = rest ?: return null
        return runCatching { r.post("/api/gateway/restart", profileParam()) }.getOrNull()
    }

    // ------------------------------------------------------------------ //
    // Chat RPC helpers (used by ChatViewModel)
    // ------------------------------------------------------------------ //

    suspend fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val s = socket ?: throw RpcException(-32001, "socket not connected")
        return s.request(method, params)
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // streaming socket
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
