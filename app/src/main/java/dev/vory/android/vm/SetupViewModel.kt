package dev.vory.android.vm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.vory.android.data.AppRepository
import dev.vory.android.data.AuthMode
import dev.vory.android.data.Gateway
import dev.vory.android.data.HermesRestClient
import dev.vory.android.data.HermesSocketClient
import dev.vory.android.data.LegResult
import dev.vory.android.data.SocketEvent
import dev.vory.android.data.TestLeg
import dev.vory.android.util.isPrivateHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom

private const val WIZARD_ID = "wizard-temp"

/**
 * First-launch / add-gateway wizard.
 *
 * Test Connection runs all 3 legs (PROTOCOL.md §3): GET /api/status JSON check,
 * credential acceptance via /api/auth/me, and wss /api/ws awaiting gateway.ready.
 * Save is enabled only when all three pass.
 */
class SetupViewModel(private val repo: AppRepository) : ViewModel() {

    private val store get() = repo.store

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()
    private val _authMode = MutableStateFlow(AuthMode.SESSION_TOKEN)
    val authMode: StateFlow<AuthMode> = _authMode.asStateFlow()
    private val _sessionToken = MutableStateFlow("")
    val sessionToken: StateFlow<String> = _sessionToken.asStateFlow()
    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    private val _cfId = MutableStateFlow("")
    val cfId: StateFlow<String> = _cfId.asStateFlow()
    private val _cfSecret = MutableStateFlow("")
    val cfSecret: StateFlow<String> = _cfSecret.asStateFlow()
    private val _showAdvanced = MutableStateFlow(false)
    val showAdvanced: StateFlow<Boolean> = _showAdvanced.asStateFlow()

    private val _legs = MutableStateFlow<List<LegResult>>(emptyList())
    val legs: StateFlow<List<LegResult>> = _legs.asStateFlow()
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()
    private val _canSave = MutableStateFlow(false)
    val canSave: StateFlow<Boolean> = _canSave.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _plainHttpWarning = MutableStateFlow(false)
    val plainHttpWarning: StateFlow<Boolean> = _plainHttpWarning.asStateFlow()

    private var testJob: Job? = null

    fun setName(v: String) { _name.value = v }
    fun setUrl(v: String) {
        _url.value = v
        _plainHttpWarning.value = v.trim().startsWith("http://") &&
            runCatching {
                val host = android.net.Uri.parse(v.trim()).host ?: ""
                !isPrivateHost(host)
            }.getOrDefault(true)
        _canSave.value = false
    }
    fun setAuthMode(v: AuthMode) { _authMode.value = v; _canSave.value = false }
    fun setSessionToken(v: String) { _sessionToken.value = v }
    fun setUsername(v: String) { _username.value = v }
    fun setPassword(v: String) { _password.value = v }
    fun setCfId(v: String) { _cfId.value = v }
    fun setCfSecret(v: String) { _cfSecret.value = v }
    fun toggleAdvanced() { _showAdvanced.value = !_showAdvanced.value }
    fun clearError() { _error.value = null }

    private fun tempGateway(): Gateway = Gateway(
        id = WIZARD_ID,
        name = _name.value.ifBlank { "Gateway" },
        baseUrl = HermesRestClient.normalizeUrl(_url.value),
        authMode = _authMode.value,
        username = _username.value,
        hasCloudflareAccess = _cfId.value.isNotBlank() && _cfSecret.value.isNotBlank(),
        hasCredential = true,
    )

    private fun stageSecrets() {
        store.clearSecrets(WIZARD_ID)
        when (_authMode.value) {
            AuthMode.SESSION_TOKEN ->
                if (_sessionToken.value.isNotBlank()) store.putSecret(WIZARD_ID, "session_token", _sessionToken.value.trim())
            AuthMode.USERNAME_PASSWORD, AuthMode.BROWSER_OIDC -> Unit // tokens arrive via flows below
        }
        if (_cfId.value.isNotBlank() && _cfSecret.value.isNotBlank()) {
            store.putSecret(WIZARD_ID, "cf_id", _cfId.value.trim())
            store.putSecret(WIZARD_ID, "cf_secret", _cfSecret.value)
        }
    }

    fun testConnection() {
        testJob?.cancel()
        _error.value = null
        _legs.value = emptyList()
        _canSave.value = false
        val normalized = HermesRestClient.normalizeUrl(_url.value)
        if (normalized.isBlank()) {
            _error.value = "Enter the gateway URL first."
            return
        }
        testJob = viewModelScope.launch {
            _testing.value = true
            try {
                stageSecrets()
                val gw = tempGateway()
                // Username+password: run the RFC 8252 native flow first to mint tokens.
                if (_authMode.value == AuthMode.USERNAME_PASSWORD) {
                    val ok = runNativePasswordFlow(normalized)
                    if (!ok) return@launch
                }
                val rest = HermesRestClient(repo.http, normalized, store, WIZARD_ID, gw.authMode)
                val results = mutableListOf<LegResult>()

                results += rest.legStatus()
                _legs.value = results.toList()
                if (!results.last().ok) return@launch

                results += rest.legAuth()
                _legs.value = results.toList()
                if (!results.last().ok) return@launch

                results += socketLeg(rest, gw)
                _legs.value = results.toList()
                _canSave.value = results.all { it.ok }
            } finally {
                _testing.value = false
            }
        }
    }

