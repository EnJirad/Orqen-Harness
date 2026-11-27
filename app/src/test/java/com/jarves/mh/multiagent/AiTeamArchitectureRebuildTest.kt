package com.jarves.mh.multiagent

import com.jarves.mh.data.ChatDraftStore
import com.jarves.mh.data.DraftChatMode
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectType
import com.jarves.mh.model.WorkspaceKind
import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.collab.AgentCommunicationManager
import com.jarves.mh.multiagent.collab.AgentProfileEntity
import com.jarves.mh.multiagent.collab.AgentTeamEntity
import com.jarves.mh.multiagent.collab.AgentTeamMemberEntity
import com.jarves.mh.multiagent.collab.InMemoryCollaborationStore
import com.jarves.mh.multiagent.collab.SpvConversationRouter
import com.jarves.mh.multiagent.collab.SpvInteractionMode
import com.jarves.mh.multiagent.collab.SpvModePolicy
import com.jarves.mh.runtime.ExecutionAgentRole
import com.jarves.mh.multiagent.engine.AIExecutionRuntime
import com.jarves.mh.multiagent.engine.CheckpointManager
import com.jarves.mh.multiagent.engine.MultiPartPlanManager
import com.jarves.mh.multiagent.engine.ResultVerifier
import com.jarves.mh.multiagent.engine.RuntimeCapabilities
import com.jarves.mh.multiagent.engine.RuntimeExecutionRequest
import com.jarves.mh.multiagent.engine.RuntimeExecutionResponse
import com.jarves.mh.multiagent.engine.VerificationMode
import com.jarves.mh.multiagent.engine.VerificationSpec
import com.jarves.mh.multiagent.locks.FileLockManager
import com.jarves.mh.multiagent.model.AgentConfig
import com.jarves.mh.multiagent.model.AgentRole as UiAgentRole
import com.jarves.mh.multiagent.model.AgentRoleType
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.multiagent.model.AgentState
import com.jarves.mh.multiagent.model.LockAcquisitionResult
import com.jarves.mh.multiagent.persistence.AgentMessageKind
import com.jarves.mh.multiagent.persistence.InMemoryOrqenTaskStore
import com.jarves.mh.multiagent.persistence.PersistentTaskStatus
import com.jarves.mh.multiagent.persistence.ProjectEntity
import com.jarves.mh.multiagent.persistence.TaskCompletionCriteria
import com.jarves.mh.multiagent.recovery.ExecutionLivenessMonitor
import com.jarves.mh.multiagent.supervisor.AutonomousSupervisorEngine
import com.jarves.mh.runtime.AgentPermissionGuard
import com.jarves.mh.runtime.AgentSessionManager
import com.jarves.mh.runtime.AgenticToolLoop
import com.jarves.mh.runtime.CommandExecutionResult
import com.jarves.mh.runtime.TestExecutionEvidence
import com.jarves.mh.runtime.TestExecutionOutcome
import com.jarves.mh.runtime.WorkspaceCommandRunner
import com.jarves.mh.runtime.WorkspaceToolExecutor
import com.jarves.mh.runtime.WorkspaceZipExporter
import com.jarves.mh.ui.ProjectIconCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class AiTeamArchitectureRebuildTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testEnabledAgents(): List<AgentState> = listOf(
        AgentState(
            AgentConfig(
                slot = AgentSlot.AGENT_1,
                name = "SPV",
                role = UiAgentRole(AgentRoleType.TEAM_LEADER, "Supervisor", "Supervise team", listOf("lead")),
                isEnabled = true,
            ),
        ),
        AgentState(
            AgentConfig(
                slot = AgentSlot.AGENT_2,
                name = "Developer",
                role = UiAgentRole(AgentRoleType.SOFTWARE_ENGINEER, "Developer", "Write code", listOf("code")),
                isEnabled = true,
            ),
        ),
        AgentState(
            AgentConfig(
                slot = AgentSlot.AGENT_3,
                name = "QA Reviewer",
                role = UiAgentRole(AgentRoleType.TESTER, "Reviewer & QA", "Review and test", listOf("review", "qa")),
                isEnabled = true,
            ),
        ),
    )

    // ==================== 1. SHARED EXECUTION ENGINE & PARITY ====================

    @Test
    fun `workspace tool executor supports full CRUD, delete, MCP, file locks, and blocks traversal`() = runBlocking {
        val ws = tempFolder.newFolder("parity-ws")
        val lockMgr = FileLockManager()
        var mcpCalledWith: Pair<String, String>? = null

        val cmdRunner = WorkspaceCommandRunner { dir, _, command, _ ->
            val now = System.currentTimeMillis()
            CommandExecutionResult(
                command = command,
                exitCode = 0,
                stdout = "5 tests passed, 0 failed",
                stderr = "",
                startedAtMs = now,
                completedAtMs = now + 20,
            )
        }

        val devExecutor = WorkspaceToolExecutor(
            workspaceDir = ws,
            projectSlug = "parity-ws",
            commandRunner = cmdRunner,
            allowShellCommands = true,
            readOnly = false,
            agentId = "agent-custom-dev-5",
            fileLockManager = lockMgr,
            mcpToolInvoker = { toolId, argsJson ->
                mcpCalledWith = toolId to argsJson
                true to "mcp-ok:$toolId"
            },
        )

        // Write, read, edit, search, list, run test command, MCP call, delete
        val (wEv, _, _) = devExecutor.executeTool("write_file", """{"path":"src/App.kt","content":"fun main() { println(\"v1\") }"}""")
        assertTrue(wEv.success)
        assertTrue(File(ws, "src/App.kt").isFile)

        // Another dynamic agent (>4 slots) cannot overwrite locked file
        val otherDevExecutor = WorkspaceToolExecutor(
            workspaceDir = ws,
            projectSlug = "parity-ws",
            agentId = "agent-custom-dev-6",
            fileLockManager = lockMgr,
        )
        val (conflictEv, _, _) = otherDevExecutor.executeTool("write_file", """{"path":"src/App.kt","content":"overwrite"}""")
        assertFalse("Dynamic agent lock conflict must block concurrent write", conflictEv.success)
        assertTrue(conflictEv.stderr.contains("lock conflict", ignoreCase = true))

        // Same agent can edit & read
        val (eEv, _, _) = devExecutor.executeTool("edit_file", """{"path":"src/App.kt","target":"v1","replacement":"v2"}""")
        assertTrue(eEv.success)
        val (rEv, _, _) = devExecutor.executeTool("read_file", """{"path":"src/App.kt"}""")
        assertTrue(rEv.success && rEv.stdout.contains("v2"))

        // Real test command produces structured TestExecutionEvidence with counts & workingDir
        val (cmdEv, testEv, _) = devExecutor.executeTool("run_command", """{"command":"./gradlew testOnlineDebugUnitTest"}""", taskId = "T1", stepId = "S1")
        assertTrue(cmdEv.success)
        assertNotNull(testEv)
        assertEquals(0, testEv!!.exitCode)
        assertEquals(5, testEv.passedCount)
        assertEquals(0, testEv.failedCount)
        assertEquals(ws.absolutePath, testEv.workingDir)

        // Authorized MCP tool executes inside the same tool broker
        val (mcpEv, _, _) = devExecutor.executeTool("mcp_call", """{"tool":"github:list_issues","arguments":{"state":"open"}}""")
        assertTrue(mcpEv.success)
        assertEquals("github:list_issues", mcpCalledWith?.first)

        // Delete file works and blocks workspace root or traversal
        val (delRootEv, _, _) = devExecutor.executeTool("delete_file", """{"path":".."}""")
        assertFalse(delRootEv.success)
        val (delEv, _, _) = devExecutor.executeTool("delete_file", """{"path":"src/App.kt"}""")
        assertTrue(delEv.success)
        assertFalse(File(ws, "src/App.kt").exists())
    }

    @Test
    fun `multiline JSON tool calls parse accurately and test command classifier rejects false positives`() {
        val multilineReply = """
            Let me write the file now.
            [TOOL_CALL name="write_file" args={
              "path": "src/Demo.kt",
              "content": "fun greet() {\n  println(\"hello\")\n}"
            }]
        """.trimIndent()

        val parsed = AgenticToolLoop.parseToolCalls(multilineReply)
        assertEquals(1, parsed.size)
        assertEquals("write_file", parsed[0].first)
        assertTrue(parsed[0].second.contains("src/Demo.kt"))

        assertTrue(WorkspaceToolExecutor.isTestCommand("./gradlew testDebugUnitTest"))
        assertTrue(WorkspaceToolExecutor.isTestCommand("pytest tests/"))
        assertTrue(WorkspaceToolExecutor.isTestCommand("npm test"))
        assertFalse("ls test/ is NOT a test runner", WorkspaceToolExecutor.isTestCommand("ls test/"))
        assertFalse("git checkout is NOT a test runner", WorkspaceToolExecutor.isTestCommand("git checkout main"))
    }

    @Test
    fun `agent sessions are strictly isolated per agent and permissions guard chat mode`() {
        val store = InMemoryOrqenTaskStore()
        val sessionMgr = AgentSessionManager(store)

        val spvSession = sessionMgr.getOrCreateSession("agent-01", "proj-1", "T1", providerId = "p1", modelId = "m1")
        val devSession = sessionMgr.getOrCreateSession("agent-02", "proj-1", "T1", providerId = "p2", modelId = "m2")
        sessionMgr.updateNativeConversationId(spvSession.sessionId, "conv-spv-secret")
        sessionMgr.updateNativeConversationId(devSession.sessionId, "conv-dev-secret")

        assertNotEquals(spvSession.sessionId, devSession.sessionId)
        assertEquals("conv-spv-secret", store.getAgentSession(spvSession.sessionId)?.nativeConversationId)
        assertEquals("conv-dev-secret", store.getAgentSession(devSession.sessionId)?.nativeConversationId)

        val chatPerms = AgentPermissionGuard.forChatMode()
        assertFalse(chatPerms.allowWorkspaceTools)
        assertFalse(chatPerms.allowShellCommands)
        assertFalse(chatPerms.allowMcpTools)
        assertFalse(chatPerms.allowTaskCreation)
        assertFalse(chatPerms.allowDelegation)
        assertTrue(chatPerms.readOnly)

        val reviewerPerms = AgentPermissionGuard.forTaskExecution(ExecutionAgentRole.REVIEWER)
        assertTrue(reviewerPerms.allowWorkspaceTools)
        assertTrue(reviewerPerms.readOnly)
        assertFalse(reviewerPerms.allowFileDeletion)
    }

    // ==================== 2. STRICT CHAT MODE VS TASK MODE ====================

    @Test
    fun `chat mode never triggers tasks or execution even on imperative implementation phrases`() {
        val agents = testEnabledAgents()
        val imperativePhrases = listOf(
            "Create an Android application right now",
            "Start building this",
            "Fix this bug immediately",
            "Add a login screen",
            "เริ่มสร้างเลย",
            "ลงมือทำเลย",
            "Why can't agents work independently?",
        )

        for (phrase in imperativePhrases) {
            val mode = SpvModePolicy.resolveMode(GroupMessageType.NORMAL, phrase)
            assertEquals("Chat Mode must stay CHAT_CONSULTATION for: $phrase", SpvInteractionMode.CHAT_CONSULTATION, mode)

            val route = SpvConversationRouter.routeOwnerMessage(phrase, GroupMessageType.NORMAL, agents)
            assertNotNull(route)
            assertFalse("Chat Mode must NEVER create a task for: $phrase", route!!.shouldCreateTask)
            assertEquals(AgentSlot.AGENT_1, route.ownerFacingAgent.config.slot)
        }

        // Explicit Task Mode ALWAYS creates a task
        val taskRoute = SpvConversationRouter.routeOwnerMessage(
            "Implement everything we agreed on in this conversation.",
            GroupMessageType.TASK_ASSIGNMENT,
            agents,
        )
        assertNotNull(taskRoute)
        assertTrue(taskRoute!!.shouldCreateTask)
        assertEquals(SpvInteractionMode.TASK_EXECUTION, taskRoute.interactionMode)
    }

    @Test
    fun `task mode synthesizes full conversation history and separates agreed decisions from rejected ideas`() {
        val history = listOf(
            GroupChatMessage(senderSlot = null, senderName = "Owner", text = "We will use SQLite for local persistence"),
            GroupChatMessage(senderSlot = AgentSlot.AGENT_1, senderName = "SPV", text = "Should we also use Firebase?"),
            GroupChatMessage(senderSlot = null, senderName = "Owner", text = "No, don't use Firebase. Use local SQLite instead of Firebase."),
            GroupChatMessage(senderSlot = null, senderName = "Owner", text = "Final decision: add a calm neutral theme with dark mode support."),
        )

        val brief = SpvModePolicy.synthesizeTaskBrief(
            conversationHistory = history,
            explicitInstruction = "Implement everything we agreed on in this conversation.",
            attachmentPaths = listOf("designs/mockup.png"),
        )

        assertEquals(4, brief.sourceMessageCount)
        assertTrue(brief.finalDecisions.any { it.contains("SQLite", ignoreCase = true) })
        assertTrue(brief.rejectedOrSupersededIdeas.any { it.contains("Firebase", ignoreCase = true) })
        assertTrue(brief.renderedBrief.contains("DO NOT IMPLEMENT"))
        assertTrue(brief.renderedBrief.contains("designs/mockup.png"))
    }

    // ==================== 3. END-TO-END MULTI-AGENT AUTONOMOUS EXECUTION ====================

    @Test
    fun `end to end task mode runs SPV plan, Dev implementation, Reviewer rejection, Dev fix, real QA test, and verified completion`() = runBlocking {
        val ws = tempFolder.newFolder("e2e-team-ws")
        File(ws, "src").mkdirs()
        File(ws, "src/Calculator.kt").writeText("fun add(a: Int, b: Int): Int = 0\n")

        val taskStore = InMemoryOrqenTaskStore()
        val collabStore = InMemoryCollaborationStore()
        val project = ProjectEntity(projectId = "proj-e2e", projectName = "CalcApp", workspacePath = ws.absolutePath)
        taskStore.upsertProject(project)

        val spv = AgentProfileEntity("agent-01", "SPV", role = "SUPERVISOR", providerId = "p1", modelId = "m1", capabilities = setOf("lead", "architect"))
        val dev = AgentProfileEntity("agent-02", "Developer", role = "DEVELOPER", providerId = "p1", modelId = "m1", capabilities = setOf("code", "dev"))
        val qa = AgentProfileEntity("agent-03", "QA Reviewer", role = "REVIEWER", providerId = "p1", modelId = "m1", capabilities = setOf("review", "qa", "test"))
        listOf(spv, dev, qa).forEach { collabStore.upsertProfile(it) }

        val team = AgentTeamEntity(teamId = "team-e2e", projectId = "proj-e2e", teamName = "Core Team")
        collabStore.upsertTeam(team)
        collabStore.upsertTeamMember(AgentTeamMemberEntity("team-e2e", "agent-01", "SUPERVISOR"))
        collabStore.upsertTeamMember(AgentTeamMemberEntity("team-e2e", "agent-02", "DEVELOPER"))
        collabStore.upsertTeamMember(AgentTeamMemberEntity("team-e2e", "agent-03", "REVIEWER"))

        var devCalls = 0
        var reviewCalls = 0
        val scriptedRuntime = object : AIExecutionRuntime {
            override suspend fun execute(
                request: RuntimeExecutionRequest,
                onProgress: suspend (String) -> Unit,
            ): RuntimeExecutionResponse {
                val now = System.currentTimeMillis()
                val agentId = request.agentId ?: request.agent.config.slot.id
                return when (agentId) {
                    "agent-02" -> {
                        devCalls++
                        // Verify Developer received the real Part specification content!
                        if (devCalls == 1) {
                            assertTrue("Developer must receive real Part spec", request.prompt.contains("part_1_"))
                            File(ws, "src/Calculator.kt").writeText("fun add(a: Int, b: Int): Int = a + b\n")
                        } else {
                            // Apply reviewer feedback fix
                            File(ws, "src/Calculator.kt").writeText("fun add(a: Int, b: Int): Int = Math.addExact(a, b)\n")
                        }
                        RuntimeExecutionResponse(
                            requestId = request.requestId,
                            providerId = "p1",
                            modelId = "m1",
                            startedAtMs = now,
                            completedAtMs = now + 10,
                            success = true,
                            outputText = "Implemented Calculator.add (round $devCalls)",
                            modifiedFiles = listOf("src/Calculator.kt"),
                        )
                    }
                    "agent-03" -> {
                        reviewCalls++
                        assertTrue("Reviewer must run read-only", request.readOnlyTools)
                        if (reviewCalls == 1) {
                            // First review rejects and requests overflow safety fix
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m1",
                                startedAtMs = now,
                                completedAtMs = now + 10,
                                success = true,
                                outputText = "REVIEW_REJECTED: Use Math.addExact to guard integer overflow",
                                testEvidence = listOf(
                                    TestExecutionEvidence(
                                        command = "./gradlew test",
                                        startedAtMs = now,
                                        completedAtMs = now + 5,
                                        exitCode = 0,
                                        stdout = "1 tests passed",
                                        stderr = "",
                                        outcome = TestExecutionOutcome.TESTS_PASSED,
                                        summary = "Passed",
                                        workingDir = ws.absolutePath,
                                        passedCount = 1,
                                        failedCount = 0,
                                    ),
                                ),
                            )
                        } else {
                            // Second review verifies fix + real passing test evidence
                            val code = File(ws, "src/Calculator.kt").readText()
                            assertTrue(code.contains("Math.addExact"))
                            RuntimeExecutionResponse(
                                requestId = request.requestId,
                                providerId = "p1",
                                modelId = "m1",
                                startedAtMs = now,
                                completedAtMs = now + 10,
                                success = true,
                                outputText = "REVIEW_APPROVED — verified Math.addExact and passing unit tests.",
                                testEvidence = listOf(
                                    TestExecutionEvidence(
                                        command = "./gradlew testOnlineDebugUnitTest",
                                        startedAtMs = now,
                                        completedAtMs = now + 8,
                                        exitCode = 0,
                                        stdout = "2 tests passed, 0 failed",
                                        stderr = "",
                                        outcome = TestExecutionOutcome.TESTS_PASSED,
                                        summary = "Tests passed (exit 0)",
                                        workingDir = ws.absolutePath,
                                        passedCount = 2,
                                        failedCount = 0,
                                    ),
                                ),
                            )
                        }
                    }
                    else -> RuntimeExecutionResponse(
                        requestId = request.requestId,
                        providerId = "p1",
                        modelId = "m1",
                        startedAtMs = now,
                        completedAtMs = now + 5,
                        success = true,
                        outputText = "SPV acknowledged",
                    )
                }
            }

            override fun cancel(requestId: String): Boolean = true
            override fun getProviderCapabilities(providerId: String) = RuntimeCapabilities(true, true, true, true)
        }

        val engine = AutonomousSupervisorEngine(
            taskStore = taskStore,
            collabStore = collabStore,
            runtime = scriptedRuntime,
            checkpointManager = CheckpointManager(taskStore),
            fileLockManager = FileLockManager(),
            livenessMonitor = ExecutionLivenessMonitor(taskStore),
            delayFn = {},
        )

        val result = engine.submitAndSupervise(
            projectId = "proj-e2e",
            team = team,
            objective = "Implement safe integer addition in Calculator.kt",
            completionCriteria = TaskCompletionCriteria(
                requireBuildPass = false,
                requireTestsPass = true,
                requireReviewerApproval = true,
                requireDeliverableVerification = true,
            ),
            requiredDeliverableFiles = listOf("src/Calculator.kt"),
        )

        assertNotNull(result)
        assertEquals("lastError=${result!!.lastError}", PersistentTaskStatus.COMPLETED, result.status)
        assertTrue("Expected at least 2 Developer executions (impl + fix), got $devCalls", devCalls >= 2)
        assertTrue("Expected at least 2 independent Reviewer executions (reject + approve), got $reviewCalls", reviewCalls >= 2)

        // Verify real .orqen/plan/ and .orqen/reports/ files exist on disk!
        val overview = File(ws, ".orqen/plan/overview.md")
        assertTrue(overview.isFile && overview.length() > 100)
        val partFiles = File(ws, ".orqen/plan").listFiles()?.filter { it.name.startsWith("part_1_") }.orEmpty()
        assertTrue(partFiles.isNotEmpty())
        val reportFile = File(ws, ".orqen/reports/part_1_report.md")
        assertTrue("Part 1 report must be written on disk", reportFile.isFile && reportFile.length() > 50)

        // Verify real verification evidence & CHANGE_REQUEST message were persisted to SQLite/store!
        val evidence = taskStore.listVerificationEvidenceForTask(result.taskId)
        assertTrue(evidence.any { it.evidenceType == "TEST" && it.exitCode == 0 })
        assertTrue(evidence.any { it.evidenceType == "REVIEW" && it.reviewVerdict == "REVIEW_APPROVED" })
        val messages = taskStore.listAgentMessagesForTask(result.taskId)
        assertTrue(messages.any { it.messageType == AgentMessageKind.CHANGE_REQUEST && it.toAgentId == "agent-02" })
    }

    @Test
    fun `prose TESTS_PASSED without real TestExecutionEvidence is rejected by ResultVerifier`() {
        val ws = tempFolder.newFolder("no-fake-test")
        val outcome = ResultVerifier.verify(
            workspaceDir = ws,
            outputSummary = "All good! TESTS_PASSED REVIEW_APPROVED",
            spec = VerificationSpec(
                mode = VerificationMode.TEST_VERIFICATION,
                testsPassed = true,
                testEvidence = emptyList(),
            ),
        )
        assertFalse("Prose TESTS_PASSED without real TestExecutionEvidence must FAIL", outcome.passed)
    }

    @Test
    fun `plan version replacement archives previous plan snapshot into history directory`() {
        val ws = tempFolder.newFolder("plan-history")
        val planV1 = MultiPartPlanManager.createPlan("p1", "T1", "Demo", "Build v1 feature", ws)
        assertTrue(MultiPartPlanManager.persistPlan(ws, planV1).valid)

        val planV2 = MultiPartPlanManager.createPlan("p1", "T2", "Demo", "Build v2 feature", ws).copy(version = 2)
        assertTrue(MultiPartPlanManager.persistPlan(ws, planV2).valid)

        val historyDirs = File(ws, ".orqen/plan/history").listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("Previous plan must be archived under .orqen/plan/history/", historyDirs.isNotEmpty())
        assertTrue(File(historyDirs.first(), "overview.md").isFile)
    }

    // ==================== 4. PERSISTENT DRAFTS (SOLO + TEAM) ====================

    @Test
    fun `shared draft store persists Solo and Team drafts to store across restart, preserves on send failure, clears on success`() {
        val store = InMemoryOrqenTaskStore()
        val legacyFile = File(tempFolder.newFolder("drafts"), "drafts.json")

        val draftStore1 = ChatDraftStore(legacyFile, taskStore = store)
        val soloKey = draftStore1.singleProjectKey("proj-solo", "chat-1")
        val teamKey = draftStore1.teamProjectKey("proj-team", "group", DraftChatMode.TEAM_CHAT)

        draftStore1.saveDraft(soloKey, "Unsent Solo draft", cursorStart = 5, cursorEnd = 10, attachments = listOf("a.png"))
        draftStore1.saveDraft(teamKey, "Unsent Team draft", cursorStart = 3, cursorEnd = 3)

        // Simulate app process restart by creating a brand new ChatDraftStore against the same store
        val draftStore2 = ChatDraftStore(legacyFile, taskStore = store)
        val restoredSolo = draftStore2.getDraft(soloKey)
        val restoredTeam = draftStore2.getDraft(teamKey)

        assertNotNull(restoredSolo)
        assertEquals("Unsent Solo draft", restoredSolo!!.text)
        assertEquals(5, restoredSolo.cursorStart)
        assertEquals(10, restoredSolo.cursorEnd)
        assertEquals(listOf("a.png"), restoredSolo.attachments)

        assertNotNull(restoredTeam)
        assertEquals("Unsent Team draft", restoredTeam!!.text)

        // Clearing Solo draft must never leak or clear Team draft
        draftStore2.clearDraft(soloKey)
        assertNull(draftStore2.getDraft(soloKey))
        assertEquals("Unsent Team draft", draftStore2.getDraftText(teamKey))
    }

    // ==================== 5. WORKSPACE ZIP & PROJECT METADATA ====================

    @Test
    fun `workspace zip exporter creates valid archive, excludes unsafe dirs, and blocks path traversal`() {
        val ws = tempFolder.newFolder("zip-ws")
        File(ws, "src").mkdirs()
        File(ws, "src/Main.kt").writeText("fun main() {}")
        File(ws, ".git").mkdirs()
        File(ws, ".git/config").writeText("secret")
        File(ws, "node_modules/pkg").mkdirs()
        File(ws, "node_modules/pkg/index.js").writeText("ignored")
        File(ws, "build/out").mkdirs()
        File(ws, "build/out/a.class").writeText("ignored")

        val zipFile = File(tempFolder.root, "out.zip")
        val res = WorkspaceZipExporter.exportToFile(ws, listOf(""), zipFile)
        assertTrue(res.success)
        assertEquals(1, res.entryCount)
        assertTrue(zipFile.isFile && zipFile.length() > 0L)

        ZipFile(zipFile).use { zf ->
            val names = zf.entries().toList().map { it.name }
            assertEquals(listOf("src/Main.kt"), names)
        }

        // Path traversal attempt must be rejected
        val traversalZip = File(tempFolder.root, "traversal.zip")
        val badRes = WorkspaceZipExporter.exportToFile(ws, listOf("../secret.txt"), traversalZip)
        assertFalse(badRes.success)
        assertFalse(traversalZip.exists())
    }

    @Test
    fun `project metadata distinguishes workspace kind, project type, and default icons`() {
        val androidProj = Project(
            name = "MyAndroidApp",
            description = "Android app",
            language = "Kotlin",
            workspaceKind = WorkspaceKind.APP_PROJECT,
            type = ProjectType.ANDROID,
        )
        val folderProj = Project(
            name = "NotesFolder",
            description = "Folder",
            language = "General",
            workspaceKind = WorkspaceKind.FOLDER_WORKSPACE,
            type = ProjectType.GENERAL,
        )
        assertEquals(WorkspaceKind.APP_PROJECT, androidProj.workspaceKind)
        assertEquals(WorkspaceKind.FOLDER_WORKSPACE, folderProj.workspaceKind)
        assertEquals("android", ProjectIconCatalog.defaultIconKeyFor(androidProj.type))
        assertEquals("folder", ProjectIconCatalog.defaultIconKeyFor(folderProj.type))
        assertNotEquals(
            ProjectIconCatalog.resolveIcon(androidProj),
            ProjectIconCatalog.resolveIcon(folderProj),
        )
    }
}
