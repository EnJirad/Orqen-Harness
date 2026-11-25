package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.persistence.*

class AgentRegistry(
    private val store: CollaborationStore,
) {
    fun register(profile: AgentProfileEntity): AgentProfileEntity {
        require(profile.agentId.isNotBlank()) { "agentId must not be blank" }
        require(profile.displayName.isNotBlank()) { "displayName must not be blank" }
        store.upsertProfile(profile)
        return profile
    }

    fun get(agentId: String): AgentProfileEntity? = store.getProfile(agentId)

    fun listAll(onlyEnabled: Boolean = false): List<AgentProfileEntity> = store.listProfiles(onlyEnabled)

    fun setEnabled(agentId: String, enabled: Boolean): AgentProfileEntity? {
        val current = store.getProfile(agentId) ?: return null
        val updated = current.copy(isEnabled = enabled, updatedAt = System.currentTimeMillis())
        store.upsertProfile(updated)
        return updated
    }
}

/**
 * Dynamic team manager supporting arbitrary configurable team sizes (1, 3, 6, 8, N agents).
 * Never hardcodes 2 or 4 agents.
 */
class AgentTeamManager(
    private val store: CollaborationStore,
    private val registry: AgentRegistry,
) {
    fun createTeam(
        projectId: String,
        teamName: String,
        memberAgentIds: List<String>,
        strategy: CollaborationStrategy = CollaborationStrategy.HIERARCHICAL,
        maxConcurrentAgents: Int = 2,
        maxDelegationDepth: Int = 3,
        requireIndependentReview: Boolean = true,
    ): AgentTeamEntity {
        val team = AgentTeamEntity(
            projectId = projectId,
            teamName = teamName,
            strategy = strategy,
            maxConcurrentAgents = maxConcurrentAgents.coerceAtLeast(1),
            maxDelegationDepth = maxDelegationDepth.coerceAtLeast(1),
            requireIndependentReview = requireIndependentReview,
        )
        store.upsertTeam(team)
        memberAgentIds.distinct().forEachIndexed { idx, agentId ->
            val prof = registry.get(agentId)
                ?: throw IllegalArgumentException("Unknown agent profile: $agentId")
            store.upsertTeamMember(
                AgentTeamMemberEntity(
                    teamId = team.teamId,
                    agentId = prof.agentId,
                    assignedRole = prof.role,
                    priority = prof.priority.coerceAtLeast(1) + (memberAgentIds.size - idx),
                    isActive = prof.isEnabled,
                ),
            )
        }
        return team
    }

    fun getTeamProfiles(teamId: String, onlyEnabled: Boolean = true): List<AgentProfileEntity> {
        return store.listTeamMembers(teamId)
            .mapNotNull { member -> registry.get(member.agentId) }
            .filter { !onlyEnabled || it.isEnabled }
    }
}

/**
 * Capability-based agent resolver selecting the most suitable enabled agent
 * considering required capabilities, provider availability, workload, and priority.
 */
class AgentCapabilityResolver(
    private val unavailableProviders: () -> Set<String> = { emptySet() },
) {
    fun selectBestAgent(
        candidates: List<AgentProfileEntity>,
        requiredCapabilities: Set<String>,
        activeWorkloads: Map<String, Int> = emptyMap(),
        excludeAgentIds: Set<String> = emptySet(),
    ): AgentProfileEntity? {
        val downProviders = unavailableProviders()
        val eligible = candidates.filter { agent ->
            agent.isEnabled &&
                agent.agentId !in excludeAgentIds &&
                agent.providerId !in downProviders &&
                (requiredCapabilities.isEmpty() || requiredCapabilities.all { it in agent.capabilities })
        }
        if (eligible.isEmpty()) return null
        return eligible.sortedWith(
            compareBy<AgentProfileEntity> { activeWorkloads[it.agentId] ?: 0 }
                .thenByDescending { it.priority }
                .thenBy { it.agentId },
        ).first()
    }
}