    /** Leg 3: open wss /api/ws and await gateway.ready (15 s). */
    private suspend fun socketLeg(rest: HermesRestClient, gw: Gateway): LegResult {
        val socket = HermesSocketClient(repo.http, rest, store, gw)
        return try {
            val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val job = viewModelScope.launch {
                socket.events.collect { if (it is SocketEvent.GatewayReady) ready.complete(true) }
            }
            socket.connect()
            val ok = withTimeoutOrNull(15_000) { ready.await() } == true
            job.cancel()
            if (ok) LegResult(TestLeg.SOCKET, true, "gateway.ready received")
            else LegResult(
                TestLeg.SOCKET, false,
                "WebSocket failed — proxy not forwarding Upgrade, Access blocking the socket, " +
                    "or dashboard.public_url mismatches the typed host.",
            )
        } catch (e: Exception) {
            LegResult(TestLeg.SOCKET, false, e.message ?: "failed")
        } finally {
            socket.release()
        }
    }

    /**
     * RFC 8252 native flow: POST /auth/native/authorize → POST /auth/password-login
     * → POST /auth/native/token. Keeps only access + refresh tokens; the password
     * is never stored. Best-effort over the documented endpoints — the server's
     * message is surfaced verbatim on failure.
     */
    private suspend fun runNativePasswordFlow(base: String): Boolean {
        return try {
            val json = "application/json; charset=utf-8".toMediaType()
            fun post(path: String, body: JSONObject): JSONObject {
                val req = Request.Builder()
                    .url(base + path)
                    .post(body.toString().toRequestBody(json))
                    .build()
                repo.http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${text.take(200)}")
                    return JSONObject(text.ifBlank { "{}" })
                }
            }
            var code: String? = null
            runCatching {
                val r = post("/auth/native/authorize", JSONObject())
                code = r.optString("code", r.optString("authorization_code", "")).ifEmpty { null }
            }
            val loginBody = JSONObject()
                .put("username", _username.value.trim())
                .put("password", _password.value)
            if (code != null) loginBody.put("code", code)
            val login = post("/auth/password-login", loginBody)

            var access = login.optString("access_token", "")
            var refresh = login.optString("refresh_token", "")
            if (access.isEmpty()) {
                // Exchange step: hand whatever we got to /auth/native/token.
                val tokenBody = JSONObject()
                login.keys().forEach { k -> tokenBody.put(k, login.get(k)) }
                val token = post("/auth/native/token", tokenBody)
                access = token.optString("access_token", "")
                refresh = token.optString("refresh_token", "")
            }
            if (access.isEmpty() || refresh.isEmpty()) {
                _error.value = "Sign-in did not return tokens."
                _password.value = "" // never keep it around
                return false
            }
            store.putOAuthTokens(WIZARD_ID, access, refresh)
            _password.value = ""
            true
        } catch (e: Exception) {
            _password.value = ""
            _error.value = e.message ?: "Sign-in failed."
            false
        }
    }

    // ------------------------------------------------------------------ //
    // Browser OIDC (thin: PKCE + system browser, loopback-style deep link)
    // ------------------------------------------------------------------ //

    private var pkceVerifier: String = ""

    /** Build the OIDC authorize URL and open it in the system browser. */
    fun startBrowserSignIn(context: Context) {
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }
        pkceVerifier = Base64.encodeToString(verifier, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pkceVerifier.toByteArray())
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val base = HermesRestClient.normalizeUrl(_url.value)
        val uri = Uri.parse("$base/auth/native/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", "vory-android")
            .appendQueryParameter("redirect_uri", "vory://oauth/callback")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Exchange the authorization code from the vory://oauth/callback deep link. */
    fun exchangeOidcCode(code: String, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _testing.value = true
            try {
                val base = HermesRestClient.normalizeUrl(_url.value)
                val body = JSONObject()
                    .put("grant_type", "authorization_code")
                    .put("code", code)
                    .put("redirect_uri", "vory://oauth/callback")
                    .put("code_verifier", pkceVerifier)
                val req = Request.Builder()
                    .url("$base/auth/native/token")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val text = repo.http.newCall(req).execute().use { resp ->
                    val t = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}: ${t.take(200)}")
                    t
                }
                val o = JSONObject(text)
                val access = o.optString("access_token", "")
                val refresh = o.optString("refresh_token", "")
                if (access.isEmpty()) {
                    onDone(false, "Token exchange did not return tokens.")
                } else {
                    store.putOAuthTokens(WIZARD_ID, access, refresh)
                    onDone(true, null)
                }
            } catch (e: Exception) {
                onDone(false, e.message)
            } finally {
                _testing.value = false
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Save
    // ------------------------------------------------------------------ //

    /** Persist the tested gateway and make it active. */
    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            val src = WIZARD_ID
            val gw = tempGateway().copy(
                id = store.newGatewayId(),
                name = _name.value.ifBlank { "Gateway" },
            )
            // Move staged secrets onto the real gateway id.
            listOf("session_token", "access_token", "refresh_token", "cf_id", "cf_secret").forEach { k ->
                store.getSecret(src, k)?.let { store.putSecret(gw.id, k, it) }
            }
            store.clearSecrets(src)
            store.saveGateway(gw.copy(hasCredential = true))
            store.setActiveGateway(gw.id)
            repo.selectGateway(gw)
            // Reset wizard state.
            _name.value = ""; _url.value = ""; _sessionToken.value = ""
            _username.value = ""; _password.value = ""
            _cfId.value = ""; _cfSecret.value = ""
            _legs.value = emptyList(); _canSave.value = false
            onSaved()
        }
    }

    fun legState(leg: TestLeg): LegResult? = _legs.value.firstOrNull { it.leg == leg }

    fun reportError(msg: String) { _error.value = msg }

    class Factory(private val repo: AppRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SetupViewModel(repo) as T
    }
}
