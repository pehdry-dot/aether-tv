package com.aether.tv.data.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Minimal JSON-over-HTTP helper shared by provider adapters. */
class JsonHttp(val client: OkHttpClient = defaultClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonElement = exec(Request.Builder().url(url).get(), headers)

    suspend fun post(url: String, body: String?, headers: Map<String, String> = emptyMap(), contentType: String = "application/json"): JsonElement =
        exec(Request.Builder().url(url).post((body ?: "").toRequestBody(contentType.toMediaType())), headers)

    private suspend fun exec(b: Request.Builder, headers: Map<String, String>): JsonElement = withContext(Dispatchers.IO) {
        headers.forEach { (k, v) -> b.header(k, v) }
        b.header("Accept", "application/json")
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}")
            val text = r.body?.string().orEmpty()
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        }
    }
}

// Tolerant JSON accessors.
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.doubleOrNull?.toLong()
val JsonElement?.double: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray)?.toList().orEmpty()

fun String.trimSlash() = trim().trimEnd('/')
fun normalizeServerUrl(input: String, defaultPort: Int? = null): String {
    var s = input.trim().trimEnd('/')
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    if (defaultPort != null && !Regex("://[^/]+:\\d+").containsMatchIn(s)) s = "$s:$defaultPort"
    return s
}
