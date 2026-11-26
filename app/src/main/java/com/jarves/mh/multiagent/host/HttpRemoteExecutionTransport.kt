package com.jarves.mh.multiagent.host

import com.jarves.mh.multiagent.persistence.ExecutionMode
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteLogEntry(
    val sequenceNo: Long,
    val level: String,
    val message: String,
    val createdAt: Long,
)

data class RemoteArtifactEntry(
    val artifactId: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Production Android HTTP client transport connecting [RemoteExecutionHost]
 * to the ORQEN Remote Execution API (`remote-executor/orqen_api.py`).
 *
 * Security:
 * - Never hardcodes credentials; caller supplies user-scoped Bearer token from Keystore.
 * - Uses `Idempotency-Key` header on task submission to prevent duplicate remote tasks.
 */
class HttpRemoteExecutionTransport(
    private val baseUrl: String,
    private val bearerTokenProvider: () -> String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : RemoteExecutionTransport {

    private val normalizedBase = baseUrl.trimEnd('/')

    fun submitWithPayload(
        taskId: String,
        projectId: String,
        title: String,
        payloadJson: JSONObject,
        idempotencyKey: String = taskId,
    ): Boolean {
        val body = JSONObject()
            .put("taskId", taskId)
            .put("projectId", projectId)
            .put("title", title)
            .put("idempotencyKey", idempotencyKey)
            .put("payload", payloadJson)
        val (code, _) = request(
            method = "POST",
            path = "/v1/tasks",
            body = body.toString(),
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        )
        return code in 200..299
    }

    override fun submit(taskId: String): Boolean = submitWithPayload(
        taskId = taskId,
        projectId = "default",
        title = "Remote Task $taskId",
        payloadJson = JSONObject(),
        idempotencyKey = taskId,
    )

    override fun pause(taskId: String, reason: String) {
        request("POST", "/v1/tasks/$taskId/pause", JSONObject().put("reason", reason).toString())
    }

    override fun resume(taskId: String): Boolean {
        val (code, _) = request("POST", "/v1/tasks/$taskId/resume", "{}")
        return code in 200..299
    }

    override fun stop(taskId: String, reason: String) {
        request("POST", "/v1/tasks/$taskId/cancel", JSONObject().put("reason", reason).toString())
    }

    override fun stopAll(reason: String): Int {
        val (code, text) = request("GET", "/v1/tasks", null)
        if (code != 200 || text.isBlank()) return 0
        val arr = runCatching { JSONObject(text).optJSONArray("tasks") }.getOrNull() ?: return 0
        var stopped = 0
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val st = t.optString("status")
            if (st in setOf("QUEUED", "CLAIMED", "RUNNING", "RETRYING", "PAUSED")) {
                stop(t.getString("taskId"), reason)
                stopped++
            }
        }
        return stopped
    }

    override fun fetchProgress(taskId: String): HostTaskProgress? {
        val (code, text) = request("GET", "/v1/tasks/$taskId", null)
        if (code != 200 || text.isBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            val status = mapRemoteStatus(o.optString("status", "QUEUED"))
            HostTaskProgress(
                taskId = o.getString("taskId"),
                status = status,
                completedSteps = o.optInt("completedSteps", 0),
                totalSteps = o.optInt("totalSteps", 1),
                currentStepTitle = o.optString("currentStepTitle").takeIf(String::isNotBlank),
                actualCostUsd = 0.0,
                lastError = o.optString("lastError").takeIf(String::isNotBlank),
                isConnected = true,
                executionMode = ExecutionMode.REMOTE_HOST,
            )
        }.getOrNull()
    }

    fun fetchLogs(taskId: String): List<RemoteLogEntry> {
        val (code, text) = request("GET", "/v1/tasks/$taskId/logs", null)
        if (code != 200 || text.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("logs") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            RemoteLogEntry(
                sequenceNo = o.optLong("sequence_no"),
                level = o.optString("level", "INFO"),
                message = o.optString("message", ""),
                createdAt = o.optLong("created_at"),
            )
        }
    }

    fun fetchArtifacts(taskId: String): List<RemoteArtifactEntry> {
        val (code, text) = request("GET", "/v1/tasks/$taskId/artifacts", null)
        if (code != 200 || text.isBlank()) return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("artifacts") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            RemoteArtifactEntry(
                artifactId = o.optString("artifact_id"),
                relativePath = o.optString("relative_path"),
                sha256 = o.optString("sha256"),
                sizeBytes = o.optLong("size_bytes"),
            )
        }
    }

    override fun ping(): Boolean {
        val (code, _) = request("GET", "/v1/health", null, includeAuth = false)
        return code == 200
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        includeAuth: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        return runCatching {
            val conn = (URL("$normalizedBase$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                if (includeAuth) {
                    val tok = bearerTokenProvider().trim()
                    if (tok.isNotEmpty()) setRequestProperty("Authorization", "Bearer $tok")
                }
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            code to text
        }.getOrElse { -1 to (it.message ?: "Network error") }
    }

    private fun mapRemoteStatus(remote: String): PersistentTaskStatus = when (remote.uppercase()) {
        "QUEUED", "RETRYING" -> PersistentTaskStatus.QUEUED
        "CLAIMED", "RUNNING" -> PersistentTaskStatus.RUNNING
        "PAUSED" -> PersistentTaskStatus.PAUSED
        "COMPLETED" -> PersistentTaskStatus.COMPLETED
        "CANCELLED" -> PersistentTaskStatus.CANCELLED
        "FAILED" -> PersistentTaskStatus.FAILED
        else -> PersistentTaskStatus.QUEUED
    }
}
