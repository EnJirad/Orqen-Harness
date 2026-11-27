package com.jarves.mh.data

import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentChatDraftEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DraftChatMode {
    SOLO,
    TEAM_CHAT,
    TEAM_TASK,
    PRIVATE_AGENT,
}

data class ChatDraftRecord(
    val conversationKey: String,
    val text: String,
    val cursorPosition: Int = text.length,
    val revision: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val projectId: String = "",
    val conversationId: String = "",
    val mode: String = DraftChatMode.SOLO.name,
    val cursorStart: Int = cursorPosition,
    val cursorEnd: Int = cursorPosition,
    val attachments: List<String> = emptyList(),
)

/**
 * Shared durable Chat Draft Store for BOTH Single Project (Solo) and AI Team.
 *
 * Primary persistence: SQLite (`chat_drafts` table via [OrqenTaskStore]) when provided.
 * Fallback / legacy migration: atomic JSON file (`storageFile`), automatically migrated
 * into SQLite on first launch without losing any existing drafts.
 *
 * Enforces:
 * - Strict isolation by (projectId, conversationId, mode)
 * - Monotonic revision guard against stale async overwrites
 * - Exact text, cursor/selection range, and unsent attachment preservation
 * - Explicit clear only after confirmed send acceptance
 */
class ChatDraftStore(
    private val storageFile: File,
    private val taskStore: OrqenTaskStore? = null,
) {
    private val lock = Any()
    private val revisionCounter = AtomicLong(0L)
    private val drafts = ConcurrentHashMap<String, ChatDraftRecord>()

    init {
        loadAll()
    }

    fun singleProjectKey(projectId: String, chatId: String): String =
        "single:${projectId.trim()}:${chatId.trim().ifBlank { "default" }}"

    fun teamProjectKey(
        projectId: String,
        conversationId: String = "group",
        mode: DraftChatMode = DraftChatMode.TEAM_CHAT,
    ): String {
        val conv = conversationId.trim().ifBlank { "group" }
        return if (mode == DraftChatMode.TEAM_CHAT) {
            "team:${projectId.trim()}:$conv"
        } else {
            "team:${projectId.trim()}:$conv:${mode.name.lowercase()}"
        }
    }

    fun nextRevision(): Long = revisionCounter.incrementAndGet()

    fun getDraft(conversationKey: String): ChatDraftRecord? =
        drafts[conversationKey]

    fun getDraftText(conversationKey: String): String =
        drafts[conversationKey]?.text.orEmpty()

    /**
     * Saves a draft durably to SQLite (and backup JSON file if no SQLite store is attached).
     * Rejects stale writes whose [revision] is older than the currently stored revision.
     * Empty text + empty attachments removes the stored entry cleanly.
     */
    fun saveDraft(
        conversationKey: String,
        text: String,
        cursorPosition: Int = text.length,
        revision: Long = nextRevision(),
        projectId: String = "",
        conversationId: String = "",
        mode: DraftChatMode? = null,
        cursorStart: Int = cursorPosition,
        cursorEnd: Int = cursorPosition,
        attachments: List<String> = emptyList(),
    ): Boolean {
        if (conversationKey.isBlank()) return false
        synchronized(lock) {
            val existing = drafts[conversationKey]
            if (existing != null && revision < existing.revision) {
                return false
            }
            val parsedMeta = parseKeyMetadata(conversationKey)
            val resolvedProjectId = projectId.ifBlank { parsedMeta.first }
            val resolvedConversationId = conversationId.ifBlank { parsedMeta.second }
            val resolvedMode = (mode?.name ?: parsedMeta.third)
            val cleanAttachments = attachments.map { it.trim() }.filter { it.isNotBlank() }.distinct()

            if (text.isEmpty() && cleanAttachments.isEmpty()) {
                drafts.remove(conversationKey)
                runCatching { taskStore?.deleteChatDraft(conversationKey) }
                if (taskStore == null) persistToDiskLocked()
                return true
            }

            val clampedStart = cursorStart.coerceIn(0, text.length)
            val clampedEnd = cursorEnd.coerceIn(0, text.length)
            val now = System.currentTimeMillis()
            val record = ChatDraftRecord(
                conversationKey = conversationKey,
                text = text,
                cursorPosition = clampedEnd,
                revision = revision,
                updatedAtMs = now,
                projectId = resolvedProjectId,
                conversationId = resolvedConversationId,
                mode = resolvedMode,
                cursorStart = clampedStart,
                cursorEnd = clampedEnd,
                attachments = cleanAttachments,
            )
            drafts[conversationKey] = record
            val store = taskStore
            if (store != null) {
                runCatching {
                    store.upsertChatDraft(
                        PersistentChatDraftEntity(
                            draftKey = record.conversationKey,
                            projectId = record.projectId,
                            conversationId = record.conversationId,
                            mode = record.mode,
                            draftText = record.text,
                            cursorStart = record.cursorStart,
                            cursorEnd = record.cursorEnd,
                            attachments = record.attachments,
                            revision = record.revision,
                            updatedAtMs = record.updatedAtMs,
                        ),
                    )
                }
            } else {
                persistToDiskLocked()
            }
            return true
        }
    }

    /**
     * Clears the draft ONLY after a message has been successfully accepted for submission.
     * Never affects drafts belonging to other conversations or projects.
     */
    fun clearDraft(conversationKey: String) {
        if (conversationKey.isBlank()) return
        synchronized(lock) {
            val removed = drafts.remove(conversationKey) != null
            runCatching { taskStore?.deleteChatDraft(conversationKey) }
            if (removed && taskStore == null) {
                persistToDiskLocked()
            }
        }
    }

    fun listAllDraftKeys(): Set<String> = drafts.keys.toSet()

    private fun loadAll() {
        synchronized(lock) {
            var maxRev = 0L
            // 1. Load from SQLite if attached
            val store = taskStore
            if (store != null) {
                runCatching {
                    store.listAllChatDrafts().forEach { entity ->
                        if (entity.draftText.isEmpty() && entity.attachments.isEmpty()) return@forEach
                        if (entity.revision > maxRev) maxRev = entity.revision
                        drafts[entity.draftKey] = ChatDraftRecord(
                            conversationKey = entity.draftKey,
                            text = entity.draftText,
                            cursorPosition = entity.cursorEnd.coerceIn(0, entity.draftText.length),
                            revision = entity.revision,
                            updatedAtMs = entity.updatedAtMs,
                            projectId = entity.projectId,
                            conversationId = entity.conversationId,
                            mode = entity.mode,
                            cursorStart = entity.cursorStart.coerceIn(0, entity.draftText.length),
                            cursorEnd = entity.cursorEnd.coerceIn(0, entity.draftText.length),
                            attachments = entity.attachments,
                        )
                    }
                }
            }

            // 2. Load legacy JSON file (and migrate into SQLite if SQLite is attached)
            if (storageFile.isFile && storageFile.length() > 0L) {
                runCatching {
                    val root = JSONObject(storageFile.readText())
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (drafts.containsKey(key)) continue
                        val obj = root.optJSONObject(key) ?: continue
                        val text = obj.optString("text", "")
                        val attachArr = obj.optJSONArray("attachments")
                        val attachments = buildList {
                            if (attachArr != null) {
                                for (i in 0 until attachArr.length()) {
                                    val s = attachArr.optString(i)
                                    if (s.isNotBlank()) add(s)
                                }
                            }
                        }
                        if (text.isEmpty() && attachments.isEmpty()) continue
                        val rev = obj.optLong("revision", 1L)
                        if (rev > maxRev) maxRev = rev
                        val (pId, cId, m) = parseKeyMetadata(key)
                        val cursorPos = obj.optInt("cursorPosition", text.length).coerceIn(0, text.length)
                        val record = ChatDraftRecord(
                            conversationKey = key,
                            text = text,
                            cursorPosition = cursorPos,
                            revision = rev,
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                            projectId = obj.optString("projectId", pId).ifBlank { pId },
                            conversationId = obj.optString("conversationId", cId).ifBlank { cId },
                            mode = obj.optString("mode", m).ifBlank { m },
                            cursorStart = obj.optInt("cursorStart", cursorPos).coerceIn(0, text.length),
                            cursorEnd = obj.optInt("cursorEnd", cursorPos).coerceIn(0, text.length),
                            attachments = attachments,
                        )
                        drafts[key] = record
                        taskStore?.let { store ->
                            runCatching {
                                store.upsertChatDraft(
                                    PersistentChatDraftEntity(
                                        draftKey = record.conversationKey,
                                        projectId = record.projectId,
                                        conversationId = record.conversationId,
                                        mode = record.mode,
                                        draftText = record.text,
                                        cursorStart = record.cursorStart,
                                        cursorEnd = record.cursorEnd,
                                        attachments = record.attachments,
                                        revision = record.revision,
                                        updatedAtMs = record.updatedAtMs,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            if (maxRev > revisionCounter.get()) {
                revisionCounter.set(maxRev)
            }
        }
    }

    private fun parseKeyMetadata(key: String): Triple<String, String, String> {
        val parts = key.split(":")
        return when {
            parts.size >= 3 && parts[0] == "single" -> Triple(parts[1], parts[2], DraftChatMode.SOLO.name)
            parts.size >= 4 && parts[0] == "team" -> Triple(parts[1], parts[2], parts[3].uppercase())
            parts.size >= 3 && parts[0] == "team" -> Triple(parts[1], parts[2], DraftChatMode.TEAM_CHAT.name)
            parts.size >= 2 -> Triple(parts[1], "default", DraftChatMode.SOLO.name)
            else -> Triple("unknown", "default", DraftChatMode.SOLO.name)
        }
    }

    private fun persistToDiskLocked() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val root = JSONObject()
            drafts.forEach { (key, rec) ->
                root.put(
                    key,
                    JSONObject().apply {
                        put("text", rec.text)
                        put("cursorPosition", rec.cursorPosition)
                        put("cursorStart", rec.cursorStart)
                        put("cursorEnd", rec.cursorEnd)
                        put("projectId", rec.projectId)
                        put("conversationId", rec.conversationId)
                        put("mode", rec.mode)
                        put("attachments", JSONArray(rec.attachments))
                        put("revision", rec.revision)
                        put("updatedAtMs", rec.updatedAtMs)
                    },
                )
            }
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(root.toString())
                tmp.delete()
            }
        }
    }
}
