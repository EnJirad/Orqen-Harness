package com.jarves.mh.multiagent.git

import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import org.json.JSONObject
import java.io.File

data class HubEnvelope(
    val id: String,
    val fromSlot: String,
    val fromName: String,
    val toSlot: String,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Manages `.agent/` disk-backed coordination hub inside any active project workspace:
 * - Persists agent state snapshots for PRoot crash & 45-minute session recovery
 * - Exchanges inbox/outbox JSON messages between agents
 */
class HubExchangeService(private val workspaceDir: File) {
    private val agentRoot: File get() = File(workspaceDir, ".agent")
    private val stateDir: File get() = File(agentRoot, "state")
    private val inboxDir: File get() = File(agentRoot, "communication/inbox")
    private val outboxDir: File get() = File(agentRoot, "communication/outbox")

    fun ensureHubStructure() {
        stateDir.mkdirs()
        AgentSlot.entries.forEach { slot ->
            File(inboxDir, slot.id).mkdirs()
            File(outboxDir, slot.id).mkdirs()
        }
    }

    fun saveAgentSnapshot(agent: AgentState): File {
        ensureHubStructure()
        val file = File(stateDir, "${agent.config.slot.id}.json")
        val json = JSONObject().apply {
            put("slot", agent.config.slot.id)
            put("name", agent.config.name)
            put("role", agent.config.role.type.name)
            put("roleTitle", agent.config.role.title)
            put("provider", agent.config.providerId)
            put("model", agent.config.modelId)
            put("isEnabled", agent.config.isEnabled)
            put("lifecycleState", agent.lifecycleState.name)
            put("currentTask", agent.activityState.currentTask)
            put("currentFile", agent.activityState.currentFile)
            put("updatedAt", System.currentTimeMillis())
        }
        file.writeText(json.toString(2))
        return file
    }

    fun readAgentSnapshot(slot: AgentSlot): JSONObject? {
        val file = File(stateDir, "${slot.id}.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    fun dispatchMessage(envelope: HubEnvelope): File {
        ensureHubStructure()
        val targetInbox = File(inboxDir, envelope.toSlot).apply { mkdirs() }
        val senderOutbox = File(outboxDir, envelope.fromSlot).apply { mkdirs() }
        val payload = JSONObject().apply {
            put("id", envelope.id)
            put("fromSlot", envelope.fromSlot)
            put("fromName", envelope.fromName)
            put("toSlot", envelope.toSlot)
            put("subject", envelope.subject)
            put("body", envelope.body)
            put("timestamp", envelope.timestamp)
        }.toString(2)

        File(senderOutbox, "${envelope.id}.json").writeText(payload)
        val inboxFile = File(targetInbox, "${envelope.id}.json")
        inboxFile.writeText(payload)
        return inboxFile
    }

    fun readInbox(slot: AgentSlot): List<HubEnvelope> {
        val dir = File(inboxDir, slot.id)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.mapNotNull { file ->
                runCatching {
                    val obj = JSONObject(file.readText())
                    HubEnvelope(
                        id = obj.getString("id"),
                        fromSlot = obj.getString("fromSlot"),
                        fromName = obj.optString("fromName", obj.getString("fromSlot")),
                        toSlot = obj.getString("toSlot"),
                        subject = obj.optString("subject", ""),
                        body = obj.optString("body", ""),
                        timestamp = obj.optLong("timestamp", file.lastModified()),
                    )
                }.getOrNull()
            } ?: emptyList()
    }
}
