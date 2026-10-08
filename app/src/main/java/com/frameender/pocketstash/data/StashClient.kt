package com.frameender.pocketstash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

open class StashException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The server didn't answer at all (no network, Tailscale off, server down). */
class ServerUnreachableException(message: String, cause: Throwable? = null) : StashException(message, cause)

/** Something needed the server while the app is in offline mode. */
class OfflineModeException : StashException("You're in offline mode, so this needs your Stash server. Tap “Go online” when it's reachable.")

val StashJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

/**
 * Holds the live connection settings. Everything network-related (GraphQL,
 * Coil, ExoPlayer) shares one OkHttpClient whose interceptor adds the ApiKey
 * header to requests aimed at the configured Stash host.
 */
class Connection {
    @Volatile
    var settings: AppSettings = AppSettings()

    /**
     * Offline mode: every screen reads from the phone (downloads and saved lists) instead of
     * the server, until [leaveOffline]. Entered automatically when the server can't be reached
     * (if allowed in Settings) or by hand.
     */
    val offline = MutableStateFlow(false)

    /** Offline mode was switched on by hand (not because the server vanished). */
    @Volatile var manualOffline = false
        private set

    /** While offline: the server answered the last check, so going online would work. */
    val serverBack = MutableStateFlow(false)

    /** Emits each time offline mode turns on or off (not the current state). */
    val modeChanges: Flow<Boolean> = offline.drop(1)

    /** Offline mode: maps a picture URL to a file saved on the phone. */
    @Volatile var localMedia: ((String) -> String?)? = null

    fun enterOffline(manual: Boolean) {
        manualOffline = manual
        serverBack.value = false
        offline.value = true
    }

    fun leaveOffline() {
        manualOffline = false
        serverBack.value = false
        offline.value = false
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val s = settings
            val base = s.baseUrl
            if (base != null && s.apiKey.isNotEmpty() && req.url.host == base.host && req.header("ApiKey") == null) {
                chain.proceed(req.newBuilder().header("ApiKey", s.apiKey).build())
            } else {
                chain.proceed(req)
            }
        })
        .build()

    /** Turns a URL Stash returned into one this device can reach. */
    fun media(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (offline.value) localMedia?.invoke(raw)?.let { return it }
        val s = settings
        val base = s.baseUrl ?: return raw
        val parsed = raw.toHttpUrlOrNull() ?: return base.resolve(raw)?.toString() ?: raw
        if (!s.rewriteHost) return raw
        if (parsed.host == base.host && parsed.port == base.port && parsed.scheme == base.scheme) return raw
        val basePath = base.encodedPath.trimEnd('/')
        val path = if (basePath.isNotEmpty() && !parsed.encodedPath.startsWith(basePath)) {
            basePath + parsed.encodedPath
        } else parsed.encodedPath
        return base.newBuilder().encodedPath(path).encodedQuery(parsed.encodedQuery).build().toString()
    }
}

class StashClient(private val connection: Connection) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * Runs a GraphQL document. [override] targets another server/key (connection tests);
     * [evenOffline] lets the reconnect check through while offline mode is on.
     */
    suspend fun execute(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        override: Pair<HttpUrl, String>? = null,
        evenOffline: Boolean = false,
    ): JsonObject = withContext(Dispatchers.IO) {
        if (override == null && !evenOffline && connection.offline.value) throw OfflineModeException()
        val (base, apiKey) = override ?: run {
            val s = connection.settings
            (s.baseUrl ?: throw StashException("No Stash server configured")) to s.apiKey
        }
        val endpoint = base.newBuilder().addPathSegment("graphql").build()
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(jsonType)

        val builder = Request.Builder().url(endpoint).post(payload)
        // The interceptor covers the normal case; an override (connection test)
        // may target a different host, so set the header explicitly.
        if (apiKey.isNotEmpty()) builder.header("ApiKey", apiKey)

        val response = try {
            connection.http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            val msg = "Can't reach ${base.host}:${base.port} — ${e.message ?: e.javaClass.simpleName}"
            throw if (override == null) ServerUnreachableException(msg, e) else StashException(msg, e)
        }

        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 ->
                    throw StashException("Unauthorized (${r.code}). Check the API key in Stash → Settings → Security.")
                !r.isSuccessful && text.isBlank() ->
                    throw StashException("HTTP ${r.code} from Stash")
            }
            parse(text, r.code)
        }
    }

    /** True if the configured server answers right now (used while offline). */
    suspend fun ping(): Boolean = runCatching { execute(Q.serverInfo, evenOffline = true) }.isSuccess

    private fun parse(text: String, code: Int): JsonObject {
        val root: JsonObject = try {
            StashJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw StashException("Stash returned something that isn't JSON (HTTP $code). Is the URL right?", e)
        }
        val errors = root["errors"] as? JsonArray
        val data = root["data"]
        if (!errors.isNullOrEmpty() && (data == null || data is JsonNull)) {
            val msg = errors.joinToString("\n") {
                (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: it.toString()
            }
            throw StashException(msg)
        }
        return (data as? JsonObject) ?: throw StashException("Empty response from Stash")
    }
}

/** Small helpers for walking GraphQL responses. */
internal fun JsonObject.obj(key: String): JsonObject =
    (this[key] as? JsonObject) ?: throw StashException("Missing '$key' in response")

internal fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject

internal inline fun <reified T> JsonElement.decode(): T =
    StashJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), this)