class AgentDelegationManager(
    private val collabStore: CollaborationStore,
    private val taskStore: OrqenTaskStore,
    private val capabilityResolver: AgentCapabilityResolver,
) {
    fun delegate(
        team: AgentTeamEntity,
        candidates: List<AgentProfileEntity>,
        taskId: String,
        parentStepId: String,
        delegatingAgentId: String,
        objective: String,
        requiredCapabilities: Set<String>,
        acceptanceCriteria: List<String> = emptyList(),
        currentDepth: Int = 1,
    ): AgentDelegationEntity {
        require(currentDepth <= team.maxDelegationDepth) {
            "Delegation depth $currentDepth exceeds configured team limit (${team.maxDelegationDepth})."
        }
        val assignee = capabilityResolver.selectBestAgent(
            candidates = candidates,
            requiredCapabilities = requiredCapabilities,
            excludeAgentIds = setOf(delegatingAgentId),
        ) ?: capabilityResolver.selectBestAgent(candidates, requiredCapabilities)
            ?: throw IllegalStateException("No capable agent available for capabilities: $requiredCapabilities")

        val delegation = AgentDelegationEntity(
            taskId = taskId,
            projectId = team.projectId,
            parentStepId = parentStepId,
            delegatingAgentId = delegatingAgentId,
            assignedAgentId = assignee.agentId,
            objective = objective,
            requiredCapabilities = requiredCapabilities,
            acceptanceCriteria = acceptanceCriteria,
            depth = currentDepth,
            status = DelegationStatus.PENDING,
        )
        collabStore.upsertDelegation(delegation)
        taskStore.insertAgentMessage(
            AgentMessageEntity(
                taskId = taskId,
                fromAgentId = delegatingAgentId,
                toAgentId = assignee.agentId,
                messageType = AgentMessageKind.TASK_DELEGATION,
                content = "[corr=${delegation.correlationId}] $objective",
                relatedStepId = parentStepId,
            ),
        )
        return delegation
    }

    fun completeDelegation(delegationId: String, resultSummary: String): AgentDelegationEntity {
        val current = requireNotNull(collabStore.getDelegation(delegationId)) { "Delegation not found: $delegationId" }
        val completed = current.copy(
            status = DelegationStatus.COMPLETED,
            resultSummary = resultSummary,
            completedAt = System.currentTimeMillis(),
        )
        collabStore.upsertDelegation(completed)
        taskStore.insertAgentMessage(
            AgentMessageEntity(
                taskId = completed.taskId,
                fromAgentId = completed.assignedAgentId,
                toAgentId = completed.delegatingAgentId,
                messageType = AgentMessageKind.COMPLETION_REPORT,
                content = "[corr=${completed.correlationId}] $resultSummary",
                relatedStepId = completed.parentStepId,
            ),
        )
        return completed
    }
}

/**
 * Persistent direct & broadcast inter-agent communication with strict context isolation.
 */
class AgentCommunicationManager(
    private val taskStore: OrqenTaskStore,
) {
    private val processedMessageIds = mutableSetOf<String>()

    fun sendDirect(
        taskId: String,
        fromAgentId: String,
        toAgentId: String,
        type: AgentMessageKind,
        content: String,
        relatedStepId: String? = null,
        correlationId: String? = null,
    ): AgentMessageEntity {
        val body = if (correlationId != null) "[corr=$correlationId] $content" else content
        val msg = AgentMessageEntity(
            taskId = taskId,
            fromAgentId = fromAgentId,
            toAgentId = toAgentId,
            messageType = type,
            content = SecretSanitizer.sanitize(body),
            relatedStepId = relatedStepId,
        )
        taskStore.insertAgentMessage(msg)
        return msg
    }

    fun broadcast(
        taskId: String,
        fromAgentId: String,
        type: AgentMessageKind,
        content: String,
        relatedStepId: String? = null,
    ): AgentMessageEntity = sendDirect(
        taskId = taskId,
        fromAgentId = fromAgentId,
        toAgentId = BROADCAST_TARGET,
        type = type,
        content = content,
        relatedStepId = relatedStepId,
    )

    /**
     * Strict per-agent isolated inbox: returns ONLY messages for this specific [taskId]
     * addressed directly to [agentId] or broadcast to the task, preventing cross-project
     * and unrelated-agent transcript leaks.
     */
    fun getIsolatedContextMessages(
        projectId: String,
        taskId: String,
        agentId: String,
        correlationId: String? = null,
    ): List<AgentMessageEntity> {
        val task = taskStore.getTask(taskId) ?: return emptyList()
        if (task.projectId != projectId) {
            throw SecurityException("Cross-project access denied: task $taskId does not belong to project $projectId")
        }
        return taskStore.listAgentMessagesForTask(taskId, limit = 200).filter { msg ->
            val targeted = msg.toAgentId == agentId || msg.toAgentId == BROADCAST_TARGET || msg.fromAgentId == agentId
            val matchesCorr = correlationId == null || msg.content.contains("[corr=$correlationId]")
            targeted && matchesCorr
        }
    }

    @Synchronized
    fun markProcessedOnce(messageId: String): Boolean = processedMessageIds.add(messageId)

    companion object {
        const val BROADCAST_TARGET = "*"
    }
}
