package com.jarves.mh.runtime

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import com.jarves.mh.multiagent.persistence.SecretSanitizer
import com.jarves.mh.multiagent.persistence.WorkspacePathValidator
import com.jarves.mh.provider.core.AiImageAttachment
import com.jarves.mh.provider.core.AiMessage
import com.jarves.mh.provider.core.ProviderAdapter
import com.jarves.mh.provider.core.ProviderConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class TestExecutionOutcome {
    TESTS_PENDING,
    TESTS_RUNNING,
    TESTS_PASSED,
    TESTS_FAILED,
    TESTS_ERROR,
}

data class ToolExecutionEvidence(
    val toolName: String,
    val command: String? = null,
    val targetPath: String? = null,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val success: Boolean,
    val modifiedFiles: List<String> = emptyList(),
    val isTestCommand: Boolean = false,
    val isBuildCommand: Boolean = false,
)

data class TestExecutionEvidence(
    val command: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val outcome: TestExecutionOutcome,
    val summary: String,
    val taskId: String? = null,
    val stepId: String? = null,
    val workingDir: String = "",
    val passedCount: Int? = null,
    val failedCount: Int? = null,
    val skippedCount: Int? = null,
    val artifactPath: String? = null,
    val artifactSizeBytes: Long = 0L,
)

data class BuildVerificationEvidence(
    val command: String,
    val exitCode: Int,
    val apkPath: String?,
    val apkSizeBytes: Long,
    val buildVariant: String,
    val success: Boolean,
    val errorSummary: String? = null,
)

data class CommandExecutionResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
)

/**
 * Pluggable command execution backend.
 * In Android production, backed by PRoot via [RuntimeInstaller.process].
 * In JVM unit/integration tests, backed by a real local shell runner or controlled test runner.
 */
fun interface WorkspaceCommandRunner {
    suspend fun run(
        workspaceDir: File,
        projectSlug: String,
        command: String,
        timeoutMs: Long,
    ): CommandExecutionResult
}

/**
 * Production PRoot command runner using the exact same [RuntimeInstaller] infrastructure
 * as Single Project execution and Team Terminal.
 */
class ProotWorkspaceCommandRunner(
    private val installer: RuntimeInstaller,
    private val cacheDir: File,
) : WorkspaceCommandRunner {
    override suspend fun run(
        workspaceDir: File,
        projectSlug: String,
        command: String,
        timeoutMs: Long,
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val installed = installer.installedRuntime()
        val outFile = File(cacheDir, "orqen-tool-cmd-${System.nanoTime()}.log")
        val proc = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspaceDir,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", command),
            guestWorkspacePath = "/workspace/$projectSlug",
            emulateHardLinks = false,
            outputFile = outFile,
        )
        try {
            proc.outputStream.close()
            val exitCode = withTimeout(timeoutMs) {
                proc.waitFor()
            }
            val rawOut = runCatching { outFile.readTailText(64 * 1024) }.getOrDefault("")
            CommandExecutionResult(
                command = command,
                exitCode = exitCode,
                stdout = SecretSanitizer.sanitize(rawOut),
                stderr = "",
                startedAtMs = started,
                completedAtMs = System.currentTimeMillis(),
            )
        } catch (err: Throwable) {
            runCatching { proc.destroy() }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }
            val partialOut = runCatching { outFile.readTailText(16 * 1024) }.getOrDefault("")
            CommandExecutionResult(
                command = command,
                exitCode = -1,
                stdout = SecretSanitizer.sanitize(partialOut),
                stderr = SecretSanitizer.sanitize(err.message ?: err.javaClass.simpleName),
                startedAtMs = started,
                completedAtMs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { if (proc.isAlive) proc.destroyForcibly() }
            runCatching { outFile.delete() }
        }
    }
}

/**
 * Shared, security-hardened Workspace Tool Executor giving ALL AI Team agents
 * (API providers, CLI agents, SPV, background workflows) the same real execution
 * capabilities as Single Project AI:
 * - read_file, write_file, edit_file, list_dir, search_files
 * - run_command (real shell / Gradle / test / git commands with exit code & output capture)
 * - verify_apk (real non-empty APK verification on disk)
 */
