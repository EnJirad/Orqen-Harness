package com.jarves.mh.multiagent.session

import com.jarves.mh.multiagent.persistence.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class MemoryScope {
    PROJECT,
    AGENT,
    TASK,
    EXECUTION,
    DECISION,
}

enum class MemoryStoreOutcome {
    STORED,
    DEDUPLICATED,
    REJECTED_NOISE,
}

data class StructuredMemoryEntry(
    val id: String = "mem-${UUID.randomUUID().toString().take(8)}",
    val scope: MemoryScope,
    val projectId: String,
    val taskId: String? = null,
    val agentId: String? = null,
    val key: String = "",
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val contentSha256: String,
    val usefulnessScore: Double = 1.0,
    val accessCount: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class MemoryStoreResult(
    val outcome: MemoryStoreOutcome,
    val entry: StructuredMemoryEntry? = null,
    val reason: String = "",
)

data class CompactionResult(
    val wasCompacted: Boolean,
    val originalEstimatedTokens: Int,
    val compactedEstimatedTokens: Int,
    val preservedDecisions: List<String>,
    val preservedFilePaths: List<String>,
    val summaryText: String,
    val recentMessages: List<ConversationMessageEntity>,
)

/**
 * 5-Scope Persistent Hierarchical Memory & Automatic Context Compaction engine:
 * 1. PROJECT   — shared architecture, conventions, build rules, verified deliverables
 * 2. AGENT     — agent-specific expertise & review lessons (owner/Manager access-controlled)
 * 3. TASK      — task objective, acceptance criteria, active blockers
 * 4. EXECUTION — verified tool/step outcomes, modified files, test/build results
 * 5. DECISION  — Manager decisions, failovers, retries, replans, and rationale
 *
 * Incorporates Agent-Swarm-inspired reliability guarantees:
 * - Secret scrubbing via [SecretSanitizer] before hashing or persistence
 * - Noise-floor rejection for trivial/empty lines
 * - SHA-256 content deduplication (boosts usefulness score instead of duplicating)
 * - Relevance-filtered retrieval (never dumps entire memory store into LLM context)
 * - Durable persistence via [OrqenTaskStore] surviving app restarts and process death
 */
class HierarchicalMemoryManager(
    private val store: OrqenTaskStore,
) {
    private val filePathRegex = Regex(
        """([A-Za-z0-9_\-./]+\.(?:kt|java|xml|html|css|js|ts|tsx|jsx|json|py|md|gradle|kts|yml|yaml|sql|sh))""",
        RegexOption.IGNORE_CASE,
    )
    private val cacheLock = Any()
    private val structuredCache = LinkedHashMap<String, List<StructuredMemoryEntry>>(16, 0.75f, true)

    // ==================== 5-SCOPE STRUCTURED MEMORY API ====================

    fun storeStructuredMemory(
        scope: MemoryScope,
        projectId: String,
        title: String,
        content: String,
        taskId: String? = null,
        agentId: String? = null,
        key: String = "",
        tags: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): MemoryStoreResult {
        val cleanTitle = SecretSanitizer.sanitize(title.trim()).take(140)
        val cleanContent = SecretSanitizer.sanitize(content.trim()).take(MAX_ENTRY_CONTENT_CHARS)
        if (cleanContent.length < MIN_LEARNING_CHARS) {
            return MemoryStoreResult(
                outcome = MemoryStoreOutcome.REJECTED_NOISE,
                reason = "Rejected below noise floor (${cleanContent.length} < $MIN_LEARNING_CHARS chars).",
            )
        }
        val hash = sha256Hex("${scope.name}|${projectId.trim()}|${cleanContent.lowercase()}")
        val entries = loadProjectEntries(projectId).toMutableList()

        // Deduplication: if an identical content hash already exists in the same project & scope,
        // boost its usefulnessScore instead of creating a duplicate entry.
        val existingIdx = entries.indexOfFirst { it.contentSha256 == hash && it.scope == scope }
        if (existingIdx >= 0) {
            val existing = entries[existingIdx]
            val boosted = existing.copy(
                usefulnessScore = (existing.usefulnessScore + 0.35).coerceAtMost(5.0),
                updatedAtMs = nowMs,
            )
            entries[existingIdx] = boosted
            saveProjectEntries(projectId, entries)
            return MemoryStoreResult(
                outcome = MemoryStoreOutcome.DEDUPLICATED,
                entry = boosted,
                reason = "Boosted existing deduplicated memory ${boosted.id}.",
            )
        }

        val entry = StructuredMemoryEntry(
            scope = scope,
            projectId = projectId,
            taskId = taskId,
            agentId = agentId,
            key = key.ifBlank { "/${scope.name.lowercase()}/${slugify(cleanTitle)}" },
            title = cleanTitle.ifBlank { scope.name },
            content = cleanContent,
            tags = tags.map { SecretSanitizer.sanitize(it.trim().lowercase()) }.filter(String::isNotBlank).distinct().take(6),
            contentSha256 = hash,
            usefulnessScore = 1.0,
            accessCount = 0,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        entries.add(entry)
        // Bounded retention: keep most useful & recent entries (max 160 per project)
        val trimmed = if (entries.size > MAX_ENTRIES_PER_PROJECT) {
            entries.sortedByDescending { it.usefulnessScore * 1000.0 + (it.updatedAtMs / 86_400_000.0) }
                .take(MAX_ENTRIES_PER_PROJECT)
        } else entries
        saveProjectEntries(projectId, trimmed)
        return MemoryStoreResult(outcome = MemoryStoreOutcome.STORED, entry = entry)
    }

    /**
     * Retrieves only the top-[limit] most relevant memories for [query], enforcing strict
     * project isolation and AGENT-scope ownership access control.
     */
    fun retrieveRelevant(
        projectId: String,
        query: String,
        requestingAgentId: String? = null,
        isManagerOrLead: Boolean = false,
        allowedScopes: Set<MemoryScope> = MemoryScope.entries.toSet(),
        limit: Int = 5,
        nowMs: Long = System.currentTimeMillis(),
    ): List<StructuredMemoryEntry> {
        val entries = loadProjectEntries(projectId).toMutableList()
        if (entries.isEmpty()) return emptyList()

        val queryTokens = tokenize(query)
        val accessible = entries.filter { entry ->
            if (entry.scope !in allowedScopes) return@filter false
            if (entry.scope == MemoryScope.AGENT && !isManagerOrLead) {
                entry.agentId == null || entry.agentId == requestingAgentId
            } else {
                true
            }
        }
        if (accessible.isEmpty()) return emptyList()

        val scored = accessible.map { entry ->
            val docTokens = tokenize("${entry.title} ${entry.content} ${entry.tags.joinToString(" ")}")
            val overlap = if (queryTokens.isEmpty()) {
                0.25
            } else {
                val hits = queryTokens.count { it in docTokens }
                hits.toDouble() / queryTokens.size.toDouble()
            }
            val ageHours = ((nowMs - entry.updatedAtMs).coerceAtLeast(0L)) / 3_600_000.0
            val recency = 1.0 / (1.0 + (ageHours / 72.0))
            val accessBoost = 1.0 + (entry.accessCount.coerceAtMost(10) * 0.04)
            val score = (overlap * 2.6 + recency * 0.65) * entry.usefulnessScore * accessBoost
            entry to score
        }
            .filter { (entry, score) -> queryTokens.isEmpty() || score > 0.35 || entry.scope == MemoryScope.PROJECT }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        if (scored.isNotEmpty()) {
            val hitIds = scored.map { it.id }.toSet()
            val updated = entries.map { e ->
                if (e.id in hitIds) e.copy(accessCount = e.accessCount + 1) else e
            }
            saveProjectEntries(projectId, updated)
        }
        return scored
    }

    fun listMemoriesForProject(
        projectId: String,
        scope: MemoryScope? = null,
    ): List<StructuredMemoryEntry> {
        val all = loadProjectEntries(projectId)
        return if (scope == null) all else all.filter { it.scope == scope }
    }

    fun buildRelevantMemoryPromptBlock(
        projectId: String,
        query: String,
        requestingAgentId: String,
        isManagerOrLead: Boolean = false,
        limit: Int = 5,
    ): String {
        val hits = retrieveRelevant(
            projectId = projectId,
            query = query,
            requestingAgentId = requestingAgentId,
            isManagerOrLead = isManagerOrLead,
            limit = limit,
        )
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("## Relevant Persistent Memory (${hits.size} filtered entries)")
            hits.forEach { m ->
                appendLine("- [${m.scope.name}] ${m.title}: ${m.content.take(220)}")
            }
        }.trim()
    }

    /**
     * Automatically distills reusable execution/agent/project learnings from a completed step.
     */
    fun distillFromCompletedStep(
        projectId: String,
        taskId: String,
        step: TaskStepEntity,
        modifiedFiles: List<String>,
        summary: String,
    ): MemoryStoreResult {
        val cleanSummary = summary
            .replace("REVIEW_APPROVED", "")
            .replace("TESTS_PASSED", "")
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(360)
        val fileNote = if (modifiedFiles.isNotEmpty()) {
            " Files: ${modifiedFiles.take(8).joinToString(", ")}."
        } else ""
        val content = "Completed '${step.title}' by ${step.assignedAgentId}.$fileNote Outcome: $cleanSummary"
        val scope = if (step.requiresReview) MemoryScope.AGENT else MemoryScope.EXECUTION
        return storeStructuredMemory(
            scope = scope,
            projectId = projectId,
            taskId = taskId,
            agentId = step.assignedAgentId,
            title = step.title,
            content = content,
            tags = listOfNotNull(step.partId, step.assignedAgentId, if (step.requiresReview) "review" else "code"),
        )
    }

    /**
     * Automatically distills a Manager decision or recovery action into DECISION memory.
     */
    fun distillFromDecision(
        projectId: String,
        taskId: String,
        decisionTitle: String,
        reason: String,
        actionTaken: String,
        result: String,
    ): MemoryStoreResult {
        val content = "$decisionTitle — Reason: $reason | Action: $actionTaken | Result: $result"
        return storeStructuredMemory(
            scope = MemoryScope.DECISION,
            projectId = projectId,
            taskId = taskId,
            agentId = "agent-01",
            title = decisionTitle,
            content = content,
            tags = listOf("decision", result.lowercase()),
        )
    }

    private fun loadProjectEntries(projectId: String): List<StructuredMemoryEntry> {
        synchronized(cacheLock) {
            structuredCache[projectId]?.let { return it }
        }
        val draft = store.getChatDraft("$MEMORY_INDEX_PREFIX$projectId") ?: return emptyList()
        if (draft.draftText.isBlank()) return emptyList()
        val parsed = runCatching {
            val arr = JSONArray(draft.draftText)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val scope = runCatching { MemoryScope.valueOf(o.optString("scope", "PROJECT")) }.getOrDefault(MemoryScope.PROJECT)
                    val tagsArr = o.optJSONArray("tags")
                    val tags = buildList {
                        if (tagsArr != null) {
                            for (t in 0 until tagsArr.length()) {
                                val v = tagsArr.optString(t)
                                if (v.isNotBlank()) add(v)
                            }
                        }
                    }
                    add(
                        StructuredMemoryEntry(
                            id = o.optString("id"),
                            scope = scope,
                            projectId = o.optString("projectId", projectId),
                            taskId = o.optString("taskId").takeIf { it.isNotBlank() },
                            agentId = o.optString("agentId").takeIf { it.isNotBlank() },
                            key = o.optString("key"),
                            title = o.optString("title"),
                            content = o.optString("content"),
                            tags = tags,
                            contentSha256 = o.optString("contentSha256"),
                            usefulnessScore = o.optDouble("usefulnessScore", 1.0),
                            accessCount = o.optInt("accessCount", 0),
                            createdAtMs = o.optLong("createdAtMs", 0L),
                            updatedAtMs = o.optLong("updatedAtMs", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        synchronized(cacheLock) {
            structuredCache[projectId] = parsed
            while (structuredCache.size > MAX_CACHED_PROJECTS) {
                val eldest = structuredCache.keys.firstOrNull() ?: break
                structuredCache.remove(eldest)
            }
        }
        return parsed
    }

    private fun saveProjectEntries(projectId: String, entries: List<StructuredMemoryEntry>) {
        synchronized(cacheLock) {
            structuredCache[projectId] = entries.toList()
        }
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("scope", e.scope.name)
                    .put("projectId", e.projectId)
                    .put("taskId", e.taskId.orEmpty())
                    .put("agentId", e.agentId.orEmpty())
                    .put("key", e.key)
                    .put("title", e.title)
                    .put("content", e.content)
                    .put("tags", JSONArray(e.tags))
                    .put("contentSha256", e.contentSha256)
                    .put("usefulnessScore", e.usefulnessScore)
                    .put("accessCount", e.accessCount)
                    .put("createdAtMs", e.createdAtMs)
                    .put("updatedAtMs", e.updatedAtMs),
            )
        }
        store.upsertChatDraft(
            PersistentChatDraftEntity(
                draftKey = "$MEMORY_INDEX_PREFIX$projectId",
                projectId = projectId,
                conversationId = "memory",
                mode = "MEMORY_INDEX",
                draftText = arr.toString(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun tokenize(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z0-9\\u0E00-\\u0E7F]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun slugify(input: String): String = input.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(36)
        .ifBlank { "note" }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ==================== LEGACY COMPATIBILITY METHODS ====================

    fun updateProjectMemory(projectId: String, decisionOrNote: String) {
        val clean = SecretSanitizer.sanitize(decisionOrNote.trim())
        val project = store.getProject(projectId) ?: return
        val existing = project.projectMemory.trim()
        val appended = if (existing.isBlank()) clean else "$existing\n- $clean"
        store.upsertProject(
            project.copy(
                projectMemory = appended.takeLast(MAX_PROJECT_MEMORY_CHARS),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        storeStructuredMemory(
            scope = MemoryScope.PROJECT,
            projectId = projectId,
            title = clean.take(64),
            content = clean,
        )
    }

    fun updateTaskMemory(taskId: String, note: String) {
        val clean = SecretSanitizer.sanitize(note.trim())
        val task = store.getTask(taskId) ?: return
        val existing = task.taskMemory.trim()
        val appended = if (existing.isBlank()) clean else "$existing\n- $clean"
        store.upsertTask(
            task.copy(
                taskMemory = appended.takeLast(MAX_TASK_MEMORY_CHARS),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        storeStructuredMemory(
            scope = MemoryScope.TASK,
            projectId = task.projectId,
            taskId = taskId,
            title = task.title.take(64),
            content = clean,
        )
    }

    fun buildAgentMemoryPrompt(taskId: String, agentId: String): String {
        val steps = store.listStepsForTask(taskId).filter { it.assignedAgentId == agentId }
        val msgs = store.listAgentMessagesForTask(taskId, limit = 40)
            .filter { it.fromAgentId == agentId || it.toAgentId == agentId }
            .takeLast(8)
        return buildString {
            if (steps.isNotEmpty()) {
                appendLine("Previous assignments for $agentId:")
                steps.takeLast(5).forEach { s ->
                    appendLine("- #${s.sequenceNumber} ${s.title} [${s.status}]: ${s.outputSummary.take(160)}")
                }
            }
            if (msgs.isNotEmpty()) {
                appendLine("Recent structured messages:")
                msgs.forEach { m ->
                    appendLine("- [${m.messageType}] ${m.fromAgentId} -> ${m.toAgentId}: ${m.content.take(160)}")
                }
            }
        }.trim()
    }

    fun compactConversationIfNeeded(
        conversationId: String,
        maxContextTokens: Int = 24_000,
        preserveRecentMessages: Int = 8,
    ): CompactionResult {
        val conv = store.getConversation(conversationId)
        val messages = store.listConversationMessages(conversationId, limit = 1_000)
        val initialTokens = messages.sumOf { estimateTokens(it.content) } + estimateTokens(conv?.contextSummary.orEmpty())

        if (initialTokens <= maxContextTokens || messages.size <= preserveRecentMessages) {
            return CompactionResult(
                wasCompacted = false,
                originalEstimatedTokens = initialTokens,
                compactedEstimatedTokens = initialTokens,
                preservedDecisions = emptyList(),
                preservedFilePaths = emptyList(),
                summaryText = conv?.contextSummary.orEmpty(),
                recentMessages = messages,
            )
        }

        val recent = messages.takeLast(preserveRecentMessages)
        val older = messages.dropLast(preserveRecentMessages)

        val preservedDecisions = linkedSetOf<String>()
        val preservedFiles = linkedSetOf<String>()
        val preservedIssues = linkedSetOf<String>()

        older.forEach { msg ->
            filePathRegex.findAll(msg.content).forEach { m -> preservedFiles.add(m.groupValues[1]) }
            msg.content.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                val lower = line.lowercase()
                when {
                    lower.contains("decision") || lower.contains("constraint") ||
                        lower.contains("must ") || lower.contains("architecture") ||
                        lower.contains("security") || lower.contains("requirement") ||
                        lower.contains("ตัดสินใจ") || lower.contains("ข้อกำหนด") -> {
                        preservedDecisions.add(line.take(180))
                    }
                    lower.contains("error") || lower.contains("fail") || lower.contains("blocked") ||
                        lower.contains("bug") || lower.contains("unresolved") || lower.contains("ปัญหา") -> {
                        preservedIssues.add(line.take(180))
                    }
                }
            }
        }

        val summary = buildString {
            if (conv != null && conv.contextSummary.isNotBlank()) {
                appendLine(conv.contextSummary.take(1_200))
                appendLine()
            }
            appendLine("[Compacted ${older.size} earlier messages]")
            if (preservedDecisions.isNotEmpty()) {
                appendLine("Decisions & Constraints:")
                preservedDecisions.take(12).forEach { appendLine("- $it") }
            }
            if (preservedFiles.isNotEmpty()) {
                appendLine("Key File Paths: ${preservedFiles.take(25).joinToString(", ")}")
            }
            if (preservedIssues.isNotEmpty()) {
                appendLine("Unresolved Issues / Findings:")
                preservedIssues.take(8).forEach { appendLine("- $it") }
            }
        }.trim()

        if (conv != null) {
            store.upsertConversation(
                conv.copy(
                    contextSummary = summary.take(MAX_CONVERSATION_SUMMARY_CHARS),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        val compactedTokens = estimateTokens(summary) + recent.sumOf { estimateTokens(it.content) }
        return CompactionResult(
            wasCompacted = true,
            originalEstimatedTokens = initialTokens,
            compactedEstimatedTokens = compactedTokens,
            preservedDecisions = preservedDecisions.toList(),
            preservedFilePaths = preservedFiles.toList(),
            summaryText = summary,
            recentMessages = recent,
        )
    }

    fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length / 4).coerceAtLeast(1)

    /**
     * Evicts in-memory project memory caches under memory pressure while keeping
     * all structured memories durably persisted in [OrqenTaskStore] for lazy reload.
     */
    fun trimInMemoryCache(keepProjectId: String? = null): Int = synchronized(cacheLock) {
        if (keepProjectId == null) {
            val count = structuredCache.size
            structuredCache.clear()
            return@synchronized count
        }
        val toRemove = structuredCache.keys.filter { it != keepProjectId }
        toRemove.forEach { structuredCache.remove(it) }
        toRemove.size
    }

    fun inMemoryProjectCount(): Int = synchronized(cacheLock) { structuredCache.size }

    companion object {
        private const val MEMORY_INDEX_PREFIX = "orqen_mem_v1:"
        private const val MIN_LEARNING_CHARS = 24
        private const val MAX_ENTRY_CONTENT_CHARS = 1_200
        private const val MAX_ENTRIES_PER_PROJECT = 160
        private const val MAX_CACHED_PROJECTS = 8
        private const val MAX_PROJECT_MEMORY_CHARS = 8_000
        private const val MAX_TASK_MEMORY_CHARS = 6_000
        private const val MAX_CONVERSATION_SUMMARY_CHARS = 6_000
    }
}
