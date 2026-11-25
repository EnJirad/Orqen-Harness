package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.persistence.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent peer review manager.
 * Blocks self-approval when independent review is required, records structured findings,
 * and triggers revision loops on CHANGES_REQUESTED / REJECTED.
 */
class AgentReviewManager(
    private val collabStore: CollaborationStore,
    private val taskStore: OrqenTaskStore,
) {
    fun submitReview(
        taskId: String,
        reviewerAgentId: String,
        implementingAgentId: String,
        reviewedExecutionId: String,
        reviewedStepId: String,
        outcome: ReviewOutcome,
        findings: List<String> = emptyList(),
        severity: ReviewSeverity = ReviewSeverity.INFO,
        evidenceReferences: List<String> = emptyList(),
        requiredChanges: List<String> = emptyList(),
        requireIndependentReviewer: Boolean = true,
    ): AgentReviewEntity {
        if (requireIndependentReviewer) {
            require(reviewerAgentId != implementingAgentId) {
                "Independent review violation: agent '$implementingAgentId' cannot approve its own work."
            }
        }
        val review = AgentReviewEntity(
            taskId = taskId,
            reviewerAgentId = reviewerAgentId,
            implementingAgentId = implementingAgentId,
            reviewedExecutionId = reviewedExecutionId,
            reviewedStepId = reviewedStepId,
            outcome = outcome,
            findings = findings,
            severity = severity,
            evidenceReferences = evidenceReferences,
            requiredChanges = requiredChanges,
        )
        collabStore.insertReview(review)
        val msgKind = when (outcome) {
            ReviewOutcome.APPROVED, ReviewOutcome.APPROVED_WITH_WARNINGS -> AgentMessageKind.REVIEW_APPROVAL
            ReviewOutcome.CHANGES_REQUESTED, ReviewOutcome.REJECTED -> AgentMessageKind.CHANGE_REQUEST
            ReviewOutcome.BLOCKED, ReviewOutcome.NEEDS_HUMAN_REVIEW -> AgentMessageKind.ESCALATION_TO_LEADER
        }
        taskStore.insertAgentMessage(
            AgentMessageEntity(
                taskId = taskId,
                fromAgentId = reviewerAgentId,
                toAgentId = implementingAgentId,
                messageType = msgKind,
                content = "${outcome.name} (${severity.name}): ${(findings + requiredChanges).joinToString("; ")}",
                relatedStepId = reviewedStepId,
            ),
        )
        return review
    }
}

/**
 * Evidence-based consensus & disagreement resolver.
 * Never picks a technically insecure or requirement-violating proposal by naive majority vote.
 */
class AgentConsensusManager(
    private val collabStore: CollaborationStore,
) {
    fun evaluateProposals(
        taskId: String,
        topic: String,
        proposals: List<TechnicalProposal>,
        highImpactRequiresOwner: Boolean = false,
    ): AgentDecisionEntity {
        require(proposals.isNotEmpty()) { "At least one proposal is required" }

        val validProposals = proposals.filter { it.passesSecurityCheck && it.satisfiesRequirements }
        val (outcome, rationale, needsOwner) = when {
            validProposals.isEmpty() -> Triple(
                ConsensusOutcome.BLOCKED,
                "All proposals failed security or requirement verification.",
                true,
            )
            highImpactRequiresOwner -> Triple(
                ConsensusOutcome.ESCALATE_TO_OWNER,
                "High-impact architectural/security decision requires explicit owner approval.",
                true,
            )
            validProposals.size == 1 -> {
                val winner = validProposals.first()
                val idx = proposals.indexOf(winner)
                val out = if (idx == 0) ConsensusOutcome.ACCEPT_PROPOSAL_A else ConsensusOutcome.ACCEPT_PROPOSAL_B
                Triple(out, "Selected '${winner.proposalId}' based on security & requirement compliance (${winner.evidence.size} evidence items).", false)
            }
            else -> {
                val ranked = validProposals.sortedByDescending { it.evidence.size }
                if (ranked[0].evidence.size == ranked[1].evidence.size) {
                    Triple(ConsensusOutcome.REQUEST_MORE_EVIDENCE, "Multiple valid proposals have equal supporting evidence; requesting further verification.", false)
                } else {
                    val idx = proposals.indexOf(ranked[0])
                    val out = if (idx == 0) ConsensusOutcome.ACCEPT_PROPOSAL_A else ConsensusOutcome.ACCEPT_PROPOSAL_B
                    Triple(out, "Selected '${ranked[0].proposalId}' with strongest supporting evidence.", false)
                }
            }
        }

        val decision = AgentDecisionEntity(
            taskId = taskId,
            topic = topic,
            proposals = proposals,
            outcome = outcome,
            rationale = rationale,
            requiresOwnerApproval = needsOwner,
        )
        collabStore.insertDecision(decision)
        return decision
    }
}

