package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.AgentKind
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.provider.core.AiMessage
import com.jarves.mh.provider.core.ProviderConfig
import com.jarves.mh.provider.core.ProviderType
import com.jarves.mh.provider.manager.ProviderManager
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class TeamAgentExecutionResult(
    val responseText: String,
    val writtenFiles: List<String> = emptyList(),
    val isError: Boolean = false,
    val isRecoverable: Boolean = false,
)

/**
 * Executes a REAL AI turn for an AI Team agent inside the team project's isolated workspace folder
 * (/team_workspaces/<projectId>).
 *
 * - For Antigravity providers: spawns the real `agy` CLI in PRoot with that agent's isolated HOME
 *   (falling back to default /root if the agent hasn't linked a separate account yet).
 * - For API providers (OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Custom): streams a real
 *   completion via ProviderManager and writes any generated file blocks directly into the workspace.
 */
class TeamAgentRunner(
    private val context: Context,
    private val installer: RuntimeInstaller,
    private val providerManager: ProviderManager,
) {
    private data class CachedTurnEntry(
        val result: TeamAgentExecutionResult,
        val cachedAtMs: Long,
    )

    private val localResponseCache = object : LinkedHashMap<String, CachedTurnEntry>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTurnEntry>?): Boolean = size > 40
    }

    suspend fun executeTurn(
        agent: AgentState,
        providerConfig: ProviderConfig?,
        workspaceDir: File,
        projectSlug: String,
        prompt: String,
        useIsolatedAgyHome: Boolean,
        enableCache: Boolean = true,
        imageFiles: List<File> = emptyList(),
        knownAgyModels: List<String> = emptyList(),
        onProgress: suspend (String) -> Unit,
    ): TeamAgentExecutionResult = withContext(Dispatchers.IO) {
        workspaceDir.mkdirs()
        val validImages = imageFiles.filter { it.isFile && it.length() > 0L }.take(3)
        val cacheKey = if (enableCache && validImages.isEmpty()) {
            val wsSig = snapshotWorkspaceFiles(workspaceDir).hashCode()
            "${agent.config.slot.id}|${agent.config.providerId}|${agent.config.modelId}|${prompt.hashCode()}|$wsSig"
        } else null

        if (cacheKey != null) {
            val hit = synchronized(localResponseCache) { localResponseCache[cacheKey] }
            if (hit != null && (System.currentTimeMillis() - hit.cachedAtMs) < 10 * 60_000L) {
                onProgress("Cache hit (0 tokens used)")
                return@withContext hit.result
            }
        }

        val isAntigravity = agent.config.providerId == "preset-antigravity" ||
            providerConfig?.type == ProviderType.ANTIGRAVITY

        val result = if (isAntigravity) {
            val agyPrompt = if (validImages.isNotEmpty()) {
                val guestPaths = validImages.mapNotNull { img ->
                    runCatching { "/workspace/$projectSlug/${img.relativeTo(workspaceDir).path}" }.getOrNull()
                }
                buildString {
                    appendLine("ATTACHED REFERENCE IMAGES FROM OWNER (inspect them visually with read_file to match the exact UI, layout, colors, and requirements):")
                    guestPaths.forEach { appendLine("- $it") }
                    appendLine()
                    append(prompt)
                }
            } else prompt
            val agyRes = runAntigravityCli(
                agent = agent,
                workspaceDir = workspaceDir,
                projectSlug = projectSlug,
                prompt = agyPrompt,
                useIsolatedHome = useIsolatedAgyHome,
                knownAgyModels = knownAgyModels,
                onProgress = onProgress,
            )
            // If agy rejected a stale/invalid --model flag, immediately retry once with the account's own default model!
            if (agyRes.isError &&
                (agyRes.responseText.contains("invalid model selection", ignoreCase = true) ||
                    agyRes.responseText.contains("not recognized as a known model", ignoreCase = true))
            ) {
                onProgress("Retrying with account's valid default model…")
                runAntigravityCli(
                    agent = agent.copy(config = agent.config.copy(modelId = "")),
                    workspaceDir = workspaceDir,
                    projectSlug = projectSlug,
                    prompt = agyPrompt,
                    useIsolatedHome = useIsolatedAgyHome,
                    knownAgyModels = emptyList(),
                    onProgress = onProgress,
                )
            } else {
                agyRes
            }
        } else {
            runApiProvider(
                agent = agent,
                providerConfig = providerConfig,
                workspaceDir = workspaceDir,
                prompt = prompt,
                imageFiles = validImages,
                onProgress = onProgress,
            )
        }

        if (cacheKey != null && !result.isError && result.writtenFiles.isEmpty()) {
            synchronized(localResponseCache) {
                localResponseCache[cacheKey] = CachedTurnEntry(result, System.currentTimeMillis())
            }
        }
        result
    }

    private suspend fun runAntigravityCli(
        agent: AgentState,
        workspaceDir: File,
        projectSlug: String,
        prompt: String,
        useIsolatedHome: Boolean,
        knownAgyModels: List<String> = emptyList(),
        onProgress: suspend (String) -> Unit,
    ): TeamAgentExecutionResult {
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            return TeamAgentExecutionResult(
                responseText = "Antigravity CLI is not installed yet. Please install it in Providers or Agent Settings.",
                isError = true,
            )
        }
        val installed = runCatching { installer.installedRuntime() }.getOrElse { err ->
            return TeamAgentExecutionResult(
                responseText = err.message ?: "Linux runtime is not ready yet.",
                isError = true,
            )
        }

        val slotKey = agent.config.slot.name.lowercase()
        val guestHome = if (useIsolatedHome) "/root/.orqen/agy-profiles/$slotKey" else "/root"
        File(context.filesDir, "runtime/ubuntu/${guestHome.removePrefix("/")}").mkdirs()

        val requestedModel = agent.config.modelId.trim()
        val resolvedModel = when {
            requestedModel.isBlank() -> null
            knownAgyModels.isNotEmpty() && requestedModel in knownAgyModels -> requestedModel
            knownAgyModels.isNotEmpty() -> knownAgyModels.first()
            requestedModel == "gemini-3.8-flash-high" -> null // legacy placeholder: let agy use account default
            else -> requestedModel
        }

        val command = buildList {
            add(RuntimeInstaller.AGY_GUEST_PATH)
            addAll(listOf("--input-format", "stream-json"))
            addAll(listOf("--output-format", "stream-json"))
            addAll(listOf("--print-timeout", "12m"))
            add("--dangerously-skip-permissions")
            if (!resolvedModel.isNullOrBlank()) {
                addAll(listOf("--model", resolvedModel))
            }
            add("--new-project")
        }

        val beforeMtimes = snapshotWorkspaceFiles(workspaceDir)
        val outputFile = File(context.cacheDir, "team-agy-${slotKey}-${System.nanoTime()}.log")
        val process = installer.process(
            installed.proot,
            installed.rootfs,
            workspaceDir,
            mapOf("HOME" to guestHome),
            command,
            guestWorkspacePath = "/workspace/$projectSlug",
            emulateHardLinks = false,
            outputFile = outputFile,
        )

        return try {
            withTimeout(12 * 60_000L) {
                val request = JSONObject()
                    .put("event", "user")
                    .put("message", JSONObject().put("content", prompt))
                    .toString() + "\n"
                process.outputStream.write(request.toByteArray())
                process.outputStream.flush()
                process.outputStream.close()

                var offset = 0L
                val pending = StringBuilder()
                val textAccumulator = StringBuilder()
                var finalResponse: String? = null
                var errorMessage: String? = null
                var done = false

                while (!done && (process.isAlive || outputFile.length() > offset)) {
                    val available = outputFile.length() - offset
                    if (available <= 0L) {
                        delay(120)
                        continue
                    }
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(outputFile, "r").use { file ->
                        file.seek(offset)
                        file.read(bytes)
                    }
                    if (count <= 0) continue
                    offset += count
                    pending.append(bytes.decodeToString(0, count))
                    var newline = pending.indexOf("\n")
                    while (newline >= 0) {
                        val line = pending.substring(0, newline).trimEnd('\r')
                        pending.delete(0, newline + 1)
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> textAccumulator.append(event.value)
                            is AntigravityParsedEvent.ToolStarted -> onProgress("${event.name}: ${event.detail.take(80)}")
                            is AntigravityParsedEvent.ToolCompleted -> onProgress("Done: ${event.name}")
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    finalResponse = event.response?.takeIf { it.isNotBlank() } ?: textAccumulator.toString()
                                } else {
                                    errorMessage = event.error ?: event.status
                                }
                                done = true
                            }
                            else -> Unit
                        }
                        if (done) break
                        newline = pending.indexOf("\n")
                    }
                }

                val changedFiles = diffWorkspaceFiles(workspaceDir, beforeMtimes)
                if (errorMessage != null) {
                    TeamAgentExecutionResult(
                        responseText = errorMessage,
                        writtenFiles = changedFiles,
                        isError = true,
                        isRecoverable = isRecoverableErrorText(errorMessage),
                    )
                } else {
                    val reply = (finalResponse ?: textAccumulator.toString()).trim()
                    TeamAgentExecutionResult(
                        responseText = reply.ifBlank { "Task step completed in /workspace/$projectSlug." },
                        writtenFiles = changedFiles,
                        isError = false,
                        isRecoverable = false,
                    )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val changedFiles = diffWorkspaceFiles(workspaceDir, beforeMtimes)
            TeamAgentExecutionResult(
                responseText = "Timed out waiting for response (${timeout.message ?: "12m limit"})",
                writtenFiles = changedFiles,
                isError = true,
                isRecoverable = true,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            val msg = err.message?.take(240) ?: "Antigravity execution failed"
            TeamAgentExecutionResult(
                responseText = msg,
                writtenFiles = diffWorkspaceFiles(workspaceDir, beforeMtimes),
                isError = true,
                isRecoverable = isRecoverableErrorText(msg),
            )
        } finally {
            runCatching { process.destroy() }
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { outputFile.delete() }
        }
    }

    private suspend fun runApiProvider(
        agent: AgentState,
        providerConfig: ProviderConfig?,
        workspaceDir: File,
        prompt: String,
        imageFiles: List<File> = emptyList(),
        onProgress: suspend (String) -> Unit,
    ): TeamAgentExecutionResult {
        val cfg = providerConfig ?: return TeamAgentExecutionResult(
            responseText = "Provider not configured for ${agent.config.name}. Please select a provider in Team Settings.",
            isError = true,
        )
        val apiKey = providerManager.getApiKey(cfg.id)
        if (apiKey.isBlank()) {
            return TeamAgentExecutionResult(
                responseText = "API Key not set for ${cfg.name}. Please configure it in the Providers tab.",
                isError = true,
            )
        }
        onProgress("Calling ${cfg.name} (${agent.config.modelId})…")
        val adapter = providerManager.getAdapter(cfg.type)
        val systemInstruction = buildString {
            appendLine(agent.config.customPrompt.ifBlank { agent.config.role.systemInstructions })
            appendLine()
            appendLine("When you create or edit files, output each file inside a fenced code block with the relative file path header like:")
            appendLine("```file:index.html")
            appendLine("<!-- content -->")
            appendLine("```")
        }
        val imageAttachments = imageFiles.mapNotNull { file ->
            runCatching {
                val bytes = file.readBytes()
                if (bytes.isEmpty() || bytes.size > 6 * 1024 * 1024) return@runCatching null
                val mime = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "image/jpeg"
                }
                com.jarves.mh.provider.core.AiImageAttachment(
                    mimeType = mime,
                    base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                )
            }.getOrNull()
        }
        val fullText = StringBuilder()
        return try {
            withTimeout(8 * 60_000L) {
                adapter.sendChatCompletion(
                    config = cfg,
                    apiKey = apiKey,
                    messages = listOf(AiMessage(role = "user", content = prompt, images = imageAttachments)),
                    systemInstruction = systemInstruction,
                    modelId = agent.config.modelId.ifBlank { cfg.selectedModelId },
                    temperature = agent.config.temperature.toDouble(),
                    maxTokens = agent.config.maxTokens,
                ).collect { chunk ->
                    if (chunk.deltaText.isNotEmpty()) {
                        fullText.append(chunk.deltaText)
                    }
                }
            }
            val reply = fullText.toString().trim()
            val written = extractAndWriteFiles(workspaceDir, reply)
            providerManager.recordUsage(cfg.id, agent.config.modelId, prompt.length / 4, reply.length / 4, isError = false)
            TeamAgentExecutionResult(
                responseText = reply.ifBlank { "Completed." },
                writtenFiles = written,
                isError = false,
                isRecoverable = false,
            )
        } catch (timeout: TimeoutCancellationException) {
            providerManager.recordUsage(cfg.id, agent.config.modelId, prompt.length / 4, 0, isError = true)
            TeamAgentExecutionResult(
                responseText = "API request timed out (${timeout.message ?: "8m limit"})",
                isError = true,
                isRecoverable = true,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Throwable) {
            val msg = err.message?.take(240) ?: "API request failed"
            providerManager.recordUsage(cfg.id, agent.config.modelId, prompt.length / 4, 0, isError = true)
            TeamAgentExecutionResult(
                responseText = msg,
                isError = true,
                isRecoverable = isRecoverableErrorText(msg),
            )
        }
    }

    private fun isRecoverableErrorText(msg: String): Boolean {
        val lower = msg.lowercase()
        val permanent = lower.contains("401") ||
            lower.contains("403") ||
            lower.contains("unauthorized") ||
            lower.contains("api key") ||
            lower.contains("not installed") ||
            lower.contains("sign in") ||
            lower.contains("invalid_api_key")
        return !permanent
    }

    private fun snapshotWorkspaceFiles(workspaceDir: File): Map<String, Long> {
        if (!workspaceDir.exists()) return emptyMap()
        val map = mutableMapOf<String, Long>()
        workspaceDir.walkTopDown()
            .maxDepth(6)
            .onEnter { dir -> dir.name !in setOf(".git", "node_modules", "build", ".gradle") }
            .filter { it.isFile }
            .take(400)
            .forEach { f -> map[f.relativeTo(workspaceDir).path] = f.lastModified() xor f.length() }
        return map
    }

    private fun diffWorkspaceFiles(workspaceDir: File, before: Map<String, Long>): List<String> {
        val after = snapshotWorkspaceFiles(workspaceDir)
        return after.entries
            .filter { (path, sig) -> before[path] != sig }
            .map { it.key }
            .sorted()
    }

    private fun extractAndWriteFiles(workspaceDir: File, response: String): List<String> {
        val written = mutableListOf<String>()
        val rootCanonical = workspaceDir.canonicalFile.toPath()
        val pattern = Regex("```(?:file:|path:)?([A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)\\s*\\n([\\s\\S]*?)```")
        pattern.findAll(response).forEach { match ->
            val rawPath = match.groupValues[1].trim().removePrefix("/")
            val content = match.groupValues[2]
            if (rawPath.isNotBlank() && ".." !in rawPath) {
                val target = File(workspaceDir, rawPath).canonicalFile
                if (target.toPath().startsWith(rootCanonical)) {
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                    written.add(rawPath)
                }
            }
        }
        // Fallback: if response contains a single ```html block and no explicit file: header, save as index.html
        if (written.isEmpty()) {
            val htmlMatch = Regex("```html\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(response)
            if (htmlMatch != null) {
                val target = File(workspaceDir, "index.html")
                target.writeText(htmlMatch.groupValues[1])
                written.add("index.html")
            }
        }
        return written
    }
}
