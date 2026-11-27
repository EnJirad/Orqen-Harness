package com.jarves.mh.runtime

import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.persistence.AgentSessionEntity
import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.WorkspacePathValidator
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExecutionAgentRole {
    SUPERVISOR,
    DEVELOPER,
    REVIEWER,
    QA,
    GENERAL,
}

/**
 * Shared execution capability policy enforced architecturally (not only via prompts)
 * across BOTH Single Project and AI Team agents.
 */
data class AgentExecutionPermissions(
    val allowWorkspaceTools: Boolean,
    val readOnly: Boolean,
    val allowShellCommands: Boolean,
    val allowFileDeletion: Boolean,
    val allowMcpTools: Boolean,
    val allowTaskCreation: Boolean,
    val allowDelegation: Boolean,
)

/**
 * Architectural permission guard.
 *
 * - CHAT mode is strictly consultation-only: zero tools, zero shell, zero file writes,
 *   zero MCP calls, zero task creation, zero delegation.
 * - REVIEWER role defaults to read-only inspection so reviewers inspect independently
 *   without silently editing implementation files.
 * - SPV, DEVELOPER, QA, and SOLO all share the same underlying execution engine.
 */
object AgentPermissionGuard {
    fun forChatMode(): AgentExecutionPermissions = AgentExecutionPermissions(
        allowWorkspaceTools = false,
        readOnly = true,
        allowShellCommands = false,
        allowFileDeletion = false,
        allowMcpTools = false,
        allowTaskCreation = false,
        allowDelegation = false,
    )

    fun forTaskExecution(
        role: ExecutionAgentRole,
        allowShell: Boolean = true,
        allowMcp: Boolean = true,
        allowDelete: Boolean = true,
    ): AgentExecutionPermissions = when (role) {
        ExecutionAgentRole.REVIEWER -> AgentExecutionPermissions(
            allowWorkspaceTools = true,
            readOnly = true,
            allowShellCommands = allowShell,
            allowFileDeletion = false,
            allowMcpTools = allowMcp,
            allowTaskCreation = false,
            allowDelegation = false,
        )
        ExecutionAgentRole.SUPERVISOR -> AgentExecutionPermissions(
            allowWorkspaceTools = true,
            readOnly = false,
            allowShellCommands = allowShell,
            allowFileDeletion = allowDelete,
            allowMcpTools = allowMcp,
            allowTaskCreation = true,
            allowDelegation = true,
        )
        else -> AgentExecutionPermissions(
            allowWorkspaceTools = true,
            readOnly = false,
            allowShellCommands = allowShell,
            allowFileDeletion = allowDelete,
            allowMcpTools = allowMcp,
            allowTaskCreation = false,
            allowDelegation = false,
        )
    }
}

/**
 * Manages isolated, persistent per-agent execution sessions backed by [OrqenTaskStore].
 * Guarantees every agent has its own session ID, provider conversation ID, and context
 * without accidentally sharing private provider sessions across agents.
 */
