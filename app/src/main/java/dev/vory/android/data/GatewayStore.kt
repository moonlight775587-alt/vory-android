package dev.vory.android.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

private val Context.voryPrefs: DataStore<Preferences> by preferencesDataStore(name = "vory_prefs")

/**
 * Gateway + app preferences store.
 *
 * Secrets (session tokens, OAuth access/refresh tokens, Cloudflare Access
 * id/secret) live in EncryptedSharedPreferences and are never written to the
 * DataStore JSON, never logged, and never leave the device.
 */
class GatewayStore(private val appContext: Context) {

    companion object {
        private const val TAG = "VoryStore"
        private val K_GATEWAYS = stringPreferencesKey("gateways_json")
        private val K_ACTIVE_GW = stringPreferencesKey("active_gateway_id")
        private val K_PROFILE = stringPreferencesKey("active_profile")
        private val K_HOME_LAYOUT = stringPreferencesKey("home_layout_json")
        private val K_ACCENT = stringPreferencesKey("accent_argb")
        private val K_DARK_MODE = stringPreferencesKey("dark_mode") // system|dark|light
        private val K_HOME_TAB = stringPreferencesKey("start_tab")
        private val K_BOT_COLORS = stringPreferencesKey("bot_colors_json")
        private val K_FACE_MOTION = stringPreferencesKey("face_motion") // lively|calm|still
        private val K_CHAT_TOOL_CARDS = booleanPreferencesKey("chat_tool_cards")
        private val K_CHAT_STATS = booleanPreferencesKey("chat_stats")
        private val K_CHAT_REASONING = booleanPreferencesKey("chat_reasoning")
        private val K_NOTIF_TURN_DONE = booleanPreferencesKey("notif_turn_done")
        private val K_NOTIF_APPROVALS = booleanPreferencesKey("notif_approvals")
    }

    private val prefs: DataStore<Preferences> get() = appContext.voryPrefs

    private val secrets: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "vory_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ------------------------------------------------------------------ //
    // Gateways
    // ------------------------------------------------------------------ //

