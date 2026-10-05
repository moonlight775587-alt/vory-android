package dev.vory.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Events pushed by the gateway over /api/ws (PROTOCOL.md §5). */
sealed interface SocketEvent {
    data object GatewayReady : SocketEvent
    data class RpcEvent(
        val type: String,
        val sessionId: String,
        val payload: JSONObject,
        val seq: Long,
    ) : SocketEvent
    data class ConnectionLost(val reason: String) : SocketEvent
    data object Reconnected : SocketEvent
}

/** A gateway→client JSON-RPC request (approvals, clarify, sudo, secret, vault.*). */
data class ServerRequest(
    val id: String,
    val method: String,
    val params: JSONObject,
)

/**
 * JSON-RPC 2.0 client over `ws(s)://<base>/api/ws`.
 *
 * - Client→server requests use incrementing integer ids; an id→continuation map
 *   matches responses (120 s timeout).
 * - Server→client requests carry string ids and are emitted on [serverRequests];
 *   answer them with [respond] on the same id.
 * - Server→client notifications (`method == "event"`) go to [events].
 * - Unknown event types are ignored, never crash.
 * - Reconnect uses exponential backoff (1s, 2s, 4s … max 30s); aggressive retry
 *   stops while backgrounded (Doze) and resumes on [appForeground].
 */