class AgentSessionManager(
    private val store: OrqenTaskStore,
) {
    private val activeCache = ConcurrentHashMap<String, AgentSessionEntity>()

    fun getOrCreateSession(
        agentId: String,
        projectId: String,
        taskId: String? = null,
        assignmentId: String? = null,
        providerId: String = "",
        modelId: String = "",
    ): AgentSessionEntity {
        val cacheKey = "${agentId.trim()}|${projectId.trim()}|${taskId.orEmpty()}"
        val existing = activeCache[cacheKey]
            ?: store.findActiveAgentSession(agentId, projectId, taskId)
        if (existing != null &&
            (providerId.isBlank() || existing.providerId == providerId) &&
            (modelId.isBlank() || existing.modelId == modelId)
        ) {
            val refreshed = existing.copy(
                assignmentId = assignmentId ?: existing.assignmentId,
                updatedAt = System.currentTimeMillis(),
            )
            activeCache[cacheKey] = refreshed
            store.upsertAgentSession(refreshed)
            return refreshed
        }
        val created = AgentSessionEntity(
            sessionId = "sess-${agentId.take(12)}-${UUID.randomUUID().toString().take(8)}",
            agentId = agentId,
            projectId = projectId,
            taskId = taskId,
            assignmentId = assignmentId,
            providerId = providerId,
            modelId = modelId,
            nativeConversationId = null,
            status = "ACTIVE",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        activeCache[cacheKey] = created
        store.upsertAgentSession(created)
        return created
    }

    fun updateNativeConversationId(sessionId: String, nativeConversationId: String?) {
        val existing = store.getAgentSession(sessionId) ?: return
        val updated = existing.copy(
            nativeConversationId = nativeConversationId,
            updatedAt = System.currentTimeMillis(),
        )
        store.upsertAgentSession(updated)
        val cacheKey = "${updated.agentId.trim()}|${updated.projectId.trim()}|${updated.taskId.orEmpty()}"
        activeCache[cacheKey] = updated
    }

    fun closeSession(sessionId: String) {
        val existing = store.getAgentSession(sessionId) ?: return
        val closed = existing.copy(status = "CLOSED", updatedAt = System.currentTimeMillis())
        store.upsertAgentSession(closed)
        val cacheKey = "${closed.agentId.trim()}|${closed.projectId.trim()}|${closed.taskId.orEmpty()}"
        activeCache.remove(cacheKey)
    }
}

/**
 * Shared, security-hardened workspace file & ZIP export service used by BOTH
 * Single Project (Solo) and AI Team Files tabs and agent runtimes.
 */
object WorkspaceZipExporter {
    val DEFAULT_EXCLUDED_DIRS = setOf(
        ".git",
        "node_modules",
        ".gradle",
        "build",
        ".claude",
        ".kotlin",
        "__pycache__",
    )
    val DEFAULT_EXCLUDED_FILES = setOf(
        ".claude.json",
        ".orqen_chat.json",
        ".DS_Store",
    )

    data class ZipExportResult(
        val success: Boolean,
        val entryCount: Int,
        val totalBytesWritten: Long,
        val errorMessage: String? = null,
    )

    /**
     * Writes a verified ZIP archive of [relativePaths] (files or folders) inside [workspaceRoot]
     * into [outputStream].
     *
     * Security guarantees:
     * - Blocks all path traversal (`..`, absolute escape, canonical path outside workspace root)
     * - Skips all symbolic links (preventing symlink escape attacks)
     * - Excludes unsafe/internal directories (`.git`, `node_modules`, `.gradle`, `build`, `.claude`)
     * - Preserves clean relative folder structure inside the archive
     * - Rejects empty archives (never produces a bogus 0-entry ZIP)
     */
    fun exportToStream(
        workspaceRoot: File,
        relativePaths: List<String>,
        outputStream: OutputStream,
        excludedDirs: Set<String> = DEFAULT_EXCLUDED_DIRS,
        excludedFiles: Set<String> = DEFAULT_EXCLUDED_FILES,
    ): ZipExportResult {
        val rootCanonical = runCatching { workspaceRoot.canonicalFile }.getOrElse {
            return ZipExportResult(false, 0, 0L, "Invalid workspace root: ${it.message}")
        }
        val rootPath = rootCanonical.toPath()
        val filesToZip = linkedMapOf<String, File>() // zipEntryName -> canonical File

        for (rawRel in relativePaths) {
            val cleanRel = rawRel.trim().replace('\\', '/').trimStart('/')
            if (cleanRel.isBlank() && relativePaths.size > 1) continue
            val target = if (cleanRel.isBlank()) {
                rootCanonical
            } else {
                if (!WorkspacePathValidator.isPathInsideRoot(rootCanonical, cleanRel)) {
                    return ZipExportResult(false, 0, 0L, "Blocked path traversal attempt: $rawRel")
                }
                File(rootCanonical, cleanRel)
            }
            if (!target.exists()) continue
            if (Files.isSymbolicLink(target.toPath())) continue
            val targetCanonical = runCatching { target.canonicalFile }.getOrNull() ?: continue
            if (!targetCanonical.toPath().startsWith(rootPath)) {
                return ZipExportResult(false, 0, 0L, "Blocked symlink/canonical escape: $rawRel")
            }

            if (targetCanonical.isFile) {
                if (targetCanonical.name in excludedFiles) continue
                val entryName = targetCanonical.relativeTo(rootCanonical).path.replace('\\', '/')
                if (entryName.isNotBlank() && !entryName.startsWith("..")) {
                    filesToZip[entryName] = targetCanonical
                }
            } else if (targetCanonical.isDirectory) {
                targetCanonical.walkTopDown()
                    .onEnter { dir ->
                        if (Files.isSymbolicLink(dir.toPath())) return@onEnter false
                        val canon = runCatching { dir.canonicalFile }.getOrNull() ?: return@onEnter false
                        if (!canon.toPath().startsWith(rootPath)) return@onEnter false
                        dir == rootCanonical || dir.name !in excludedDirs
                    }
                    .forEach { f ->
                        if (!f.isFile) return@forEach
                        if (Files.isSymbolicLink(f.toPath())) return@forEach
                        if (f.name in excludedFiles) return@forEach
                        val canon = runCatching { f.canonicalFile }.getOrNull() ?: return@forEach
                        if (!canon.toPath().startsWith(rootPath)) return@forEach
                        val entryName = canon.relativeTo(rootCanonical).path.replace('\\', '/')
                        if (entryName.isNotBlank() && !entryName.startsWith("..")) {
                            filesToZip[entryName] = canon
                        }
                    }
            }
        }

        if (filesToZip.isEmpty()) {
            return ZipExportResult(false, 0, 0L, "No exportable files found in selection.")
        }

        var totalBytes = 0L
        ZipOutputStream(outputStream).use { zos ->
            filesToZip.forEach { (entryName, file) ->
                zos.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input ->
                    val copied = input.copyTo(zos)
                    totalBytes += copied
                }
                zos.closeEntry()
            }
            zos.finish()
        }
        return ZipExportResult(
            success = true,
            entryCount = filesToZip.size,
            totalBytesWritten = totalBytes,
        )
    }

    /**
     * Convenience wrapper that writes to a target [File] and verifies the resulting archive
     * exists and is non-empty.
     */
    fun exportToFile(
        workspaceRoot: File,
        relativePaths: List<String>,
        destinationZip: File,
        excludedDirs: Set<String> = DEFAULT_EXCLUDED_DIRS,
        excludedFiles: Set<String> = DEFAULT_EXCLUDED_FILES,
    ): ZipExportResult {
        destinationZip.parentFile?.mkdirs()
        val result = destinationZip.outputStream().use { out ->
            exportToStream(workspaceRoot, relativePaths, out, excludedDirs, excludedFiles)
        }
        if (!result.success || !destinationZip.isFile || destinationZip.length() <= 0L) {
            runCatching { destinationZip.delete() }
            return result.copy(
                success = false,
                errorMessage = result.errorMessage ?: "Generated ZIP archive is empty or missing.",
            )
        }
        return result
    }
}