    val gatewaysFlow: Flow<List<Gateway>> = prefs.data.map { p ->
        val raw = p[K_GATEWAYS] ?: return@map emptyList()
        runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { Gateway.fromJson(arr.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    val activeGatewayIdFlow: Flow<String?> = prefs.data.map { it[K_ACTIVE_GW] }

    suspend fun gateways(): List<Gateway> = gatewaysFlow.first()

    suspend fun saveGateway(g: Gateway) {
        val list = gateways().toMutableList()
        val i = list.indexOfFirst { it.id == g.id }
        if (i >= 0) list[i] = g else list += g
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit { it[K_GATEWAYS] = arr.toString() }
    }

    suspend fun deleteGateway(id: String) {
        val list = gateways().filterNot { it.id == id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit {
            it[K_GATEWAYS] = arr.toString()
            if (it[K_ACTIVE_GW] == id) it.remove(K_ACTIVE_GW)
        }
        clearSecrets(id)
    }

    suspend fun setActiveGateway(id: String?) {
        prefs.edit { if (id == null) it.remove(K_ACTIVE_GW) else it[K_ACTIVE_GW] = id }
    }

    fun newGatewayId(): String = UUID.randomUUID().toString()

    // ------------------------------------------------------------------ //
    // Secrets (EncryptedSharedPreferences) — redacted everywhere else
    // ------------------------------------------------------------------ //

    private fun skey(gatewayId: String, name: String) = "gw_${gatewayId}_$name"

    fun putSecret(gatewayId: String, name: String, value: String) {
        secrets.edit().putString(skey(gatewayId, name), value).apply()
    }

    fun getSecret(gatewayId: String, name: String): String? =
        secrets.getString(skey(gatewayId, name), null)

    fun hasSecret(gatewayId: String, name: String): Boolean =
        !getSecret(gatewayId, name).isNullOrEmpty()

    fun clearSecrets(gatewayId: String) {
        val e = secrets.edit()
        listOf("session_token", "access_token", "refresh_token", "cf_id", "cf_secret")
            .forEach { e.remove(skey(gatewayId, it)) }
        e.apply()
    }

    /** Refresh the OAuth token pair; never logs values. */
    fun putOAuthTokens(gatewayId: String, access: String, refresh: String) {
        secrets.edit()
            .putString(skey(gatewayId, "access_token"), access)
            .putString(skey(gatewayId, "refresh_token"), refresh)
            .apply()
    }

    fun logRedacted(msg: String) = Log.d(TAG, redact(msg))

    /** Redact anything that looks like a token before it hits logcat. */
    fun redact(s: String): String {
        var out = s
        for (name in listOf("session_token", "access_token", "refresh_token", "cf_secret")) {
            // best-effort: never print stored values
        }
        return out
            .replace(Regex("(?i)(token[\"'=: ]+)([^\"'&,\\s}]{6,})"), "$1•••")
            .replace(Regex("(?i)(CF-Access-Client-Secret[\"': ]+)([^\"'&,\\s}]{4,})"), "$1•••")
    }

    // ------------------------------------------------------------------ //
    // Misc preferences
    // ------------------------------------------------------------------ //

    val activeProfileFlow: Flow<String> = prefs.data.map { it[K_PROFILE] ?: "" }
    suspend fun setActiveProfile(name: String) { prefs.edit { it[K_PROFILE] = name } }

    val accentFlow: Flow<Int?> = prefs.data.map { it[K_ACCENT]?.toIntOrNull() }
    suspend fun setAccent(argb: Int?) { prefs.edit { if (argb == null) it.remove(K_ACCENT) else it[K_ACCENT] = argb.toString() } }

    val darkModeFlow: Flow<String> = prefs.data.map { it[K_DARK_MODE] ?: "system" }
    suspend fun setDarkMode(v: String) { prefs.edit { it[K_DARK_MODE] = v } }

    val startTabFlow: Flow<String> = prefs.data.map { it[K_HOME_TAB] ?: "home" }
    suspend fun setStartTab(v: String) { prefs.edit { it[K_HOME_TAB] = v } }

    val faceMotionFlow: Flow<String> = prefs.data.map { it[K_FACE_MOTION] ?: "lively" }
    suspend fun setFaceMotion(v: String) { prefs.edit { it[K_FACE_MOTION] = v } }

    val homeLayoutFlow: Flow<List<String>> = prefs.data.map { p ->
        val raw = p[K_HOME_LAYOUT]
        if (raw.isNullOrEmpty()) defaultHomeLayout()
        else runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrElse { defaultHomeLayout() }
    }
    suspend fun setHomeLayout(cards: List<String>) {
        val arr = JSONArray(); cards.forEach { arr.put(it) }
        prefs.edit { it[K_HOME_LAYOUT] = arr.toString() }
    }
    private fun defaultHomeLayout() = listOf("overview", "pickup", "bots", "activity")

    val botColorsFlow: Flow<Map<String, Int>> = prefs.data.map { p ->
        val raw = p[K_BOT_COLORS] ?: return@map emptyMap()
        runCatching {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associateWith { o.getInt(it) }
        }.getOrElse { emptyMap() }
    }
    suspend fun setBotColor(profile: String, argb: Int) {
        val cur = botColorsFlow.first().toMutableMap()
        cur[profile] = argb
        val o = org.json.JSONObject()
        cur.forEach { (k, v) -> o.put(k, v) }
        prefs.edit { it[K_BOT_COLORS] = o.toString() }
    }

    val showToolCardsFlow: Flow<Boolean> = prefs.data.map { it[K_CHAT_TOOL_CARDS] ?: true }
    suspend fun setShowToolCards(v: Boolean) { prefs.edit { it[K_CHAT_TOOL_CARDS] = v } }
    val showStatsFlow: Flow<Boolean> = prefs.data.map { it[K_CHAT_STATS] ?: true }
    suspend fun setShowStats(v: Boolean) { prefs.edit { it[K_CHAT_STATS] = v } }
    val showReasoningFlow: Flow<Boolean> = prefs.data.map { it[K_CHAT_REASONING] ?: true }
    suspend fun setShowReasoning(v: Boolean) { prefs.edit { it[K_CHAT_REASONING] = v } }

    val notifTurnDoneFlow: Flow<Boolean> = prefs.data.map { it[K_NOTIF_TURN_DONE] ?: true }
    suspend fun setNotifTurnDone(v: Boolean) { prefs.edit { it[K_NOTIF_TURN_DONE] = v } }
    val notifApprovalsFlow: Flow<Boolean> = prefs.data.map { it[K_NOTIF_APPROVALS] ?: true }
    suspend fun setNotifApprovals(v: Boolean) { prefs.edit { it[K_NOTIF_APPROVALS] = v } }
}
