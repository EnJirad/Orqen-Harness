package com.jarves.mh.multiagent.collab

import com.jarves.mh.multiagent.persistence.SecretSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface CollaborationStore {
    fun upsertProfile(profile: AgentProfileEntity)
    fun getProfile(agentId: String): AgentProfileEntity?
    fun listProfiles(onlyEnabled: Boolean = false): List<AgentProfileEntity>

    fun upsertTeam(team: AgentTeamEntity)
    fun getTeam(teamId: String): AgentTeamEntity?
    fun listTeamsForProject(projectId: String): List<AgentTeamEntity>
    fun upsertTeamMember(member: AgentTeamMemberEntity)
    fun listTeamMembers(teamId: String): List<AgentTeamMemberEntity>

    fun upsertDelegation(delegation: AgentDelegationEntity)
    fun getDelegation(delegationId: String): AgentDelegationEntity?
    fun listDelegationsForTask(taskId: String): List<AgentDelegationEntity>

    fun insertReview(review: AgentReviewEntity)
    fun listReviewsForTask(taskId: String): List<AgentReviewEntity>
    fun listReviewsForStep(stepId: String): List<AgentReviewEntity>

    fun insertDecision(decision: AgentDecisionEntity)
    fun listDecisionsForTask(taskId: String): List<AgentDecisionEntity>

    fun upsertFileLock(lock: AgentFileLockEntity)
    fun getActiveLock(projectId: String, normalizedPath: String, nowMs: Long = System.currentTimeMillis()): AgentFileLockEntity?
    fun listLocksForProject(projectId: String): List<AgentFileLockEntity>

    fun upsertApproval(request: OwnerApprovalRequest)
    fun getApproval(approvalId: String): OwnerApprovalRequest?
    fun listApprovalsForTask(taskId: String): List<OwnerApprovalRequest>
}

class InMemoryCollaborationStore : CollaborationStore {
    private val lock = Any()
    private val profiles = LinkedHashMap<String, AgentProfileEntity>()
    private val teams = LinkedHashMap<String, AgentTeamEntity>()
    private val teamMembers = mutableListOf<AgentTeamMemberEntity>()
    private val delegations = LinkedHashMap<String, AgentDelegationEntity>()
    private val reviews = mutableListOf<AgentReviewEntity>()
    private val decisions = mutableListOf<AgentDecisionEntity>()
    private val fileLocks = LinkedHashMap<String, AgentFileLockEntity>()
    private val approvals = LinkedHashMap<String, OwnerApprovalRequest>()

    override fun upsertProfile(profile: AgentProfileEntity) {
        synchronized(lock) {
            profiles[profile.agentId] = profile.copy(
                systemInstructions = profile.systemInstructions?.let { SecretSanitizer.sanitize(it) },
                description = profile.description?.let { SecretSanitizer.sanitize(it) },
            )
        }
    }

    override fun getProfile(agentId: String): AgentProfileEntity? = synchronized(lock) { profiles[agentId] }

    override fun listProfiles(onlyEnabled: Boolean): List<AgentProfileEntity> = synchronized(lock) {
        profiles.values.filter { !onlyEnabled || it.isEnabled }.sortedByDescending { it.priority }
    }

    override fun upsertTeam(team: AgentTeamEntity) {
        synchronized(lock) { teams[team.teamId] = team }
    }

    override fun getTeam(teamId: String): AgentTeamEntity? = synchronized(lock) { teams[teamId] }

    override fun listTeamsForProject(projectId: String): List<AgentTeamEntity> = synchronized(lock) {
        teams.values.filter { it.projectId == projectId }
    }

    override fun upsertTeamMember(member: AgentTeamMemberEntity) {
        synchronized(lock) {
            teamMembers.removeAll { it.teamId == member.teamId && it.agentId == member.agentId }
            teamMembers.add(member)
        }
    }

    override fun listTeamMembers(teamId: String): List<AgentTeamMemberEntity> = synchronized(lock) {
        teamMembers.filter { it.teamId == teamId }.sortedByDescending { it.priority }
    }

    override fun upsertDelegation(delegation: AgentDelegationEntity) {
        synchronized(lock) {
            delegations[delegation.delegationId] = delegation.copy(
                objective = SecretSanitizer.sanitize(delegation.objective),
                resultSummary = delegation.resultSummary?.let { SecretSanitizer.sanitize(it) },
            )
        }
    }

    override fun getDelegation(delegationId: String): AgentDelegationEntity? = synchronized(lock) { delegations[delegationId] }

    override fun listDelegationsForTask(taskId: String): List<AgentDelegationEntity> = synchronized(lock) {
        delegations.values.filter { it.taskId == taskId }.sortedBy { it.createdAt }
    }

    override fun insertReview(review: AgentReviewEntity) {
        synchronized(lock) { reviews.add(review) }
    }

    override fun listReviewsForTask(taskId: String): List<AgentReviewEntity> = synchronized(lock) {
        reviews.filter { it.taskId == taskId }.sortedBy { it.createdAt }
    }

    override fun listReviewsForStep(stepId: String): List<AgentReviewEntity> = synchronized(lock) {
        reviews.filter { it.reviewedStepId == stepId }.sortedBy { it.createdAt }
    }

    override fun insertDecision(decision: AgentDecisionEntity) {
        synchronized(lock) { decisions.add(decision) }
    }

    override fun listDecisionsForTask(taskId: String): List<AgentDecisionEntity> = synchronized(lock) {
        decisions.filter { it.taskId == taskId }.sortedBy { it.createdAt }
    }

