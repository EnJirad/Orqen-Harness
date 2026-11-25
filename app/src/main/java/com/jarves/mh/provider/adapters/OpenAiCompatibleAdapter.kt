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

class OpenAiCompatibleAdapter : ProviderAdapter {
    override val type: ProviderType = ProviderType.OPENAI_COMPATIBLE
    override val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val base = config.baseUrl.trim().trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                }
                config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val model = config.selectedModelId.ifBlank { "gpt-4o-mini" }
            val testBody = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .put("max_tokens", 2)
                .toString()

            conn.outputStream.use { it.write(testBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val latency = System.currentTimeMillis() - start

            if (code in 200..299) {
                ConnectionTestResult(
                    isSuccess = true,
                    latencyMs = latency,
                    message = "Connected successfully (${latency}ms)",
                    detectedProtocol = ApiProtocol.OPENAI_CHAT,
                    httpStatusCode = code,
                )
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val errMessage = parseErrorMessage(errorStream, code)
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    message = "HTTP $code: $errMessage",
                    detectedProtocol = ApiProtocol.OPENAI_CHAT,
                    httpStatusCode = code,
                    errorDetail = errorStream.take(500),
                )
            }
        } catch (e: Exception) {
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - start,
                message = e.localizedMessage ?: "Connection error",
                detectedProtocol = ApiProtocol.OPENAI_CHAT,
                errorDetail = e.stackTraceToString().take(500),
            )
        }
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        val endpoint = "$base/models"
        val models = mutableListOf<ModelInfo>()
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                }
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
                        if (id.isNotBlank()) {
                            models.add(ModelInfo(id = id, displayName = id, providerId = config.id))
                        }
                    }
                }
            }
        } catch (_: Exception) { }
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
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        val isOpenRouter = base.contains("openrouter.ai", ignoreCase = true)
        val messagesArray = JSONArray()
        if (systemInstruction.isNotBlank()) {
            if (isOpenRouter) {
                val sysBlock = JSONObject()
                    .put("type", "text")
                    .put("text", systemInstruction)
                    .put("cache_control", JSONObject().put("type", "ephemeral"))
                messagesArray.put(JSONObject().put("role", "system").put("content", JSONArray().put(sysBlock)))
            } else {
                messagesArray.put(JSONObject().put("role", "system").put("content", systemInstruction))
            }
        }
        for (m in messages) {
            val obj = JSONObject().put("role", m.role)
            if (m.images.isNotEmpty()) {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.content))
                for (img in m.images) {
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:${img.mimeType};base64,${img.base64Data}"),
                            ),
                    )
                }
                obj.put("content", parts)
            } else {
                obj.put("content", m.content)
            }
            if (m.name != null) obj.put("name", m.name)
            messagesArray.put(obj)
        }

        val requestJson = JSONObject()
            .put("model", modelId)
            .put("messages", messagesArray)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put("stream", true)
            .toString()

        conn.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("OpenAI API HTTP ${conn.responseCode}: ${parseErrorMessage(err, conn.responseCode)}")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: continue
            if (l.isEmpty() || l.startsWith(":")) continue
            if (l.startsWith("data:")) {
                val data = l.substring(5).trim()
                if (data == "[DONE]") {
                    emit(AiStreamChunk(deltaText = "", isFinished = true, finishReason = "stop"))
                    break
                }
                try {
                    val chunkJson = JSONObject(data)
                    val choices = chunkJson.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val first = choices.getJSONObject(0)
                        val delta = first.optJSONObject("delta")
                        val content = delta?.optString("content", "") ?: ""
                        val finish = first.optString("finish_reason", "").takeIf { it.isNotBlank() && it != "null" }
                        if (content.isNotEmpty() || finish != null) {
                            emit(AiStreamChunk(
                                deltaText = content,
                                isFinished = finish != null && finish != "null",
                                finishReason = finish,
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
                connectTimeout = 8_000
                readTimeout = 8_000
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            conn.responseCode
            val remainingReq = conn.getHeaderField("x-ratelimit-remaining-requests")
            val remainingTokens = conn.getHeaderField("x-ratelimit-remaining-tokens")
            val resetTime = conn.getHeaderField("x-ratelimit-reset-requests") ?: conn.getHeaderField("retry-after")

            if (remainingReq != null || remainingTokens != null) {
                OfficialQuota(
                    providerId = config.id,
                    remainingPercent = remainingReq?.toFloatOrNull()?.let { it / 1000f }?.coerceIn(0f, 1f),
                    quotaUsed = null,
                    quotaLimit = conn.getHeaderField("x-ratelimit-limit-requests"),
                    resetTime = resetTime,
                    rateLimit = remainingReq?.let { "$it req remaining" },
                    source = "HTTP Response Headers",
                    isAvailable = true,
                )
            } else {
                OfficialQuota(
                    providerId = config.id,
                    isAvailable = false,
                    unavailableReason = "Provider does not expose public quota API",
                )
            }
        } catch (e: Exception) {
            OfficialQuota(
                providerId = config.id,
                isAvailable = false,
                unavailableReason = "Could not check quota: ${e.message}",
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
