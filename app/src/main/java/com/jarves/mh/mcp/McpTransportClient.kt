package com.jarves.mh.mcp

import com.jarves.mh.multiagent.persistence.SecretSanitizer
import com.jarves.mh.runtime.RuntimeInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

data class McpInitializeResult(
    val protocolVersion: String,
    val serverName: String,
    val sessionId: String? = null,
)

data class McpRawToolInfo(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

/**
 * Transport abstraction for real MCP JSON-RPC 2.0 protocol communication.
 * Implemented by:
 * - [StdioMcpTransportClient] (local STDIO servers inside PRoot Linux)
 * - [StreamableHttpMcpTransportClient] (remote Streamable HTTP MCP servers)
 * - [LegacySseMcpTransportClient] (distinct Legacy HTTP + SSE MCP servers)
 */
interface McpTransportClient {
    val serverId: String
    val transportType: McpTransportType
    val isConnected: Boolean
    val isProcessAlive: Boolean

    suspend fun connect(
        config: McpServerConfig,
        resolvedEnv: Map<String, String>,
        resolvedHeaders: Map<String, String>,
        onLog: (String) -> Unit = {},
    ): McpInitializeResult

    suspend fun listTools(requestTimeoutMs: Long): List<McpRawToolInfo>

    suspend fun callTool(
        rawToolName: String,
        argumentsJson: String,
        requestTimeoutMs: Long,
    ): McpToolCallResult

    suspend fun disconnect()
}

/**
 * Real STDIO MCP transport client executing inside ORQEN's PRoot Linux runtime via [RuntimeInstaller.process].
 * Uses safe argv list execution (never unsafe shell string concatenation), separates stderr,
 * detects process termination, and cleans up child processes on disconnect.
 */
class StdioMcpTransportClient(
    override val serverId: String,
    private val installer: RuntimeInstaller,
    private val workspaceResolver: (String?) -> File,
) : McpTransportClient {
    override val transportType: McpTransportType = McpTransportType.STDIO

    @Volatile private var process: Process? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stderrThread: Thread? = null
    @Volatile private var connected: Boolean = false
    private val rpcId = AtomicInteger(1)
    private val ioLock = Any()

    override val isConnected: Boolean
        get() = connected && (process?.isAlive == true)

    override val isProcessAlive: Boolean
        get() = process?.isAlive == true

    override suspend fun connect(
        config: McpServerConfig,
        resolvedEnv: Map<String, String>,
        resolvedHeaders: Map<String, String>,
        onLog: (String) -> Unit,
    ): McpInitializeResult = withContext(Dispatchers.IO) {
        disconnect()
        require(config.command.isNotBlank()) { "STDIO server requires a non-blank command." }

        val installed = installer.installedRuntime()
        val hostWorkspace = workspaceResolver(config.workspaceId).apply { mkdirs() }.canonicalFile
        val argv = buildList {
            add(config.command.trim())
            addAll(config.args)
        }
        onLog("Starting STDIO MCP process: ${argv.joinToString(" ")}")

        val proc = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = hostWorkspace,
            environment = resolvedEnv,
            guestCommand = argv,
            guestWorkspacePath = "/workspace",
            emulateHardLinks = false,
        )
        process = proc
        reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))

        // Read stderr separately so it never corrupts JSON-RPC stdout framing
        stderrThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { errReader ->
                    while (true) {
                        val line = errReader.readLine() ?: break
                        if (line.isNotBlank()) {
                            onLog("[stderr] ${SecretSanitizer.sanitize(line.take(300))}")
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "mcp-stderr-$serverId"
            start()
        }

        val initParams = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("clientInfo", JSONObject().put("name", "ORQEN").put("version", "1.0.0"))

        val initResp = withTimeout(config.connectionTimeoutMs) {
            sendRpcRequest("initialize", initParams)
        }
        val resultObj = initResp.optJSONObject("result")
            ?: error("MCP initialize failed: ${initResp.optJSONObject("error")?.optString("message") ?: "missing result"}")

        // Send notifications/initialized per MCP spec
        sendRpcNotification("notifications/initialized", JSONObject())
        connected = true

        McpInitializeResult(
            protocolVersion = resultObj.optString("protocolVersion", "2024-11-05"),
            serverName = resultObj.optJSONObject("serverInfo")?.optString("name")?.ifBlank { config.name } ?: config.name,
        )
    }

    override suspend fun listTools(requestTimeoutMs: Long): List<McpRawToolInfo> = withContext(Dispatchers.IO) {
        check(isConnected) { "STDIO MCP server $serverId is not connected." }
        val resp = withTimeout(requestTimeoutMs) {
            sendRpcRequest("tools/list", JSONObject())
        }
        parseToolsListResponse(resp)
    }

    override suspend fun callTool(
        rawToolName: String,
        argumentsJson: String,
        requestTimeoutMs: Long,
    ): McpToolCallResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!isConnected) {
            return@withContext McpToolCallResult(
                toolId = rawToolName,
                serverId = serverId,
                success = false,
                contentText = "MCP server $serverId is disconnected.",
                errorCode = "DISCONNECTED",
            )
        }
        val argsObj = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val params = JSONObject()
            .put("name", rawToolName)
            .put("arguments", argsObj)
        val resp = withTimeout(requestTimeoutMs) {
            sendRpcRequest("tools/call", params)
        }
        parseToolCallResponse(rawToolName, serverId, resp, System.currentTimeMillis() - started)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        connected = false
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching {
            val p = process
            if (p != null && p.isAlive) {
                p.destroy()
                if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            }
        }
        process = null
        reader = null
        writer = null
        stderrThread = null
    }

    private fun sendRpcNotification(method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()
        synchronized(ioLock) {
            val w = writer ?: error("STDIO writer closed")
            w.write(payload)
            w.newLine()
            w.flush()
        }
    }

    private fun sendRpcRequest(method: String, params: JSONObject): JSONObject {
        val id = rpcId.getAndIncrement()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        synchronized(ioLock) {
            val w = writer ?: error("STDIO writer closed")
            val r = reader ?: error("STDIO reader closed")
            w.write(payload)
            w.newLine()
            w.flush()

            // Read lines until we receive the matching JSON-RPC response ID (skipping server notifications / non-JSON noise)
            while (true) {
                val line = r.readLine() ?: error("STDIO MCP process terminated unexpectedly while waiting for '$method'.")
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) continue
                val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                if (obj.has("id") && obj.optInt("id", -1) == id) {
                    return obj
                }
            }
        }
    }
}

