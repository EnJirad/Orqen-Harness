package com.jarves.mh.provider.adapters

import com.jarves.mh.provider.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AnthropicAdapter : ProviderAdapter {
    override val type: ProviderType = ProviderType.ANTHROPIC
    override val protocol: ApiProtocol = ApiProtocol.ANTHROPIC_MESSAGES

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val base = config.baseUrl.trim().ifBlank { "https://api.anthropic.com" }.trimEnd('/')
            val endpoint = if (base.endsWith("/messages")) base else "$base/v1/messages"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey.trim())
                setRequestProperty("anthropic-version", "2023-06-01")
                config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val model = config.selectedModelId.ifBlank { "claude-3-5-haiku-20241022" }
            val testBody = JSONObject()
                .put("model", model)
                .put("max_tokens", 1)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .toString()

            conn.outputStream.use { it.write(testBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val latency = System.currentTimeMillis() - start

            if (code in 200..299) {
                ConnectionTestResult(
                    isSuccess = true,
                    latencyMs = latency,
                    message = "Anthropic connection verified (${latency}ms)",
                    detectedProtocol = ApiProtocol.ANTHROPIC_MESSAGES,
                    httpStatusCode = code,
                )
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val errMessage = parseErrorMessage(errorStream, code)
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    message = "HTTP $code: $errMessage",
                    detectedProtocol = ApiProtocol.ANTHROPIC_MESSAGES,
                    httpStatusCode = code,
                    errorDetail = errorStream.take(500),
                )
            }
        } catch (e: Exception) {
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - start,
                message = e.localizedMessage ?: "Connection error",
                detectedProtocol = ApiProtocol.ANTHROPIC_MESSAGES,
                errorDetail = e.stackTraceToString().take(500),
            )
        }
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val endpoint = "$base/v1/models"
        val models = mutableListOf<ModelInfo>()
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("x-api-key", apiKey.trim())
                setRequestProperty("anthropic-version", "2023-06-01")
                config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val id = item.optString("id")
                        val displayName = item.optString("display_name", id)
                        if (id.isNotBlank()) {
                            models.add(ModelInfo(id = id, displayName = displayName, providerId = config.id))
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        if (models.isEmpty()) {
            models.addAll(listOf(
                ModelInfo("claude-sonnet-4-6", "Claude 3.7 Sonnet (Hybrid Reasoning)", providerId = config.id),
                ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", providerId = config.id),
                ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku (Fast)", providerId = config.id),
                ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", providerId = config.id),
            ))
        }
        models
    }

    override suspend fun sendChatCompletion(
        config: ProviderConfig,
        apiKey: String,
        messages: List<AiMessage>,
        systemInstruction: String,
        modelId: String,
        temperature: Double,
        maxTokens: Int,
    ): Flow<AiStreamChunk> = flow {
        val base = config.baseUrl.trim().ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val endpoint = if (base.endsWith("/messages")) base else "$base/v1/messages"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey.trim())
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
            config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        val messagesArray = JSONArray()
        for ((idx, m) in messages.withIndex()) {
            val role = if (m.role == "system") "user" else m.role
            val shouldCacheMessage = messages.size > 1 && idx == messages.lastIndex - 1 && m.content.length > 400
            if (m.images.isNotEmpty()) {
                val parts = JSONArray()
                for (img in m.images) {
                    parts.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", img.mimeType)
                                    .put("data", img.base64Data),
                            ),
                    )
                }
                val textBlock = JSONObject().put("type", "text").put("text", m.content)
                if (shouldCacheMessage) {
                    textBlock.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
                parts.put(textBlock)
                messagesArray.put(JSONObject().put("role", role).put("content", parts))
            } else if (shouldCacheMessage) {
                val block = JSONObject()
                    .put("type", "text")
                    .put("text", m.content)
                    .put("cache_control", JSONObject().put("type", "ephemeral"))
                messagesArray.put(JSONObject().put("role", role).put("content", JSONArray().put(block)))
            } else {
                messagesArray.put(JSONObject().put("role", role).put("content", m.content))
            }
        }

        val requestJson = JSONObject()
            .put("model", modelId)
            .put("messages", messagesArray)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("stream", true)

        if (systemInstruction.isNotBlank()) {
            val sysBlock = JSONObject()
                .put("type", "text")
                .put("text", systemInstruction)
                .put("cache_control", JSONObject().put("type", "ephemeral"))
            requestJson.put("system", JSONArray().put(sysBlock))
        }

        conn.outputStream.use { it.write(requestJson.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("Anthropic API HTTP ${conn.responseCode}: ${parseErrorMessage(err, conn.responseCode)}")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: continue
            if (l.isEmpty() || l.startsWith(":")) continue
            if (l.startsWith("data:")) {
                val data = l.substring(5).trim()
                try {
                    val eventObj = JSONObject(data)
                    val eventType = eventObj.optString("type")
                    if (eventType == "content_block_delta") {
                        val delta = eventObj.optJSONObject("delta")
                        val text = delta?.optString("text", "") ?: ""
                        if (text.isNotEmpty()) {
                            emit(AiStreamChunk(deltaText = text, isFinished = false))
                        }
                    } else if (eventType == "message_stop") {
                        emit(AiStreamChunk(deltaText = "", isFinished = true, finishReason = "stop"))
                        break
                    }
                } catch (_: Exception) { }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val endpoint = if (base.endsWith("/messages")) base else "$base/v1/messages"
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey.trim())
                setRequestProperty("anthropic-version", "2023-06-01")
            }
            // Send empty message to provoke ratelimit header response
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            conn.responseCode

            val reqRemaining = conn.getHeaderField("anthropic-ratelimit-requests-remaining")
            val tokensRemaining = conn.getHeaderField("anthropic-ratelimit-tokens-remaining")
            val reqReset = conn.getHeaderField("anthropic-ratelimit-requests-reset") ?: conn.getHeaderField("retry-after")

            if (reqRemaining != null || tokensRemaining != null) {
                OfficialQuota(
                    providerId = config.id,
                    remainingPercent = reqRemaining?.toFloatOrNull()?.let { it / 1000f }?.coerceIn(0f, 1f),
                    quotaLimit = conn.getHeaderField("anthropic-ratelimit-requests-limit"),
                    resetTime = reqReset,
                    rateLimit = reqRemaining?.let { "$it req remaining" },
                    source = "Anthropic Rate Limit Headers",
                    isAvailable = true,
                )
            } else {
                OfficialQuota(
                    providerId = config.id,
                    isAvailable = false,
                    unavailableReason = "Anthropic Console billing quota is not available via public API",
                )
            }
        } catch (e: Exception) {
            OfficialQuota(
                providerId = config.id,
                isAvailable = false,
                unavailableReason = "Could not reach Anthropic: ${e.message}",
            )
        }
    }

    private fun parseErrorMessage(errorBody: String, code: Int): String {
        return try {
            val obj = JSONObject(errorBody)
            val error = obj.optJSONObject("error")
            error?.optString("message") ?: obj.optString("message", "HTTP $code")
        } catch (_: Exception) {
            if (errorBody.isNotBlank()) errorBody.take(150) else "HTTP $code"
        }
    }
}