    override fun upsertFileLock(lock: AgentFileLockEntity) {
        synchronized(this.lock) {
            fileLocks[lock.lockId] = lock
        }
    }

    override fun getActiveLock(projectId: String, normalizedPath: String, nowMs: Long): AgentFileLockEntity? = synchronized(lock) {
        fileLocks.values.firstOrNull {
            it.projectId == projectId &&
                it.filePath == normalizedPath &&
                it.lockStatus == FileLockStatus.ACTIVE &&
                !it.isExpired(nowMs)
        }
    }

    override fun listLocksForProject(projectId: String): List<AgentFileLockEntity> = synchronized(lock) {
        fileLocks.values.filter { it.projectId == projectId }
    }

    override fun upsertApproval(request: OwnerApprovalRequest) {
        synchronized(lock) { approvals[request.approvalId] = request }
    }

    override fun getApproval(approvalId: String): OwnerApprovalRequest? = synchronized(lock) { approvals[approvalId] }

    override fun listApprovalsForTask(taskId: String): List<OwnerApprovalRequest> = synchronized(lock) {
        approvals.values.filter { it.taskId == taskId }.sortedBy { it.createdAt }
    }
}

/**
 * Disk-backed [CollaborationStore] ensuring profiles, teams, delegations, and reviews survive restarts.
 */
class FileBackedCollaborationStore(
    private val file: File,
) : CollaborationStore {
    private val delegate = InMemoryCollaborationStore()

    init {
        if (file.isFile && file.length() > 0L) load()
    }

    override fun upsertProfile(profile: AgentProfileEntity) { delegate.upsertProfile(profile); flush() }
    override fun getProfile(agentId: String) = delegate.getProfile(agentId)
    override fun listProfiles(onlyEnabled: Boolean) = delegate.listProfiles(onlyEnabled)

    override fun upsertTeam(team: AgentTeamEntity) { delegate.upsertTeam(team); flush() }
    override fun getTeam(teamId: String) = delegate.getTeam(teamId)
    override fun listTeamsForProject(projectId: String) = delegate.listTeamsForProject(projectId)
    override fun upsertTeamMember(member: AgentTeamMemberEntity) { delegate.upsertTeamMember(member); flush() }
    override fun listTeamMembers(teamId: String) = delegate.listTeamMembers(teamId)

    override fun upsertDelegation(delegation: AgentDelegationEntity) { delegate.upsertDelegation(delegation); flush() }
    override fun getDelegation(delegationId: String) = delegate.getDelegation(delegationId)
    override fun listDelegationsForTask(taskId: String) = delegate.listDelegationsForTask(taskId)

    override fun insertReview(review: AgentReviewEntity) { delegate.insertReview(review); flush() }
    override fun listReviewsForTask(taskId: String) = delegate.listReviewsForTask(taskId)
    override fun listReviewsForStep(stepId: String) = delegate.listReviewsForStep(stepId)

    override fun insertDecision(decision: AgentDecisionEntity) { delegate.insertDecision(decision); flush() }
    override fun listDecisionsForTask(taskId: String) = delegate.listDecisionsForTask(taskId)

    override fun upsertFileLock(lock: AgentFileLockEntity) { delegate.upsertFileLock(lock); flush() }
    override fun getActiveLock(projectId: String, normalizedPath: String, nowMs: Long) = delegate.getActiveLock(projectId, normalizedPath, nowMs)
    override fun listLocksForProject(projectId: String) = delegate.listLocksForProject(projectId)

    override fun upsertApproval(request: OwnerApprovalRequest) { delegate.upsertApproval(request); flush() }
    override fun getApproval(approvalId: String) = delegate.getApproval(approvalId)
    override fun listApprovalsForTask(taskId: String) = delegate.listApprovalsForTask(taskId)

    private fun flush() {
        val root = JSONObject()
        val profArr = JSONArray()
        delegate.listProfiles(onlyEnabled = false).forEach { p ->
            profArr.put(
                JSONObject()
                    .put("agentId", p.agentId)
                    .put("displayName", p.displayName)
                    .put("role", p.role)
                    .put("providerId", p.providerId)
                    .put("modelId", p.modelId)
                    .put("runtimeType", p.runtimeType)
                    .put("capabilities", JSONArray(p.capabilities.toList()))
                    .put("priority", p.priority)
                    .put("isEnabled", p.isEnabled),
            )
        }
        root.put("profiles", profArr)
        file.parentFile?.mkdirs()
        file.writeText(root.toString())
    }

    private fun load() {
        val root = JSONObject(file.readText())
        val profArr = root.optJSONArray("profiles") ?: JSONArray()
        for (i in 0 until profArr.length()) {
            val o = profArr.getJSONObject(i)
            val capsArr = o.optJSONArray("capabilities") ?: JSONArray()
            val caps = (0 until capsArr.length()).map { capsArr.getString(it) }.toSet()
            delegate.upsertProfile(
                AgentProfileEntity(
                    agentId = o.getString("agentId"),
                    displayName = o.getString("displayName"),
                    role = o.getString("role"),
                    providerId = o.getString("providerId"),
                    modelId = o.getString("modelId"),
                    runtimeType = o.optString("runtimeType", "API"),
                    capabilities = caps,
                    priority = o.optInt("priority", 1),
                    isEnabled = o.optBoolean("isEnabled", true),
                ),
            )
        }
    }
}
