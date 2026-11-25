package com.jarves.mh.multiagent.ui

import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.PersistentTaskOrchestrator
import com.jarves.mh.multiagent.engine.UsageSource
import com.jarves.mh.multiagent.host.ExecutionHost
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.recovery.InterruptionCause
import com.jarves.mh.multiagent.recovery.ProcessRecoveryCoordinator
import com.jarves.mh.multiagent.session.MultiSessionChatController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CommandCenterSection {
    HOME,
    PROJECTS,
    TASKS,
    AGENTS,
    WORKERS,
    ACTIVITY,
    SETTINGS,
}

enum class TaskFilterMode {
    ALL,
    RUNNING,
    PENDING,
    PAUSED,
    RECOVERING,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class ExecutionHostPreference {
    LOCAL_ONLY,
    REMOTE_ONLY,
    AUTO,
    HYBRID,
}

data class WorkerUiStatus(
    val workerId: String,
    val displayName: String,
    val mode: ExecutionMode,
    val isOnline: Boolean,
    val activeTaskCount: Int,
    val maxConcurrency: Int,
    val capabilities: Set<String>,
    val statusDetail: String,
)

data class UsageUiSummary(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val reportedCostUsd: Double,
    val source: UsageSource,
    val label: String,
)

data class GlobalSearchResult(
    val resourceType: String, // PROJECT, CONVERSATION, TASK, AGENT
    val resourceId: String,
    val title: String,
    val subtitle: String,
    val projectId: String?,
)

data class CommandCenterUiState(
    val activeSection: CommandCenterSection = CommandCenterSection.HOME,
    val backStack: List<CommandCenterSection> = emptyList(),
    val selectedProjectId: String? = null,
    val selectedTaskId: String? = null,
    val selectedConversationId: String? = null,
    val taskFilter: TaskFilterMode = TaskFilterMode.ALL,
    val searchQuery: String = "",
    val hostPreference: ExecutionHostPreference = ExecutionHostPreference.LOCAL_ONLY,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isOffline: Boolean = false,
    val projects: List<ProjectEntity> = emptyList(),
    val conversations: List<ConversationEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val filteredTasks: List<TaskEntity> = emptyList(),
    val timelineEvents: List<TaskEventEntity> = emptyList(),
    val agents: List<AgentProfileEntity> = emptyList(),
    val delegations: List<AgentDelegationEntity> = emptyList(),
    val workers: List<WorkerUiStatus> = emptyList(),
    val pendingApprovals: List<OwnerApprovalRequest> = emptyList(),
    val usageSummary: UsageUiSummary = UsageUiSummary(0L, 0L, 0.0, UsageSource.UNKNOWN, "No usage recorded yet"),
    val searchResults: List<GlobalSearchResult> = emptyList(),
)

/**
 * Unidirectional Data Flow (UDF) presentation controller for the ORQEN AI Development Command Center.
 *
 * Strictly observes authoritative persistent stores and delegates every state mutation
 * to [PersistentTaskOrchestrator], [MultiSessionChatController], [ProcessRecoveryCoordinator],
 * and [AgentOrchestrator]. Never duplicates business logic or exposes raw secrets.
 */
class OrqenCommandCenterController(
    private val taskStore: OrqenTaskStore,
    private val collabStore: CollaborationStore,
    private val chatController: MultiSessionChatController,
    private val orchestrator: PersistentTaskOrchestrator,
    private val recoveryCoordinator: ProcessRecoveryCoordinator? = null,
    private val executionHosts: List<ExecutionHost> = emptyList(),
) {
    private val _state = MutableStateFlow(CommandCenterUiState())
    val state: StateFlow<CommandCenterUiState> = _state.asStateFlow()

    private val processedSubmissionTokens = LinkedHashSet<String>()

    fun refresh(timelineOffset: Int = 0, timelineLimit: Int = 50) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val projects = taskStore.listProjects()
            val curProjectId = _state.value.selectedProjectId ?: projects.firstOrNull()?.projectId
            val conversations = if (curProjectId != null) {
                taskStore.listConversationsForProject(curProjectId, includeArchived = false)
            } else emptyList()
            val allTasks = if (curProjectId != null) {
                taskStore.listTasksForProject(curProjectId)
            } else taskStore.listAllTasks()

            val filter = _state.value.taskFilter
            val filtered = applyTaskFilter(allTasks, filter, _state.value.searchQuery)
            val selTaskId = _state.value.selectedTaskId ?: filtered.firstOrNull()?.taskId
            val events = if (selTaskId != null) {
                taskStore.listTaskEventsPaged(selTaskId, offset = timelineOffset, limit = timelineLimit)
            } else emptyList()

            val agents = collabStore.listProfiles(onlyEnabled = false)
            val delegations = if (selTaskId != null) collabStore.listDelegationsForTask(selTaskId) else emptyList()
            val approvals = allTasks.flatMap { collabStore.listApprovalsForTask(it.taskId) }
                .filter { it.status == ApprovalStatus.PENDING }

            val workers = executionHosts.map { host ->
                WorkerUiStatus(
                    workerId = host.hostId,
                    displayName = if (host.mode == ExecutionMode.LOCAL_ANDROID) "Local Android Host" else "Remote Worker (${host.hostId})",
                    mode = host.mode,
                    isOnline = host.isConnected,
                    activeTaskCount = allTasks.count { it.status == PersistentTaskStatus.RUNNING && it.executionMode == host.mode },
                    maxConcurrency = 2,
                    capabilities = if (host.mode == ExecutionMode.LOCAL_ANDROID) setOf("local", "proot", "android") else setOf("remote", "container"),
                    statusDetail = if (host.isConnected) "Connected" else "Disconnected",
                )
            }

            val totalIn = allTasks.sumOf { it.totalInputTokens }
            val totalOut = allTasks.sumOf { it.totalOutputTokens }
            val totalCost = allTasks.sumOf { it.actualCostUsd }
            val usageSource = if (totalCost > 0.0 || totalIn > 0L) UsageSource.ACTUAL_PROVIDER else UsageSource.UNKNOWN
            val usage = UsageUiSummary(
                totalInputTokens = totalIn,
                totalOutputTokens = totalOut,
                reportedCostUsd = totalCost,
                source = usageSource,
                label = when (usageSource) {
                    UsageSource.ACTUAL_PROVIDER -> "Reported provider usage"
                    UsageSource.ESTIMATED_LOCAL -> "Estimated local usage"
                    UsageSource.UNKNOWN -> "No provider usage reported yet"
                },
            )

            _state.update {
                it.copy(
                    isLoading = false,
                    selectedProjectId = curProjectId,
                    selectedTaskId = selTaskId,
                    projects = projects,
                    conversations = conversations,
                    tasks = allTasks,
                    filteredTasks = filtered,
                    timelineEvents = events,
                    agents = agents,
                    delegations = delegations,
                    workers = workers,
                    pendingApprovals = approvals,
                    usageSummary = usage,
                )
            }
        } catch (err: Throwable) {
            _state.update {
                it.copy(
                    isLoading = false,
                    errorMessage = SecretSanitizer.sanitize(err.message ?: "Failed to load state"),
                )
            }
        }
    }

    fun navigateTo(section: CommandCenterSection) {
        _state.update { cur ->
            if (cur.activeSection == section) cur
            else cur.copy(
                backStack = (cur.backStack + cur.activeSection).takeLast(12),
                activeSection = section,
            )
        }
    }

    fun navigateBack(): Boolean {
        val stack = _state.value.backStack
        if (stack.isEmpty()) return false
        _state.update { cur ->
            cur.copy(
                activeSection = stack.last(),
                backStack = stack.dropLast(1),
            )
        }
        return true
    }

    fun openDeepLink(resourceType: String, resourceId: String, projectId: String? = null) {
        when (resourceType.uppercase()) {
            "TASK" -> {
                val t = taskStore.getTask(resourceId)
                _state.update {
                    it.copy(
                        selectedProjectId = projectId ?: t?.projectId ?: it.selectedProjectId,
                        selectedTaskId = resourceId,
                        activeSection = CommandCenterSection.TASKS,
                    )
                }
            }
            "CONVERSATION" -> {
                val c = taskStore.getConversation(resourceId)
                _state.update {
                    it.copy(
                        selectedProjectId = projectId ?: c?.projectId ?: it.selectedProjectId,
                        selectedConversationId = resourceId,
                        activeSection = CommandCenterSection.PROJECTS,
                    )
                }
            }
            "AGENT" -> {
                _state.update { it.copy(activeSection = CommandCenterSection.AGENTS) }
            }
            else -> {
                _state.update {
                    it.copy(
                        selectedProjectId = projectId ?: resourceId,
                        activeSection = CommandCenterSection.PROJECTS,
                    )
                }
            }
        }
        refresh()
    }

    fun selectProject(projectId: String) {
        _state.update {
            it.copy(
                selectedProjectId = projectId,
                selectedTaskId = null,
                selectedConversationId = null,
            )
        }
        refresh()
    }

    fun selectTask(taskId: String) {
        _state.update { it.copy(selectedTaskId = taskId) }
        refresh()
    }

    fun setTaskFilter(filter: TaskFilterMode) {
        _state.update { cur ->
            cur.copy(
                taskFilter = filter,
                filteredTasks = applyTaskFilter(cur.tasks, filter, cur.searchQuery),
            )
        }
    }

    fun setHostPreference(pref: ExecutionHostPreference) {
        _state.update { it.copy(hostPreference = pref) }
    }

    /**
     * Prevents accidental duplicate chat/command submissions caused by rapid taps or recomposition.
     */
    @Synchronized
    fun submitMessageOnce(
        submissionToken: String,
        conversationId: String,
        projectId: String,
        senderName: String,
        text: String,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (!processedSubmissionTokens.add(submissionToken)) return false
        chatController.postMessage(
            conversationId = conversationId,
            projectId = projectId,
            senderId = "owner",
            senderName = senderName,
            isUser = true,
            content = trimmed,
        )
        refresh()
        return true
    }

    fun pauseTask(taskId: String) {
        orchestrator.pauseTask(taskId, "Paused from Command Center")
        refresh()
    }

    fun resumeTask(scope: CoroutineScope, taskId: String) {
        orchestrator.resumeTask(scope, taskId)
        refresh()
    }

    fun stopTask(taskId: String) {
        orchestrator.stopTask(taskId, "Cancelled from Command Center")
        refresh()
    }

    fun triggerRecoveryReconciliation() {
        recoveryCoordinator?.reconcileOnStartup(InterruptionCause.UNEXPECTED_PROCESS_DEATH)
        refresh()
    }

    fun resolveApproval(approvalId: String, approved: Boolean) {
        val cur = collabStore.getApproval(approvalId) ?: return
        collabStore.upsertApproval(
            cur.copy(
                status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
                resolvedAt = System.currentTimeMillis(),
            ),
        )
        refresh()
    }

    fun searchAuthorized(query: String): List<GlobalSearchResult> {
        val q = query.trim().lowercase()
        val projectId = _state.value.selectedProjectId
        val results = mutableListOf<GlobalSearchResult>()
        if (q.isBlank()) {
            _state.update { it.copy(searchQuery = "", searchResults = emptyList()) }
            return emptyList()
        }

        taskStore.listProjects()
            .filter { it.projectName.lowercase().contains(q) }
            .forEach { p -> results.add(GlobalSearchResult("PROJECT", p.projectId, p.projectName, p.workspacePath, p.projectId)) }

        val scopedTasks = if (projectId != null) taskStore.listTasksForProject(projectId) else taskStore.listAllTasks()
        scopedTasks.filter { it.title.lowercase().contains(q) || it.taskId.lowercase().contains(q) }
            .forEach { t -> results.add(GlobalSearchResult("TASK", t.taskId, "${t.taskId} · ${t.title}", t.status.name, t.projectId)) }

        if (projectId != null) {
            taskStore.listConversationsForProject(projectId, includeArchived = true)
                .filter { it.title.lowercase().contains(q) }
                .forEach { c -> results.add(GlobalSearchResult("CONVERSATION", c.conversationId, c.title, if (c.archived) "Archived" else "Active", c.projectId)) }
        }

        collabStore.listProfiles(onlyEnabled = false)
            .filter { it.displayName.lowercase().contains(q) || it.role.lowercase().contains(q) }
            .forEach { a -> results.add(GlobalSearchResult("AGENT", a.agentId, a.displayName, "${a.role} · ${a.modelId}", null)) }

        _state.update {
            it.copy(
                searchQuery = query,
                searchResults = results,
                filteredTasks = applyTaskFilter(it.tasks, it.taskFilter, query),
            )
        }
        return results
    }

    fun canHandoffToRemote(taskId: String): Pair<Boolean, String> {
        val remote = executionHosts.firstOrNull { it.mode == ExecutionMode.REMOTE_HOST }
            ?: return false to "No remote worker host is configured or deployed."
        if (!remote.isConnected) return false to "Remote worker '${remote.hostId}' is offline."
        val task = taskStore.getTask(taskId) ?: return false to "Task not found."
        if (task.ownerStopRequested || task.status == PersistentTaskStatus.CANCELLED) {
            return false to "Cancelled tasks cannot be handed off."
        }
        return true to "Eligible for checkpointed handoff to ${remote.hostId}."
    }

    private fun applyTaskFilter(tasks: List<TaskEntity>, filter: TaskFilterMode, query: String): List<TaskEntity> {
        val q = query.trim().lowercase()
        return tasks.filter { t ->
            val matchesFilter = when (filter) {
                TaskFilterMode.ALL -> true
                TaskFilterMode.RUNNING -> t.status == PersistentTaskStatus.RUNNING
                TaskFilterMode.PENDING -> t.status in setOf(PersistentTaskStatus.DRAFT, PersistentTaskStatus.QUEUED, PersistentTaskStatus.PLANNING, PersistentTaskStatus.READY)
                TaskFilterMode.PAUSED -> t.status == PersistentTaskStatus.PAUSED || t.status == PersistentTaskStatus.PAUSE_REQUESTED
                TaskFilterMode.RECOVERING -> t.status == PersistentTaskStatus.RECOVERING
                TaskFilterMode.BLOCKED -> t.status == PersistentTaskStatus.BLOCKED
                TaskFilterMode.COMPLETED -> t.status == PersistentTaskStatus.COMPLETED
                TaskFilterMode.FAILED -> t.status == PersistentTaskStatus.FAILED
                TaskFilterMode.CANCELLED -> t.status == PersistentTaskStatus.CANCELLED
            }
            val matchesQuery = q.isBlank() || t.title.lowercase().contains(q) || t.taskId.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    companion object {
        /**
         * Masks API keys for UI display — never shows complete secrets after saving.
         */
        fun maskSecret(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.length <= 8) return "••••••••"
            return "${trimmed.take(4)}••••••••${trimmed.takeLast(4)}"
        }
    }
}
