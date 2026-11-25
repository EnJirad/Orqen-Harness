package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.CheckpointEntity
import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import java.io.File
import java.security.MessageDigest

data class WorkspaceInspectionResult(
    val consistent: Boolean,
    val unexpectedModifiedFiles: List<String>,
    val missingFiles: List<String>,
    val currentSnapshot: Map<String, String>,
    val summary: String,
)

/**
 * Manages durable filesystem checkpoints and integrity verification.
 *
 * - Never stores large source code blobs inside SQLite; stores SHA-256 file manifests + metadata.
 * - Inspects the real filesystem before resuming interrupted execution.
 * - Detects partial/inconsistent writes without discarding user changes or resetting Git HEAD.
 */
class CheckpointManager(
    private val store: OrqenTaskStore,
) {
    fun snapshotWorkspace(workspaceDir: File): Map<String, String> {
        if (!workspaceDir.exists() || !workspaceDir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val rootPath = workspaceDir.canonicalFile.toPath()
        workspaceDir.walkTopDown()
            .maxDepth(8)
            .onEnter { dir ->
                dir == workspaceDir || (
                    dir.name !in IGNORED_DIRS &&
                        !java.nio.file.Files.isSymbolicLink(dir.toPath()) &&
                        runCatching { dir.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .take(MAX_SNAPSHOT_FILES)
            .forEach { file ->
                val rel = file.relativeTo(workspaceDir).invariantSeparatorsPath
                result[rel] = sha256(file)
            }
        return result
    }

    fun diffSnapshots(before: Map<String, String>, after: Map<String, String>): List<String> {
        return (before.keys + after.keys)
            .distinct()
            .filter { before[it] != after[it] }
            .sorted()
    }

    fun buildCheckpoint(
        taskId: String,
        stepId: String,
        workspaceDir: File,
        modifiedFiles: List<String>,
        contextSummary: String,
        gitCommitSha: String? = null,
    ): CheckpointEntity {
        val snap = snapshotWorkspace(workspaceDir)
        return CheckpointEntity(
            taskId = taskId,
            stepId = stepId,
            workspaceSnapshot = snap,
            modifiedFiles = modifiedFiles.distinct().sorted(),
            gitCommitSha = gitCommitSha ?: readGitHeadSha(workspaceDir),
            contextSummary = contextSummary.take(2_000),
            timestamp = System.currentTimeMillis(),
        )
    }

    fun createCheckpoint(
        taskId: String,
        stepId: String,
        workspaceDir: File,
        modifiedFiles: List<String>,
        contextSummary: String,
        gitCommitSha: String? = null,
    ): CheckpointEntity {
        val checkpoint = buildCheckpoint(taskId, stepId, workspaceDir, modifiedFiles, contextSummary, gitCommitSha)
        store.insertCheckpoint(checkpoint)
        return checkpoint
    }

    /**
     * Inspects actual workspace files against the latest durable checkpoint before resuming.
     * Detects partial writes (e.g., crash mid-write, truncated/temp marker files, or checksum drift).
     */
    fun verifyWorkspaceAgainstCheckpoint(
        workspaceDir: File,
        checkpoint: CheckpointEntity?,
    ): WorkspaceInspectionResult {
        val current = snapshotWorkspace(workspaceDir)
        val tempPartialMarkers = current.keys.filter {
            it.endsWith(".tmp") || it.endsWith(".partial") || it.endsWith(".orqen_writing")
        }
        if (checkpoint == null) {
            val consistent = tempPartialMarkers.isEmpty()
            return WorkspaceInspectionResult(
                consistent = consistent,
                unexpectedModifiedFiles = tempPartialMarkers,
                missingFiles = emptyList(),
                currentSnapshot = current,
                summary = if (consistent) {
                    "Workspace inspected (${current.size} files, no prior checkpoint)."
                } else {
                    "Detected incomplete partial write markers: ${tempPartialMarkers.joinToString(", ")}"
                },
            )
        }

        val expected = checkpoint.workspaceSnapshot
        val missing = expected.keys.filter { it !in current }.sorted()
        val modified = current.entries
            .filter { (path, hash) -> expected[path] != hash }
            .map { it.key }
            .sorted()

        val consistent = missing.isEmpty() && modified.isEmpty() && tempPartialMarkers.isEmpty()
        val summary = when {
            consistent -> "Workspace matches checkpoint ${checkpoint.checkpointId} (${current.size} files verified)."
            tempPartialMarkers.isNotEmpty() -> "Inconsistent workspace: partial write files detected (${tempPartialMarkers.joinToString(", ")})."
            else -> "Workspace diverged from checkpoint ${checkpoint.checkpointId}: modified=${modified.size}, missing=${missing.size}."
        }
        return WorkspaceInspectionResult(
            consistent = consistent,
            unexpectedModifiedFiles = (modified + tempPartialMarkers).distinct().sorted(),
            missingFiles = missing,
            currentSnapshot = current,
            summary = summary,
        )
    }

    /**
     * Atomic file write using temp file + rename to prevent partial writes on crash.
     */
    fun writeFileAtomically(workspaceDir: File, relativePath: String, content: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") && ".." !in relativePath.split('/', '\\')) {
            "Unsafe workspace path: $relativePath"
        }
        val rootCanonical = workspaceDir.canonicalFile.toPath()
        val target = File(workspaceDir, relativePath).canonicalFile
        require(target.toPath().startsWith(rootCanonical)) { "Path escapes workspace: $relativePath" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
        return target
    }

    private fun readGitHeadSha(workspaceDir: File): String? {
        val headFile = File(workspaceDir, ".git/HEAD")
        if (!headFile.isFile) return null
        val head = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
        return if (head.startsWith("ref:")) {
            val refPath = head.removePrefix("ref:").trim()
            runCatching { File(workspaceDir, ".git/$refPath").takeIf(File::isFile)?.readText()?.trim() }.getOrNull()
        } else {
            head.takeIf { it.length >= 7 }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SNAPSHOT_FILES = 1_000
        private val IGNORED_DIRS = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".orqen_uploads", ".venv", "venv")
    }
}