/**
 * Workspace file lock & conflict coordinator with TTL expiration, renewal, transfer,
 * path-traversal defense, and stale-lease write prevention.
 */
class AgentWorkspaceCoordinator(
    private val collabStore: CollaborationStore,
) {
    @Synchronized
    fun acquireLock(
        projectId: String,
        taskId: String,
        workspaceRoot: File,
        relativeFilePath: String,
        agentId: String,
        executionId: String,
        ttlMs: Long = 5 * 60_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): AgentFileLockEntity? {
        val normalized = normalizeAndValidate(workspaceRoot, relativeFilePath)
        val existing = collabStore.getActiveLock(projectId, normalized, nowMs)
        if (existing != null && existing.agentId != agentId) {
            return null // Locked exclusively by another active agent
        }
        val lock = AgentFileLockEntity(
            projectId = projectId,
            taskId = taskId,
            filePath = normalized,
            agentId = agentId,
            executionId = executionId,
            acquiredAt = nowMs,
            expiresAt = nowMs + ttlMs,
            lockStatus = FileLockStatus.ACTIVE,
        )
        collabStore.upsertFileLock(lock)
        return lock
    }

    @Synchronized
    fun canWrite(
        projectId: String,
        workspaceRoot: File,
        relativeFilePath: String,
        agentId: String,
        executionId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalized = normalizeAndValidate(workspaceRoot, relativeFilePath)
        val active = collabStore.getActiveLock(projectId, normalized, nowMs) ?: return false
        return active.agentId == agentId && active.executionId == executionId
    }

    @Synchronized
    fun releaseLock(lock: AgentFileLockEntity) {
        collabStore.upsertFileLock(lock.copy(lockStatus = FileLockStatus.RELEASED))
    }

    @Synchronized
    fun transferLock(lock: AgentFileLockEntity, toAgentId: String, newExecutionId: String, ttlMs: Long = 5 * 60_000L): AgentFileLockEntity {
        collabStore.upsertFileLock(lock.copy(lockStatus = FileLockStatus.TRANSFERRED))
        val next = AgentFileLockEntity(
            projectId = lock.projectId,
            taskId = lock.taskId,
            filePath = lock.filePath,
            agentId = toAgentId,
            executionId = newExecutionId,
            acquiredAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + ttlMs,
            lockStatus = FileLockStatus.ACTIVE,
        )
        collabStore.upsertFileLock(next)
        return next
    }

    private fun normalizeAndValidate(workspaceRoot: File, relativeFilePath: String): String {
        val normalized = relativeFilePath.trim().replace('\\', '/').trimStart('/')
        require(WorkspacePathValidator.isPathInsideRoot(workspaceRoot, normalized)) {
            "Path traversal rejected: $relativeFilePath"
        }
        return normalized
    }
}

/**
 * Resource-aware concurrency & provider rate-limit manager.
 * Never silently shrinks configured team size — queues overflow work cleanly.
 */
class AgentResourceManager(
    val maxConcurrentAgents: Int = 2,
    private val providerConcurrencyLimits: Map<String, Int> = emptyMap(),
) {
    private val globalSemaphore = Semaphore(maxConcurrentAgents.coerceAtLeast(1))
    private val providerSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val activeCount = AtomicInteger(0)
    private val queuedCount = AtomicInteger(0)
    private val peakActive = AtomicInteger(0)

    fun peakObservedConcurrency(): Int = peakActive.get()

    suspend fun <T> withAgentPermit(providerId: String, block: suspend () -> T): T {
        val provLimit = providerConcurrencyLimits[providerId] ?: maxConcurrentAgents
        val provSem = providerSemaphores.computeIfAbsent(providerId) { Semaphore(provLimit.coerceAtLeast(1)) }
        queuedCount.incrementAndGet()
        return globalSemaphore.withPermit {
            provSem.withPermit {
                queuedCount.decrementAndGet()
                val cur = activeCount.incrementAndGet()
                peakActive.updateAndGet { prev -> maxOf(prev, cur) }
                try {
                    block()
                } finally {
                    activeCount.decrementAndGet()
                }
            }
        }
    }

    fun snapshot(
        allProfiles: List<AgentProfileEntity>,
        failedAgentIds: Set<String> = emptySet(),
        unavailableProviders: Set<String> = emptySet(),
    ): TeamResourceSnapshot = TeamResourceSnapshot(
        configuredAgentCount = allProfiles.size,
        enabledAgentCount = allProfiles.count { it.isEnabled },
        activeAgentCount = activeCount.get(),
        queuedAgentCount = queuedCount.get(),
        failedAgentCount = failedAgentIds.size,
        unavailableAgentCount = allProfiles.count { !it.isEnabled || it.providerId in unavailableProviders },
    )
}

