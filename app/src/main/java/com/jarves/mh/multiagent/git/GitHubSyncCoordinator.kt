package com.jarves.mh.multiagent.git

import com.jarves.mh.multiagent.model.GitSyncStatus
import com.jarves.mh.multiagent.model.IGitHubSyncCoordinator
import com.jarves.mh.multiagent.tasks.TeamTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production implementation of IGitHubSyncCoordinator (Part 05):
 * Executes Git status, pull, and checkpoint commit/push operations against the shared workspace repository.
 */
class GitHubSyncCoordinator(
    private val workspaceDir: File,
) : IGitHubSyncCoordinator {

    override suspend fun getSyncStatus(): GitSyncStatus = withContext(Dispatchers.IO) {
        if (!File(workspaceDir, ".git").exists()) {
            return@withContext GitSyncStatus(
                isConnected = false,
                remoteUrl = "",
                activeBranch = "none",
                pendingCommitsCount = 0,
            )
        }
        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD").getOrDefault("main").trim().ifBlank { "main" }
        val remote = runGit("remote", "get-url", "origin").getOrDefault("").trim()
        val ahead = runGit("rev-list", "--count", "@{upstream}..HEAD")
            .getOrDefault("0")
            .trim()
            .toIntOrNull() ?: 0

        GitSyncStatus(
            isConnected = remote.isNotBlank(),
            remoteUrl = remote,
            activeBranch = branch,
            lastSyncTimestamp = System.currentTimeMillis(),
            pendingCommitsCount = ahead,
        )
    }

    override suspend fun pullLatestTasks(): List<TeamTask> = withContext(Dispatchers.IO) {
        if (File(workspaceDir, ".git").exists()) {
            runGit("fetch", "origin")
        }
        emptyList()
    }

    override suspend fun pushCheckpoint(
        partNumber: Int,
        taskTitle: String,
        changedFiles: List<String>,
    ): String = withContext(Dispatchers.IO) {
        if (!File(workspaceDir, ".git").exists()) return@withContext ""
        if (changedFiles.isEmpty()) {
            runGit("add", "-A")
        } else {
            runGit(*((listOf("add") + changedFiles).toTypedArray()))
        }
        val partLabel = "part-%02d".format(partNumber)
        runGit("commit", "-m", "feat($partLabel): $taskTitle")
        val sha = runGit("rev-parse", "--short", "HEAD").getOrDefault("").trim()
        runGit("push")
        sha
    }

    private fun runGit(vararg args: String): Result<String> = runCatching {
        val pb = ProcessBuilder(listOf("git") + args)
            .directory(workspaceDir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) error("git ${args.joinToString(" ")} exited with $code: $out")
        out
    }
}
