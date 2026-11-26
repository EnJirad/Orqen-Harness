package com.jarves.mh.multiagent

import com.jarves.mh.multiagent.host.HttpRemoteExecutionTransport
import com.jarves.mh.multiagent.host.RemoteExecutionHost
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6 — Real HTTP Client Integration Test for [HttpRemoteExecutionTransport] + [RemoteExecutionHost].
 * Spins up a real localhost TCP/HTTP/1.1 server using [ServerSocket] and exercises
 * health ping, auth enforcement, submit, progress, logs, artifacts, disconnect/reconnect,
 * pause/resume, and cancel over actual HTTP sockets.
 */
class Phase6RemoteExecutionHttpTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private val running = AtomicBoolean(false)
    private lateinit var baseUrl: String
    private val tasks = ConcurrentHashMap<String, JSONObject>()

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        running.set(true)
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        serverThread = Thread({
            while (running.get()) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                Thread { handleClient(client) }.start()
            }
        }, "phase6-http-test-server").apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        running.set(false)
        runCatching { serverSocket.close() }
        runCatching { serverThread.join(1000) }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            val input = BufferedInputStream(sock.getInputStream())
            val headerBytes = ByteArrayOutputStream()
            var prev3 = 0
            var prev2 = 0
            var prev1 = 0
            while (true) {
                val b = input.read()
                if (b == -1) return
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }
            val headerLines = headerBytes.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
            if (headerLines.isEmpty()) return
            val requestParts = headerLines.first().split(" ")
            val method = requestParts[0]
            val rawPath = requestParts[1].substringBefore("?").trimEnd('/')
            val headers = mutableMapOf<String, String>()
            for (line in headerLines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            val bodyText = String(bodyBytes, 0, readTotal, Charsets.UTF_8)

            val (status, respBody) = route(method, rawPath, headers, bodyText)
            val payload = respBody.toByteArray(Charsets.UTF_8)
            val response = buildString {
                append("HTTP/1.1 $status OK\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            sock.getOutputStream().apply {
                write(response.toByteArray(Charsets.UTF_8))
                write(payload)
                flush()
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
    ): Pair<Int, String> {
        if (method == "GET" && path == "/v1/health") {
            return 200 to """{"status":"ok","online_workers":1}"""
        }
        if (headers["authorization"] != "Bearer test-client-token") {
            return 401 to """{"error":"unauthorized"}"""
        }
        if (method == "POST" && path == "/v1/tasks") {
            val req = JSONObject(body)
            val id = req.getString("taskId")
            val stored = JSONObject()
                .put("taskId", id)
                .put("status", "RUNNING")
                .put("completedSteps", 1)
                .put("totalSteps", 3)
                .put("currentStepTitle", "Compiling")
            tasks[id] = stored
            return 202 to stored.toString()
        }
        val parts = path.split("/")
        if (parts.size == 4 && parts[1] == "v1" && parts[2] == "tasks" && method == "GET") {
            val t = tasks[parts[3]] ?: return 404 to """{"error":"not_found"}"""
            return 200 to t.toString()
        }
        if (parts.size == 5 && parts[1] == "v1" && parts[2] == "tasks") {
            val id = parts[3]
            return when (parts[4]) {
                "logs" -> 200 to JSONObject().put(
                    "logs",
                    JSONArray().put(
                        JSONObject()
                            .put("sequence_no", 1)
                            .put("level", "INFO")
                            .put("message", "Step 1 OK")
                            .put("created_at", 1L),
                    ),
                ).toString()

                "artifacts" -> 200 to JSONObject().put(
                    "artifacts",
                    JSONArray().put(
                        JSONObject()
                            .put("artifact_id", "a1")
                            .put("relative_path", "out.txt")
                            .put("sha256", "abc")
                            .put("size_bytes", 12),
                    ),
                ).toString()

                "pause" -> {
                    tasks[id]?.put("status", "PAUSED")
                    200 to (tasks[id] ?: JSONObject()).toString()
                }

                "resume" -> {
                    tasks[id]?.put("status", "RUNNING")
                    200 to (tasks[id] ?: JSONObject()).toString()
                }

                "cancel" -> {
                    tasks[id]?.put("status", "CANCELLED")
                    200 to (tasks[id] ?: JSONObject()).toString()
                }

                else -> 404 to """{"error":"not_found"}"""
            }
        }
        return 404 to """{"error":"not_found"}"""
    }

    @Test
    fun testHttpTransportSubmitDisconnectReconnectLogsArtifactsPauseResumeAndCancel() {
        val unauthorized = HttpRemoteExecutionTransport(
            baseUrl = baseUrl,
            bearerTokenProvider = { "wrong-token" },
        )
        assertTrue(unauthorized.ping())
        assertFalse(unauthorized.submit("RT-UNAUTH"))

        val transport = HttpRemoteExecutionTransport(
            baseUrl = baseUrl,
            bearerTokenProvider = { "test-client-token" },
        )
        val host = RemoteExecutionHost("remote-local-test", transport)

        assertTrue(transport.ping())
        assertTrue(transport.submitWithPayload("RT-HTTP-1", "proj-1", "HTTP Task", JSONObject()))

        val p1 = host.queryTaskProgress("RT-HTTP-1")
        assertNotNull(p1)
        assertEquals(PersistentTaskStatus.RUNNING, p1?.status)
        assertEquals(1, p1?.completedSteps)

        // Pause & Resume over HTTP
        host.pauseTask("RT-HTTP-1", "User paused")
        assertEquals(PersistentTaskStatus.PAUSED, host.queryTaskProgress("RT-HTTP-1")?.status)
        assertTrue(host.resumeTask(CoroutineScope(Dispatchers.Unconfined), "RT-HTTP-1"))
        assertEquals(PersistentTaskStatus.RUNNING, host.queryTaskProgress("RT-HTTP-1")?.status)

        // Simulate Android network disconnect -> remote keeps running -> reconnect retrieves same taskId
        host.disconnect()
        assertNull(host.queryTaskProgress("RT-HTTP-1"))
        tasks["RT-HTTP-1"]?.put("completedSteps", 3)?.put("status", "COMPLETED")

        assertTrue(host.reconnect())
        val p2 = host.queryTaskProgress("RT-HTTP-1")
        assertEquals("RT-HTTP-1", p2?.taskId)
        assertEquals(3, p2?.completedSteps)
        assertEquals(PersistentTaskStatus.COMPLETED, p2?.status)

        assertEquals(1, transport.fetchLogs("RT-HTTP-1").size)
        assertEquals("out.txt", transport.fetchArtifacts("RT-HTTP-1").first().relativePath)

        // Cancel over HTTP
        host.stopTask("RT-HTTP-1", "Owner stopped")
        assertEquals(PersistentTaskStatus.CANCELLED, host.queryTaskProgress("RT-HTTP-1")?.status)
    }
}
