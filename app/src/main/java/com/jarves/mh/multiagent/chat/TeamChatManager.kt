package com.jarves.mh.multiagent.chat

import android.content.Context
import com.jarves.mh.multiagent.model.AgentSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GroupMessageType {
    NORMAL,
    TASK_ASSIGNMENT,
    PROGRESS_UPDATE,
    COMPLETION_REPORT,
    BUG_REPORT,
    REVIEW_REQUEST,
    APPROVAL,
    SYSTEM,
}

data class GroupChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderSlot: AgentSlot?, // null = User
    val senderName: String,
    val isUser: Boolean = (senderSlot == null),
    val text: String,
    val messageType: GroupMessageType = GroupMessageType.NORMAL,
    val relatedTaskId: String? = null,
    val relatedFilePath: String? = null,
    val relatedCommitSha: String? = null,
    val workStatus: String? = null,
    val workDetails: List<String> = emptyList(),
    val imagePaths: List<String> = emptyList(),
    val suggestedTaskPrompt: String? = null,
    val apkFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

class TeamChatManager(context: Context, private val teamId: String) {
    private val prefs = context.getSharedPreferences("orqen_team_chat_$teamId", Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow<List<GroupChatMessage>>(emptyList())
    val messages: StateFlow<List<GroupChatMessage>> = _messages.asStateFlow()

    init {
        loadHistory()
    }

    @Synchronized
    private fun loadHistory() {
        val raw = prefs.getString("chat_history", null)
        val list = mutableListOf<GroupChatMessage>()
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val slotStr = obj.optString("senderSlot", "").takeIf { it.isNotBlank() && it != "null" }
                    val slot = slotStr?.let { AgentSlot.fromId(it) }
                    val type = runCatching { GroupMessageType.valueOf(obj.getString("messageType")) }.getOrDefault(GroupMessageType.NORMAL)
                    val detailsArr = obj.optJSONArray("workDetails")
                    val details = buildList {
                        if (detailsArr != null) {
                            for (j in 0 until detailsArr.length()) {
                                val s = detailsArr.optString(j)
                                if (s.isNotBlank()) add(s)
                            }
                        }
                    }
                    val imgArr = obj.optJSONArray("imagePaths")
                    val imgs = buildList {
                        if (imgArr != null) {
                            for (j in 0 until imgArr.length()) {
                                val s = imgArr.optString(j)
                                if (s.isNotBlank()) add(s)
                            }
                        }
                    }
                    list.add(
                        GroupChatMessage(
                            id = obj.getString("id"),
                            senderSlot = slot,
                            senderName = obj.getString("senderName"),
                            isUser = obj.optBoolean("isUser", slot == null),
                            text = obj.getString("text"),
                            messageType = type,
                            relatedTaskId = obj.optString("relatedTaskId").takeIf { it.isNotEmpty() },
                            relatedFilePath = obj.optString("relatedFilePath").takeIf { it.isNotEmpty() },
                            relatedCommitSha = obj.optString("relatedCommitSha").takeIf { it.isNotEmpty() },
                            workStatus = obj.optString("workStatus").takeIf { it.isNotEmpty() },
                            workDetails = details,
                            imagePaths = imgs,
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        )
                    )
                }
            } catch (_: Exception) { }
        }
        if (list.isEmpty()) {
            list.add(
                GroupChatMessage(
                    senderSlot = null,
                    senderName = "System",
                    isUser = false,
                    text = "AI Development Team created. Agent 01, 02, 03, 04 are ready in the shared workspace.",
                    messageType = GroupMessageType.SYSTEM,
                )
            )
        }
        _messages.value = list
    }

    @Synchronized
    fun postMessage(
        senderSlot: AgentSlot?,
        senderName: String,
        text: String,
        messageType: GroupMessageType = GroupMessageType.NORMAL,
        relatedTaskId: String? = null,
        relatedFilePath: String? = null,
        relatedCommitSha: String? = null,
        workStatus: String? = null,
        workDetails: List<String> = emptyList(),
        imagePaths: List<String> = emptyList(),
    ): GroupChatMessage {
        val msg = GroupChatMessage(
            senderSlot = senderSlot,
            senderName = senderName,
            isUser = (senderSlot == null),
            text = text,
            messageType = messageType,
            relatedTaskId = relatedTaskId,
            relatedFilePath = relatedFilePath,
            relatedCommitSha = relatedCommitSha,
            workStatus = workStatus,
            workDetails = workDetails,
            imagePaths = imagePaths,
        )
        val current = _messages.value.toMutableList()
        current.add(msg)
        _messages.value = current
        saveHistory(current)
        return msg
    }

    fun getPrivateMessagesForAgent(slot: AgentSlot): List<GroupChatMessage> {
        return _messages.value.filter { it.senderSlot == slot || (it.isUser && it.text.contains("@${slot.defaultName}")) }
    }

    @Synchronized
    fun clearHistory() {
        _messages.value = emptyList()
        prefs.edit().remove("chat_history").apply()
    }

    private fun saveHistory(list: List<GroupChatMessage>) {
        val array = JSONArray()
        // Save at most 500 recent messages
        val trimmed = if (list.size > 500) list.takeLast(500) else list
        for (m in trimmed) {
            val detailsArr = JSONArray()
            m.workDetails.forEach { detailsArr.put(it) }
            val imgArr = JSONArray()
            m.imagePaths.forEach { imgArr.put(it) }
            val obj = JSONObject()
                .put("id", m.id)
                .put("senderSlot", m.senderSlot?.id ?: "")
                .put("senderName", m.senderName)
                .put("isUser", m.isUser)
                .put("text", m.text)
                .put("messageType", m.messageType.name)
                .put("relatedTaskId", m.relatedTaskId ?: "")
                .put("relatedFilePath", m.relatedFilePath ?: "")
                .put("relatedCommitSha", m.relatedCommitSha ?: "")
                .put("workStatus", m.workStatus ?: "")
                .put("workDetails", detailsArr)
                .put("imagePaths", imgArr)
                .put("timestamp", m.timestamp)
            array.put(obj)
        }
        prefs.edit().putString("chat_history", array.toString()).apply()
    }
}
