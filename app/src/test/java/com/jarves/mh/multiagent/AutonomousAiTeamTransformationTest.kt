package com.jarves.mh.multiagent

import com.jarves.mh.mcp.*
import com.jarves.mh.multiagent.collab.*
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.model.*
import com.jarves.mh.multiagent.persistence.*
import com.jarves.mh.multiagent.supervisor.*
import com.jarves.mh.provider.core.*
import com.jarves.mh.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutonomousAiTeamTransformationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ==================== 14.1: SINGLE AGENT CAPABILITY PARITY ====================

    @Test
    fun `agentic tool loop gives API developer full multi-step read-edit-test-fix-build parity`() = runBlocking {
        val workspace = tempFolder.newFolder("calc-app")
        File(workspace, "src/Calc.kt").apply {
            parentFile?.mkdirs()
            writeText("fun add(a: Int, b: Int) = a - b\n")
        }

        // Real workspace command runner simulating failing test -> fix -> passing test -> APK build
        val commandRunner = WorkspaceCommandRunner { ws, _, cmd, _ ->
            val now = System.currentTimeMillis()
            val code = File(ws, "src/Calc.kt").readText()
            when {
                cmd.contains("test") -> {
                    val passes = code.contains("a + b")
                    CommandExecutionResult(
                        command = cmd,
                        exitCode = if (passes) 0 else 1,
                        stdout = if (passes) "1 test passed\nTESTS_PASSED" else "AssertionError: expected 5 but was -1",
                        stderr = if (passes) "" else "CalcTest.testAdd FAILED",
                        startedAtMs = now,
                        completedAtMs = now + 25,
                    )
                }
                cmd.contains("assembleDebug") -> {
                    val apk = File(ws, "app/build/outputs/apk/debug/app-debug.apk").apply {
                        parentFile?.mkdirs()
                        writeBytes(ByteArray(2048) { 0x50 })
                    }
                    CommandExecutionResult(
                        command = cmd,
                        exitCode = 0,
                        stdout = "BUILD SUCCESSFUL: ${apk.name}",
                        stderr = "",
                        startedAtMs = now,
                        completedAtMs = now + 40,
                    )
                }
                else -> CommandExecutionResult(cmd, 0, "ok", "", now, now + 5)
            }
        }

        var turnIndex = 0
        val scriptedAdapter = object : ProviderAdapter {
            override val type: ProviderType = ProviderType.OPENAI_COMPATIBLE
            override val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT
            override suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<ModelInfo> = listOf(ModelInfo("gpt-4o"))
            override suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult = ConnectionTestResult(true, 10L, "ok")
            override suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuota = OfficialQuota(providerId = config.id)
            override suspend fun sendChatCompletion(
                config: ProviderConfig,
                apiKey: String,
                messages: List<AiMessage>,
                systemInstruction: String,
                modelId: String,
                temperature: Double,
                maxTokens: Int,
            ): Flow<AiStreamChunk> {
                turnIndex++
                val reply = when (turnIndex) {
                    1 -> """Inspecting workspace and running tests.
[TOOL_CALL name="read_file" args={"path":"src/Calc.kt"}]
[TOOL_CALL name="run_command" args={"command":"./gradlew testDebugUnitTest"}]"""
                    2 -> """Found bug in src/Calc.kt (a - b instead of a + b). Fixing and re-running tests + build.
[TOOL_CALL name="edit_file" args={"path":"src/Calc.kt","target":"a - b","replacement":"a + b"}]
[TOOL_CALL name="run_command" args={"command":"./gradlew testDebugUnitTest"}]
[TOOL_CALL name="run_command" args={"command":"./gradlew assembleDebug"}]
[TOOL_CALL name="verify_apk" args={"variant":"debug"}]"""
                    else -> "Fixed Calc.kt, verified tests pass (exit 0) and debug APK built cleanly. TESTS_PASSED"
                }
                return flowOf(AiStreamChunk(deltaText = reply, isFinished = true))
            }
        }

        val executor = WorkspaceToolExecutor(
            workspaceDir = workspace,
            projectSlug = "calc-app",
            commandRunner = commandRunner,
            allowShellCommands = true,
        )

        val result = AgenticToolLoop.run(
            adapter = scriptedAdapter,
            config = ProviderConfig(id = "p-openai", name = "OpenAI", type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "https://api.openai.com", selectedModelId = "gpt-4o"),
            apiKey = "test-key",
            modelId = "gpt-4o",
            systemInstruction = WorkspaceToolExecutor.toolPromptInstructions(),
            initialPrompt = "Fix add() in src/Calc.kt, verify tests pass, and build debug APK.",
            imageAttachments = emptyList(),
            temperature = 0.2,
            maxTokens = 2048,
            maxResponseBytes = 64_000,
            executor = executor,
            workspaceDir = workspace,
            extractFencedFiles = { _, _ -> emptyList() },
            maxTurns = 6,
        )

        assertEquals(3, result.turnsExecuted)
        assertTrue(File(workspace, "src/Calc.kt").readText().contains("a + b"))
        assertTrue(result.modifiedFiles.contains("src/Calc.kt"))
        assertEquals(2, result.testEvidence.size)
        assertEquals(TestExecutionOutcome.TESTS_FAILED, result.testEvidence[0].outcome)
        assertEquals(TestExecutionOutcome.TESTS_PASSED, result.testEvidence[1].outcome)
        assertTrue(result.buildEvidence.any { it.success && it.apkSizeBytes == 2048L })
    }

    @Test
    fun `workspace tool executor blocks path traversal and destructive commands`() = runBlocking {
        val workspace = tempFolder.newFolder("secure-ws")
        val executor = WorkspaceToolExecutor(
            workspaceDir = workspace,
            projectSlug = "secure-ws",
            commandRunner = WorkspaceCommandRunner { _, _, cmd, _ ->
                CommandExecutionResult(cmd, 0, "should not run", "", 0L, 1L)
            },
        )

        val (readTraversal, _, _) = executor.executeTool("read_file", """{"path":"../../etc/passwd"}""")
        assertFalse(readTraversal.success)
        assertTrue(readTraversal.stderr.contains("Path traversal", ignoreCase = true))

        val (writeTraversal, _, _) = executor.executeTool("write_file", """{"path":"../escape.txt","content":"bad"}""")
        assertFalse(writeTraversal.success)

        val (destructiveCmd, _, _) = executor.executeTool("run_command", """{"command":"rm -rf /"}""")
        assertFalse(destructiveCmd.success)
        assertTrue(destructiveCmd.stderr.contains("Blocked", ignoreCase = true))
    }

    // ==================== 14.2: AUTONOMOUS TEAM + PLAN PERSISTENCE + HELP + REVIEW + BUILD ====================

    @Test
    fun `autonomous SPV persists plan, coordinates inter-agent help, handles review rejection, and verifies real APK`() = runBlocking {
        val workspace = tempFolder.newFolder("android-calc-team")
        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()

        val project = ProjectEntity(
            projectId = "proj-calc",
            projectName = "Android Calculator",
            workspacePath = workspace.absolutePath,
        )
        taskStore.upsertProject(project)

        var reviewCalls = 0
        var helpRequested = false
        var helpAnswered = false

        val fakeRuntime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val agentId = request.agent.config.slot.id
                return when {
                    request.prompt.contains("Assistance requested by") -> {
                        helpAnswered = true
                        RuntimeExecutionResponse(
                            requestId = request.requestId,
                            providerId = "p1",
                            modelId = "m-arch",
                            startedAtMs = now,
                            completedAtMs = now + 10,
                            success = true,
                            outputText = "Use Material3 Scaffold and rememberSaveable for history list.",
                        )
                    }
                    agentId == "agent-01" -> {
                        RuntimeExecutionResponse(
                            requestId = request.requestId,
                            providerId = "p1",
                            modelId = "m-arch",
                            startedAtMs = now,
                            completedAtMs = now + 10,
                            success = true,
                            outputText = "Analyzed requirements and finalized .orqen/plan.md.",
                        )
                    }
                    agentId == "agent-02" -> {
                        if (!helpRequested && !request.prompt.contains("Assistance from")) {
                            helpRequested = true
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m-dev",
                                startedAtMs = now,
                                completedAtMs = now + 10,
                                success = true,
                                outputText = "[HELP to=agent-01] Should calculator history use rememberSaveable?",
                            )
                        } else {
                            File(workspace, "app/src/main/MainActivity.kt").apply {
                                parentFile?.mkdirs()
                                writeText("class MainActivity // with scientific functions & history")
                            }
                            if (reviewCalls >= 1) {
                                // After review rejection, Developer also builds real non-empty APK
                                File(workspace, "app/build/outputs/apk/debug/app-debug.apk").apply {
                                    parentFile?.mkdirs()
                                    writeBytes(ByteArray(4096) { 0x4B })
                                }
                            }
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m-dev",
                                startedAtMs = now,
                                completedAtMs = now + 15,
                                success = true,
                                outputText = "Implemented calculator UI, scientific functions, and history.",
                                modifiedFiles = listOf("app/src/main/MainActivity.kt"),
                            )
                        }
                    }
                    else -> {
                        reviewCalls++
                        if (reviewCalls == 1) {
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m-qa",
                                startedAtMs = now,
                                completedAtMs = now + 10,
                                success = true,
                                outputText = "REVIEW_REJECTED: Missing debug APK build artifact.",
                                testEvidence = listOf(
                                    com.jarves.mh.runtime.TestExecutionEvidence(
                                        command = "./gradlew test", startedAtMs = now, completedAtMs = now + 5, exitCode = 0,
                                        stdout = "1 tests passed", stderr = "", outcome = com.jarves.mh.runtime.TestExecutionOutcome.TESTS_PASSED, summary = "Passed",
                                    ),
                                ),
                            )
                        } else {
                            val testEv = TestExecutionEvidence(
                                command = "./gradlew testDebugUnitTest",
                                startedAtMs = now,
                                completedAtMs = now + 10,
                                exitCode = 0,
                                stdout = "All tests passed",
                                stderr = "",
                                outcome = TestExecutionOutcome.TESTS_PASSED,
                                summary = "Tests passed",
                            )
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m-qa",
                                startedAtMs = now,
                                completedAtMs = now + 15,
                                success = true,
                                outputText = "Verified calculator implementation, unit tests, and debug APK. REVIEW_APPROVED TESTS_PASSED",
                                testEvidence = listOf(testEv),
                            )
                        }
                    }
                }
            }

            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = fakeRuntime,
            delayFn = {},
        )

        val arch = engine.registry.register(
            AgentProfileEntity("agent-01", "SPV Architect", "Architect", "Lead", "p1", "m-arch", capabilities = setOf("lead", "architect", "plan")),
        )
        val dev = engine.registry.register(
            AgentProfileEntity("agent-02", "Developer", "Developer", "Android Dev", "p1", "m-dev", capabilities = setOf("code", "fix")),
        )
        val qa = engine.registry.register(
            AgentProfileEntity("agent-03", "QA Reviewer", "Reviewer", "QA & Review", "p1", "m-qa", capabilities = setOf("review", "qa", "test")),
        )
        val team = engine.teamManager.createTeam(
            projectId = project.projectId,
            teamName = "Android Team",
            memberAgentIds = listOf(arch.agentId, dev.agentId, qa.agentId),
            requireIndependentReview = true,
            maxConcurrentAgents = 2,
        )

        val finalTask = engine.submitAndSupervise(
            projectId = project.projectId,
            team = team,
            objective = "Create Android calculator with scientific functions, history, and debug APK",
            completionCriteria = TaskCompletionCriteria(
                requireBuildPass = true,
                requireTestsPass = true,
                requireReviewerApproval = true,
                requireDeliverableVerification = true,
            ),
        )

        assertNotNull(finalTask)
        assertEquals(PersistentTaskStatus.COMPLETED, finalTask!!.status)
        assertTrue("Expected inter-agent help request", helpRequested)
        assertTrue("Expected inter-agent help response from Architect", helpAnswered)
        assertTrue("Expected initial rejection + re-review approval (>=2), got $reviewCalls", reviewCalls >= 2)

        // Verify canonical `.orqen/plan/overview.md` + `.orqen/reports/` exist on disk and NO legacy `.orqen/plan.md` was created!
        val overviewFile = File(workspace, MultiPartPlanManager.OVERVIEW_REL_PATH)
        assertTrue("Expected canonical .orqen/plan/overview.md", overviewFile.isFile && overviewFile.length() > 50)
        assertFalse("Legacy .orqen/plan.md must NOT be automatically generated", File(workspace, ".orqen/plan.md").exists())
        val execReport = File(workspace, MultiPartPlanManager.EXECUTION_REPORT_REL_PATH)
        assertTrue("Expected .orqen/reports/execution_report.md", execReport.isFile && execReport.readText().contains("[x]"))
        assertTrue("Expected .orqen/reports/review_report.md", File(workspace, MultiPartPlanManager.REVIEW_REPORT_REL_PATH).isFile)
        assertTrue("Expected .orqen/reports/verification_report.md", File(workspace, MultiPartPlanManager.VERIFICATION_REPORT_REL_PATH).isFile)

        // Verify real APK exists and passes ResultVerifier.BUILD_VERIFICATION
        val buildVerify = ResultVerifier.verify(
            workspaceDir = workspace,
            outputSummary = "APK check",
            spec = VerificationSpec(mode = VerificationMode.BUILD_VERIFICATION, expectedApkVariant = "debug"),
        )
        assertTrue(buildVerify.passed)
    }

    // ==================== 14.4: TEAM-SPECIFIC MCP MANAGEMENT & SECURITY ====================

    @Test
    fun `team MCP server toggle isolates teams and preserves SPV security boundaries`() = runBlocking {
        val store = InMemoryMcpStore()
        val secrets = InMemoryMcpSecretStore()
        val fakeClient = object : McpTransportClient {
            override val serverId: String = "srv-gh"
            override val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP
            override var isConnected: Boolean = false
            override val isProcessAlive: Boolean = true
            override suspend fun connect(config: McpServerConfig, resolvedEnv: Map<String, String>, resolvedHeaders: Map<String, String>, onLog: (String) -> Unit): McpInitializeResult {
                isConnected = true
                return McpInitializeResult("2025-03-26", "GitHub MCP", "sess-1")
            }
            override suspend fun listTools(requestTimeoutMs: Long): List<McpRawToolInfo> = listOf(
                McpRawToolInfo("list_issues", "List repository issues", "{}"),
                McpRawToolInfo("delete_repository", "Delete repo permanently", "{}"),
            )
            override suspend fun callTool(rawToolName: String, argumentsJson: String, requestTimeoutMs: Long): McpToolCallResult =
                McpToolCallResult(rawToolName, serverId, true, "OK:$rawToolName")
            override suspend fun disconnect() { isConnected = false }
        }

        val manager = McpConnectionManager(
            store = store,
            secretStore = secrets,
            transportFactory = { fakeClient },
        )
        val srv = manager.saveServer(McpServerConfig(serverId = "srv-gh", name = "GitHub", transportType = McpTransportType.STREAMABLE_HTTP, remoteUrl = "https://mcp.example.com"))
        assertTrue(manager.connectServer(srv.serverId).isSuccess)

        val listToolId = manager.tools.value.first { it.rawToolName == "list_issues" }.namespacedToolId
        val deleteToolId = manager.tools.value.first { it.rawToolName == "delete_repository" }.namespacedToolId

        // Apply SPV least-privilege recommendations
        val agents = listOf(
            AgentState(AgentConfig(AgentSlot.AGENT_1, "SPV", AgentRole(AgentRoleType.TEAM_LEADER, systemInstructions = "", responsibilities = emptyList()))),
            AgentState(AgentConfig(AgentSlot.AGENT_2, "Dev", AgentRole(AgentRoleType.SOFTWARE_ENGINEER, systemInstructions = "", responsibilities = emptyList()))),
            AgentState(AgentConfig(AgentSlot.AGENT_3, "QA", AgentRole(AgentRoleType.TESTER, systemInstructions = "", responsibilities = emptyList()))),
        )
        val applied = manager.applyRecommendedTeamPermissions("team-A", agents)
        assertTrue(applied.isNotEmpty())

        // Team A disables GitHub MCP -> Dev is blocked with TEAM_SERVER_DISABLED
        manager.setTeamServerEnabled("team-A", srv.serverId, false)
        val blockedForTeamA = manager.executeToolForAgent("agent-02", "Developer", listToolId, "{}", workspaceId = "team-A")
        assertFalse(blockedForTeamA.success)
        assertEquals("TEAM_SERVER_DISABLED", blockedForTeamA.errorCode)

        // Team B still has GitHub MCP enabled -> Dev succeeds
        val allowedForTeamB = manager.executeToolForAgent("agent-02", "Developer", listToolId, "{}", workspaceId = "team-B")
        assertTrue(allowedForTeamB.success)

        // High-risk destructive tool STILL requires explicit Owner approval even after SPV configuration
        val highRiskBlocked = manager.executeToolForAgent("agent-02", "Developer", deleteToolId, "{}", workspaceId = "team-B")
        assertFalse(highRiskBlocked.success)
        assertEquals("OWNER_APPROVAL_REQUIRED", highRiskBlocked.errorCode)
    }

    // ==================== PHASE H: MODEL INTELLIGENCE & SPV MANAGEMENT COMMANDS ====================

    @Test
    fun `model intelligence selects role-appropriate models and parses natural-language SPV commands`() {
        val configs = listOf(
            ProviderConfig(
                id = "p-anthropic",
                name = "Anthropic",
                type = ProviderType.ANTHROPIC,
                baseUrl = "https://api.anthropic.com",
                availableModels = listOf("claude-3-7-sonnet", "claude-3-5-haiku"),
                selectedModelId = "claude-3-7-sonnet",
            ),
            ProviderConfig(
                id = "p-openai",
                name = "OpenAI",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.openai.com",
                availableModels = listOf("o3-mini", "gpt-4o-mini"),
                selectedModelId = "gpt-4o-mini",
            ),
        )
        val pool = TeamModelIntelligence.buildModelPool(
            providerConfigs = configs,
            hasApiKey = { true },
            isAntigravitySignedIn = false,
        )
        assertEquals(4, pool.size)

        val agents = listOf(
            AgentState(AgentConfig(AgentSlot.AGENT_1, "Architect", AgentRole(AgentRoleType.TEAM_LEADER, systemInstructions = "", responsibilities = emptyList()), providerId = "p-openai", modelId = "gpt-4o-mini")),
            AgentState(AgentConfig(AgentSlot.AGENT_2, "Developer", AgentRole(AgentRoleType.SOFTWARE_ENGINEER, systemInstructions = "", responsibilities = emptyList()), providerId = "p-openai", modelId = "gpt-4o-mini")),
        )

        val lowCostRecs = TeamModelIntelligence.recommendModelAssignments(agents, pool, TeamOptimizationGoal.LOW_COST)
        assertTrue(lowCostRecs.all { it.recommendedModelId.contains("mini") || it.recommendedModelId.contains("haiku") })

        val highQualityRecs = TeamModelIntelligence.recommendModelAssignments(agents, pool, TeamOptimizationGoal.HIGH_QUALITY)
        assertEquals("claude-3-7-sonnet", highQualityRecs.first { it.slot == AgentSlot.AGENT_2 }.recommendedModelId)

        assertEquals(
            SpvManagementCommandKind.CONFIGURE_ANDROID_TEAM,
            TeamModelIntelligence.parseManagementCommand("Configure the entire team for Android development")?.kind,
        )
        assertEquals(
            SpvManagementCommandKind.CHOOSE_BEST_MODELS,
            TeamModelIntelligence.parseManagementCommand("Choose the best models for each agent")?.kind,
        )
        assertEquals(
            SpvManagementCommandKind.OPTIMIZE_LOW_COST,
            TeamModelIntelligence.parseManagementCommand("Optimize the team for low API costs")?.kind,
        )
        assertEquals(
            SpvManagementCommandKind.CHECK_AGENT_TOOLS,
            TeamModelIntelligence.parseManagementCommand("Check whether every agent has the necessary tools")?.kind,
        )
        assertEquals(
            SpvManagementCommandKind.FIND_FAILING_AGENT,
            TeamModelIntelligence.parseManagementCommand("Find the agent that is causing repeated failures")?.kind,
        )
    }
}
