package dev.vory.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Thin REST client for the Hermes dashboard API (see PROTOCOL.md §4).
 *
 * - Every request carries `?profile=<profile>` except auth/status bootstrap calls.
 * - Auth: `X-Hermes-Session-Token` for SESSION_TOKEN mode, `Authorization: Bearer`
 *   for OAuth/native modes, plus optional Cloudflare Access headers everywhere.
 * - 401 on a Bearer token → run the refresher once → retry once.
 * - An HTML body on an API call means "wrong URL": surfaced, never retried blindly.
 */
class HermesRestClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val store: GatewayStore,
    private val gatewayId: String,
    private val authMode: AuthMode,
) {
    val baseUrl: String = normalizeUrl(baseUrl)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Strip trailing slashes and any pasted `/api/...` suffix before storing. */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            u = u.replace(Regex("/api(/.*)?$"), "")
            return u.trimEnd('/')
        }

        /** Bootstrap calls that must NOT carry ?profile=. */
        private val BOOTSTRAP = listOf(
            "/api/status", "/api/health", "/api/auth/me",
            "/api/auth/providers", "/api/auth/ws-ticket",
            "/auth/native/authorize", "/auth/password-login",
            "/auth/native/token", "/auth/native/refresh",
        )
    }

    // ------------------------------------------------------------------ //
    // Auth headers
    // ------------------------------------------------------------------ //

    private fun authHeaders(): Map<String, String> {
        val h = mutableMapOf<String, String>()
        when (authMode) {
            AuthMode.SESSION_TOKEN -> store.getSecret(gatewayId, "session_token")?.let {
                h["X-Hermes-Session-Token"] = it
            }
            AuthMode.USERNAME_PASSWORD, AuthMode.BROWSER_OIDC ->
                store.getSecret(gatewayId, "access_token")?.let {
                    h["Authorization"] = "Bearer $it"
                }
        }
        val cfId = store.getSecret(gatewayId, "cf_id")
        val cfSecret = store.getSecret(gatewayId, "cf_secret")
        if (!cfId.isNullOrEmpty() && !cfSecret.isNullOrEmpty()) {
            h["CF-Access-Client-Id"] = cfId
            h["CF-Access-Client-Secret"] = cfSecret
        }
        return h
    }

    private fun url(path: String, profile: String?, extra: Map<String, String>): String {
        val sb = StringBuilder(baseUrl).append(path)
        val params = mutableListOf<String>()
        val scoped = BOOTSTRAP.none { path == it || path.startsWith("$it/") }
        if (scoped && !profile.isNullOrEmpty()) {
            params += "profile=" + URLEncoder.encode(profile, "UTF-8")
        }
        extra.forEach { (k, v) -> params += "$k=" + URLEncoder.encode(v, "UTF-8") }
        if (params.isNotEmpty()) sb.append('?').append(params.joinToString("&"))
        return sb.toString()
    }

    private fun request(
        method: String,
        path: String,
        profile: String?,
        extra: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Request {
        val b = Request.Builder().url(url(path, profile, extra))
        authHeaders().forEach { (k, v) -> b.header(k, v) }
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post(body ?: "{}".toRequestBody(JSON))
            "PUT" -> b.put(body ?: "{}".toRequestBody(JSON))
            "DELETE" -> if (body != null) b.delete(body) else b.delete()
        }
        return b.build()
    }

    // ------------------------------------------------------------------ //
    // Execution
    // ------------------------------------------------------------------ //

    private suspend fun execute(req: Request, retried: Boolean = false): okhttp3.Response =
        withContext(Dispatchers.IO) {
            val resp = http.newCall(req).execute()
            if (resp.code == 401 && !retried && authMode != AuthMode.SESSION_TOKEN) {
                resp.close()
                if (refreshTokens()) {
                    val retry = req.newBuilder()
                        .header("Authorization", "Bearer ${store.getSecret(gatewayId, "access_token")}")
                        .build()
                    return@withContext http.newCall(retry).execute()
                }
            }
            resp
        }

    /** RFC 8252 native refresh: POST /auth/native/refresh. False = sign in again. */
    suspend fun refreshTokens(): Boolean {
        val refresh = store.getSecret(gatewayId, "refresh_token") ?: return false
        return try {
            val body = JSONObject().put("refresh_token", refresh).toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("$baseUrl/auth/native/refresh")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val o = JSONObject(resp.body!!.string())
                val access = o.optString("access_token", "")
                val newRefresh = o.optString("refresh_token", refresh)
                if (access.isEmpty()) return false
                store.putOAuthTokens(gatewayId, access, newRefresh)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseJson(resp: okhttp3.Response, url: String): JSONObject {
        val text = resp.body!!.string()
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<") || trimmed.startsWith("<!DOCTYPE", ignoreCase = true)) {
            throw HtmlInsteadOfJsonException(url)
        }
        return JSONObject(text.ifBlank { "{}" })
    }

    private suspend fun json(
        method: String, path: String, profile: String?,
        extra: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        val rb = body?.toString()?.toRequestBody(JSON)
        val req = request(method, path, profile, extra, rb)
        execute(req).use { resp ->
            if (resp.code == 503) throw RpcException(-32000, "Restart required (HTTP 503)")
            if (!resp.isSuccessful) {
                val text = runCatching { resp.body!!.string() }.getOrDefault("")
                throw RpcException(resp.code, text.ifBlank { "HTTP ${resp.code}" }.take(300))
            }
            return parseJson(resp, req.url.toString())
        }
    }

    suspend fun get(path: String, profile: String? = null, extra: Map<String, String> = emptyMap()) =
        json("GET", path, profile, extra)

    suspend fun post(path: String, profile: String? = null, body: JSONObject = JSONObject()) =
        json("POST", path, profile, body = body)

    suspend fun put(path: String, profile: String? = null, body: JSONObject = JSONObject()) =
        json("PUT", path, profile, body = body)

    suspend fun delete(path: String, profile: String? = null, body: JSONObject? = null) =
        json("DELETE", path, profile, body = body)

    suspend fun getArray(path: String, profile: String? = null, extra: Map<String, String> = emptyMap()): JSONArray {
        val req = request("GET", path, profile, extra)
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw RpcException(resp.code, "HTTP ${resp.code}")
            val text = resp.body!!.string()
            if (text.trimStart().startsWith("<")) throw HtmlInsteadOfJsonException(req.url.toString())
            return JSONArray(text.ifBlank { "[]" })
        }
    }

    /** Raw text (logs, soul.md …). */
    suspend fun getText(path: String, profile: String? = null): String {
        val req = request("GET", path, profile)
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw RpcException(resp.code, "HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    /** Download a file to [dest]. Returns bytes written. */
    suspend fun download(path: String, profile: String?, query: Map<String, String>, dest: File): Long =
        withContext(Dispatchers.IO) {
            val req = request("GET", path, profile, query)
            execute(req).use { resp ->
                if (!resp.isSuccessful) throw RpcException(resp.code, "HTTP ${resp.code}")
                var n = 0L
                resp.body!!.byteStream().use { input ->
                    dest.outputStream().use { out -> n = input.copyTo(out) }
                }
                n
            }
        }

    /** Multipart upload to POST /api/files/upload-stream. */
    suspend fun upload(path: String, profile: String?, dir: String, file: File): JSONObject =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("path", dir)
                .addFormDataPart(
                    "file", file.name,
                    file.asRequestBody("application/octet-stream".toMediaType()),
                )
                .build()
            val req = request("POST", path, profile, body = multipart)
            execute(req).use { resp ->
                if (!resp.isSuccessful) throw RpcException(resp.code, "HTTP ${resp.code}")
                parseJson(resp, req.url.toString())
            }
        }

    // ------------------------------------------------------------------ //
    // Connection test legs (PROTOCOL.md §3)
    // ------------------------------------------------------------------ //

    /** Leg 1: GET /api/status must return JSON starting with {"version":. */
    suspend fun legStatus(): LegResult = try {
        val o = get("/api/status")
        if (o.has("version")) LegResult(TestLeg.STATUS, true, "dashboard ${o.optString("version")}")
        else LegResult(TestLeg.STATUS, false, "JSON but no version field")
    } catch (e: HtmlInsteadOfJsonException) {
        LegResult(TestLeg.STATUS, false, "HTML page — wrong URL / login page / proxy")
    } catch (e: Exception) {
        LegResult(TestLeg.STATUS, false, e.message ?: "failed")
    }

    /** Leg 2: the credential is accepted (GET /api/auth/me). */
    suspend fun legAuth(): LegResult = try {
        val o = get("/api/auth/me")
        LegResult(TestLeg.AUTH, true, o.optString("user", o.optString("username", "ok")))
    } catch (e: Exception) {
        LegResult(TestLeg.AUTH, false, e.message ?: "failed")
    }

    /** Used by the socket leg: mint a fresh ws ticket for bearer auth. */
    suspend fun wsTicket(): String? = try {
        get("/api/auth/ws-ticket").optString("ticket", null)?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
