package dev.vory.android.data

import org.json.JSONObject

/** Auth modes supported by the Hermes dashboard (see PROTOCOL.md §2). */
enum class AuthMode { SESSION_TOKEN, USERNAME_PASSWORD, BROWSER_OIDC }

/**
 * A saved gateway. Tokens themselves live in EncryptedSharedPreferences —
 * this record only carries references/flags so DataStore JSON never holds a secret.
 */
data class Gateway(
    val id: String,
    val name: String,
    /** Normalised: no trailing slash, no /api/* suffix. */
    val baseUrl: String,
    val authMode: AuthMode,
    /** Username for USERNAME_PASSWORD mode (password is never stored). */
    val username: String = "",
    /** True when this gateway sits behind Cloudflare Access (id/secret in secure storage). */
    val hasCloudflareAccess: Boolean = false,
    /** True when at least one credential is present in secure storage. */
    val hasCredential: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("authMode", authMode.name)
        .put("username", username)
        .put("hasCloudflareAccess", hasCloudflareAccess)
        .put("hasCredential", hasCredential)

    companion object {
        fun fromJson(o: JSONObject): Gateway = Gateway(
            id = o.getString("id"),
            name = o.optString("name", "Gateway"),
            baseUrl = o.getString("baseUrl"),
            authMode = runCatching { AuthMode.valueOf(o.optString("authMode", "SESSION_TOKEN")) }
                .getOrDefault(AuthMode.SESSION_TOKEN),
            username = o.optString("username", ""),
            hasCloudflareAccess = o.optBoolean("hasCloudflareAccess", false),
            hasCredential = o.optBoolean("hasCredential", false),
        )
    }
}

/** One bot profile from GET /api/profiles. */
data class BotProfile(
    val name: String,
    val description: String = "",
    val model: String = "",
    val active: Boolean = false,
    /** Per-bot accent colour (device-only, stored in DataStore). Null = default Samsung blue. */
    val colorArgb: Int? = null,
    /** Code-drawn face seed: which body/eyes/finish variant this bot draws with. */
    val faceSeed: Int = 0,
)

/** One chat session row from GET /api/sessions?order=recent. */
data class ChatSession(
    val id: String,
    val title: String,
    val profile: String = "",
    val project: String = "",
    val updatedAt: Long = 0L,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** True while a card (approval/clarify/secret) is waiting on the user. */
    val needsYou: Boolean = false,
    val lastPreview: String = "",
)

/** A message or card inside the transcript. */
sealed interface ChatItem {
    val key: String
}

data class TextMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system" | "notice"
    val text: String,
    val at: Long = 0L,
    val replyTo: String? = null,
    /** Per-turn stats line: "1.2k · 34 tok/s · 12.3s". Null while streaming. */
    val stats: String? = null,
    val streaming: Boolean = false,
) : ChatItem { override val key: String get() = id }

data class ToolCardItem(
    val id: String,
    val tool: String,
    val command: String = "",
    val output: String = "",
    val done: Boolean = false,
    /** todo tool → checklist entries. */
    val todos: List<TodoEntry> = emptyList(),
) : ChatItem { override val key: String get() = id }

data class TodoEntry(val title: String, val done: Boolean)

/** A gateway→client JSON-RPC request rendered as an answerable card. */
data class PendingCard(
    val requestId: String,
    val kind: CardKind,
    val sessionId: String,
    val title: String,
    val detail: String,
    val secret: Boolean = false,
)

enum class CardKind { APPROVAL, CLARIFY, SUDO, SECRET, VAULT }

/** Analytics overview from GET /api/analytics/usage?days=. */
data class UsageOverview(
    val sessions: Long = 0,
    val messages: Long = 0,
    val tokens: Long = 0,
    val activeDays: Long = 0,
    val peakHour: String = "",
    val topModel: String = "",
    val costEstimate: String = "",
    /** 13 weeks of activity blocks (7 ints per week, 0..4 intensity). */
    val weeks: List<List<Int>> = emptyList(),
)

/** A file entry from GET /api/files. */
data class RemoteFile(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
)

/** A cron job row from GET /api/cron/jobs. */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String = "",
    val enabled: Boolean = true,
    val lastRun: String = "",
)

/** Model option row from GET /api/model/options (grouped by provider client-side). */
data class ModelOption(val provider: String, val model: String)

/** Connection-test legs for the setup wizard. */
enum class TestLeg { STATUS, AUTH, SOCKET }

data class LegResult(val leg: TestLeg, val ok: Boolean, val detail: String)

/** Bot liveliness from socket turn/phase events. */
enum class BotMood { IDLE, WORKING, WAITING, ERROR }

/** Thrown when the gateway answers a JSON-RPC request with an error object. */
class RpcException(val code: Int, message: String) : Exception(message) {
    val featureMissing: Boolean get() = code == -32601
}

/** Thrown when an HTTP call returns HTML (login page / proxy) instead of JSON. */
class HtmlInsteadOfJsonException(val url: String) :
    Exception("Expected the dashboard API but got an HTML page — wrong URL, login page or proxy in front.")
