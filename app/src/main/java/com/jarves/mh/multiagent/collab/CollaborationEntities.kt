package com.jarves.mh.multiagent.collab

import java.util.UUID

object AgentCapability {
    const val CODE_ANALYSIS = "CODE_ANALYSIS"
    const val ARCHITECTURE_DESIGN = "ARCHITECTURE_DESIGN"
    const val KOTLIN_DEVELOPMENT = "KOTLIN_DEVELOPMENT"
    const val ANDROID_DEVELOPMENT = "ANDROID_DEVELOPMENT"
    const val WEB_DEVELOPMENT = "WEB_DEVELOPMENT"
    const val BACKEND_DEVELOPMENT = "BACKEND_DEVELOPMENT"
    const val DATABASE_DESIGN = "DATABASE_DESIGN"
    const val API_INTEGRATION = "API_INTEGRATION"
    const val SECURITY_REVIEW = "SECURITY_REVIEW"
    const val TESTING = "TESTING"
    const val DEBUGGING = "DEBUGGING"
    const val DOCUMENTATION = "DOCUMENTATION"
    const val RESEARCH = "RESEARCH"
    const val CODE_REVIEW = "CODE_REVIEW"
    const val BUILD_VERIFICATION = "BUILD_VERIFICATION"
}

enum class CollaborationStrategy {
    SEQUENTIAL,
    PARALLEL,
    HIERARCHICAL,
    COLLABORATIVE,
    REVIEW_BASED,
}

enum class DelegationStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    REASSIGNED,
    CANCELLED,
}

enum class ReviewOutcome {
    APPROVED,
    APPROVED_WITH_WARNINGS,
    CHANGES_REQUESTED,
    REJECTED,
    BLOCKED,
    NEEDS_HUMAN_REVIEW,
}

enum class ReviewSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class ConsensusOutcome {
    ACCEPT_PROPOSAL_A,
    ACCEPT_PROPOSAL_B,
    REQUEST_MORE_EVIDENCE,
    REQUEST_REVIEW,
    ESCALATE_TO_OWNER,
    BLOCKED,
}

enum class FileLockStatus {
    ACTIVE,
    RELEASED,
    EXPIRED,
    TRANSFERRED,
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * Persistent, provider-agnostic agent identity.
 * Never stores API keys or authentication headers.
 */
data class AgentProfileEntity(
    val agentId: String = "agent-${UUID.randomUUID().toString().take(8)}",
    val displayName: String,
    val description: String? = null,
    val role: String,
    val providerId: String,
    val modelId: String,
    val runtimeType: String = "API",
    val systemInstructions: String? = null,
    val capabilities: Set<String> = emptySet(),
    val preferredTools: Set<String> = emptySet(),
    val priority: Int = 1,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AgentTeamEntity(
    val teamId: String = "team-${UUID.randomUUID().toString().take(8)}",
    val projectId: String,
    val teamName: String,
    val strategy: CollaborationStrategy = CollaborationStrategy.HIERARCHICAL,
    val maxConcurrentAgents: Int = 2,
    val maxDelegationDepth: Int = 3,
    val requireIndependentReview: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class AgentTeamMemberEntity(
    val teamId: String,
    val agentId: String,
    val assignedRole: String,
    val priority: Int = 1,
    val isActive: Boolean = true,
)

data class AgentDelegationEntity(
    val delegationId: String = "del-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val projectId: String,
    val parentStepId: String,
    val delegatingAgentId: String,
    val assignedAgentId: String,
    val objective: String,
    val requiredCapabilities: Set<String> = emptySet(),
    val acceptanceCriteria: List<String> = emptyList(),
    val priority: Int = 1,
    val depth: Int = 1,
    val correlationId: String = "corr-${UUID.randomUUID().toString().take(8)}",
    val status: DelegationStatus = DelegationStatus.PENDING,
    val resultSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)

data class AgentReviewEntity(
    val reviewId: String = "rev-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val reviewerAgentId: String,
    val implementingAgentId: String,
    val reviewedExecutionId: String,
    val reviewedStepId: String,
    val outcome: ReviewOutcome,
    val findings: List<String> = emptyList(),
    val severity: ReviewSeverity = ReviewSeverity.INFO,
    val evidenceReferences: List<String> = emptyList(),
    val requiredChanges: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class TechnicalProposal(
    val proposalId: String,
    val authorAgentId: String,
    val summary: String,
    val evidence: List<String>,
    val passesSecurityCheck: Boolean = true,
    val satisfiesRequirements: Boolean = true,
)

data class AgentDecisionEntity(
    val decisionId: String = "dec-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val topic: String,
    val proposals: List<TechnicalProposal>,
    val outcome: ConsensusOutcome,
    val rationale: String,
    val requiresOwnerApproval: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class AgentFileLockEntity(
    val lockId: String = "lock-${UUID.randomUUID().toString().take(8)}",
    val projectId: String,
    val taskId: String,
    val filePath: String,
    val agentId: String,
    val executionId: String,
    val acquiredAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 5 * 60_000L,
    val lockStatus: FileLockStatus = FileLockStatus.ACTIVE,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAt
}

data class OwnerApprovalRequest(
    val approvalId: String = "appr-${UUID.randomUUID().toString().take(8)}",
    val taskId: String,
    val projectId: String,
    val requestedByAgentId: String,
    val actionTitle: String,
    val reason: String,
    val impact: String,
    val affectedResources: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.HIGH,
    val alternatives: List<String> = emptyList(),
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
)

data class TeamResourceSnapshot(
    val configuredAgentCount: Int,
    val enabledAgentCount: Int,
    val activeAgentCount: Int,
    val queuedAgentCount: Int,
    val failedAgentCount: Int,
    val unavailableAgentCount: Int,
)
