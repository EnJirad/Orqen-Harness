package com.jarves.mh.multiagent.collab

import android.content.ContentValues
import android.content.Context
import com.jarves.mh.multiagent.persistence.OrqenTaskDatabaseHelper
import com.jarves.mh.multiagent.persistence.SecretSanitizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable SQLite implementation of [CollaborationStore] sharing ORQEN's single
 * [OrqenTaskDatabaseHelper] (`orqen_autonomous_tasks.db`, WAL mode, foreign keys).
 *
 * Ensures agent profiles, teams, delegations, independent reviews, consensus decisions,
 * file locks, and owner approvals survive Android process recreation without creating
 * a second database.
 */
class SqliteCollaborationStore(
    private val dbHelper: OrqenTaskDatabaseHelper,
) : CollaborationStore {

    constructor(context: Context) : this(OrqenTaskDatabaseHelper(context))

    override fun upsertProfile(profile: AgentProfileEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("agent_id", profile.agentId)
            put("display_name", profile.displayName)
            put("description", profile.description?.let { SecretSanitizer.sanitize(it) })
            put("role", profile.role)
            put("provider_id", profile.providerId)
            put("model_id", profile.modelId)
            put("runtime_type", profile.runtimeType)
            put("system_instructions", profile.systemInstructions?.let { SecretSanitizer.sanitize(it) })
            put("capabilities_json", encodeStringList(profile.capabilities.toList()))
            put("preferred_tools_json", encodeStringList(profile.preferredTools.toList()))
            put("priority", profile.priority)
            put("is_enabled", if (profile.isEnabled) 1 else 0)
            put("created_at", profile.createdAt)
            put("updated_at", profile.updatedAt)
        }
        val rowId = db.insertWithOnConflict("agent_profiles", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        check(rowId != -1L) { "Failed to persist agent profile '${profile.agentId}' into SQLite table agent_profiles." }
    }

    override fun getProfile(agentId: String): AgentProfileEntity? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM agent_profiles WHERE agent_id = ?", arrayOf(agentId)).use { c ->
            return if (c.moveToFirst()) readProfile(c) else null
        }
    }

    override fun listProfiles(onlyEnabled: Boolean): List<AgentProfileEntity> {
        val db = dbHelper.readableDatabase
        val sql = if (onlyEnabled) {
            "SELECT * FROM agent_profiles WHERE is_enabled = 1 ORDER BY priority DESC, agent_id ASC"
        } else {
            "SELECT * FROM agent_profiles ORDER BY priority DESC, agent_id ASC"
        }
        val list = mutableListOf<AgentProfileEntity>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) list.add(readProfile(c))
        }
        return list
    }

    override fun upsertTeam(team: AgentTeamEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("team_id", team.teamId)
            put("project_id", team.projectId)
            put("team_name", team.teamName)
            put("strategy", team.strategy.name)
            put("max_concurrent_agents", team.maxConcurrentAgents)
            put("max_delegation_depth", team.maxDelegationDepth)
            put("require_independent_review", if (team.requireIndependentReview) 1 else 0)
            put("created_at", team.createdAt)
            put("updated_at", team.updatedAt)
        }
        db.insertWithOnConflict("agent_teams", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getTeam(teamId: String): AgentTeamEntity? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM agent_teams WHERE team_id = ?", arrayOf(teamId)).use { c ->
            return if (c.moveToFirst()) readTeam(c) else null
        }
    }

    override fun listTeamsForProject(projectId: String): List<AgentTeamEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentTeamEntity>()
        db.rawQuery("SELECT * FROM agent_teams WHERE project_id = ? ORDER BY created_at DESC", arrayOf(projectId)).use { c ->
            while (c.moveToNext()) list.add(readTeam(c))
        }
        return list
    }

    override fun upsertTeamMember(member: AgentTeamMemberEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("team_id", member.teamId)
            put("agent_id", member.agentId)
            put("assigned_role", member.assignedRole)
            put("priority", member.priority)
            put("is_active", if (member.isActive) 1 else 0)
        }
        db.insertWithOnConflict("agent_team_members", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listTeamMembers(teamId: String): List<AgentTeamMemberEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentTeamMemberEntity>()
        db.rawQuery("SELECT * FROM agent_team_members WHERE team_id = ? ORDER BY priority DESC", arrayOf(teamId)).use { c ->
            while (c.moveToNext()) {
                list.add(
                    AgentTeamMemberEntity(
                        teamId = c.getString(c.getColumnIndexOrThrow("team_id")),
                        agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
                        assignedRole = c.getString(c.getColumnIndexOrThrow("assigned_role")),
                        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
                        isActive = c.getInt(c.getColumnIndexOrThrow("is_active")) == 1,
                    ),
                )
            }
        }
        return list
    }

    override fun upsertDelegation(delegation: AgentDelegationEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("delegation_id", delegation.delegationId)
            put("task_id", delegation.taskId)
            put("project_id", delegation.projectId)
            put("parent_step_id", delegation.parentStepId)
            put("delegating_agent_id", delegation.delegatingAgentId)
            put("assigned_agent_id", delegation.assignedAgentId)
            put("objective", SecretSanitizer.sanitize(delegation.objective))
            put("required_capabilities_json", encodeStringList(delegation.requiredCapabilities.toList()))
            put("priority", delegation.priority)
            put("depth", delegation.depth)
            put("correlation_id", delegation.correlationId)
            put("status", delegation.status.name)
            put("result_summary", delegation.resultSummary?.let { SecretSanitizer.sanitize(it) })
            put("created_at", delegation.createdAt)
            put("completed_at", delegation.completedAt)
        }
        db.insertWithOnConflict("agent_delegations", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getDelegation(delegationId: String): AgentDelegationEntity? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM agent_delegations WHERE delegation_id = ?", arrayOf(delegationId)).use { c ->
            return if (c.moveToFirst()) readDelegation(c) else null
        }
    }

    override fun listDelegationsForTask(taskId: String): List<AgentDelegationEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentDelegationEntity>()
        db.rawQuery("SELECT * FROM agent_delegations WHERE task_id = ? ORDER BY created_at ASC", arrayOf(taskId)).use { c ->
            while (c.moveToNext()) list.add(readDelegation(c))
        }
        return list
    }

    override fun insertReview(review: AgentReviewEntity) {
        val db = dbHelper.writableDatabase
        val payload = JSONObject()
            .put("findings", JSONArray(review.findings.map { SecretSanitizer.sanitize(it) }))
            .put("evidence", JSONArray(review.evidenceReferences))
            .put("requiredChanges", JSONArray(review.requiredChanges.map { SecretSanitizer.sanitize(it) }))
            .toString()
        val cv = ContentValues().apply {
            put("review_id", review.reviewId)
            put("task_id", review.taskId)
            put("reviewer_agent_id", review.reviewerAgentId)
            put("implementing_agent_id", review.implementingAgentId)
            put("reviewed_execution_id", review.reviewedExecutionId)
            put("reviewed_step_id", review.reviewedStepId)
            put("outcome", review.outcome.name)
            put("findings_json", payload)
            put("severity", review.severity.name)
            put("created_at", review.createdAt)
        }
        db.insertWithOnConflict("agent_reviews", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listReviewsForTask(taskId: String): List<AgentReviewEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentReviewEntity>()
        db.rawQuery("SELECT * FROM agent_reviews WHERE task_id = ? ORDER BY created_at ASC", arrayOf(taskId)).use { c ->
            while (c.moveToNext()) list.add(readReview(c))
        }
        return list
    }

    override fun listReviewsForStep(stepId: String): List<AgentReviewEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentReviewEntity>()
        db.rawQuery("SELECT * FROM agent_reviews WHERE reviewed_step_id = ? ORDER BY created_at ASC", arrayOf(stepId)).use { c ->
            while (c.moveToNext()) list.add(readReview(c))
        }
        return list
    }

    override fun insertDecision(decision: AgentDecisionEntity) {
        val db = dbHelper.writableDatabase
        val proposalsArr = JSONArray()
        decision.proposals.forEach { p ->
            proposalsArr.put(
                JSONObject()
                    .put("proposalId", p.proposalId)
                    .put("authorAgentId", p.authorAgentId)
                    .put("summary", SecretSanitizer.sanitize(p.summary))
                    .put("evidence", JSONArray(p.evidence))
                    .put("passesSecurityCheck", p.passesSecurityCheck)
                    .put("satisfiesRequirements", p.satisfiesRequirements),
            )
        }
        val cv = ContentValues().apply {
            put("decision_id", decision.decisionId)
            put("task_id", decision.taskId)
            put("topic", SecretSanitizer.sanitize(decision.topic))
            put("proposals_json", proposalsArr.toString())
            put("outcome", decision.outcome.name)
            put("rationale", SecretSanitizer.sanitize(decision.rationale))
            put("requires_owner_approval", if (decision.requiresOwnerApproval) 1 else 0)
            put("created_at", decision.createdAt)
        }
        db.insertWithOnConflict("agent_decisions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listDecisionsForTask(taskId: String): List<AgentDecisionEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentDecisionEntity>()
        db.rawQuery("SELECT * FROM agent_decisions WHERE task_id = ? ORDER BY created_at ASC", arrayOf(taskId)).use { c ->
            while (c.moveToNext()) list.add(readDecision(c))
        }
        return list
    }

    override fun upsertFileLock(lock: AgentFileLockEntity) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("lock_id", lock.lockId)
            put("project_id", lock.projectId)
            put("task_id", lock.taskId)
            put("file_path", lock.filePath)
            put("agent_id", lock.agentId)
            put("execution_id", lock.executionId)
            put("acquired_at", lock.acquiredAt)
            put("expires_at", lock.expiresAt)
            put("lock_status", lock.lockStatus.name)
        }
        db.insertWithOnConflict("agent_file_locks", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getActiveLock(projectId: String, normalizedPath: String, nowMs: Long): AgentFileLockEntity? {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            "SELECT * FROM agent_file_locks WHERE project_id = ? AND file_path = ? AND lock_status = 'ACTIVE' AND expires_at > ? ORDER BY acquired_at DESC LIMIT 1",
            arrayOf(projectId, normalizedPath, nowMs.toString()),
        ).use { c ->
            return if (c.moveToFirst()) readFileLock(c) else null
        }
    }

    override fun listLocksForProject(projectId: String): List<AgentFileLockEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<AgentFileLockEntity>()
        db.rawQuery("SELECT * FROM agent_file_locks WHERE project_id = ? ORDER BY acquired_at DESC", arrayOf(projectId)).use { c ->
            while (c.moveToNext()) list.add(readFileLock(c))
        }
        return list
    }

    override fun upsertApproval(request: OwnerApprovalRequest) {
        val db = dbHelper.writableDatabase
        val cv = ContentValues().apply {
            put("approval_id", request.approvalId)
            put("task_id", request.taskId)
            put("project_id", request.projectId)
            put("requested_by_agent_id", request.requestedByAgentId)
            put("action_title", SecretSanitizer.sanitize(request.actionTitle))
            put("reason", SecretSanitizer.sanitize(request.reason))
            put("impact", SecretSanitizer.sanitize(request.impact))
            put("affected_resources_json", encodeStringList(request.affectedResources))
            put("risk_level", request.riskLevel.name)
            put("alternatives_json", encodeStringList(request.alternatives))
            put("status", request.status.name)
            put("created_at", request.createdAt)
            put("resolved_at", request.resolvedAt)
        }
        db.insertWithOnConflict("owner_approvals", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getApproval(approvalId: String): OwnerApprovalRequest? {
        val db = dbHelper.readableDatabase
        db.rawQuery("SELECT * FROM owner_approvals WHERE approval_id = ?", arrayOf(approvalId)).use { c ->
            return if (c.moveToFirst()) readApproval(c) else null
        }
    }

    override fun listApprovalsForTask(taskId: String): List<OwnerApprovalRequest> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<OwnerApprovalRequest>()
        db.rawQuery("SELECT * FROM owner_approvals WHERE task_id = ? ORDER BY created_at ASC", arrayOf(taskId)).use { c ->
            while (c.moveToNext()) list.add(readApproval(c))
        }
        return list
    }

    private fun readProfile(c: android.database.Cursor) = AgentProfileEntity(
        agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
        displayName = c.getString(c.getColumnIndexOrThrow("display_name")),
        description = c.getString(c.getColumnIndexOrThrow("description")),
        role = c.getString(c.getColumnIndexOrThrow("role")),
        providerId = c.getString(c.getColumnIndexOrThrow("provider_id")),
        modelId = c.getString(c.getColumnIndexOrThrow("model_id")),
        runtimeType = c.getString(c.getColumnIndexOrThrow("runtime_type")),
        systemInstructions = c.getString(c.getColumnIndexOrThrow("system_instructions")),
        capabilities = decodeStringList(c.getString(c.getColumnIndexOrThrow("capabilities_json"))).toSet(),
        preferredTools = c.getColumnIndex("preferred_tools_json")
            .takeIf { it >= 0 }
            ?.let { decodeStringList(c.getString(it)).toSet() }
            ?: emptySet(),
        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
        isEnabled = c.getInt(c.getColumnIndexOrThrow("is_enabled")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    private fun readTeam(c: android.database.Cursor) = AgentTeamEntity(
        teamId = c.getString(c.getColumnIndexOrThrow("team_id")),
        projectId = c.getString(c.getColumnIndexOrThrow("project_id")),
        teamName = c.getString(c.getColumnIndexOrThrow("team_name")),
        strategy = runCatching { CollaborationStrategy.valueOf(c.getString(c.getColumnIndexOrThrow("strategy"))) }
            .getOrDefault(CollaborationStrategy.HIERARCHICAL),
        maxConcurrentAgents = c.getInt(c.getColumnIndexOrThrow("max_concurrent_agents")),
        maxDelegationDepth = c.getInt(c.getColumnIndexOrThrow("max_delegation_depth")),
        requireIndependentReview = c.getInt(c.getColumnIndexOrThrow("require_independent_review")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
    )

    private fun readDelegation(c: android.database.Cursor) = AgentDelegationEntity(
        delegationId = c.getString(c.getColumnIndexOrThrow("delegation_id")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        projectId = c.getString(c.getColumnIndexOrThrow("project_id")),
        parentStepId = c.getString(c.getColumnIndexOrThrow("parent_step_id")),
        delegatingAgentId = c.getString(c.getColumnIndexOrThrow("delegating_agent_id")),
        assignedAgentId = c.getString(c.getColumnIndexOrThrow("assigned_agent_id")),
        objective = c.getString(c.getColumnIndexOrThrow("objective")),
        requiredCapabilities = decodeStringList(c.getString(c.getColumnIndexOrThrow("required_capabilities_json"))).toSet(),
        priority = c.getInt(c.getColumnIndexOrThrow("priority")),
        depth = c.getInt(c.getColumnIndexOrThrow("depth")),
        correlationId = c.getString(c.getColumnIndexOrThrow("correlation_id")),
        status = runCatching { DelegationStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }
            .getOrDefault(DelegationStatus.PENDING),
        resultSummary = c.getString(c.getColumnIndexOrThrow("result_summary")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        completedAt = if (c.isNull(c.getColumnIndexOrThrow("completed_at"))) null else c.getLong(c.getColumnIndexOrThrow("completed_at")),
    )

    private fun readReview(c: android.database.Cursor): AgentReviewEntity {
        val raw = c.getString(c.getColumnIndexOrThrow("findings_json"))
        val (findings, evidence, changes) = runCatching {
            val obj = JSONObject(raw)
            Triple(
                decodeJsonArray(obj.optJSONArray("findings")),
                decodeJsonArray(obj.optJSONArray("evidence")),
                decodeJsonArray(obj.optJSONArray("requiredChanges")),
            )
        }.getOrElse { Triple(decodeStringList(raw), emptyList(), emptyList()) }
        return AgentReviewEntity(
            reviewId = c.getString(c.getColumnIndexOrThrow("review_id")),
            taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
            reviewerAgentId = c.getString(c.getColumnIndexOrThrow("reviewer_agent_id")),
            implementingAgentId = c.getString(c.getColumnIndexOrThrow("implementing_agent_id")),
            reviewedExecutionId = c.getString(c.getColumnIndexOrThrow("reviewed_execution_id")),
            reviewedStepId = c.getString(c.getColumnIndexOrThrow("reviewed_step_id")),
            outcome = runCatching { ReviewOutcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))) }
                .getOrDefault(ReviewOutcome.CHANGES_REQUESTED),
            findings = findings,
            severity = runCatching { ReviewSeverity.valueOf(c.getString(c.getColumnIndexOrThrow("severity"))) }
                .getOrDefault(ReviewSeverity.INFO),
            evidenceReferences = evidence,
            requiredChanges = changes,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    private fun readDecision(c: android.database.Cursor): AgentDecisionEntity {
        val proposals = runCatching {
            val arr = JSONArray(c.getString(c.getColumnIndexOrThrow("proposals_json")))
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                TechnicalProposal(
                    proposalId = o.optString("proposalId"),
                    authorAgentId = o.optString("authorAgentId"),
                    summary = o.optString("summary"),
                    evidence = decodeJsonArray(o.optJSONArray("evidence")),
                    passesSecurityCheck = o.optBoolean("passesSecurityCheck", true),
                    satisfiesRequirements = o.optBoolean("satisfiesRequirements", true),
                )
            }
        }.getOrDefault(emptyList())
        return AgentDecisionEntity(
            decisionId = c.getString(c.getColumnIndexOrThrow("decision_id")),
            taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
            topic = c.getString(c.getColumnIndexOrThrow("topic")),
            proposals = proposals,
            outcome = runCatching { ConsensusOutcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))) }
                .getOrDefault(ConsensusOutcome.BLOCKED),
            rationale = c.getString(c.getColumnIndexOrThrow("rationale")),
            requiresOwnerApproval = c.getInt(c.getColumnIndexOrThrow("requires_owner_approval")) == 1,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        )
    }

    private fun readFileLock(c: android.database.Cursor) = AgentFileLockEntity(
        lockId = c.getString(c.getColumnIndexOrThrow("lock_id")),
        projectId = c.getString(c.getColumnIndexOrThrow("project_id")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        filePath = c.getString(c.getColumnIndexOrThrow("file_path")),
        agentId = c.getString(c.getColumnIndexOrThrow("agent_id")),
        executionId = c.getString(c.getColumnIndexOrThrow("execution_id")),
        acquiredAt = c.getLong(c.getColumnIndexOrThrow("acquired_at")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at")),
        lockStatus = runCatching { FileLockStatus.valueOf(c.getString(c.getColumnIndexOrThrow("lock_status"))) }
            .getOrDefault(FileLockStatus.ACTIVE),
    )

    private fun readApproval(c: android.database.Cursor) = OwnerApprovalRequest(
        approvalId = c.getString(c.getColumnIndexOrThrow("approval_id")),
        taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
        projectId = c.getString(c.getColumnIndexOrThrow("project_id")),
        requestedByAgentId = c.getString(c.getColumnIndexOrThrow("requested_by_agent_id")),
        actionTitle = c.getString(c.getColumnIndexOrThrow("action_title")),
        reason = c.getString(c.getColumnIndexOrThrow("reason")),
        impact = c.getString(c.getColumnIndexOrThrow("impact")),
        affectedResources = decodeStringList(c.getString(c.getColumnIndexOrThrow("affected_resources_json"))),
        riskLevel = runCatching { RiskLevel.valueOf(c.getString(c.getColumnIndexOrThrow("risk_level"))) }
            .getOrDefault(RiskLevel.HIGH),
        alternatives = decodeStringList(c.getString(c.getColumnIndexOrThrow("alternatives_json"))),
        status = runCatching { ApprovalStatus.valueOf(c.getString(c.getColumnIndexOrThrow("status"))) }
            .getOrDefault(ApprovalStatus.PENDING),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        resolvedAt = if (c.isNull(c.getColumnIndexOrThrow("resolved_at"))) null else c.getLong(c.getColumnIndexOrThrow("resolved_at")),
    )

    private fun encodeStringList(items: List<String>): String = JSONArray(items).toString()

    private fun decodeStringList(raw: String?): List<String> = runCatching {
        decodeJsonArray(JSONArray(raw ?: "[]"))
    }.getOrDefault(emptyList())

    private fun decodeJsonArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }
}
