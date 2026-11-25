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

class GeminiAdapter : ProviderAdapter {
    override val type: ProviderType = ProviderType.GEMINI
    override val protocol: ApiProtocol = ApiProtocol.GEMINI_DEV_API

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val base = config.baseUrl.trim().ifBlank { "https://generativelanguage.googleapis.com" }.trimEnd('/')
            val model = config.selectedModelId.ifBlank { "gemini-2.5-flash" }
            val endpoint = "$base/v1beta/models/$model:generateContent"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 20_000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey.trim())
                config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val testBody = JSONObject()
                .put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "hi")))
                ))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 2))
                .toString()

            conn.outputStream.use { it.write(testBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val latency = System.currentTimeMillis() - start

            if (code in 200..299) {
                ConnectionTestResult(
                    isSuccess = true,
                    latencyMs = latency,
                    message = "Gemini API connection verified (${latency}ms)",
                    detectedProtocol = ApiProtocol.GEMINI_DEV_API,
                    httpStatusCode = code,
                )
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val errMessage = parseErrorMessage(errorStream, code)
                ConnectionTestResult(
                    isSuccess = false,
                    latencyMs = latency,
                    message = "HTTP $code: $errMessage",
                    detectedProtocol = ApiProtocol.GEMINI_DEV_API,
                    httpStatusCode = code,
                    errorDetail = errorStream.take(500),
                )
            }
        } catch (e: Exception) {
            ConnectionTestResult(
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - start,
                message = e.localizedMessage ?: "Connection error",
                detectedProtocol = ApiProtocol.GEMINI_DEV_API,
                errorDetail = e.stackTraceToString().take(500),
            )
        }
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().ifBlank { "https://generativelanguage.googleapis.com" }.trimEnd('/')
        val endpoint = "$base/v1beta/models"
        val models = mutableListOf<ModelInfo>()
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("x-goog-api-key", apiKey.trim())
                config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val items = json.optJSONArray("models")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val name = item.optString("name").removePrefix("models/")
                        val displayName = item.optString("displayName", name)
                        val supportedGenerationMethods = item.optJSONArray("supportedGenerationMethods")
                        var canGenerate = false
                        if (supportedGenerationMethods != null) {
                            for (j in 0 until supportedGenerationMethods.length()) {
                                if (supportedGenerationMethods.getString(j) == "generateContent") canGenerate = true
                            }
                        }
                        if (name.isNotBlank() && canGenerate) {
                            models.add(ModelInfo(id = name, displayName = displayName, providerId = config.id))
                        }
                    }
                }
            }
        } catch (_: Exception) { }

        if (models.isEmpty()) {
            models.addAll(listOf(
                ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro (State-of-the-art Reasoning)", providerId = config.id),
                ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash (Fast & Capable)", providerId = config.id),
                ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", providerId = config.id),
                ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", providerId = config.id),
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
        val base = config.baseUrl.trim().ifBlank { "https://generativelanguage.googleapis.com" }.trimEnd('/')
        val endpoint = "$base/v1beta/models/$modelId:streamGenerateContent?alt=sse"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey.trim())
            config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        val contentsArray = JSONArray()
        for (m in messages) {
            val role = if (m.role == "assistant") "model" else "user"
            contentsArray.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", m.content))))
        }

        val requestJson = JSONObject()
            .put("contents", contentsArray)
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))

        if (systemInstruction.isNotBlank()) {
            requestJson.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
        }

        conn.outputStream.use { it.write(requestJson.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw RuntimeException("Gemini API HTTP ${conn.responseCode}: ${parseErrorMessage(err, conn.responseCode)}")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: continue
            if (l.isEmpty() || l.startsWith(":")) continue
            if (l.startsWith("data:")) {
                val data = l.substring(5).trim()
                try {
                    val root = JSONObject(data)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        val finishReason = candidate.optString("finishReason", "").takeIf { it.isNotBlank() && it != "null" }
                        var text = ""
                        if (parts != null) {
                            for (p in 0 until parts.length()) {
                                text += parts.getJSONObject(p).optString("text", "")
                            }
                        }
                        if (text.isNotEmpty() || finishReason != null) {
                            emit(AiStreamChunk(
                                deltaText = text,
                                isFinished = finishReason != null && finishReason != "null",
                                finishReason = finishReason,
                            ))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        emit(AiStreamChunk(deltaText = "", isFinished = true, finishReason = "STOP"))
    }.flowOn(Dispatchers.IO)

    override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota = withContext(Dispatchers.IO) {
        OfficialQuota(
            providerId = config.id,
            isAvailable = false,
            unavailableReason = "Google Developer API quotas are managed per-project in Google Cloud Console",
        )
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