/**
 * Real Streamable HTTP MCP transport client (MCP 2025-03-26 / 2024-11-05 Streamable HTTP spec).
 * Supports session IDs (`Mcp-Session-Id`), auth headers, JSON & SSE-framed responses, and request timeouts.
 */
class StreamableHttpMcpTransportClient(
    override val serverId: String,
) : McpTransportClient {
    override val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP

    @Volatile private var endpointUrl: String = ""
    @Volatile private var headers: Map<String, String> = emptyMap()
    @Volatile private var sessionId: String? = null
    @Volatile private var connected: Boolean = false
    private val rpcId = AtomicInteger(1)

    override val isConnected: Boolean get() = connected && endpointUrl.isNotBlank()
    override val isProcessAlive: Boolean get() = isConnected

    override suspend fun connect(
        config: McpServerConfig,
        resolvedEnv: Map<String, String>,
        resolvedHeaders: Map<String, String>,
        onLog: (String) -> Unit,
    ): McpInitializeResult = withContext(Dispatchers.IO) {
        require(config.remoteUrl.startsWith("http://", true) || config.remoteUrl.startsWith("https://", true)) {
            "Streamable HTTP requires an http:// or https:// URL."
        }
        endpointUrl = config.remoteUrl.trim()
        headers = resolvedHeaders
        sessionId = null

        onLog("Connecting Streamable HTTP MCP: $endpointUrl")
        val initParams = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("clientInfo", JSONObject().put("name", "ORQEN").put("version", "1.0.0"))

        val (resp, returnedSessionId) = postJsonRpc(
            urlStr = endpointUrl,
            method = "initialize",
            params = initParams,
            id = rpcId.getAndIncrement(),
            timeoutMs = config.connectionTimeoutMs,
        )
        if (!returnedSessionId.isNullOrBlank()) {
            sessionId = returnedSessionId
        }
        val resultObj = resp.optJSONObject("result")
            ?: error("MCP initialize failed: ${resp.optJSONObject("error")?.optString("message") ?: "missing result"}")

        // Send notifications/initialized best-effort
        runCatching {
            postJsonRpc(
                urlStr = endpointUrl,
                method = "notifications/initialized",
                params = JSONObject(),
                id = null,
                timeoutMs = config.connectionTimeoutMs.coerceAtMost(10_000L),
            )
        }
        connected = true
        McpInitializeResult(
            protocolVersion = resultObj.optString("protocolVersion", "2024-11-05"),
            serverName = resultObj.optJSONObject("serverInfo")?.optString("name")?.ifBlank { config.name } ?: config.name,
            sessionId = sessionId,
        )
    }

    override suspend fun listTools(requestTimeoutMs: Long): List<McpRawToolInfo> = withContext(Dispatchers.IO) {
        check(isConnected) { "HTTP MCP server $serverId is not connected." }
        val (resp, _) = postJsonRpc(endpointUrl, "tools/list", JSONObject(), rpcId.getAndIncrement(), requestTimeoutMs)
        parseToolsListResponse(resp)
    }

    override suspend fun callTool(
        rawToolName: String,
        argumentsJson: String,
        requestTimeoutMs: Long,
    ): McpToolCallResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!isConnected) {
            return@withContext McpToolCallResult(rawToolName, serverId, false, "MCP server $serverId is disconnected.", errorCode = "DISCONNECTED")
        }
        val argsObj = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val params = JSONObject().put("name", rawToolName).put("arguments", argsObj)
        val (resp, _) = postJsonRpc(endpointUrl, "tools/call", params, rpcId.getAndIncrement(), requestTimeoutMs)
        parseToolCallResponse(rawToolName, serverId, resp, System.currentTimeMillis() - started)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        val sid = sessionId
        val url = endpointUrl
        connected = false
        sessionId = null
        if (!sid.isNullOrBlank() && url.isNotBlank()) {
            // Optional Streamable HTTP session termination DELETE
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Mcp-Session-Id", sid)
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    private fun postJsonRpc(
        urlStr: String,
        method: String,
        params: JSONObject,
        id: Int?,
        timeoutMs: Long,
    ): Pair<JSONObject, String?> {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs.toInt().coerceIn(5_000, 60_000)
            readTimeout = timeoutMs.toInt().coerceIn(5_000, 120_000)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) {
                error("Authentication failed (HTTP $code): check your MCP token/API key.")
            }
            if (code !in 200..299) {
                error("HTTP $code from MCP server: ${SecretSanitizer.sanitize(raw.take(240))}")
            }
            val returnedSid = conn.getHeaderField("Mcp-Session-Id")
            if (id == null || raw.isBlank()) {
                return JSONObject() to returnedSid
            }
            val parsed = parseJsonOrSseFrame(raw)
            return parsed to returnedSid
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Distinct Legacy HTTP + SSE MCP transport client (Part 3.3).
 * Never silently downgrades Streamable HTTP; explicitly discovers SSE message endpoint on GET
 * and posts JSON-RPC calls to that endpoint.
 */
class LegacySseMcpTransportClient(
    override val serverId: String,
) : McpTransportClient {
    override val transportType: McpTransportType = McpTransportType.LEGACY_HTTP_SSE

    private val delegate = StreamableHttpMcpTransportClient(serverId)
    @Volatile private var connected: Boolean = false

    override val isConnected: Boolean get() = connected && delegate.isConnected
    override val isProcessAlive: Boolean get() = isConnected

    override suspend fun connect(
        config: McpServerConfig,
        resolvedEnv: Map<String, String>,
        resolvedHeaders: Map<String, String>,
        onLog: (String) -> Unit,
    ): McpInitializeResult = withContext(Dispatchers.IO) {
        onLog("Connecting Legacy HTTP + SSE MCP: ${config.remoteUrl}")
        val discoveredPostUrl = discoverSseEndpoint(config.remoteUrl, resolvedHeaders, config.connectionTimeoutMs)
            ?: config.remoteUrl
        val res = delegate.connect(
            config = config.copy(remoteUrl = discoveredPostUrl),
            resolvedEnv = resolvedEnv,
            resolvedHeaders = resolvedHeaders,
            onLog = onLog,
        )
        connected = true
        res
    }

    override suspend fun listTools(requestTimeoutMs: Long): List<McpRawToolInfo> =
        delegate.listTools(requestTimeoutMs)

    override suspend fun callTool(rawToolName: String, argumentsJson: String, requestTimeoutMs: Long): McpToolCallResult =
        delegate.callTool(rawToolName, argumentsJson, requestTimeoutMs)

    override suspend fun disconnect() {
        connected = false
        delegate.disconnect()
    }

    private fun discoverSseEndpoint(sseUrl: String, headers: Map<String, String>, timeoutMs: Long): String? = runCatching {
        val conn = (URL(sseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs.toInt().coerceIn(5_000, 15_000)
            readTimeout = 6_000
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { br ->
                var isEndpointEvent = false
                repeat(20) {
                    val line = br.readLine() ?: return@runCatching null
                    if (line.startsWith("event:")) {
                        isEndpointEvent = line.removePrefix("event:").trim().equals("endpoint", ignoreCase = true)
                    } else if (isEndpointEvent && line.startsWith("data:")) {
                        val rel = line.removePrefix("data:").trim()
                        val resolved = java.net.URI(sseUrl).resolve(rel).toString()
                        return@runCatching resolved
                    }
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

internal fun parseJsonOrSseFrame(raw: String): JSONObject {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) return JSONObject(trimmed)
    // Extract last JSON object from SSE `data: {...}` frames
    val dataLines = trimmed.lines()
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter { it.startsWith("{") }
    val jsonStr = dataLines.lastOrNull() ?: error("Unexpected non-JSON MCP response: ${trimmed.take(200)}")
    return JSONObject(jsonStr)
}

internal fun parseToolsListResponse(resp: JSONObject): List<McpRawToolInfo> {
    val err = resp.optJSONObject("error")
    if (err != null) {
        error("MCP tools/list error (${err.optInt("code")}): ${err.optString("message")}")
    }
    val resultObj = resp.optJSONObject("result") ?: return emptyList()
    val arr: JSONArray = resultObj.optJSONArray("tools") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val name = t.optString("name").trim()
            if (name.isBlank()) continue
            add(
                McpRawToolInfo(
                    name = name,
                    description = t.optString("description").trim(),
                    inputSchemaJson = t.optJSONObject("inputSchema")?.toString() ?: "{}",
                ),
            )
        }
    }
}

internal fun parseToolCallResponse(
    rawToolName: String,
    serverId: String,
    resp: JSONObject,
    durationMs: Long,
): McpToolCallResult {
    val err = resp.optJSONObject("error")
    if (err != null) {
        return McpToolCallResult(
            toolId = rawToolName,
            serverId = serverId,
            success = false,
            contentText = SecretSanitizer.sanitize(err.optString("message", "MCP tool error")),
            errorCode = err.optInt("code", -1).toString(),
            durationMs = durationMs,
        )
    }
    val resultObj = resp.optJSONObject("result")
    val isError = resultObj?.optBoolean("isError", false) == true
    val contentArr = resultObj?.optJSONArray("content")
    val text = buildString {
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val item = contentArr.optJSONObject(i) ?: continue
                val t = item.optString("text")
                if (t.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append(t)
                }
            }
        }
    }.ifBlank { resultObj?.toString() ?: "" }

    return McpToolCallResult(
        toolId = rawToolName,
        serverId = serverId,
        success = !isError,
        contentText = SecretSanitizer.sanitize(text),
        isError = isError,
        durationMs = durationMs,
    )
}
