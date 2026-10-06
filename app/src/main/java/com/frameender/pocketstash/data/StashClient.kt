package com.frameender.pocketstash.data

import kotlinx.coroutines.Dispatchers
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

class StashException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
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

    suspend fun execute(
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        override: Pair<HttpUrl, String>? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
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
            throw StashException("Can't reach ${base.host}:${base.port} — ${e.message ?: e.javaClass.simpleName}", e)
        }

        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 ->
                    throw StashException("Unauthorized (${r.code}). Check the API key in Stash → Settings → Security.")
                !r.isSuccessful && text.isBlank() ->
                    throw StashException("HTTP ${r.code} from Stash")
            }
            val root: JsonObject = try {
                StashJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw StashException("Stash returned something that isn't JSON (HTTP ${r.code}). Is the URL right?", e)
            }
            val errors = root["errors"] as? JsonArray
            val data = root["data"]
            if (!errors.isNullOrEmpty() && (data == null || data is JsonNull)) {
                val msg = errors.joinToString("\n") {
                    (it as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: it.toString()
                }
                throw StashException(msg)
            }
            (data as? JsonObject) ?: throw StashException("Empty response from Stash")
        }
    }
}

/** Small helpers for walking GraphQL responses. */
internal fun JsonObject.obj(key: String): JsonObject =
    (this[key] as? JsonObject) ?: throw StashException("Missing '$key' in response")

internal fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject

internal inline fun <reified T> JsonElement.decode(): T =
    StashJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), this)
