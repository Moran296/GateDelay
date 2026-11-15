package app.gatedelay.palgate

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.getTimeMillis
import io.ktor.http.fromHttpToGmtDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** Error returned by PalGate, already phrased for the user. */
class PalgateException(message: String, val httpStatus: Int? = null, val isAuthError: Boolean = false) : Exception(message)

/** A gate output as listed by `/devices`. */
data class PalgateGate(val deviceId: String, val outputNum: Int, val name: String, val address: String?)

/** Credentials needed to sign requests for one account. */
data class PalgateAuth(val phone: Long, val sessionToken: ByteArray, val tokenType: Int)

/**
 * Minimal PalGate client, ported from ha-palgate (`api.py`, `config_flow.py`) and pylgate.
 * Unofficial API: https://api1.pal-es.com/v1/bt
 */
class PalgateApi(
    private val client: HttpClient = defaultHttpClient(),
    private val baseUrl: String = BASE_URL,
    private val nowMillis: () -> Long = { getTimeMillis() },
) {
    /** Server time minus local time, learned from the HTTP `Date` header (tokens are time based). */
    private var clockOffsetMs: Long = 0

    private val json = Json { ignoreUnknownKeys = true }

    /** Lists all gates (device outputs) the account can operate. */
    suspend fun listGates(auth: PalgateAuth): List<PalgateGate> {
        val body = signedGet(auth, "$baseUrl/devices")
        val devices = body["devices"]?.jsonArray ?: return emptyList()
        return devices.flatMap { el ->
            val d = el.jsonObject
            val id = d.str("id") ?: return@flatMap emptyList()
            val address = d.str("address")
            // Output 1 always exists. Extra outputs are listed when the device names them.
            (1..4).mapNotNull { n ->
                val name = d.str("customName$n") ?: d.str("name$n")
                when {
                    n == 1 -> PalgateGate(id, 1, name ?: id, address)
                    name != null -> PalgateGate(id, n, name, address)
                    else -> null
                }
            }
        }
    }

    /** Sends an "open" pulse to one gate output. */
    suspend fun openGate(auth: PalgateAuth, deviceId: String, outputNum: Int) {
        signedGet(auth, "$baseUrl/device/$deviceId/open-gate?openBy=100&outputNum=$outputNum")
    }

    /**
     * Device linking: blocks until the PalGate app scans the QR `{"id": "<linkId>"}`, then returns credentials.
     * Returns null when the server ends the wait without a result (caller should simply call again).
     */
    suspend fun waitForLink(linkId: String, timeoutMs: Long = 5 * 60_000): LinkResult? {
        val resp = client.get("$baseUrl/un/secondary/init/$linkId") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            timeout {
                requestTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
        val text = resp.bodyAsText()
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw PalgateException("Unexpected linking response (HTTP ${resp.status.value})", resp.status.value)
        val user = body["user"] as? JsonObject
        val token = user?.str("token")
        val phone = user?.get("id")?.jsonPrimitive?.contentOrNull
        if (resp.status == HttpStatusCode.OK && token != null && phone != null) {
            val type = body["secondary"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: TokenType.PRIMARY
            return LinkResult(phone, token, type)
        }
        body.errorMessage()?.let { throw PalgateException(it, resp.status.value) }
        return null
    }

    data class LinkResult(val phone: String, val tokenHex: String, val tokenType: Int)

    private suspend fun signedGet(auth: PalgateAuth, url: String): JsonObject {
        var resp = doSignedGet(auth, url)
        // Token is time based: if the server rejected it and our clock turned out to be off, retry once.
        if (resp.first.status == HttpStatusCode.Unauthorized && resp.second) {
            resp = doSignedGet(auth, url)
        }
        return parse(resp.first)
    }

    /** @return response and whether the clock offset was corrected significantly by this response. */
    private suspend fun doSignedGet(auth: PalgateAuth, url: String): Pair<HttpResponse, Boolean> {
        val ts = (nowMillis() + clockOffsetMs) / 1000
        val token = TokenGenerator.generate(auth.sessionToken, auth.phone, auth.tokenType, ts)
        val resp = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "*/*")
            header("x-bt-token", token)
            timeout { requestTimeoutMillis = 15_000 }
        }
        return resp to updateClockOffset(resp)
    }

    private fun updateClockOffset(resp: HttpResponse): Boolean {
        val dateHeader = resp.headers[HttpHeaders.Date] ?: return false
        val serverMs = runCatching { dateHeader.fromHttpToGmtDate().timestamp }.getOrNull() ?: return false
        val measured = serverMs - nowMillis()
        // HTTP Date has 1 s resolution: ignore small differences.
        val newOffset = if (abs(measured) > 2_000) measured else 0L
        val changed = newOffset != clockOffsetMs
        clockOffsetMs = newOffset
        return changed
    }

    private suspend fun parse(resp: HttpResponse): JsonObject {
        val text = resp.bodyAsText()
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val apiError = body?.errorMessage()
        if (resp.status == HttpStatusCode.Unauthorized) {
            throw PalgateException("Not authorized - the link may have been removed. Re-link this account.", 401, isAuthError = true)
        }
        if (resp.status != HttpStatusCode.OK || apiError != null) {
            throw PalgateException(friendly(apiError ?: "HTTP ${resp.status.value}"), resp.status.value)
        }
        return body ?: throw PalgateException("Unexpected response from PalGate")
    }

    private fun JsonObject.errorMessage(): String? {
        val err = this["err"]
        val hasErr = err != null && err is JsonPrimitive && err.contentOrNull.let { !it.isNullOrBlank() && it != "false" }
        if (!hasErr && str("status") != "failed") return null
        return str("msg") ?: str("err") ?: "Request failed"
    }

    private fun friendly(msg: String): String = when {
        msg.contains("Secondary device not authorized", ignoreCase = true) ->
            "Linked devices are not allowed for this user on this gate. Ask the gate admin to enable 'Linked Device' for you."
        msg.contains("check device", ignoreCase = true) ->
            "The gate controller is offline or not responding (\"check device\")."
        else -> msg
    }

    companion object {
        const val BASE_URL = "https://api1.pal-es.com/v1/bt"
        const val USER_AGENT = "okhttp/4.9.3"

        fun defaultHttpClient() = HttpClient {
            install(HttpTimeout)
            expectSuccess = false
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