class WorkspaceToolExecutor(
    private val workspaceDir: File,
    private val projectSlug: String,
    private val commandRunner: WorkspaceCommandRunner? = null,
    private val allowShellCommands: Boolean = true,
    private val readOnly: Boolean = false,
    private val agentId: String? = null,
    private val fileLockManager: FileLockManager? = null,
    private val mcpToolInvoker: (suspend (toolId: String, argsJson: String) -> Pair<Boolean, String>)? = null,
) {
    private val blockedCommandPatterns = listOf(
        Regex("""rm\s+-rf\s+/(?:\s|$)"""),
        Regex("""mkfs\."""),
        Regex("""dd\s+if=.*of=/dev/"""),
        Regex(""">\s*/dev/sd[a-z]"""),
        Regex("""curl\s+.*\|\s*(?:sh|bash)"""),
        Regex("""wget\s+.*\|\s*(?:sh|bash)"""),
    )

    suspend fun executeTool(
        toolName: String,
        argsJson: String,
        taskId: String? = null,
        stepId: String? = null,
    ): Triple<ToolExecutionEvidence, TestExecutionEvidence?, BuildVerificationEvidence?> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        when (toolName.lowercase().trim()) {
            "read_file" -> {
                val relPath = args.optString("path").trim().removePrefix("/")
                if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, relPath)) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "read_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Path traversal or invalid path blocked: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val file = File(workspaceDir, relPath)
                if (!file.isFile) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "read_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "File not found: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
                val endLine = args.optInt("end_line", Int.MAX_VALUE)
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                val slice = lines.drop(startLine - 1).take((endLine - startLine + 1).coerceIn(1, 500))
                val numbered = slice.mapIndexed { idx, line -> "${startLine + idx}: $line" }.joinToString("\n").take(24_000)
                Triple(
                    ToolExecutionEvidence(
                        toolName = "read_file",
                        targetPath = relPath,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = numbered,
                        success = true,
                    ),
                    null,
                    null,
                )
            }

            "write_file" -> {
                val relPath = args.optString("path").trim().removePrefix("/")
                if (readOnly) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "write_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Write blocked: executor is in read-only mode.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, relPath)) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "write_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Path traversal or invalid path blocked: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val content = args.optString("content", "")
                if (fileLockManager != null && !agentId.isNullOrBlank()) {
                    val lockRes = fileLockManager.tryAcquireByAgentId(
                        filePath = relPath,
                        agentId = agentId,
                        taskId = taskId ?: "direct",
                        reason = "write_file",
                    )
                    if (lockRes !is LockAcquisitionResult.Success) {
                        return@withContext Triple(
                            ToolExecutionEvidence(
                                toolName = "write_file",
                                targetPath = relPath,
                                startedAtMs = started,
                                completedAtMs = System.currentTimeMillis(),
                                stderr = "File lock conflict on $relPath: $lockRes",
                                success = false,
                            ),
                            null,
                            null,
                        )
                    }
                }
                val target = File(workspaceDir, relPath).canonicalFile
                target.parentFile?.mkdirs()
                target.writeText(content)
                Triple(
                    ToolExecutionEvidence(
                        toolName = "write_file",
                        targetPath = relPath,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = "Wrote ${content.length} chars to $relPath",
                        success = true,
                        modifiedFiles = listOf(relPath),
                    ),
                    null,
                    null,
                )
            }

            "edit_file" -> {
                val relPath = args.optString("path").trim().removePrefix("/")
                if (readOnly) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "edit_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Edit blocked: executor is in read-only mode.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, relPath)) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "edit_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Path traversal or invalid path blocked: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val targetFile = File(workspaceDir, relPath).canonicalFile
                if (!targetFile.isFile) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "edit_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "File not found: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val oldText = args.optString("target", "")
                val newText = args.optString("replacement", "")
                val current = targetFile.readText()
                if (oldText.isEmpty() || !current.contains(oldText)) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "edit_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Target text not found in $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val updated = current.replace(oldText, newText)
                if (fileLockManager != null && !agentId.isNullOrBlank()) {
                    val lockRes = fileLockManager.tryAcquireByAgentId(
                        filePath = relPath,
                        agentId = agentId,
                        taskId = taskId ?: "direct",
                        reason = "edit_file",
                    )
                    if (lockRes !is LockAcquisitionResult.Success) {
                        return@withContext Triple(
                            ToolExecutionEvidence(
                                toolName = "edit_file",
                                targetPath = relPath,
                                startedAtMs = started,
                                completedAtMs = System.currentTimeMillis(),
                                stderr = "File lock conflict on $relPath: $lockRes",
                                success = false,
                            ),
                            null,
                            null,
                        )
                    }
                }
                targetFile.writeText(updated)
                Triple(
                    ToolExecutionEvidence(
                        toolName = "edit_file",
                        targetPath = relPath,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = "Updated $relPath (${updated.length} chars)",
                        success = true,
                        modifiedFiles = listOf(relPath),
                    ),
                    null,
                    null,
                )
            }

            "delete_file" -> {
                val relPath = args.optString("path").trim().removePrefix("/")
                if (readOnly) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "delete_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Delete blocked: executor is in read-only mode.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (relPath.isBlank() || !WorkspacePathValidator.isPathInsideRoot(workspaceDir, relPath)) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "delete_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Path traversal or invalid delete path blocked: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val targetFile = File(workspaceDir, relPath).canonicalFile
                if (targetFile == workspaceDir.canonicalFile) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "delete_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Refused to delete workspace root directory.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (!targetFile.exists()) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "delete_file",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "File or directory not found: $relPath",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (fileLockManager != null && !agentId.isNullOrBlank()) {
                    val lockRes = fileLockManager.tryAcquireByAgentId(
                        filePath = relPath,
                        agentId = agentId,
                        taskId = taskId ?: "direct",
                        reason = "delete_file",
                    )
                    if (lockRes !is LockAcquisitionResult.Success) {
                        return@withContext Triple(
                            ToolExecutionEvidence(
                                toolName = "delete_file",
                                targetPath = relPath,
                                startedAtMs = started,
                                completedAtMs = System.currentTimeMillis(),
                                stderr = "File lock conflict on $relPath: $lockRes",
                                success = false,
                            ),
                            null,
                            null,
                        )
                    }
                }
                val deleted = if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
                Triple(
                    ToolExecutionEvidence(
                        toolName = "delete_file",
                        targetPath = relPath,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = if (deleted) "Deleted $relPath" else "",
                        stderr = if (deleted) "" else "Failed to delete $relPath",
                        success = deleted,
                        modifiedFiles = if (deleted) listOf(relPath) else emptyList(),
                    ),
                    null,
                    null,
                )
            }

            "mcp_call" -> {
                val mcpToolId = args.optString("tool", "").trim()
                val mcpArgs = args.optJSONObject("arguments")?.toString() ?: args.optString("arguments", "{}")
                if (mcpToolInvoker == null) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "mcp_call",
                            targetPath = mcpToolId,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "MCP tool execution is not authorized or configured for this session.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val (ok, output) = mcpToolInvoker.invoke(mcpToolId, mcpArgs)
                Triple(
                    ToolExecutionEvidence(
                        toolName = "mcp_call",
                        targetPath = mcpToolId,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = if (ok) output.take(16_000) else "",
                        stderr = if (ok) "" else output.take(8_000),
                        success = ok,
                    ),
                    null,
                    null,
                )
            }

            "list_dir" -> {
                val relPath = args.optString("path", "").trim().removePrefix("/")
                val dir = if (relPath.isBlank()) {
                    workspaceDir
                } else {
                    if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, relPath)) {
                        return@withContext Triple(
                            ToolExecutionEvidence(
                                toolName = "list_dir",
                                targetPath = relPath,
                                startedAtMs = started,
                                completedAtMs = System.currentTimeMillis(),
                                stderr = "Path traversal blocked: $relPath",
                                success = false,
                            ),
                            null,
                            null,
                        )
                    }
                    File(workspaceDir, relPath)
                }
                if (!dir.isDirectory) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "list_dir",
                            targetPath = relPath,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Directory not found: ${relPath.ifBlank { "." }}",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val maxDepth = args.optInt("max_depth", 3).coerceIn(1, 6)
                val ignored = setOf(".git", "node_modules", "build", ".gradle")
                val entries = dir.walkTopDown()
                    .maxDepth(maxDepth)
                    .onEnter { it.name !in ignored }
                    .filter { it != dir }
                    .take(250)
                    .map { f ->
                        val rel = f.relativeTo(workspaceDir).path
                        if (f.isDirectory) "$rel/" else "$rel (${f.length()}B)"
                    }
                    .sorted()
                    .joinToString("\n")
                Triple(
                    ToolExecutionEvidence(
                        toolName = "list_dir",
                        targetPath = relPath.ifBlank { "." },
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = entries.ifBlank { "(empty directory)" },
                        success = true,
                    ),
                    null,
                    null,
                )
            }

            "search_files" -> {
                val query = args.optString("query", "").trim()
                if (query.isBlank()) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "search_files",
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Search query must not be blank.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val ignored = setOf(".git", "node_modules", "build", ".gradle")
                val matches = mutableListOf<String>()
                workspaceDir.walkTopDown()
                    .maxDepth(6)
                    .onEnter { it.name !in ignored }
                    .filter { it.isFile && it.length() <= 512_000L }
                    .forEach { file ->
                        if (matches.size >= 80) return@forEach
                        val rel = file.relativeTo(workspaceDir).path
                        runCatching {
                            file.readLines().forEachIndexed { idx, line ->
                                if (matches.size < 80 && line.contains(query, ignoreCase = true)) {
                                    matches.add("$rel:${idx + 1}: ${line.trim().take(160)}")
                                }
                            }
                        }
                    }
                Triple(
                    ToolExecutionEvidence(
                        toolName = "search_files",
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        stdout = matches.joinToString("\n").ifBlank { "No matches for '$query'." },
                        success = true,
                    ),
                    null,
                    null,
                )
            }

            "run_command" -> {
                val cmd = args.optString("command", "").trim()
                if (!allowShellCommands || commandRunner == null) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "run_command",
                            command = cmd,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Shell execution is disabled or unavailable in this environment.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (cmd.isBlank()) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "run_command",
                            command = cmd,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Command must not be blank.",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                if (blockedCommandPatterns.any { it.containsMatchIn(cmd) }) {
                    return@withContext Triple(
                        ToolExecutionEvidence(
                            toolName = "run_command",
                            command = cmd,
                            startedAtMs = started,
                            completedAtMs = System.currentTimeMillis(),
                            stderr = "Blocked potentially destructive shell command: $cmd",
                            success = false,
                        ),
                        null,
                        null,
                    )
                }
                val timeoutMs = args.optLong("timeout_ms", 180_000L).coerceIn(5_000L, 900_000L)
                val res = commandRunner.run(workspaceDir, projectSlug, cmd, timeoutMs)
                val isTest = isTestCommand(cmd)
                val isBuild = isBuildCommand(cmd)
                val toolEv = ToolExecutionEvidence(
                    toolName = "run_command",
                    command = cmd,
                    startedAtMs = res.startedAtMs,
                    completedAtMs = res.completedAtMs,
                    exitCode = res.exitCode,
                    stdout = res.stdout.take(16_000),
                    stderr = res.stderr.take(8_000),
                    success = res.exitCode == 0,
                    isTestCommand = isTest,
                    isBuildCommand = isBuild,
                )
                val testEv = if (isTest) {
                    val counts = parseTestCounts(res.stdout + "\n" + res.stderr)
                    TestExecutionEvidence(
                        command = cmd,
                        startedAtMs = res.startedAtMs,
                        completedAtMs = res.completedAtMs,
                        exitCode = res.exitCode,
                        stdout = res.stdout.take(12_000),
                        stderr = res.stderr.take(4_000),
                        outcome = when (res.exitCode) {
                            0 -> TestExecutionOutcome.TESTS_PASSED
                            -1 -> TestExecutionOutcome.TESTS_ERROR
                            else -> TestExecutionOutcome.TESTS_FAILED
                        },
                        summary = if (res.exitCode == 0) "Tests passed (exit 0): $cmd" else "Tests failed (exit ${res.exitCode}): $cmd",
                        taskId = taskId,
                        stepId = stepId,
                        workingDir = workspaceDir.absolutePath,
                        passedCount = counts.first,
                        failedCount = counts.second,
                        skippedCount = counts.third,
                    )
                } else null
                val buildEv = if (isBuild) {
                    val apk = locateVerifiedApk(workspaceDir, "debug")
                    BuildVerificationEvidence(
                        command = cmd,
                        exitCode = res.exitCode,
                        apkPath = apk?.relativeTo(workspaceDir)?.path,
                        apkSizeBytes = apk?.length() ?: 0L,
                        buildVariant = "debug",
                        success = res.exitCode == 0 && (apk == null || apk.length() > 0L),
                        errorSummary = if (res.exitCode != 0) (res.stderr.ifBlank { res.stdout.takeLast(600) }) else null,
                    )
                } else null
                Triple(toolEv, testEv, buildEv)
            }

            "verify_apk" -> {
                val variant = args.optString("variant", "debug").trim().ifBlank { "debug" }
                val apk = locateVerifiedApk(workspaceDir, variant)
                val ok = apk != null && apk.isFile && apk.length() > 0L
                val buildEv = BuildVerificationEvidence(
                    command = "verify_apk($variant)",
                    exitCode = if (ok) 0 else 1,
                    apkPath = apk?.relativeTo(workspaceDir)?.path,
                    apkSizeBytes = apk?.length() ?: 0L,
                    buildVariant = variant,
                    success = ok,
                    errorSummary = if (ok) null else "No non-empty APK found for variant '$variant'",
                )
                Triple(
                    ToolExecutionEvidence(
                        toolName = "verify_apk",
                        targetPath = buildEv.apkPath,
                        startedAtMs = started,
                        completedAtMs = System.currentTimeMillis(),
                        exitCode = if (ok) 0 else 1,
                        stdout = if (ok) "Verified APK: ${buildEv.apkPath} (${buildEv.apkSizeBytes} bytes)" else "",
                        stderr = buildEv.errorSummary.orEmpty(),
                        success = ok,
                        isBuildCommand = true,
                    ),
                    null,
                    buildEv,
                )
            }

            else -> Triple(
                ToolExecutionEvidence(
                    toolName = toolName,
                    startedAtMs = started,
                    completedAtMs = System.currentTimeMillis(),
                    stderr = "Unknown workspace tool: $toolName",
                    success = false,
                ),
                null,
                null,
            )
        }
    }

    companion object {
        fun toolPromptInstructions(allowShell: Boolean = true, readOnly: Boolean = false): String = buildString {
            appendLine("## WORKSPACE EXECUTION TOOLS (Real Project Environment)")
            appendLine("You have REAL tools to inspect, edit, test, and build this project. Never fabricate file contents, command outputs, test results, or build results.")
            appendLine("To invoke a tool, emit one or more lines in this exact format:")
            appendLine("""[TOOL_CALL name="<tool_name>" args={"key":"value"}]""")
            appendLine("Available tools:")
            appendLine("""- read_file: {"path":"relative/path","start_line":1,"end_line":200}""")
            appendLine("""- list_dir: {"path":"","max_depth":3}""")
            appendLine("""- search_files: {"query":"searchTerm"}""")
            if (!readOnly) {
                appendLine("""- write_file: {"path":"relative/path","content":"full file content"}""")
                appendLine("""- edit_file: {"path":"relative/path","target":"exact old text","replacement":"new text"}""")
                appendLine("""- delete_file: {"path":"relative/path"}""")
            }
            if (allowShell) {
                appendLine("""- run_command: {"command":"./gradlew testDebugUnitTest"} (runs real shell, Gradle, test, git commands)""")
                appendLine("""- verify_apk: {"variant":"debug"} (verifies built APK exists and is non-empty)""")
            }
            appendLine("""- mcp_call: {"tool":"<server>:<toolName>","arguments":{}} (only for authorized MCP tools)""")
            appendLine("You may also request help from another team agent if blocked: [HELP to=<agentId>] <specific question>")
            appendLine("You may also create/edit files using fenced blocks: ```file:relative/path ... ```")
            appendLine("After emitting [TOOL_CALL ...] lines, stop and wait for real [TOOL_RESULT ...] outputs before claiming completion.")
        }

        private val testCommandPatterns = listOf(
            Regex("""(?:^|[\s/&|;])(?:\./)?gradlew?\b.*\b(?:test\w*|check|connected\w*androidtest)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])pytest\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])python3?\s+-m\s+(?:pytest|unittest)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])(?:npm|pnpm|yarn|bun)\s+(?:run\s+)?test\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])(?:npx\s+)?(?:jest|vitest|mocha|ava)\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])cargo\s+test\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])go\s+test\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])(?:mvn|\./mvnw)\b.*\btest\b""", RegexOption.IGNORE_CASE),
            Regex("""(?:^|[\s/&|;])(?:dotnet\s+test|ctest|phpunit|rspec|bundle\s+exec\s+rspec)\b""", RegexOption.IGNORE_CASE),
        )

        fun isTestCommand(command: String): Boolean {
            val trimmed = command.trim()
            if (trimmed.isBlank()) return false
            return testCommandPatterns.any { it.containsMatchIn(trimmed) }
        }

        fun parseTestCounts(output: String): Triple<Int?, Int?, Int?> {
            if (output.isBlank()) return Triple(null, null, null)
            val passed = Regex("""(\d+)\s+(?:tests?\s+)?passed\b""", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val failed = Regex("""(\d+)\s+(?:tests?\s+)?failed\b""", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val skipped = Regex("""(\d+)\s+(?:tests?\s+)?skipped\b""", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            return Triple(passed, failed, skipped)
        }

        fun isBuildCommand(command: String): Boolean {
            val lower = command.lowercase()
            return lower.contains("assembledebug") ||
                lower.contains("assemblerelease") ||
                lower.contains("gradle") && lower.contains("assemble") ||
                lower.contains("npm run build") ||
                lower.contains("cargo build")
        }

        fun locateVerifiedApk(workspaceDir: File, variant: String = "debug"): File? {
            val variantDir = File(workspaceDir, "app/build/outputs/apk/$variant")
            val searchRoot = if (variantDir.isDirectory) variantDir else File(workspaceDir, "app/build/outputs/apk")
            if (!searchRoot.isDirectory) return null
            return searchRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.length() > 0L }
                .maxByOrNull { it.lastModified() }
        }
    }
}

data class AgenticLoopResult(
    val finalText: String,
    val modifiedFiles: List<String>,
    val toolEvidence: List<ToolExecutionEvidence>,
    val testEvidence: List<TestExecutionEvidence>,
    val buildEvidence: List<BuildVerificationEvidence>,
    val turnsExecuted: Int,
    val estimatedPromptTokens: Int,
    val estimatedCompletionTokens: Int,
)

/**
 * Multi-step autonomous execution loop for API providers.
 * Gives API-backed agents the exact same multi-turn read -> plan -> edit -> run -> diagnose -> fix -> verify
 * loop as Single Project CLI agents.
 */
object AgenticToolLoop {
    private val toolCallHeaderRegex = Regex("""\[TOOL_CALL\s+name="([^"]+)"\s+args=""")

    /**
     * Parses `[TOOL_CALL name="..." args={...}]` blocks supporting multi-line JSON objects
     * with nested braces and escaped quotes (fixing single-line regex truncation on write_file/edit_file).
     */
    fun parseToolCalls(text: String, maxCalls: Int = 8): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        var searchFrom = 0
        while (results.size < maxCalls && searchFrom < text.length) {
            val match = toolCallHeaderRegex.find(text, searchFrom) ?: break
            val toolName = match.groupValues[1].trim()
            val jsonStart = match.range.last + 1
            if (jsonStart >= text.length || text[jsonStart] != '{') {
                searchFrom = match.range.last + 1
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var jsonEnd = -1
            for (i in jsonStart until text.length) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') depth++
                    else if (ch == '}') {
                        depth--
                        if (depth == 0) {
                            jsonEnd = i
                            break
                        }
                    }
                }
            }
            if (jsonEnd > jsonStart) {
                val argsJson = text.substring(jsonStart, jsonEnd + 1)
                results.add(toolName to argsJson)
                searchFrom = jsonEnd + 1
            } else {
                searchFrom = match.range.last + 1
            }
        }
        return results
    }

    suspend fun run(
        adapter: ProviderAdapter,
        config: ProviderConfig,
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        initialPrompt: String,
        imageAttachments: List<AiImageAttachment>,
        temperature: Double,
        maxTokens: Int,
        maxResponseBytes: Int,
        executor: WorkspaceToolExecutor,
        workspaceDir: File,
        extractFencedFiles: (File, String) -> List<String>,
        maxTurns: Int = 10,
        taskId: String? = null,
        stepId: String? = null,
        onProgress: suspend (String) -> Unit = {},
    ): AgenticLoopResult {
        val messages = mutableListOf(
            AiMessage(role = "user", content = initialPrompt, images = imageAttachments),
        )
        val allModified = linkedSetOf<String>()
        val allToolEvidence = mutableListOf<ToolExecutionEvidence>()
        val allTestEvidence = mutableListOf<TestExecutionEvidence>()
        val allBuildEvidence = mutableListOf<BuildVerificationEvidence>()
        var totalPromptChars = initialPrompt.length + systemInstruction.length
        var totalCompletionChars = 0
        var lastReply = ""
        var turns = 0

        for (turn in 1..maxTurns.coerceIn(1, 20)) {
            turns = turn
            val fullText = StringBuilder()
            adapter.sendChatCompletion(
                config = config,
                apiKey = apiKey,
                messages = messages,
                systemInstruction = systemInstruction,
                modelId = modelId,
                temperature = temperature,
                maxTokens = maxTokens,
            ).collect { chunk ->
                if (chunk.deltaText.isNotEmpty() && fullText.length < maxResponseBytes) {
                    fullText.append(chunk.deltaText)
                }
            }
            val reply = fullText.toString().trim()
            lastReply = reply
            totalCompletionChars += reply.length

            // Extract any ```file:... blocks written directly in the response
            val fencedWritten = extractFencedFiles(workspaceDir, reply)
            allModified.addAll(fencedWritten)

            val toolCalls = parseToolCalls(reply, maxCalls = 6)
            if (toolCalls.isEmpty()) {
                break
            }

            messages.add(AiMessage(role = "assistant", content = reply))
            val resultBlock = StringBuilder()
            for ((toolName, argsJson) in toolCalls) {
                onProgress("TOOL_STARTED:$toolName:${argsJson.take(80)}")
                val (toolEv, testEv, buildEv) = executor.executeTool(toolName, argsJson, taskId, stepId)
                onProgress("TOOL_COMPLETED:$toolName")
                allToolEvidence.add(toolEv)
                allModified.addAll(toolEv.modifiedFiles)
                if (testEv != null) allTestEvidence.add(testEv)
                if (buildEv != null) allBuildEvidence.add(buildEv)

                resultBlock.appendLine(
                    "[TOOL_RESULT name=\"${toolEv.toolName}\" ok=${toolEv.success}" +
                        (toolEv.exitCode?.let { " exitCode=$it" } ?: "") + "]",
                )
                if (toolEv.stdout.isNotBlank()) {
                    resultBlock.appendLine(toolEv.stdout.take(4_000))
                }
                if (toolEv.stderr.isNotBlank()) {
                    resultBlock.appendLine("STDERR: ${toolEv.stderr.take(2_000)}")
                }
                resultBlock.appendLine("[/TOOL_RESULT]")
            }
            val toolFeedback = resultBlock.toString().trim()
            totalPromptChars += toolFeedback.length
            messages.add(
                AiMessage(
                    role = "user",
                    content = "Real tool execution results (turn $turn/$maxTurns):\n$toolFeedback\n\nContinue your work based on these real results. Fix any errors and verify tests/builds before finishing.",
                ),
            )
        }

        return AgenticLoopResult(
            finalText = lastReply.ifBlank { "Completed." },
            modifiedFiles = allModified.toList(),
            toolEvidence = allToolEvidence,
            testEvidence = allTestEvidence,
            buildEvidence = allBuildEvidence,
            turnsExecuted = turns,
            estimatedPromptTokens = (totalPromptChars / 4).coerceAtLeast(1),
            estimatedCompletionTokens = (totalCompletionChars / 4).coerceAtLeast(0),
        )
    }
}
