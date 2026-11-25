package com.jarves.mh.provider.manager

import android.content.Context
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.provider.adapters.*
import com.jarves.mh.provider.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ProviderManager(
    private val context: Context,
    private val apiKeyVault: ApiKeyVault,
    private val preferences: AppPreferences,
) {
    private val prefs = context.getSharedPreferences("orqen_provider_configs", Context.MODE_PRIVATE)

    private val adapters = mapOf(
        ProviderType.OPENAI_COMPATIBLE to OpenAiCompatibleAdapter(),
        ProviderType.ANTHROPIC to AnthropicAdapter(),
        ProviderType.GEMINI to GeminiAdapter(),
        ProviderType.ANTIGRAVITY to AntigravityAdapter(preferences),
        ProviderType.DEEPSEEK to OpenAiCompatibleAdapter(),
        ProviderType.OPENROUTER to OpenAiCompatibleAdapter(),
        ProviderType.CUSTOM_API to CustomProviderAdapter(),
    )

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val quotaCache = ConcurrentHashMap<String, OfficialQuota>()
    private val estimatedUsageCache = ConcurrentHashMap<String, EstimatedUsage>()
    private val localUsageCache = ConcurrentHashMap<String, LocalUsage>()

    init {
        loadConfigs()
    }

    @Synchronized
    private fun loadConfigs() {
        val raw = prefs.getString("configs_json", null)
        val list = mutableListOf<ProviderConfig>()
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val type = runCatching { ProviderType.valueOf(obj.getString("type")) }.getOrDefault(ProviderType.CUSTOM_API)
                    val protocol = runCatching { ApiProtocol.valueOf(obj.getString("protocol")) }.getOrDefault(ApiProtocol.OPENAI_CHAT)

                    val headersObj = obj.optJSONObject("customHeaders")
                    val headers = mutableMapOf<String, String>()
                    if (headersObj != null) {
                        for (k in headersObj.keys()) {
                            headers[k] = headersObj.getString(k)
                        }
                    }

                    val modelsArray = obj.optJSONArray("availableModels")
                    val models = mutableListOf<String>()
                    if (modelsArray != null) {
                        for (j in 0 until modelsArray.length()) models.add(modelsArray.getString(j))
                    }

                    list.add(
                        ProviderConfig(
                            id = id,
                            name = obj.getString("name"),
                            type = type,
                            baseUrl = obj.optString("baseUrl", ""),
                            apiKey = "", // Stored encrypted in ApiKeyVault
                            protocol = protocol,
                            customHeaders = headers,
                            timeoutMs = obj.optInt("timeoutMs", 30_000),
                            isStreamingEnabled = obj.optBoolean("isStreamingEnabled", true),
                            isEnabled = obj.optBoolean("isEnabled", true),
                            selectedModelId = obj.optString("selectedModelId", ""),
                            availableModels = models,
                            organizationId = obj.optString("organizationId", "").takeIf { it.isNotBlank() && it != "null" },
                        )
                    )
                }
            } catch (_: Exception) { }
        }

        if (list.isEmpty()) {
            // Seed defaults: Antigravity, Anthropic, Gemini, OpenAI, DeepSeek, OpenRouter
            list.addAll(defaultPresetConfigs())
            saveConfigsInternal(list)
        }
        _configs.value = list
    }

    private fun defaultPresetConfigs(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "preset-antigravity",
            name = "Antigravity (Google)",
            type = ProviderType.ANTIGRAVITY,
            baseUrl = "",
            protocol = ApiProtocol.ANTIGRAVITY_OAUTH,
            selectedModelId = "gemini-3.8-flash-high",
            availableModels = listOf("gemini-3.8-flash-high", "gemini-3.8-pro-high", "gemini-2.5-flash"),
        ),
        ProviderConfig(
            id = "preset-anthropic",
            name = "Anthropic API",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            selectedModelId = "claude-sonnet-4-6",
            availableModels = listOf("claude-sonnet-4-6", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
        ),
        ProviderConfig(
            id = "preset-gemini",
            name = "Google Gemini API",
            type = ProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            protocol = ApiProtocol.GEMINI_DEV_API,
            selectedModelId = "gemini-2.5-flash",
            availableModels = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"),
        ),
        ProviderConfig(
            id = "preset-openai",
            name = "OpenAI",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            protocol = ApiProtocol.OPENAI_CHAT,
            selectedModelId = "gpt-4o",
            availableModels = listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
        ),
        ProviderConfig(
            id = "preset-deepseek",
            name = "DeepSeek API",
            type = ProviderType.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            protocol = ApiProtocol.OPENAI_CHAT,
            selectedModelId = "deepseek-coder",
            availableModels = listOf("deepseek-coder", "deepseek-chat"),
        ),
        ProviderConfig(
            id = "preset-openrouter",
            name = "OpenRouter",
            type = ProviderType.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            protocol = ApiProtocol.OPENAI_CHAT,
            selectedModelId = "anthropic/claude-3.5-sonnet",
            availableModels = listOf("anthropic/claude-3.5-sonnet", "deepseek/deepseek-r1", "google/gemini-2.5-flash"),
        ),
    )

    @Synchronized
    fun saveConfig(config: ProviderConfig, apiKey: String?) {
        if (!apiKey.isNullOrBlank()) {
            apiKeyVault.put("provider_${config.id}", apiKey)
        }
        val current = _configs.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            current[index] = config
        } else {
            current.add(config)
        }
        saveConfigsInternal(current)
        _configs.value = current
    }

    @Synchronized
    fun deleteConfig(configId: String) {
        val current = _configs.value.filterNot { it.id == configId }
        apiKeyVault.remove("provider_$configId", "Primary")
        saveConfigsInternal(current)
        _configs.value = current
    }

    fun getApiKey(configId: String): String {
        return apiKeyVault.credentials("provider_$configId").firstOrNull()?.secret.orEmpty()
    }

    fun getAdapter(type: ProviderType): ProviderAdapter {
        return adapters[type] ?: adapters[ProviderType.CUSTOM_API] ?: CustomProviderAdapter()
    }

    suspend fun testConnection(configId: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val config = _configs.value.firstOrNull { it.id == configId }
            ?: return@withContext ConnectionTestResult(isSuccess = false, message = "Provider not found")
        val key = getApiKey(configId)
        val adapter = getAdapter(config.type)
        adapter.testConnection(config, key)
    }

    suspend fun discoverModels(configId: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val config = _configs.value.firstOrNull { it.id == configId } ?: return@withContext emptyList()
        val key = getApiKey(configId)
        val adapter = getAdapter(config.type)
        val models = adapter.discoverModels(config, key)
        if (models.isNotEmpty()) {
            val updated = config.copy(availableModels = models.map { it.id })
            saveConfig(updated, null)
        }
        models
    }

    fun invalidateQuotaCache(configId: String? = null) {
        if (configId == null) {
            quotaCache.clear()
        } else {
            quotaCache.remove(configId)
        }
    }

    suspend fun getOfficialQuota(configId: String): OfficialQuota = withContext(Dispatchers.IO) {
        val cached = quotaCache[configId]
        if (cached != null && System.currentTimeMillis() - cached.lastUpdatedMillis < 60_000L) {
            return@withContext cached
        }
        val config = _configs.value.firstOrNull { it.id == configId }
            ?: return@withContext OfficialQuota(providerId = configId, isAvailable = false, unavailableReason = "Provider config not found")
        val key = getApiKey(configId)
        val adapter = getAdapter(config.type)
        val fresh = adapter.fetchOfficialQuota(config, key)
        quotaCache[configId] = fresh
        fresh
    }

    fun recordUsage(configId: String, modelId: String, promptTokens: Int, completionTokens: Int, isError: Boolean = false) {
        val total = (promptTokens + completionTokens).toLong()
        // Rate estimates: ~$3/M tokens average across standard models
        val costUsd = (total.toDouble() / 1_000_000.0) * 3.0
        val costThb = costUsd * 35.0 // THB estimate

        val prevEst = estimatedUsageCache[configId] ?: EstimatedUsage(modelId = modelId)
        estimatedUsageCache[configId] = prevEst.copy(
            estimatedTokens = prevEst.estimatedTokens + total,
            estimatedCostUsd = prevEst.estimatedCostUsd + costUsd,
            estimatedCostThb = prevEst.estimatedCostThb + costThb,
            requestCount = prevEst.requestCount + 1,
            modelId = modelId,
        )

        val prevLocal = localUsageCache[configId] ?: LocalUsage()
        localUsageCache[configId] = prevLocal.copy(
            totalRequests = prevLocal.totalRequests + 1,
            failedTasks = if (isError) prevLocal.failedTasks + 1 else prevLocal.failedTasks,
            completedTasks = if (!isError) prevLocal.completedTasks + 1 else prevLocal.completedTasks,
            lastActiveMillis = System.currentTimeMillis(),
        )
    }

    fun getEstimatedUsage(configId: String): EstimatedUsage = estimatedUsageCache[configId] ?: EstimatedUsage()

    fun getLocalUsage(configId: String): LocalUsage = localUsageCache[configId] ?: LocalUsage()

    private fun saveConfigsInternal(list: List<ProviderConfig>) {
        val array = JSONArray()
        for (c in list) {
            val obj = JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("type", c.type.name)
                .put("baseUrl", c.baseUrl)
                .put("protocol", c.protocol.name)
                .put("timeoutMs", c.timeoutMs)
                .put("isStreamingEnabled", c.isStreamingEnabled)
                .put("isEnabled", c.isEnabled)
                .put("selectedModelId", c.selectedModelId)
                .put("organizationId", c.organizationId ?: "")

            val headersObj = JSONObject()
            c.customHeaders.forEach { (k, v) -> headersObj.put(k, v) }
            obj.put("customHeaders", headersObj)

            val modelsArray = JSONArray()
            c.availableModels.forEach { modelsArray.put(it) }
            obj.put("availableModels", modelsArray)

            array.put(obj)
        }
        prefs.edit().putString("configs_json", array.toString()).apply()
    }
}
