package com.jarves.mh.mcp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive verification suite for:
 * - Part 13: MCP Config Parsing, Secret Isolation, Real Streamable HTTP MCP Server lifecycle,
 *   Tool Discovery, Namespacing, Duplicate Handling, Auth Failures, Workspace Scoping, Redaction.
 * - Part 16.12: All 18 SPV Authorization & Dynamic Permission Delegation scenarios.
 */
class McpIntegrationAndAuthorizationTest {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var serverPort: Int = 0
    private val requireAuthToken = AtomicBoolean(false)
    private val expectedToken = "secret-neon-token-999"

    @Before
    fun startRealLocalMcpHttpServer() {
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        serverPort = ss.localPort
        running.set(true)
        serverThread = Thread {
            while (running.get()) {
                val client = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                Thread { handleHttpClient(client) }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun handleHttpClient(socket: java.net.Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                line = reader.readLine()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            val body = String(bodyChars, 0, readTotal)

            val out = s.getOutputStream()
            val authHeader = headers["authorization"].orEmpty()
            if (requireAuthToken.get() && authHeader != "Bearer $expectedToken") {
                val err = """{"error":"Unauthorized"}""".toByteArray(StandardCharsets.UTF_8)
                val resp = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${err.size}\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(StandardCharsets.UTF_8))
                out.write(err)
                out.flush()
                return
            }

            if (requestLine.startsWith("DELETE")) {
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                return
            }

            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method")
            val responseJson = when (method) {
                "initialize" -> JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "result",
                        JSONObject().apply {
                            put("protocolVersion", "2025-03-26")
                            put("serverInfo", JSONObject().put("name", "local-neon-mcp").put("version", "1.0.0"))
                            put("capabilities", JSONObject().put("tools", JSONObject()))
                        },
                    )
                }
                "notifications/initialized" -> null
                "tools/list" -> JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "result",
                        JSONObject().put(
                            "tools",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("name", "run_sql")
                                        .put("description", "Execute read SQL query")
                                        .put(
                                            "inputSchema",
                                            JSONObject()
                                                .put("type", "object")
                                                .put("properties", JSONObject().put("sql", JSONObject().put("type", "string")))
                                                .put("required", JSONArray().put("sql")),
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("name", "create_branch")
                                        .put("description", "Create a new database branch")
                                        .put(
                                            "inputSchema",
                                            JSONObject()
                                                .put("type", "object")
                                                .put("properties", JSONObject().put("branch", JSONObject().put("type", "string")))
                                                .put("required", JSONArray().put("branch")),
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("name", "delete_branch")
                                        .put("description", "Permanently delete a production database branch")
                                        .put(
                                            "inputSchema",
                                            JSONObject()
                                                .put("type", "object")
                                                .put("properties", JSONObject().put("branch", JSONObject().put("type", "string")))
                                                .put("required", JSONArray().put("branch")),
                                        ),
                                ),
                        ),
                    )
                }
                "tools/call" -> {
                    val params = req.optJSONObject("params") ?: JSONObject()
                    val toolName = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put(
                            "result",
                            JSONObject()
                                .put("isError", false)
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", "OK:$toolName:$args"),
                                    ),
                                ),
                        )
                    }
                }
                else -> JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", JSONObject().put("code", -32601).put("message", "Method not found: $method"))
                }
            }

            if (responseJson == null) {
                val resp = "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            } else {
                val bytes = responseJson.toString().toByteArray(StandardCharsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(StandardCharsets.UTF_8))
                out.write(bytes)
                out.flush()
            }
        }
    }

    @After
    fun tearDown() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
    }

    // =========================================================================
    // PART 13 — PASTE-FIRST CONFIG PARSING & SECRET ISOLATION TESTS
    // =========================================================================

    @Test
    fun pasteFirstParser_supportsClaudeMcpServersJson_cliCommand_remoteUrl_andRejectsMalformed() {
        val claudeJson = """
            {
              "mcpServers": {
                "neon": {
                  "command": "npx",
                  "args": ["-y", "@neondatabase/mcp-server-neon"],
                  "env": {
                    "NEON_API_KEY": "neon_real_secret_12345",
                    "NODE_ENV": "production"
                  }
                },
                "github": {
                  "url": "https://api.githubcopilot.com/mcp/",
                  "headers": {
                    "Authorization": "Bearer <YOUR_TOKEN>"
                  }
                }
              }
            }
        """.trimIndent()
        val parsed = McpConfigParser.parse(claudeJson)
        assertTrue(parsed.isSuccess)
        assertEquals(2, parsed.drafts.size)

        val neon = parsed.drafts.first { it.config.name.equals("neon", ignoreCase = true) }
        assertEquals(McpTransportType.STDIO, neon.config.transportType)
        assertEquals("npx", neon.config.command)
        assertEquals("neon_real_secret_12345", neon.extractedSecretEnv["NEON_API_KEY"])
        assertFalse("Secret must NOT remain in plain env map", neon.config.env.containsKey("NEON_API_KEY"))
        assertEquals("production", neon.config.env["NODE_ENV"])

        val github = parsed.drafts.first { it.config.name.equals("github", ignoreCase = true) }
        assertEquals(McpTransportType.STREAMABLE_HTTP, github.config.transportType)
        assertTrue("Placeholder token must be excluded from secret storage", github.extractedSecretHeaders.isEmpty())
        assertTrue("Placeholder token must emit warning", github.warnings.isNotEmpty())

        val cliParsed = McpConfigParser.parse(
            "claude mcp add --transport http neon https://mcp.neon.tech/mcp --header \"Authorization: Bearer real_tok_abc\"",
        )
        assertTrue(cliParsed.isSuccess)
        assertEquals(McpTransportType.STREAMABLE_HTTP, cliParsed.drafts.first().config.transportType)
        assertEquals("https://mcp.neon.tech/mcp", cliParsed.drafts.first().config.remoteUrl)
        assertEquals("Bearer real_tok_abc", cliParsed.drafts.first().extractedSecretHeaders["Authorization"])

        val urlParsed = McpConfigParser.parse("https://mcp.neon.tech/mcp")
        assertTrue(urlParsed.isSuccess)
        assertEquals(McpTransportType.STREAMABLE_HTTP, urlParsed.drafts.first().config.transportType)

        val bad = McpConfigParser.parse("{not valid json")
        assertFalse(bad.isSuccess)
        assertNotNull(bad.error)
    }

    // =========================================================================
    // PART 13 — REAL STREAMABLE HTTP MCP LIFECYCLE, DISCOVERY, DUPLICATES & AUTH
    // =========================================================================

    @Test
    fun realStreamableHttpMcpServer_connectDiscoverExecuteDisconnect_andAuthFailure() = runBlocking {
        val store = InMemoryMcpStore()
        val secrets = InMemoryMcpSecretStore()
        val manager = McpConnectionManager(
            store = store,
            secretStore = secrets,
            transportFactory = { cfg -> StreamableHttpMcpTransportClient(cfg.serverId) },
        )

        val srvA = manager.saveServer(
            McpServerConfig(
                serverId = "srv-neon-a",
                name = "Neon A",
                transportType = McpTransportType.STREAMABLE_HTTP,
                remoteUrl = "http://127.0.0.1:$serverPort/mcp",
                scope = McpScope.GLOBAL,
            ),
        )
        val srvB = manager.saveServer(
            McpServerConfig(
                serverId = "srv-neon-b",
                name = "Neon B",
                transportType = McpTransportType.STREAMABLE_HTTP,
                remoteUrl = "http://127.0.0.1:$serverPort/mcp",
                scope = McpScope.WORKSPACE,
                workspaceId = "ws-alpha",
            ),
        )

        // Connect & discover tools from REAL local HTTP server
        assertTrue(manager.connectServer(srvA.serverId).isSuccess)
        assertTrue(manager.connectServer(srvB.serverId).isSuccess)

        val toolsA = manager.tools.value.filter { it.serverId == srvA.serverId }
        val toolsB = manager.tools.value.filter { it.serverId == srvB.serverId }
        assertEquals(3, toolsA.size)
        assertEquals(3, toolsB.size)

        val runSqlA = toolsA.first { it.rawToolName == "run_sql" }
        val runSqlB = toolsB.first { it.rawToolName == "run_sql" }
        assertNotEquals("Duplicate tool names across servers MUST be uniquely namespaced", runSqlA.namespacedToolId, runSqlB.namespacedToolId)
        assertTrue(runSqlA.namespacedToolId.startsWith("mcp__"))

        val deleteBranch = toolsA.first { it.rawToolName == "delete_branch" }
        assertTrue("delete_branch must be classified high-risk", deleteBranch.isHighRisk)

        // Grant agent-02 EXECUTE on srvA (run_sql is inferred as EXECUTE) and execute REAL tool call over HTTP
        manager.authEngine.setBasePermission(
            McpAgentBasePermission(agentId = "agent-02", serverId = srvA.serverId, maxLevel = McpPermissionLevel.EXECUTE),
        )
        val execRes = manager.executeToolForAgent(
            agentId = "agent-02",
            agentRole = "DEVELOPER",
            namespacedToolId = runSqlA.namespacedToolId,
            argumentsJson = """{"sql":"SELECT 1"}""",
            workspaceId = "ws-alpha",
            taskId = "task-1",
        )
        assertTrue("Real HTTP MCP tool call should succeed: ${execRes.contentText}", execRes.success)
        assertTrue(execRes.contentText.contains("OK:run_sql"))

        // Workspace boundary check: srvB belongs to ws-alpha, must reject call from ws-beta
        manager.authEngine.setBasePermission(
            McpAgentBasePermission(agentId = "agent-02", serverId = srvB.serverId, maxLevel = McpPermissionLevel.EXECUTE),
        )
        val crossWs = manager.executeToolForAgent(
            agentId = "agent-02",
            agentRole = "DEVELOPER",
            namespacedToolId = runSqlB.namespacedToolId,
            argumentsJson = """{"sql":"SELECT 1"}""",
            workspaceId = "ws-beta",
            taskId = "task-1",
        )
        assertFalse("Workspace boundary violation must be rejected", crossWs.success)
        assertTrue(crossWs.contentText.contains("Workspace scope violation", ignoreCase = true))

        // Disconnect server -> subsequent execution must fail cleanly
        manager.disconnectServer(srvA.serverId)
        val afterDisconnect = manager.executeToolForAgent(
            agentId = "agent-02",
            agentRole = "DEVELOPER",
            namespacedToolId = runSqlA.namespacedToolId,
            argumentsJson = """{"sql":"SELECT 1"}""",
            workspaceId = "ws-alpha",
            taskId = "task-1",
        )
        assertFalse(afterDisconnect.success)
        assertTrue(afterDisconnect.contentText.contains("disconnected", ignoreCase = true))

        // Enable 401 auth requirement -> reconnect without token must fail with ERROR status
        requireAuthToken.set(true)
        assertTrue(manager.connectServer(srvA.serverId).isFailure)
        assertEquals(McpConnectionStatus.ERROR, manager.runtimeStates.value[srvA.serverId]?.status)

        // Save real secret header in Vault (never in plain config) -> reconnect succeeds!
        manager.saveServer(srvA, secretHeaders = mapOf("Authorization" to "Bearer $expectedToken"))
        assertFalse("Secret header must never be persisted in plain headers map", store.getServer(srvA.serverId)!!.headers.containsKey("Authorization"))
        assertTrue(manager.connectServer(srvA.serverId).isSuccess)
        assertEquals(McpConnectionStatus.CONNECTED, manager.runtimeStates.value[srvA.serverId]?.status)
    }

    // =========================================================================
    // PART 16.12 — ALL 18 SPV AUTHORIZATION & DYNAMIC DELEGATION SCENARIOS
    // =========================================================================

    @Test
    fun part16_spvAuthorization_allCoreSecurityAndDelegationScenarios() {
        val clock = AtomicLong(1_000_000L)
        val spvAvailable = AtomicBoolean(true)
        var maxOwnerLevel = McpPermissionLevel.EXECUTE

        val store = InMemoryMcpStore()
        val auth = McpAuthorizationEngine(
            store = store,
            ownerMaxDelegableLevel = { maxOwnerLevel },
            isSupervisorAvailable = { spvAvailable.get() },
            nowProvider = { clock.get() },
        )

        val server = McpServerConfig(
            serverId = "srv-neon",
            name = "Neon",
            transportType = McpTransportType.STREAMABLE_HTTP,
            remoteUrl = "https://mcp.neon.tech/mcp",
            scope = McpScope.WORKSPACE,
            workspaceId = "ws-1",
        )
        val readTool = McpToolDefinition(
            namespacedToolId = "mcp__neon__list_projects",
            serverId = "srv-neon",
            serverName = "Neon",
            rawToolName = "list_projects",
            requiredPermissionLevel = McpPermissionLevel.READ,
            isHighRisk = false,
            available = true,
        )
        val writeTool = McpToolDefinition(
            namespacedToolId = "mcp__neon__create_branch",
            serverId = "srv-neon",
            serverName = "Neon",
            rawToolName = "create_branch",
            requiredPermissionLevel = McpPermissionLevel.WRITE,
            isHighRisk = false,
            available = true,
        )
        val highRiskTool = McpToolDefinition(
            namespacedToolId = "mcp__neon__delete_branch",
            serverId = "srv-neon",
            serverName = "Neon",
            rawToolName = "delete_branch",
            requiredPermissionLevel = McpPermissionLevel.WRITE,
            isHighRisk = true,
            available = true,
        )
        val deniedTool = McpToolDefinition(
            namespacedToolId = "mcp__neon__drop_db",
            serverId = "srv-neon",
            serverName = "Neon",
            rawToolName = "drop_db",
            requiredPermissionLevel = McpPermissionLevel.WRITE,
            policy = McpToolPolicy.DENY,
            isHighRisk = true,
            available = true,
        )

        // Scenario 1: Unauthorized agent denied by default (signals PERMISSION_REQUIRED)
        val s1 = auth.authorizeExecution("agent-02", server, readTool, true, "ws-1", "task-1")
        assertFalse(s1.allowed)
        assertTrue(s1.permissionRequired)

        // Scenario 2: Agent with READ base permission can call readTool, NOT writeTool
        auth.setBasePermission(McpAgentBasePermission(agentId = "agent-02", serverId = "srv-neon", maxLevel = McpPermissionLevel.READ))
        assertTrue(auth.authorizeExecution("agent-02", server, readTool, true, "ws-1", "task-1").allowed)
        assertFalse(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-1").allowed)

        // Scenario 3: Dynamic permission request -> SPV approves scoped temporary grant -> agent executes!
        val reqWrite = McpPermissionRequest(
            requesterAgentId = "agent-02",
            assignedRole = "DEVELOPER",
            taskId = "task-1",
            workspaceId = "ws-1",
            mcpServerId = "srv-neon",
            toolId = writeTool.namespacedToolId,
            requestedAction = "create_branch",
            requestedPermissionLevel = McpPermissionLevel.WRITE,
            justification = "Create dev branch for schema migration",
            requestedDurationMs = 120_000L,
        )
        val (d3, grant3) = auth.evaluatePermissionRequest(reqWrite, server, writeTool, activeTaskAgentIds = setOf("agent-02"))
        assertEquals(McpSupervisorDecisionKind.APPROVED, d3)
        assertNotNull(grant3)
        assertTrue(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-1").allowed)

        // Scenario 4: Grant is strictly scoped to task-1 (cannot be used in task-2!)
        assertFalse(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-2").allowed)

        // Scenario 5: Grant is strictly scoped to ws-1 (cannot be used in ws-2!)
        assertFalse(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-2", "task-1").allowed)

        // Scenario 6: Grant auto-expires when clock advances past expiresAt
        clock.addAndGet(125_000L)
        assertFalse("Expired grant must be rejected", auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-1").allowed)

        // Scenario 7: Partial approval (least privilege) — Reviewer requesting WRITE on READ tool gets capped to READ
        val reqPartial = McpPermissionRequest(
            requesterAgentId = "agent-03",
            assignedRole = "REVIEWER",
            taskId = "task-1",
            workspaceId = "ws-1",
            mcpServerId = "srv-neon",
            toolId = readTool.namespacedToolId,
            requestedAction = "list_projects",
            requestedPermissionLevel = McpPermissionLevel.WRITE,
            justification = "Inspect schema",
        )
        val (d7, grant7) = auth.evaluatePermissionRequest(reqPartial, server, readTool, activeTaskAgentIds = setOf("agent-03"))
        assertEquals(McpSupervisorDecisionKind.PARTIALLY_APPROVED, d7)
        assertEquals(McpPermissionLevel.READ, grant7?.grantedLevel)

        // Scenario 8: Unassigned agent requesting permission for a task is DENIED
        val reqUnassigned = reqWrite.copy(requestId = "req-unassigned", requesterAgentId = "agent-99")
        val (d8, grant8) = auth.evaluatePermissionRequest(reqUnassigned, server, writeTool, activeTaskAgentIds = setOf("agent-02"))
        assertEquals(McpSupervisorDecisionKind.DENIED, d8)
        assertNull(grant8)

        // Scenario 9: High-risk destructive operation CANNOT be approved by SPV -> MUST escalate to Owner
        val reqHighRisk = McpPermissionRequest(
            requesterAgentId = "agent-02",
            assignedRole = "DEVELOPER",
            taskId = "task-1",
            workspaceId = "ws-1",
            mcpServerId = "srv-neon",
            toolId = highRiskTool.namespacedToolId,
            requestedAction = "delete_branch",
            requestedPermissionLevel = McpPermissionLevel.WRITE,
            justification = "Delete obsolete branch",
        )
        val (d9, grant9) = auth.evaluatePermissionRequest(reqHighRisk, server, highRiskTool)
        assertEquals(McpSupervisorDecisionKind.ESCALATED_TO_OWNER, d9)
        assertNull(grant9)
        assertFalse(auth.authorizeExecution("agent-02", server, highRiskTool, true, "ws-1", "task-1").allowed)

        // Scenario 10: Owner explicitly approves escalated high-risk request -> agent can execute!
        val ownerGrant = auth.ownerApproveRequest(reqHighRisk.requestId)
        assertNotNull(ownerGrant)
        assertEquals("owner", ownerGrant?.grantedBy)
        assertTrue(auth.authorizeExecution("agent-02", server, highRiskTool, true, "ws-1", "task-1").allowed)

        // Scenario 11: Manual revocation immediately blocks execution
        assertTrue(auth.revokeGrant(ownerGrant!!.grantId, "Owner revoked manually", "owner"))
        assertFalse(auth.authorizeExecution("agent-02", server, highRiskTool, true, "ws-1", "task-1").allowed)

        // Scenario 12: Non-SPV agent attempting to approve/re-delegate is rejected
        val (d12, grant12) = auth.evaluatePermissionRequest(
            reqWrite.copy(requestId = "req-redelegate", requesterAgentId = "agent-03"),
            server,
            writeTool,
            supervisorId = "agent-02",
        )
        assertEquals(McpSupervisorDecisionKind.DENIED, d12)
        assertNull(grant12)

        // Scenario 13: SPV cannot approve its own privilege escalation -> escalated to Owner
        val reqSelfEscalate = reqWrite.copy(requestId = "req-self", requesterAgentId = "agent-01")
        val (d13, grant13) = auth.evaluatePermissionRequest(reqSelfEscalate, server, writeTool, supervisorId = "agent-01")
        assertEquals(McpSupervisorDecisionKind.ESCALATED_TO_OWNER, d13)
        assertNull(grant13)

        // Scenario 14: Owner DENY policy on tool overrides everything
        val (d14, _) = auth.evaluatePermissionRequest(
            reqWrite.copy(requestId = "req-denied-tool", toolId = deniedTool.namespacedToolId),
            server,
            deniedTool,
        )
        assertEquals(McpSupervisorDecisionKind.DENIED, d14)
        assertFalse(auth.authorizeExecution("agent-02", server, deniedTool, true, "ws-1", "task-1").allowed)

        // Scenario 15: SPV unavailable -> fail closed & escalate to Owner
        spvAvailable.set(false)
        val (d15, grant15) = auth.evaluatePermissionRequest(reqWrite.copy(requestId = "req-spv-down"), server, writeTool)
        assertEquals(McpSupervisorDecisionKind.ESCALATED_TO_OWNER, d15)
        assertNull(grant15)
        spvAvailable.set(true)

        // Scenario 16: Owner disables dynamic delegation (maxOwnerLevel = DENY)
        maxOwnerLevel = McpPermissionLevel.DENY
        val (d16, grant16) = auth.evaluatePermissionRequest(reqWrite.copy(requestId = "req-owner-disabled"), server, writeTool)
        assertEquals(McpSupervisorDecisionKind.DENIED, d16)
        assertNull(grant16)
        maxOwnerLevel = McpPermissionLevel.EXECUTE

        // Scenario 17: Task completion revokes all grants for that task
        val (_, taskGrant) = auth.evaluatePermissionRequest(reqWrite.copy(requestId = "req-task-revoke"), server, writeTool)
        assertNotNull(taskGrant)
        assertTrue(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-1").allowed)
        val revokedCount = auth.revokeGrantsForTask("task-1", "Task completed")
        assertTrue(revokedCount >= 1)
        assertFalse(auth.authorizeExecution("agent-02", server, writeTool, true, "ws-1", "task-1").allowed)

        // Scenario 18: Immutable audit trail recorded every decision & revocation
        val audit = auth.listAuditLog()
        assertTrue("Audit log must contain all decisions & revocations", audit.size >= 10)
        assertTrue(audit.any { it.decision == McpSupervisorDecisionKind.ESCALATED_TO_OWNER })
        assertTrue(audit.any { it.decisionReason.startsWith("REVOKED:") })
    }
}
