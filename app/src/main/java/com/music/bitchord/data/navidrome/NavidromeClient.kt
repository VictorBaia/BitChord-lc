package com.music.bitchord.data.navidrome

import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.SourceHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class NavidromeClient(private val config: NavidromeConfig) {
    suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        try {
            val response = request("ping.view")
            when {
                response.status == "ok" -> SourceHealth.Ok(response.serverVersion)
                response.errorCode == 40 || response.errorCode == 41 -> SourceHealth.Rejected(response.errorMessage)
                else -> SourceHealth.Unreachable(response.errorMessage.ifBlank { "Navidrome rejeitou a solicitação" })
            }
        } catch (e: IOException) {
            SourceHealth.Unreachable(e.message ?: "Servidor Navidrome inacessível")
        } catch (e: Exception) {
            SourceHealth.Unreachable(e.message ?: "Falha ao consultar o Navidrome")
        }
    }

    suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): NavidromeResponse =
        withContext(Dispatchers.IO) { request(path, parameters) }

    fun coverArtUrl(id: String, size: Int? = 1200): String {
        return authenticatedUrl(
            "getCoverArt.view",
            buildMap {
                put("id", id)
                size?.let { put("size", it.toString()) }
            },
        )
    }

    fun authenticatedUrl(path: String, parameters: Map<String, String>): String {
        val endpoint = requireNotNull(config.endpoint(path))
        return endpoint.toHttpUrl().newBuilder().apply {
            NavidromeAuth.queryParameters(config).forEach { (key, value) -> addQueryParameter(key, value) }
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
    }

    private fun request(path: String, parameters: Map<String, String> = emptyMap()): NavidromeResponse {
        val endpoint = config.endpoint(path) ?: error("URL do Navidrome inválida")
        val url = endpoint.toHttpUrl().newBuilder().apply {
            NavidromeAuth.queryParameters(config).forEach { (key, value) -> addQueryParameter(key, value) }
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
                .optJSONObject("subsonic-response") ?: error("Resposta Navidrome inválida")
            val error = root.optJSONObject("error")
            return NavidromeResponse(
                status = root.optString("status"),
                version = root.optString("version"),
                serverVersion = root.optString("serverVersion"),
                body = root,
                errorCode = error?.optInt("code", -1) ?: -1,
                errorMessage = error?.optString("message").orEmpty(),
            )
        }
    }
}

data class NavidromeResponse(
    val status: String,
    val version: String,
    val serverVersion: String,
    val body: JSONObject,
    val errorCode: Int = -1,
    val errorMessage: String = "",
)
