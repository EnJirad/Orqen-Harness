package com.jarves.mh.multiagent

import com.jarves.mh.data.ChatDraftStore
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectType
import com.jarves.mh.multiagent.chat.GroupChatMessage
import com.jarves.mh.multiagent.chat.GroupMessageType
import com.jarves.mh.multiagent.collab.SpvInteractionMode
import com.jarves.mh.multiagent.collab.SpvModePolicy
import com.jarves.mh.multiagent.engine.*
import com.jarves.mh.multiagent.model.AgentSlot
import com.jarves.mh.ui.ProjectIconCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceChatAndMultiPartPlanTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ==================== 9.1: PROJECT IDENTITY & ICONS ====================

    @Test
    fun `project types and local icon catalog distinguish app types and custom icons`() {
        val types = ProjectType.entries
        assertTrue(types.containsAll(listOf(
            ProjectType.ANDROID,
            ProjectType.IOS,
            ProjectType.WEB,
            ProjectType.DESKTOP,
            ProjectType.BACKEND,
            ProjectType.GENERAL,
            ProjectType.CUSTOM,
        )))

        val androidProj = Project(name = "MyCalc", description = "", language = "Kotlin", type = ProjectType.ANDROID)
        val folderProj = Project(name = "Notes", description = "", language = "General", type = ProjectType.GENERAL)
        val customIconProj = folderProj.copy(iconKey = "rocket")

        assertEquals("android", ProjectIconCatalog.defaultIconKeyFor(ProjectType.ANDROID))
        assertEquals("folder", ProjectIconCatalog.defaultIconKeyFor(ProjectType.GENERAL))
        assertEquals("globe", ProjectIconCatalog.defaultIconKeyFor(ProjectType.WEB))
        assertEquals("server", ProjectIconCatalog.defaultIconKeyFor(ProjectType.BACKEND))
        assertNotEquals(ProjectIconCatalog.resolveIcon(androidProj), ProjectIconCatalog.resolveIcon(folderProj))
        assertNotEquals(ProjectIconCatalog.resolveIcon(folderProj), ProjectIconCatalog.resolveIcon(customIconProj))
    }

    // ==================== 9.2: PERSISTENT CHAT DRAFTS ====================

    @Test
    fun `chat drafts persist independently across projects, restarts, stale saves, and send failures`() {
        val draftFile = File(tempFolder.newFolder("drafts"), "orqen_chat_drafts.json")
        val store1 = ChatDraftStore(draftFile)

        val singleKey1 = store1.singleProjectKey("proj-1", "chat-a")
        val singleKey2 = store1.singleProjectKey("proj-2", "chat-b")
        val teamKey1 = store1.teamProjectKey("team-1")
        val teamKey2 = store1.teamProjectKey("team-2")

        val longMultiline = "Line 1: Build calculator\nLine 2: Scientific functions\nLine 3: History tab"
        val rev1 = store1.nextRevision()
        val rev2 = store1.nextRevision()

        assertTrue(store1.saveDraft(singleKey1, longMultiline, cursorPosition = 25, revision = rev2))
        // Stale async save with older revision must NOT overwrite newer draft!
        assertFalse(store1.saveDraft(singleKey1, "old stale text", revision = rev1))
        assertEquals(longMultiline, store1.getDraftText(singleKey1))

        store1.saveDraft(singleKey2, "Other solo project draft")
        store1.saveDraft(teamKey1, "Team 1 unsent architecture notes\nwith line breaks")
        store1.saveDraft(teamKey2, "Team 2 separate draft")

        // Simulate app process termination & restart by creating a brand-new ChatDraftStore on the same file
        val storeAfterRestart = ChatDraftStore(draftFile)
        assertEquals(longMultiline, storeAfterRestart.getDraftText(singleKey1))
        assertEquals(25, storeAfterRestart.getDraft(singleKey1)?.cursorPosition)
        assertEquals("Other solo project draft", storeAfterRestart.getDraftText(singleKey2))
        assertEquals("Team 1 unsent architecture notes\nwith line breaks", storeAfterRestart.getDraftText(teamKey1))
        assertEquals("Team 2 separate draft", storeAfterRestart.getDraftText(teamKey2))

        // Clearing submitted conversation draft must NOT affect any other conversation
        storeAfterRestart.clearDraft(teamKey1)
        assertEquals("", storeAfterRestart.getDraftText(teamKey1))
        assertEquals("Team 2 separate draft", storeAfterRestart.getDraftText(teamKey2))
        assertEquals(longMultiline, storeAfterRestart.getDraftText(singleKey1))
    }

    // ==================== 9.3: CHAT VS TASK MODE SEPARATION ====================

    @Test
    fun `chat mode stays strictly consultative while explicit task mode synthesizes discussion context`() {
        val consultationMessages = listOf(
            "I want to create a new Android application.",
            "What features should we add?",
            "Help me design the architecture.",
            "What do you think about this approach?",
            "Can we make this better?",
            "Let's refine the requirements.",
        )
        consultationMessages.forEach { msg ->
            assertEquals(
                "Expected CHAT_CONSULTATION for: $msg",
                SpvInteractionMode.CHAT_CONSULTATION,
                SpvModePolicy.resolveMode(GroupMessageType.NORMAL, msg),
            )
        }

        // STRICT MODE SEPARATION: Natural-language phrases in Chat Mode NEVER auto-switch to TASK_EXECUTION
        listOf(
            "Start building this.",
            "Proceed with implementation.",
            "Create the application now.",
            "Start the AI Team.",
            "เริ่มสร้างเลย",
        ).forEach { cmd ->
            assertEquals(
                "Expected CHAT_CONSULTATION (no keyword auto-switch) for: $cmd",
                SpvInteractionMode.CHAT_CONSULTATION,
                SpvModePolicy.resolveMode(GroupMessageType.NORMAL, cmd),
            )
        }

        // Explicit Task mode chip ALWAYS enters TASK_EXECUTION
        assertEquals(
            SpvInteractionMode.TASK_EXECUTION,
            SpvModePolicy.resolveMode(GroupMessageType.TASK_ASSIGNMENT, "Build calculator"),
        )

        // Discussion synthesis carries prior consultation context so Owner never repeats requirements
        val history = listOf(
            GroupChatMessage(senderSlot = null, senderName = "Owner", text = "I want an Android calculator with scientific mode and history."),
            GroupChatMessage(senderSlot = AgentSlot.AGENT_1, senderName = "SPV", text = "Recommended: Jetpack Compose + Room/DataStore history + unit tests."),
        )
        val synthesized = SpvModePolicy.buildObjectiveFromDiscussion(history, "Start building this.")
        assertTrue(synthesized.contains("scientific mode and history"))
        assertTrue(synthesized.contains("Final Agreed Requirements"))
    }

    // ==================== MULTI-PART PLANNING & SEQUENTIAL EXECUTION (10.1 - 10.14) ====================

    @Test
    fun `multi-part plan persists overview and part specs, enforces sequential gates, recovers on restart, and revises safely`() {
        val ws = tempFolder.newFolder("multipart-android-app")

        // 1 & 2. Generate & persist Multi-Part plan (overview.md + multiple part_N_*.md + state/*.json)
        val plan = MultiPartPlanManager.createPlan(
            projectId = "proj-mp",
            taskId = "T-MP-01",
            projectName = "Scientific Calculator",
            objective = "Create an Android scientific calculator application with modern Jetpack Compose UI, history database, unit tests, and debug APK",
            workspaceDir = ws,
            isAndroidProject = true,
        )
        assertTrue("Substantial request must produce multiple Parts", plan.parts.size >= 2)

        val validation = MultiPartPlanManager.persistPlan(ws, plan)
        assertTrue("Plan validation must pass: ${validation.errors}", validation.valid)
        assertTrue(File(ws, MultiPartPlanManager.OVERVIEW_REL_PATH).isFile)
        assertTrue(File(ws, MultiPartPlanManager.EXECUTION_STATE_REL_PATH).isFile)
        assertTrue(File(ws, MultiPartPlanManager.PARTS_STATE_REL_PATH).isFile)
        plan.parts.forEach { p ->
            val specFile = File(ws, p.specRelativePath)
            assertTrue("Spec file must physically exist: ${p.specRelativePath}", specFile.isFile && specFile.length() > 0L)
        }

        // 3. Plan validation catches missing spec files & circular dependencies
        val cyclicParts = listOf(
            plan.parts[0].copy(dependencies = listOf(plan.parts[1].partId)),
            plan.parts[1].copy(dependencies = listOf(plan.parts[0].partId)),
        )
        val cycleCheck = MultiPartPlanManager.verifyPlanIntegrity(ws, plan.copy(parts = cyclicParts))
        assertFalse(cycleCheck.valid)
        assertTrue(cycleCheck.errors.any { it.contains("Circular", ignoreCase = true) })

        // 4. Sequential execution & duplicate delegation prevention
        val firstEligible = MultiPartPlanManager.selectNextEligiblePart(plan)
        assertEquals("part-1", firstEligible?.partId)

        val executingPlan = MultiPartPlanManager.beginPartExecution(ws, plan, "part-1")
        assertNotNull(executingPlan)
        // Duplicate delegation of part-1 must be rejected!
        assertNull(MultiPartPlanManager.beginPartExecution(ws, executingPlan!!, "part-1"))
        // Part 2 CANNOT start while Part 1 is not completed!
        assertNull(MultiPartPlanManager.beginPartExecution(ws, executingPlan, "part-2"))

        // 5. Simulate app restart mid-Part 1 -> recoverAfterRestart resumes Part 1 cleanly!
        val recoveredAfterCrash = MultiPartPlanManager.recoverAfterRestart(ws)
        assertNotNull(recoveredAfterCrash)
        assertEquals(ExecutionPartStatus.RECOVERING, recoveredAfterCrash!!.parts[0].status)
        assertEquals("part-1", MultiPartPlanManager.selectNextEligiblePart(recoveredAfterCrash)?.partId)

        // 6. Part 1 fails tests -> generates report, stays FAILED, Part 2 remains locked (PENDING)
        val reExecPlan = MultiPartPlanManager.beginPartExecution(ws, recoveredAfterCrash, "part-1")!!
        val failedAdv = MultiPartPlanManager.recordPartVerificationAndReport(
            workspaceDir = ws,
            plan = reExecPlan,
            partId = "part-1",
            verification = PartVerificationInput(
                modifiedFiles = listOf("app/src/main/CalcEngine.kt"),
                reviewerApproved = false,
                reviewerNotes = "Missing division-by-zero handling",
                testExitCode = 1,
                testSummary = "CalcEngineTest failed",
            ),
        )
        assertFalse(failedAdv.passed)
        assertTrue(failedAdv.reportFile.isFile)
        assertEquals(ExecutionPartStatus.FAILED, failedAdv.updatedPart.status)
        assertEquals(ExecutionPartStatus.PENDING, failedAdv.updatedPlan.parts[1].status)
        assertNull("Part 2 must NOT unlock when Part 1 failed", MultiPartPlanManager.beginPartExecution(ws, failedAdv.updatedPlan, "part-2"))

        // 7. Part 1 retry passes review & tests -> Part 1 COMPLETED, Part 2 unlocks to READY!
        val retryReadyPlan = failedAdv.updatedPlan.copy(
            parts = failedAdv.updatedPlan.parts.map { if (it.partId == "part-1") it.copy(status = ExecutionPartStatus.RETRYING) else it },
        )
        val retryExec = MultiPartPlanManager.beginPartExecution(ws, retryReadyPlan, "part-1")!!
        val passedAdv = MultiPartPlanManager.recordPartVerificationAndReport(
            workspaceDir = ws,
            plan = retryExec,
            partId = "part-1",
            verification = PartVerificationInput(
                modifiedFiles = listOf("app/src/main/CalcEngine.kt"),
                reviewerApproved = true,
                reviewerNotes = "Clean implementation with division-by-zero guard.",
                testExitCode = 0,
                testSummary = "All 8 unit tests passed",
            ),
        )
        assertTrue(passedAdv.passed)
        assertEquals(ExecutionPartStatus.COMPLETED, passedAdv.updatedPlan.parts[0].status)
        assertEquals(ExecutionPartStatus.READY, passedAdv.updatedPlan.parts[1].status)
        assertEquals("part-2", MultiPartPlanManager.selectNextEligiblePart(passedAdv.updatedPlan)?.partId)

        // 8. Plan revision preserves completed Part 1 and increments version with reason!
        val revised = MultiPartPlanManager.revisePlanPreservingCompleted(
            workspaceDir = ws,
            existingPlan = passedAdv.updatedPlan,
            revisedPendingParts = passedAdv.updatedPlan.parts.drop(1),
            reason = "Added dark theme polish to Part 2",
        )
        assertEquals(2, revised.version)
        assertEquals(ExecutionPartStatus.COMPLETED, revised.parts[0].status)
        assertTrue(revised.revisionHistory.any { it.reason.contains("dark theme polish") })
    }

    @Test
    fun `legacy single-file orqen plan migrates cleanly without losing existing content`() {
        val ws = tempFolder.newFolder("legacy-plan-ws")
        val legacyFile = File(ws, MultiPartPlanManager.LEGACY_PLAN_REL_PATH).apply {
            parentFile?.mkdirs()
            writeText("# Legacy Plan\n- [ ] Build login screen\n- [ ] Add unit tests\n")
        }
        assertTrue(legacyFile.isFile)

        val migrated = MultiPartPlanManager.migrateLegacyPlanIfNeeded(ws, "proj-legacy", "T-LEG-1", "Legacy App")
        assertNotNull(migrated)
        assertEquals(1, migrated!!.parts.size)
        assertTrue(File(ws, MultiPartPlanManager.OVERVIEW_REL_PATH).isFile)
        assertTrue(File(ws, migrated.parts[0].specRelativePath).isFile)
        val specText = MultiPartPlanManager.readPartSpecification(ws, migrated.parts[0])
        assertTrue(specText.contains("Build login screen"))
    }
}
