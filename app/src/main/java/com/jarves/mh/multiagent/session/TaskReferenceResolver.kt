package com.jarves.mh.multiagent.session

import com.jarves.mh.multiagent.persistence.OrqenTaskStore
import com.jarves.mh.multiagent.persistence.TaskEntity

enum class ChatTaskIntent {
    CREATE_NEW_TASK,
    CONTINUE_EXISTING_TASK,
    INSPECT_TASK,
    PAUSE_TASK,
    RESUME_TASK,
    STOP_TASK,
    RETRY_TASK,
    ATTACH_TASK,
    DETACH_TASK,
    GENERAL_PROJECT_CHAT,
}

data class TaskReferenceResolution(
    val intent: ChatTaskIntent,
    val exactTask: TaskEntity? = null,
    val candidateTasks: List<TaskEntity> = emptyList(),
    val isAmbiguous: Boolean = false,
    val explanation: String = "",
)

/**
 * Resolves natural-language and explicit Task ID references within a single project.
 *
 * Rules:
 * - Explicit Task IDs (e.g. `T-100`) match the exact task in the project.
 * - Status questions map to `INSPECT_TASK` without creating or starting a new task.
 * - Ambiguous matches return candidates for user selection instead of silently attaching the wrong task.
 * - Strict project scope: never searches tasks from other projects.
 */
class TaskReferenceResolver(
    private val store: OrqenTaskStore,
) {
    private val taskIdRegex = Regex("""\b(T-[A-Za-z0-9_-]{2,20})\b""", RegexOption.IGNORE_CASE)

    fun resolve(projectId: String, message: String): TaskReferenceResolution {
        val text = message.trim()
        val lower = text.lowercase()
        val projectTasks = store.listTasksForProject(projectId)

        val intent = detectIntent(lower)

        // 1. Explicit Task ID match (highest priority)
        val explicitId = taskIdRegex.find(text)?.groupValues?.get(1)
        if (explicitId != null) {
            val exact = projectTasks.firstOrNull { it.taskId.equals(explicitId, ignoreCase = true) }
            return if (exact != null) {
                TaskReferenceResolution(
                    intent = if (intent == ChatTaskIntent.GENERAL_PROJECT_CHAT) ChatTaskIntent.CONTINUE_EXISTING_TASK else intent,
                    exactTask = exact,
                    candidateTasks = listOf(exact),
                    explanation = "Matched explicit Task ID ${exact.taskId}.",
                )
            } else {
                TaskReferenceResolution(
                    intent = intent,
                    exactTask = null,
                    candidateTasks = emptyList(),
                    explanation = "No task with ID $explicitId found in project $projectId.",
                )
            }
        }

        // 2. Natural-language keyword discovery for continuation/control/inspection intents
        if (intent in setOf(
                ChatTaskIntent.CONTINUE_EXISTING_TASK,
                ChatTaskIntent.INSPECT_TASK,
                ChatTaskIntent.PAUSE_TASK,
                ChatTaskIntent.RESUME_TASK,
                ChatTaskIntent.STOP_TASK,
                ChatTaskIntent.RETRY_TASK,
                ChatTaskIntent.ATTACH_TASK,
            )
        ) {
            val tokens = lower.split(Regex("""[\s,.:;!?"]+"""))
                .filter { it.length >= 3 && it !in STOP_WORDS }
                .toSet()

            val scored = projectTasks.mapNotNull { t ->
                val haystack = "${t.title} ${t.description} ${t.taskMemory}".lowercase()
                val score = tokens.count { tok -> haystack.contains(tok) }
                if (score > 0) t to score else null
            }.sortedByDescending { it.second }

            return when {
                scored.size == 1 -> TaskReferenceResolution(
                    intent = intent,
                    exactTask = scored.first().first,
                    candidateTasks = listOf(scored.first().first),
                    explanation = "Matched task '${scored.first().first.title}' (${scored.first().first.taskId}).",
                )
                scored.size > 1 && scored[0].second > scored[1].second -> TaskReferenceResolution(
                    intent = intent,
                    exactTask = scored.first().first,
                    candidateTasks = scored.map { it.first },
                    explanation = "Best match: '${scored.first().first.title}' (${scored.first().first.taskId}).",
                )
                scored.size > 1 -> TaskReferenceResolution(
                    intent = intent,
                    exactTask = null,
                    candidateTasks = scored.map { it.first },
                    isAmbiguous = true,
                    explanation = "Multiple tasks match. Please select which task to continue.",
                )
                else -> TaskReferenceResolution(
                    intent = intent,
                    exactTask = null,
                    candidateTasks = emptyList(),
                    explanation = "No matching existing task found in this project.",
                )
            }
        }

        return TaskReferenceResolution(intent = intent, explanation = "Resolved as $intent.")
    }

    private fun detectIntent(lower: String): ChatTaskIntent = when {
        lower.contains("pause") || lower.contains("หยุดพัก") -> ChatTaskIntent.PAUSE_TASK
        lower.contains("stop") || lower.contains("cancel") || lower.contains("หยุดงาน") -> ChatTaskIntent.STOP_TASK
        lower.contains("retry") || lower.contains("ลองใหม่") -> ChatTaskIntent.RETRY_TASK
        lower.contains("detach") || lower.contains("ถอดงาน") -> ChatTaskIntent.DETACH_TASK
        lower.contains("attach") || lower.contains("แนบงาน") -> ChatTaskIntent.ATTACH_TASK
        lower.contains("status") || lower.contains("progress") || lower.contains("สถานะ") || lower.contains("คืบหน้า") -> ChatTaskIntent.INSPECT_TASK
        lower.contains("continue") || lower.contains("resume") || lower.contains("ทำต่อ") -> ChatTaskIntent.CONTINUE_EXISTING_TASK
        lower.startsWith("build ") || lower.startsWith("create ") || lower.startsWith("implement ") || lower.startsWith("สร้าง") -> ChatTaskIntent.CREATE_NEW_TASK
        else -> ChatTaskIntent.GENERAL_PROJECT_CHAT
    }

    companion object {
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "continue", "resume", "task",
            "status", "progress", "please", "started", "earlier", "our", "work", "existing",
        )
    }
}