/**
 * Shared workspace execution facade providing file inspection, safe mutations,
 * command/test/build execution, and lock-aware tool brokering for Single Project and AI Team.
 */
class SharedWorkspaceExecutionService(
    private val workspaceDir: File,
    private val projectSlug: String,
    private val commandRunner: WorkspaceCommandRunner? = null,
    private val fileLockManager: FileLockManager? = null,
    private val mcpToolInvoker: (suspend (toolId: String, argsJson: String) -> Pair<Boolean, String>)? = null,
) {
    fun createToolExecutor(
        permissions: AgentExecutionPermissions,
        agentId: String? = null,
    ): WorkspaceToolExecutor = WorkspaceToolExecutor(
        workspaceDir = workspaceDir,
        projectSlug = projectSlug,
        commandRunner = if (permissions.allowShellCommands) commandRunner else null,
        allowShellCommands = permissions.allowShellCommands && commandRunner != null,
        readOnly = permissions.readOnly,
        agentId = agentId,
        fileLockManager = fileLockManager,
        mcpToolInvoker = if (permissions.allowMcpTools) mcpToolInvoker else null,
    )

    /**
     * Inspects the real workspace structure so SPV can build project-aware plans
     * based on actual files, stack, build system, and existing source code.
     */
    fun inspectWorkspaceSummary(maxFiles: Int = 120): WorkspaceInspectionSnapshot {
        if (!workspaceDir.isDirectory) {
            return WorkspaceInspectionSnapshot(
                workspacePath = workspaceDir.absolutePath,
                exists = false,
                detectedStack = "UNKNOWN",
                buildSystem = "NONE",
                topLevelEntries = emptyList(),
                sourceFiles = emptyList(),
                testFiles = emptyList(),
                hasGitRepo = false,
            )
        }
        val ignored = WorkspaceZipExporter.DEFAULT_EXCLUDED_DIRS
        val topLevel = workspaceDir.listFiles()
            ?.filter { it.name !in ignored }
            ?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?.sorted()
            .orEmpty()

        val allFiles = workspaceDir.walkTopDown()
            .maxDepth(5)
            .onEnter { it.name !in ignored }
            .filter { it.isFile }
            .take(maxFiles)
            .map { it.relativeTo(workspaceDir).path.replace('\\', '/') }
            .sorted()
            .toList()

        val testFiles = allFiles.filter { path ->
            path.contains("/test/", ignoreCase = true) ||
                path.contains("/tests/", ignoreCase = true) ||
                path.endsWith("Test.kt") ||
                path.endsWith("Test.java") ||
                path.endsWith(".test.ts") ||
                path.endsWith(".test.js") ||
                path.endsWith("_test.py") ||
                path.startsWith("test_")
        }

        val hasGradle = allFiles.any { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
        val hasAndroidManifest = allFiles.any { it.endsWith("AndroidManifest.xml") }
        val hasPackageJson = allFiles.any { it == "package.json" || it.endsWith("/package.json") }
        val hasPyproject = allFiles.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") }
        val hasCargo = allFiles.any { it == "Cargo.toml" }
        val hasGoMod = allFiles.any { it == "go.mod" }
        val hasHtml = allFiles.any { it.endsWith(".html") }

        val stack = when {
            hasAndroidManifest && hasGradle -> "ANDROID_KOTLIN_GRADLE"
            hasGradle -> "JVM_GRADLE"
            hasPackageJson -> "NODE_WEB"
            hasPyproject -> "PYTHON"
            hasCargo -> "RUST"
            hasGoMod -> "GO"
            hasHtml -> "STATIC_WEB"
            else -> "GENERAL_WORKSPACE"
        }
        val buildSys = when {
            hasGradle -> "GRADLE"
            hasPackageJson -> "NPM"
            hasCargo -> "CARGO"
            hasGoMod -> "GO"
            hasPyproject -> "PYTHON"
            else -> "NONE"
        }

        return WorkspaceInspectionSnapshot(
            workspacePath = workspaceDir.absolutePath,
            exists = true,
            detectedStack = stack,
            buildSystem = buildSys,
            topLevelEntries = topLevel,
            sourceFiles = allFiles,
            testFiles = testFiles,
            hasGitRepo = File(workspaceDir, ".git").isDirectory,
        )
    }
}

data class WorkspaceInspectionSnapshot(
    val workspacePath: String,
    val exists: Boolean,
    val detectedStack: String,
    val buildSystem: String,
    val topLevelEntries: List<String>,
    val sourceFiles: List<String>,
    val testFiles: List<String>,
    val hasGitRepo: Boolean,
)