/**
 * Handles agent failures while preserving work history and safely reassigning unfinished work
 * without duplicating already-completed steps.
 */
class AgentFailureManager(
    private val collabStore: CollaborationStore,
    private val taskStore: OrqenTaskStore,
    private val capabilityResolver: AgentCapabilityResolver,
) {
    fun recordFailureAndReassign(
        delegationId: String,
        failedAgentId: String,
        reason: String,
        candidates: List<AgentProfileEntity>,
    ): AgentDelegationEntity? {
        val current = collabStore.getDelegation(delegationId) ?: return null
        if (current.status == DelegationStatus.COMPLETED) {
            return current // NEVER duplicate already-completed work!
        }

        // Preserve failed agent's work history via persistent event + message
        taskStore.insertAgentMessage(
            AgentMessageEntity(
                taskId = current.taskId,
                fromAgentId = failedAgentId,
                toAgentId = current.delegatingAgentId,
                messageType = AgentMessageKind.CONFLICT_REPORT,
                content = "Agent $failedAgentId failed: ${SecretSanitizer.sanitize(reason)}",
                relatedStepId = current.parentStepId,
            ),
        )

        val replacement = capabilityResolver.selectBestAgent(
            candidates = candidates,
            requiredCapabilities = current.requiredCapabilities,
            excludeAgentIds = setOf(failedAgentId),
        ) ?: run {
            collabStore.upsertDelegation(current.copy(status = DelegationStatus.FAILED, resultSummary = reason))
            return null
        }

        val reassigned = current.copy(
            assignedAgentId = replacement.agentId,
            status = DelegationStatus.REASSIGNED,
            resultSummary = "Reassigned from $failedAgentId to ${replacement.agentId}: $reason",
        )
        collabStore.upsertDelegation(reassigned)
        taskStore.insertTaskEvent(
            TaskEventEntity(
                taskId = current.taskId,
                stepId = current.parentStepId,
                eventType = "AGENT_WORK_REASSIGNED",
                eventPayload = "Delegation ${current.delegationId}: $failedAgentId -> ${replacement.agentId}",
            ),
        )
        return reassigned
    }
}

/**
 * Multi-agent orchestrator coordinating SEQUENTIAL / PARALLEL / REVIEW_BASED workflows,
 * owner approval gates, and duplicate-assignment prevention.
 */
class AgentOrchestrator(
    private val collabStore: CollaborationStore,
    private val taskStore: OrqenTaskStore,
    private val resourceManager: AgentResourceManager,
) {
    private val claimedAssignments = ConcurrentHashMap.newKeySet<String>()

    fun requestOwnerApproval(request: OwnerApprovalRequest): OwnerApprovalRequest {
        collabStore.upsertApproval(request)
        taskStore.insertTaskEvent(
            TaskEventEntity(
                taskId = request.taskId,
                eventType = "OWNER_APPROVAL_REQUESTED",
                eventPayload = "${request.riskLevel}: ${request.actionTitle}",
            ),
        )
        return request
    }

    fun resolveOwnerApproval(approvalId: String, approved: Boolean): OwnerApprovalRequest? {
        val current = collabStore.getApproval(approvalId) ?: return null
        val updated = current.copy(
            status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED,
            resolvedAt = System.currentTimeMillis(),
        )
        collabStore.upsertApproval(updated)
        return updated
    }

    fun canProceedWithAction(approvalId: String): Boolean =
        collabStore.getApproval(approvalId)?.status == ApprovalStatus.APPROVED

    /**
     * Executes independent assignments concurrently up to [AgentResourceManager.maxConcurrentAgents],
     * guaranteeing duplicate scheduling events cannot run the same assignment twice.
     */
    suspend fun <R> executeAssignmentsParallel(
        assignments: List<Pair<String, AgentProfileEntity>>,
        worker: suspend (assignmentId: String, agent: AgentProfileEntity) -> R,
    ): Map<String, R> = coroutineScope {
        val results = ConcurrentHashMap<String, R>()
        assignments.map { (assignmentId, agent) ->
            async {
                if (!claimedAssignments.add(assignmentId)) return@async
                val res = resourceManager.withAgentPermit(agent.providerId) {
                    worker(assignmentId, agent)
                }
                results[assignmentId] = res
            }
        }.awaitAll()
        results
    }
}