class HermesSocketClient(
    private val http: OkHttpClient,
    private val rest: HermesRestClient,
    private val store: GatewayStore,
    private val gateway: Gateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idSeq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Any, kotlinx.coroutines.CompletableDeferred<JSONObject>>()
    private val pendingMutex = Mutex()

    private val _events = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SocketEvent> = _events.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<ServerRequest>(extraBufferCapacity = 64)
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    private val _connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnState.DISCONNECTED)
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnState> = _connectionState

    private var ws: WebSocket? = null
    private var closedByUser = false
    private var backgrounded = false
    private var reconnectJob: Job? = null
    private var backoffSec = 1L

    enum class ConnState { DISCONNECTED, CONNECTING, READY }

    val isReady: Boolean get() = _connectionState.value == ConnState.READY

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    fun connect() {
        closedByUser = false
        scope.launch { openSocket() }
    }

    fun disconnect() {
        closedByUser = true
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _connectionState.value = ConnState.DISCONNECTED
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    /** Doze: stop aggressive retry while backgrounded; socket may die quietly. */
    fun appBackgrounded() {
        backgrounded = true
        reconnectJob?.cancel()
    }

    /** User is back: reconnect now if not ready. */
    fun appForeground() {
        backgrounded = false
        if (!closedByUser && _connectionState.value != ConnState.READY) {
            backoffSec = 1L
            scope.launch { openSocket() }
        }
    }

    // ------------------------------------------------------------------ //
    // JSON-RPC
    // ------------------------------------------------------------------ //

    /**
     * Send a client→server request and await the matching response (120 s).
     * @throws RpcException on error responses, TimeoutCancellationException on timeout.
     */
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = idSeq.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            val msg = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
                .toString()
            val ok = ws?.send(msg) ?: false
            if (!ok) throw RpcException(-32001, "socket not connected")
            return withTimeout(120_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Fire-and-forget notification (no id). */
    fun notify(method: String, params: JSONObject = JSONObject()) {
        val msg = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
        ws?.send(msg)
    }

    /**
     * Answer a gateway→client request (approval.request etc.) on the same id:
     * {"jsonrpc":"2.0","id":"<string>","result":{...}}
     */
    fun respond(requestId: String, result: JSONObject) {
        val msg = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", requestId)
            .put("result", result)
            .toString()
        ws?.send(msg)
    }

    /** Queued approvals may instead be answered with the approval.respond method. */
    suspend fun approvalRespond(approvalId: String, choice: String): JSONObject =
        request("approval.respond", JSONObject().put("id", approvalId).put("choice", choice))

    // ------------------------------------------------------------------ //
    // Socket plumbing
    // ------------------------------------------------------------------ //

    private suspend fun wsUrl(): String {
        val base = rest.baseUrl
            .replaceFirst(Regex("^http"), "ws")
        val token = store.getSecret(gateway.id, "session_token")
        return if (gateway.authMode == AuthMode.SESSION_TOKEN && !token.isNullOrEmpty()) {
            "$base/api/ws?token=$token"
        } else {
            // Re-mint a ws ticket on every attempt for bearer auth.
            val ticket = rest.wsTicket()
            if (!ticket.isNullOrEmpty()) "$base/api/ws?ticket=$ticket" else "$base/api/ws"
        }
    }

    private suspend fun openSocket() {
        if (closedByUser) return
        pendingMutex.withLock {
            if (_connectionState.value == ConnState.CONNECTING) return
            _connectionState.value = ConnState.CONNECTING
        }
        val url = try { wsUrl() } catch (e: Exception) {
            scheduleReconnect("ticket mint failed: ${e.message}")
            return
        }
        val reqBuilder = Request.Builder().url(url)
        // Cloudflare Access headers on the WebSocket upgrade too (PROTOCOL.md §2).
        val cfId = store.getSecret(gateway.id, "cf_id")
        val cfSecret = store.getSecret(gateway.id, "cf_secret")
        if (!cfId.isNullOrEmpty() && !cfSecret.isNullOrEmpty()) {
            reqBuilder.header("CF-Access-Client-Id", cfId)
            reqBuilder.header("CF-Access-Client-Secret", cfSecret)
        }
        http.newWebSocket(reqBuilder.build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            backoffSec = 1L
            scope.launch { _events.emit(SocketEvent.Reconnected) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch { handleMessage(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ws = null
            _connectionState.value = ConnState.DISCONNECTED
            scope.launch { _events.emit(SocketEvent.ConnectionLost(t.message ?: "socket failed")) }
            scheduleReconnect(t.message ?: "socket failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ws = null
            if (!closedByUser) {
                _connectionState.value = ConnState.DISCONNECTED
                scope.launch { _events.emit(SocketEvent.ConnectionLost("closed: $reason")) }
                scheduleReconnect("closed: $reason")
            } else {
                _connectionState.value = ConnState.DISCONNECTED
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (closedByUser) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Doze: no aggressive retry while backgrounded — reconnect on user return.
            if (backgrounded) return@launch
            val wait = backoffSec
            backoffSec = min(backoffSec * 2, 30L)
            store.logRedacted("ws reconnect in ${wait}s ($reason)")
            delay(wait * 1000)
            openSocket()
        }
    }

    private suspend fun handleMessage(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (o.optString("jsonrpc") != "2.0") return

        // 1. Response to one of our requests (id present, method absent).
        if (o.has("id") && !o.has("method")) {
            val id: Any = if (o.optInt("id", Int.MIN_VALUE) != Int.MIN_VALUE && !o.isNull("id")) {
                o.optInt("id")
            } else {
                o.optString("id")
            }
            val deferred = pending[id] ?: pending[id.toString()]
            if (deferred != null) {
                if (o.has("error")) {
                    val err = o.getJSONObject("error")
                    deferred.completeExceptionally(
                        RpcException(err.optInt("code", -32000), err.optString("message", "RPC error")),
                    )
                } else {
                    deferred.complete(o.optJSONObject("result") ?: JSONObject())
                }
            }
            return
        }

        // 2. Server → client notification: method == "event".
        val method = o.optString("method", "")
        if (method == "event") {
            val params = o.optJSONObject("params") ?: JSONObject()
            val type = params.optString("type", "")
            if (type == "gateway.ready") {
                _connectionState.value = ConnState.READY
                _events.emit(SocketEvent.GatewayReady)
                return
            }
            _events.emit(
                SocketEvent.RpcEvent(
                    type = type,
                    sessionId = params.optString("session_id", ""),
                    payload = params.optJSONObject("payload") ?: JSONObject(),
                    seq = params.optLong("seq", 0L),
                ),
            )
            return
        }

        // 3. Server → client request (approval.request, clarify, sudo, secret, vault.*).
        if (o.has("id") && method.isNotEmpty()) {
            _serverRequests.emit(
                ServerRequest(
                    id = o.optString("id"),
                    method = method,
                    params = o.optJSONObject("params") ?: JSONObject(),
                ),
            )
        }
        // Unknown shapes are ignored, never crash.
    }
}
