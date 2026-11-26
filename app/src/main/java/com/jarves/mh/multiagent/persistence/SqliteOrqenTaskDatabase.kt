package com.jarves.mh.multiagent.persistence

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production Android SQLite database for ORQEN's durable autonomous task engine.
 *
 * Features:
 * - Write-Ahead Logging (WAL) for crash durability and concurrent UI reads
 * - Foreign keys enabled (`PRAGMA foreign_keys = ON`)
 * - Versioned non-destructive migrations (`v1 -> v2`)
 * - Composite indexes on hot recovery and listing queries
 * - Transaction-safe mutations and `PRAGMA integrity_check`
 */
class OrqenTaskDatabaseHelper(
    context: Context,
    dbName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, dbName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createV1Schema(db)
        migrateV1ToV2(db)
        migrateV2ToV3(db)
        migrateV3ToV4(db)
        migrateV5ToV6(db)
        migrateV6ToV7(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            migrateV1ToV2(db)
        }
        if (oldVersion < 3) {
            migrateV2ToV3(db)
        }
        if (oldVersion < 4) {
            migrateV3ToV4(db)
        }
        if (oldVersion < 5) {
            // Non-destructive v4 -> v5: ensures owner_approvals table exists
            migrateV3ToV4(db)
        }
        if (oldVersion < 6) {
            // Non-destructive v5 -> v6: ensures agent_profiles.preferred_tools_json exists (P0 crash hotfix)
            migrateV5ToV6(db)
        }
        if (oldVersion < 7) {
            // Non-destructive v6 -> v7: MCP servers, tools, agent permissions, grants & audit log
            migrateV6ToV7(db)
        }
    }

    private fun createV1Schema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                project_id TEXT PRIMARY KEY,
                project_name TEXT NOT NULL,
                workspace_path TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                project_memory TEXT NOT NULL DEFAULT '',
                active_task_ids TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                conversation_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                context_summary TEXT NOT NULL DEFAULT '',
                linked_task_ids TEXT NOT NULL DEFAULT '[]',
                active_agent_id TEXT,
                archived INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversations_project ON conversations(project_id, archived, updated_at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                message_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                sender_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                is_user INTEGER NOT NULL,
                content TEXT NOT NULL,
                related_task_id TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conv_messages_conv_ts ON conversation_messages(conversation_id, timestamp)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                task_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                status TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                started_at INTEGER,
                completed_at INTEGER,
                owner_stop_requested INTEGER NOT NULL DEFAULT 0,
                pause_requested INTEGER NOT NULL DEFAULT 0,
                current_step_id TEXT,
                execution_mode TEXT NOT NULL DEFAULT 'LOCAL_ANDROID',
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                budget_json TEXT NOT NULL DEFAULT '{}',
                criteria_json TEXT NOT NULL DEFAULT '{}',
                total_input_tokens INTEGER NOT NULL DEFAULT 0,
                total_output_tokens INTEGER NOT NULL DEFAULT 0,
                actual_cost_usd REAL NOT NULL DEFAULT 0.0,
                task_memory TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_project_status ON tasks(project_id, status, updated_at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_steps (
                step_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                sequence_number INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                assigned_agent_id TEXT NOT NULL,
                status TEXT NOT NULL,
                dependencies_json TEXT NOT NULL DEFAULT '[]',
                input_context TEXT NOT NULL DEFAULT '',
                output_summary TEXT NOT NULL DEFAULT '',
                target_files_json TEXT NOT NULL DEFAULT '[]',
                requires_review INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                started_at INTEGER,
                completed_at INTEGER,
                checkpoint_id TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                idempotency_key TEXT NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_steps_task_seq ON task_steps(task_id, sequence_number)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_executions (
                execution_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                provider_id TEXT NOT NULL,
                model_id TEXT NOT NULL,
                request_id TEXT NOT NULL,
                execution_status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                error_type TEXT,
                error_message TEXT,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                estimated_cost_usd REAL NOT NULL DEFAULT 0.0,
                process_pid INTEGER,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_executions_task_step ON agent_executions(task_id, step_id, started_at)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS checkpoints (
                checkpoint_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                workspace_snapshot_json TEXT NOT NULL DEFAULT '{}',
                modified_files_json TEXT NOT NULL DEFAULT '[]',
                git_commit_sha TEXT,
                context_summary TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_checkpoints_task_ts ON checkpoints(task_id, timestamp)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_messages (
                message_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                from_agent_id TEXT NOT NULL,
                to_agent_id TEXT NOT NULL,
                message_type TEXT NOT NULL,
                content TEXT NOT NULL,
                related_step_id TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_messages_task_ts ON agent_messages(task_id, timestamp)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_events (
                event_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                event_payload TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_events_task_ts ON task_events(task_id, timestamp)")
    }

    private fun migrateV1ToV2(db: SQLiteDatabase) {
        // Non-destructive v1 -> v2 migration: add lease/heartbeat columns and idempotency ledger
        val existingCols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(tasks)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0) existingCols.add(c.getString(nameIdx))
            }
        }
        if ("execution_lease_owner" !in existingCols) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN execution_lease_owner TEXT")
        }
        if ("last_heartbeat_at" !in existingCols) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN last_heartbeat_at INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS idempotency_records (
                idempotency_key TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                result_summary TEXT NOT NULL,
                completed_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_idempotency_task ON idempotency_records(task_id, step_id)")
    }

    private fun migrateV2ToV3(db: SQLiteDatabase) {
        // Non-destructive v2 -> v3 migration:
        // 1. Many-to-many Task <-> Conversation cross-ref table (deleting conversation never deletes tasks)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_conversation_cross_ref (
                task_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                linked_at INTEGER NOT NULL,
                linked_by TEXT NOT NULL DEFAULT 'owner',
                relationship_type TEXT NOT NULL DEFAULT 'ATTACHED',
                PRIMARY KEY(task_id, conversation_id),
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE,
                FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cross_ref_conv ON task_conversation_cross_ref(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cross_ref_task ON task_conversation_cross_ref(task_id)")

        // 2. Idempotent legacy migration markers table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS migration_markers (
                marker_key TEXT PRIMARY KEY,
                details TEXT NOT NULL DEFAULT '',
                completed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // 3. Extend checkpoints with checkpoint_type, workspace_path, is_valid
        val chkCols = tableColumns(db, "checkpoints")
        if ("checkpoint_type" !in chkCols) {
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN checkpoint_type TEXT NOT NULL DEFAULT 'STEP_SNAPSHOT'")
        }
        if ("workspace_path" !in chkCols) {
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN workspace_path TEXT")
        }
        if ("is_valid" !in chkCols) {
            db.execSQL("ALTER TABLE checkpoints ADD COLUMN is_valid INTEGER NOT NULL DEFAULT 1")
        }

        // 4. Extend task_events with step_id & monotonic sequence_number
        val evtCols = tableColumns(db, "task_events")
        if ("step_id" !in evtCols) {
            db.execSQL("ALTER TABLE task_events ADD COLUMN step_id TEXT")
        }
        if ("sequence_number" !in evtCols) {
            db.execSQL("ALTER TABLE task_events ADD COLUMN sequence_number INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_task_events_seq ON task_events(task_id, sequence_number, timestamp)")
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (idx >= 0) cols.add(c.getString(idx))
            }
        }
        return cols
    }

    private fun migrateV3ToV4(db: SQLiteDatabase) {
        // Non-destructive v3 -> v4 migration: Multi-Agent Collaboration tables
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_profiles (
                agent_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                description TEXT,
                role TEXT NOT NULL,
                provider_id TEXT NOT NULL,
                model_id TEXT NOT NULL,
                runtime_type TEXT NOT NULL DEFAULT 'API',
                system_instructions TEXT,
                capabilities_json TEXT NOT NULL DEFAULT '[]',
                preferred_tools_json TEXT NOT NULL DEFAULT '[]',
                priority INTEGER NOT NULL DEFAULT 1,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_teams (
                team_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                team_name TEXT NOT NULL,
                strategy TEXT NOT NULL DEFAULT 'HIERARCHICAL',
                max_concurrent_agents INTEGER NOT NULL DEFAULT 2,
                max_delegation_depth INTEGER NOT NULL DEFAULT 3,
                require_independent_review INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_team_members (
                team_id TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                assigned_role TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 1,
                is_active INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY(team_id, agent_id),
                FOREIGN KEY(team_id) REFERENCES agent_teams(team_id) ON DELETE CASCADE,
                FOREIGN KEY(agent_id) REFERENCES agent_profiles(agent_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_delegations (
                delegation_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                parent_step_id TEXT NOT NULL,
                delegating_agent_id TEXT NOT NULL,
                assigned_agent_id TEXT NOT NULL,
                objective TEXT NOT NULL,
                required_capabilities_json TEXT NOT NULL DEFAULT '[]',
                depth INTEGER NOT NULL DEFAULT 1,
                correlation_id TEXT NOT NULL,
                status TEXT NOT NULL,
                result_summary TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delegations_task ON agent_delegations(task_id, status)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_reviews (
                review_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                reviewer_agent_id TEXT NOT NULL,
                implementing_agent_id TEXT NOT NULL,
                reviewed_execution_id TEXT NOT NULL,
                reviewed_step_id TEXT NOT NULL,
                outcome TEXT NOT NULL,
                findings_json TEXT NOT NULL DEFAULT '[]',
                severity TEXT NOT NULL DEFAULT 'INFO',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reviews_task_step ON agent_reviews(task_id, reviewed_step_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_decisions (
                decision_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                topic TEXT NOT NULL,
                proposals_json TEXT NOT NULL DEFAULT '[]',
                outcome TEXT NOT NULL,
                rationale TEXT NOT NULL,
                requires_owner_approval INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_file_locks (
                lock_id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                file_path TEXT NOT NULL,
                agent_id TEXT NOT NULL,
                execution_id TEXT NOT NULL,
                acquired_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                lock_status TEXT NOT NULL DEFAULT 'ACTIVE',
                FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_locks_proj_path ON agent_file_locks(project_id, file_path, lock_status)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS owner_approvals (
                approval_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                requested_by_agent_id TEXT NOT NULL,
                action_title TEXT NOT NULL,
                reason TEXT NOT NULL,
                impact TEXT NOT NULL,
                affected_resources_json TEXT NOT NULL DEFAULT '[]',
                risk_level TEXT NOT NULL DEFAULT 'HIGH',
                alternatives_json TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL DEFAULT 'PENDING',
                created_at INTEGER NOT NULL,
                resolved_at INTEGER,
                FOREIGN KEY(task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_owner_approvals_task ON owner_approvals(task_id, status)")
    }

    private fun migrateV5ToV6(db: SQLiteDatabase) {
        val profCols = tableColumns(db, "agent_profiles")
        if ("preferred_tools_json" !in profCols) {
            db.execSQL("ALTER TABLE agent_profiles ADD COLUMN preferred_tools_json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    private fun migrateV6ToV7(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_servers (
                server_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                transport_type TEXT NOT NULL,
                command TEXT NOT NULL DEFAULT '',
                args_json TEXT NOT NULL DEFAULT '[]',
                env_json TEXT NOT NULL DEFAULT '{}',
                secret_env_keys_json TEXT NOT NULL DEFAULT '[]',
                working_directory TEXT NOT NULL DEFAULT '',
                remote_url TEXT NOT NULL DEFAULT '',
                headers_json TEXT NOT NULL DEFAULT '{}',
                secret_header_keys_json TEXT NOT NULL DEFAULT '[]',
                auth_type TEXT NOT NULL DEFAULT 'NONE',
                auth_header_name TEXT NOT NULL DEFAULT 'Authorization',
                enabled INTEGER NOT NULL DEFAULT 1,
                scope TEXT NOT NULL DEFAULT 'GLOBAL',
                workspace_id TEXT,
                default_tool_policy TEXT NOT NULL DEFAULT 'ASK',
                connection_timeout_ms INTEGER NOT NULL DEFAULT 20000,
                request_timeout_ms INTEGER NOT NULL DEFAULT 45000,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_tools (
                namespaced_tool_id TEXT PRIMARY KEY,
                server_id TEXT NOT NULL,
                server_name TEXT NOT NULL,
                raw_tool_name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                input_schema_json TEXT NOT NULL DEFAULT '{}',
                required_permission_level TEXT NOT NULL DEFAULT 'READ',
                policy TEXT NOT NULL DEFAULT 'ASK',
                is_high_risk INTEGER NOT NULL DEFAULT 0,
                available INTEGER NOT NULL DEFAULT 1,
                discovered_at INTEGER NOT NULL,
                FOREIGN KEY(server_id) REFERENCES mcp_servers(server_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mcp_tools_server ON mcp_tools(server_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_agent_permissions (
                permission_id TEXT PRIMARY KEY,
                agent_id TEXT NOT NULL,
                server_id TEXT NOT NULL,
                max_level TEXT NOT NULL DEFAULT 'READ',
                allowed_tools_json TEXT NOT NULL DEFAULT '[]',
                denied_tools_json TEXT NOT NULL DEFAULT '[]',
                allow_dynamic_request INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_mcp_agent_perm_unique ON mcp_agent_permissions(agent_id, server_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_permission_requests (
                request_id TEXT PRIMARY KEY,
                requester_agent_id TEXT NOT NULL,
                assigned_role TEXT NOT NULL,
                task_id TEXT,
                workspace_id TEXT,
                mcp_server_id TEXT NOT NULL,
                tool_id TEXT NOT NULL,
                requested_action TEXT NOT NULL,
                requested_level TEXT NOT NULL,
                resource_scope TEXT NOT NULL DEFAULT '',
                justification TEXT NOT NULL DEFAULT '',
                requested_duration_ms INTEGER NOT NULL DEFAULT 1800000,
                status TEXT NOT NULL DEFAULT 'PENDING',
                decision_reason TEXT,
                created_at INTEGER NOT NULL,
                resolved_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_permission_grants (
                grant_id TEXT PRIMARY KEY,
                request_id TEXT,
                agent_id TEXT NOT NULL,
                granted_by TEXT NOT NULL,
                mcp_server_id TEXT NOT NULL,
                tool_id TEXT,
                granted_level TEXT NOT NULL,
                scope_type TEXT NOT NULL,
                task_id TEXT,
                workspace_id TEXT,
                resource_scope TEXT NOT NULL DEFAULT '',
                can_delegate_further INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                revoked_at INTEGER,
                revoke_reason TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mcp_grants_agent ON mcp_permission_grants(agent_id, mcp_server_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mcp_permission_audit (
                event_id TEXT PRIMARY KEY,
                request_id TEXT,
                requesting_agent_id TEXT NOT NULL,
                supervisor_id TEXT NOT NULL,
                tool_id TEXT NOT NULL,
                mcp_server_id TEXT NOT NULL,
                requested_permission TEXT NOT NULL,
                granted_permission TEXT NOT NULL,
                decision TEXT NOT NULL,
                decision_reason TEXT NOT NULL,
                resource_scope TEXT NOT NULL DEFAULT '',
                task_id TEXT,
                timestamp_ms INTEGER NOT NULL,
                expiration_ms INTEGER,
                revocation_timestamp_ms INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mcp_audit_ts ON mcp_permission_audit(timestamp_ms DESC)")
    }

    companion object {
        const val DATABASE_NAME = "orqen_autonomous_tasks.db"
        const val DATABASE_VERSION = 7
    }
}

class SqliteOrqenTaskStore(
    private val dbHelper: OrqenTaskDatabaseHelper,
) : OrqenTaskStore {

    constructor(context: Context) : this(OrqenTaskDatabaseHelper(context))

    override val schemaVersion: Int
        get() = OrqenTaskDatabaseHelper.DATABASE_VERSION

    override fun <T> runInTransaction(block: () -> T): T {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override fun validateIntegrity(): Boolean {
        val db = dbHelper.readableDatabase
        return db.rawQuery("PRAGMA integrity_check", null).use { c ->
            c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
        }
    }

    override fun upsertProject(project: ProjectEntity) {
        val cv = ContentValues().apply {
            put("project_id", project.projectId)
            put("project_name", project.projectName)
            put("workspace_path", project.workspacePath)
            put("created_at", project.createdAt)
            put("updated_at", project.updatedAt)
            put("project_memory", project.projectMemory)
            put("active_task_ids", encodeStringList(project.activeTaskIds))
        }
        dbHelper.writableDatabase.insertWithOnConflict("projects", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getProject(projectId: String): ProjectEntity? {
        return dbHelper.readableDatabase.query(
            "projects", null, "project_id = ?", arrayOf(projectId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toProjectEntity() else null }
    }

    override fun listProjects(): List<ProjectEntity> {
        val list = mutableListOf<ProjectEntity>()
        dbHelper.readableDatabase.query(
            "projects", null, null, null, null, null, "updated_at DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toProjectEntity()) }
        return list
    }

    override fun upsertConversation(conversation: ConversationEntity) {
        val cv = ContentValues().apply {
            put("conversation_id", conversation.conversationId)
            put("project_id", conversation.projectId)
            put("title", conversation.title)
            put("created_at", conversation.createdAt)
            put("updated_at", conversation.updatedAt)
            put("context_summary", conversation.contextSummary)
            put("linked_task_ids", encodeStringList(conversation.linkedTaskIds))
            put("active_agent_id", conversation.activeAgentId)
            put("archived", if (conversation.archived) 1 else 0)
        }
        dbHelper.writableDatabase.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getConversation(conversationId: String): ConversationEntity? {
        return dbHelper.readableDatabase.query(
            "conversations", null, "conversation_id = ?", arrayOf(conversationId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toConversationEntity() else null }
    }

    override fun listConversationsForProject(projectId: String, includeArchived: Boolean): List<ConversationEntity> {
        val sel = if (includeArchived) "project_id = ?" else "project_id = ? AND archived = 0"
        val list = mutableListOf<ConversationEntity>()
        dbHelper.readableDatabase.query(
            "conversations", null, sel, arrayOf(projectId), null, null, "updated_at DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toConversationEntity()) }
        return list
    }

    override fun deleteConversationOnly(conversationId: String) {
        dbHelper.writableDatabase.delete("conversations", "conversation_id = ?", arrayOf(conversationId))
    }

    override fun linkTaskToConversation(ref: TaskConversationCrossRef) {
        val cv = ContentValues().apply {
            put("task_id", ref.taskId)
            put("conversation_id", ref.conversationId)
            put("linked_at", ref.linkedAt)
            put("linked_by", ref.linkedBy)
            put("relationship_type", ref.relationshipType)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            "task_conversation_cross_ref", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun unlinkTaskFromConversation(conversationId: String, taskId: String) {
        dbHelper.writableDatabase.delete(
            "task_conversation_cross_ref",
            "conversation_id = ? AND task_id = ?",
            arrayOf(conversationId, taskId),
        )
    }

    override fun listTasksForConversation(conversationId: String): List<TaskEntity> {
        val list = mutableListOf<TaskEntity>()
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT t.* FROM tasks t
            INNER JOIN task_conversation_cross_ref r ON t.task_id = r.task_id
            WHERE r.conversation_id = ?
            ORDER BY t.updated_at DESC
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEntity()) }
        return list
    }

    override fun listConversationsForTask(taskId: String): List<ConversationEntity> {
        val list = mutableListOf<ConversationEntity>()
        dbHelper.readableDatabase.rawQuery(
            """
            SELECT c.* FROM conversations c
            INNER JOIN task_conversation_cross_ref r ON c.conversation_id = r.conversation_id
            WHERE r.task_id = ?
            ORDER BY c.updated_at DESC
            """.trimIndent(),
            arrayOf(taskId),
        ).use { c -> while (c.moveToNext()) list.add(c.toConversationEntity()) }
        return list
    }

    override fun insertConversationMessage(message: ConversationMessageEntity) {
        val cv = ContentValues().apply {
            put("message_id", message.messageId)
            put("conversation_id", message.conversationId)
            put("project_id", message.projectId)
            put("sender_id", message.senderId)
            put("sender_name", message.senderName)
            put("is_user", if (message.isUser) 1 else 0)
            put("content", message.content)
            put("related_task_id", message.relatedTaskId)
            put("timestamp", message.timestamp)
        }
        dbHelper.writableDatabase.insertWithOnConflict("conversation_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listConversationMessages(conversationId: String, limit: Int): List<ConversationMessageEntity> {
        val list = mutableListOf<ConversationMessageEntity>()
        dbHelper.readableDatabase.query(
            "conversation_messages", null, "conversation_id = ?", arrayOf(conversationId),
            null, null, "timestamp ASC", limit.toString(),
        ).use { c -> while (c.moveToNext()) list.add(c.toConversationMessageEntity()) }
        return list
    }

    override fun listConversationMessagesPaged(conversationId: String, offset: Int, limit: Int): List<ConversationMessageEntity> {
        val list = mutableListOf<ConversationMessageEntity>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM conversation_messages WHERE conversation_id = ? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(conversationId, limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString()),
        ).use { c -> while (c.moveToNext()) list.add(c.toConversationMessageEntity()) }
        return list
    }

    override fun upsertTask(task: TaskEntity) {
        val cv = ContentValues().apply {
            put("task_id", task.taskId)
            put("project_id", task.projectId)
            put("title", task.title)
            put("description", task.description)
            put("status", task.status.name)
            put("priority", task.priority)
            put("created_at", task.createdAt)
            put("updated_at", task.updatedAt)
            if (task.startedAt != null) put("started_at", task.startedAt) else putNull("started_at")
            if (task.completedAt != null) put("completed_at", task.completedAt) else putNull("completed_at")
            put("owner_stop_requested", if (task.ownerStopRequested) 1 else 0)
            put("pause_requested", if (task.pauseRequested) 1 else 0)
            put("current_step_id", task.currentStepId)
            put("execution_mode", task.executionMode.name)
            put("retry_count", task.retryCount)
            put("last_error", task.lastError)
            put("budget_json", encodeBudget(task.budgetConfiguration))
            put("criteria_json", encodeCriteria(task.completionCriteria))
            put("total_input_tokens", task.totalInputTokens)
            put("total_output_tokens", task.totalOutputTokens)
            put("actual_cost_usd", task.actualCostUsd)
            put("task_memory", task.taskMemory)
            put("execution_lease_owner", task.executionLeaseOwner)
            put("last_heartbeat_at", task.lastHeartbeatAt)
        }
        dbHelper.writableDatabase.insertWithOnConflict("tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getTask(taskId: String): TaskEntity? {
        return dbHelper.readableDatabase.query(
            "tasks", null, "task_id = ?", arrayOf(taskId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toTaskEntity() else null }
    }

    override fun listTasksForProject(projectId: String): List<TaskEntity> {
        val list = mutableListOf<TaskEntity>()
        dbHelper.readableDatabase.query(
            "tasks", null, "project_id = ?", arrayOf(projectId), null, null, "updated_at DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEntity()) }
        return list
    }

    override fun listAllTasks(): List<TaskEntity> {
        val list = mutableListOf<TaskEntity>()
        dbHelper.readableDatabase.query(
            "tasks", null, null, null, null, null, "updated_at DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEntity()) }
        return list
    }

    override fun findStaleRunningTasks(staleThresholdMs: Long, nowMs: Long): List<TaskEntity> {
        val cutoff = nowMs - staleThresholdMs
        val list = mutableListOf<TaskEntity>()
        dbHelper.readableDatabase.query(
            "tasks",
            null,
            "status = ? AND owner_stop_requested = 0 AND last_heartbeat_at <= ?",
            arrayOf(PersistentTaskStatus.RUNNING.name, cutoff.toString()),
            null, null, "updated_at DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEntity()) }
        return list
    }

    override fun findRecoverableTasks(): List<TaskEntity> {
        val list = mutableListOf<TaskEntity>()
        dbHelper.readableDatabase.query(
            "tasks",
            null,
            "owner_stop_requested = 0 AND pause_requested = 0 AND status IN (?, ?)",
            arrayOf(PersistentTaskStatus.RUNNING.name, PersistentTaskStatus.QUEUED.name),
            null, null, "priority DESC, updated_at ASC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEntity()) }
        return list
    }

    override fun upsertStep(step: TaskStepEntity) {
        val cv = ContentValues().apply {
            put("step_id", step.stepId)
            put("task_id", step.taskId)
            put("sequence_number", step.sequenceNumber)
            put("title", step.title)
            put("description", step.description)
            put("assigned_agent_id", step.assignedAgentId)
            put("status", step.status.name)
            put("dependencies_json", encodeStringList(step.dependencies))
            put("input_context", step.inputContext)
            put("output_summary", step.outputSummary)
            put("target_files_json", encodeStringList(step.targetFiles))
            put("requires_review", if (step.requiresReview) 1 else 0)
            put("created_at", step.createdAt)
            if (step.startedAt != null) put("started_at", step.startedAt) else putNull("started_at")
            if (step.completedAt != null) put("completed_at", step.completedAt) else putNull("completed_at")
            put("checkpoint_id", step.checkpointId)
            put("retry_count", step.retryCount)
            put("idempotency_key", step.idempotencyKey)
        }
        dbHelper.writableDatabase.insertWithOnConflict("task_steps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getStep(stepId: String): TaskStepEntity? {
        return dbHelper.readableDatabase.query(
            "task_steps", null, "step_id = ?", arrayOf(stepId), null, null, null,
        ).use { c -> if (c.moveToFirst()) c.toTaskStepEntity() else null }
    }

    override fun listStepsForTask(taskId: String): List<TaskStepEntity> {
        val list = mutableListOf<TaskStepEntity>()
        dbHelper.readableDatabase.query(
            "task_steps", null, "task_id = ?", arrayOf(taskId), null, null, "sequence_number ASC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskStepEntity()) }
        return list
    }

    override fun findStaleRunningSteps(taskId: String): List<TaskStepEntity> {
        val list = mutableListOf<TaskStepEntity>()
        dbHelper.readableDatabase.query(
            "task_steps", null, "task_id = ? AND status = ?",
            arrayOf(taskId, StepStatus.RUNNING.name), null, null, "sequence_number ASC",
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskStepEntity()) }
        return list
    }

    override fun insertExecution(execution: AgentExecutionEntity) {
        updateExecution(execution)
    }

    override fun updateExecution(execution: AgentExecutionEntity) {
        val cv = ContentValues().apply {
            put("execution_id", execution.executionId)
            put("task_id", execution.taskId)
            put("step_id", execution.stepId)
            put("agent_id", execution.agentId)
            put("provider_id", execution.providerId)
            put("model_id", execution.modelId)
            put("request_id", execution.requestId)
            put("execution_status", execution.executionStatus)
            put("started_at", execution.startedAt)
            if (execution.completedAt != null) put("completed_at", execution.completedAt) else putNull("completed_at")
            put("error_type", execution.errorType)
            put("error_message", execution.errorMessage)
            put("input_tokens", execution.inputTokens)
            put("output_tokens", execution.outputTokens)
            put("estimated_cost_usd", execution.estimatedCostUsd)
            if (execution.processPid != null) put("process_pid", execution.processPid) else putNull("process_pid")
        }
        dbHelper.writableDatabase.insertWithOnConflict("agent_executions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listExecutionsForTask(taskId: String): List<AgentExecutionEntity> {
        val list = mutableListOf<AgentExecutionEntity>()
        dbHelper.readableDatabase.query(
            "agent_executions", null, "task_id = ?", arrayOf(taskId), null, null, "started_at ASC",
        ).use { c -> while (c.moveToNext()) list.add(c.toAgentExecutionEntity()) }
        return list
    }

    override fun insertCheckpoint(checkpoint: CheckpointEntity) {
        val cv = ContentValues().apply {
            put("checkpoint_id", checkpoint.checkpointId)
            put("task_id", checkpoint.taskId)
            put("step_id", checkpoint.stepId)
            put("checkpoint_type", checkpoint.checkpointType)
            put("workspace_path", checkpoint.workspacePath)
            put("workspace_snapshot_json", encodeStringMap(checkpoint.workspaceSnapshot))
            put("modified_files_json", encodeStringList(checkpoint.modifiedFiles))
            put("git_commit_sha", checkpoint.gitCommitSha)
            put("context_summary", SecretSanitizer.sanitize(checkpoint.contextSummary))
            put("is_valid", if (checkpoint.isValid) 1 else 0)
            put("timestamp", checkpoint.timestamp)
        }
        dbHelper.writableDatabase.insertWithOnConflict("checkpoints", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getLatestCheckpoint(taskId: String): CheckpointEntity? {
        return dbHelper.readableDatabase.query(
            "checkpoints", null, "task_id = ? AND is_valid = 1", arrayOf(taskId), null, null, "timestamp DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.toCheckpointEntity() else null }
    }

    override fun listCheckpointsForTask(taskId: String): List<CheckpointEntity> {
        val list = mutableListOf<CheckpointEntity>()
        dbHelper.readableDatabase.query(
            "checkpoints", null, "task_id = ?", arrayOf(taskId), null, null, "timestamp DESC",
        ).use { c -> while (c.moveToNext()) list.add(c.toCheckpointEntity()) }
        return list
    }

    override fun markCheckpointInvalid(checkpointId: String) {
        val cv = ContentValues().apply { put("is_valid", 0) }
        dbHelper.writableDatabase.update("checkpoints", cv, "checkpoint_id = ?", arrayOf(checkpointId))
    }

    override fun insertAgentMessage(message: AgentMessageEntity) {
        val cv = ContentValues().apply {
            put("message_id", message.messageId)
            put("task_id", message.taskId)
            put("from_agent_id", message.fromAgentId)
            put("to_agent_id", message.toAgentId)
            put("message_type", message.messageType.name)
            put("content", SecretSanitizer.sanitize(message.content))
            put("related_step_id", message.relatedStepId)
            put("timestamp", message.timestamp)
        }
        dbHelper.writableDatabase.insertWithOnConflict("agent_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listAgentMessagesForTask(taskId: String, limit: Int): List<AgentMessageEntity> {
        val list = mutableListOf<AgentMessageEntity>()
        dbHelper.readableDatabase.query(
            "agent_messages", null, "task_id = ?", arrayOf(taskId), null, null, "timestamp ASC", limit.toString(),
        ).use { c -> while (c.moveToNext()) list.add(c.toAgentMessageEntity()) }
        return list
    }

    override fun listAgentMessagesPaged(taskId: String, offset: Int, limit: Int): List<AgentMessageEntity> {
        val list = mutableListOf<AgentMessageEntity>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM agent_messages WHERE task_id = ? ORDER BY timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(taskId, limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString()),
        ).use { c -> while (c.moveToNext()) list.add(c.toAgentMessageEntity()) }
        return list
    }

    override fun insertTaskEvent(event: TaskEventEntity) {
        val nextSeq = if (event.sequenceNumber > 0L) {
            event.sequenceNumber
        } else {
            dbHelper.readableDatabase.rawQuery(
                "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM task_events WHERE task_id = ?",
                arrayOf(event.taskId),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 1L }
        }
        val cv = ContentValues().apply {
            put("event_id", event.eventId)
            put("task_id", event.taskId)
            put("step_id", event.stepId)
            put("event_type", event.eventType)
            put("event_payload", SecretSanitizer.sanitize(event.eventPayload))
            put("sequence_number", nextSeq)
            put("timestamp", event.timestamp)
        }
        dbHelper.writableDatabase.insertWithOnConflict("task_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun listTaskEvents(taskId: String, limit: Int): List<TaskEventEntity> {
        val list = mutableListOf<TaskEventEntity>()
        dbHelper.readableDatabase.query(
            "task_events", null, "task_id = ?", arrayOf(taskId), null, null, "sequence_number ASC, timestamp ASC", limit.toString(),
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEventEntity()) }
        return list
    }

    override fun listTaskEventsPaged(taskId: String, offset: Int, limit: Int): List<TaskEventEntity> {
        val list = mutableListOf<TaskEventEntity>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM task_events WHERE task_id = ? ORDER BY sequence_number ASC, timestamp ASC LIMIT ? OFFSET ?",
            arrayOf(taskId, limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString()),
        ).use { c -> while (c.moveToNext()) list.add(c.toTaskEventEntity()) }
        return list
    }

    override fun isMigrationCompleted(markerKey: String): Boolean {
        return dbHelper.readableDatabase.query(
            "migration_markers", arrayOf("marker_key"), "marker_key = ?", arrayOf(markerKey), null, null, null,
        ).use { c -> c.moveToFirst() }
    }

    override fun markMigrationCompleted(markerKey: String, details: String) {
        val cv = ContentValues().apply {
            put("marker_key", markerKey)
            put("details", details)
            put("completed_at", System.currentTimeMillis())
        }
        dbHelper.writableDatabase.insertWithOnConflict("migration_markers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun recordIdempotency(record: IdempotencyRecordEntity) {
        val cv = ContentValues().apply {
            put("idempotency_key", record.idempotencyKey)
            put("task_id", record.taskId)
            put("step_id", record.stepId)
            put("operation_type", record.operationType)
            put("result_summary", record.resultSummary)
            put("completed_at", record.completedAt)
        }
        dbHelper.writableDatabase.insertWithOnConflict("idempotency_records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun findIdempotency(idempotencyKey: String): IdempotencyRecordEntity? {
        return dbHelper.readableDatabase.query(
            "idempotency_records", null, "idempotency_key = ?", arrayOf(idempotencyKey), null, null, null,
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            IdempotencyRecordEntity(
                idempotencyKey = c.getString(c.getColumnIndexOrThrow("idempotency_key")),
                taskId = c.getString(c.getColumnIndexOrThrow("task_id")),
                stepId = c.getString(c.getColumnIndexOrThrow("step_id")),
                operationType = c.getString(c.getColumnIndexOrThrow("operation_type")),
                resultSummary = c.getString(c.getColumnIndexOrThrow("result_summary")),
                completedAt = c.getLong(c.getColumnIndexOrThrow("completed_at")),
            )
        }
    }

    // --- Cursor & JSON helpers ---

    private fun Cursor.toProjectEntity() = ProjectEntity(
        projectId = getString(getColumnIndexOrThrow("project_id")),
        projectName = getString(getColumnIndexOrThrow("project_name")),
        workspacePath = getString(getColumnIndexOrThrow("workspace_path")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        projectMemory = getString(getColumnIndexOrThrow("project_memory")),
        activeTaskIds = decodeStringList(getString(getColumnIndexOrThrow("active_task_ids"))),
    )

    private fun Cursor.toConversationEntity() = ConversationEntity(
        conversationId = getString(getColumnIndexOrThrow("conversation_id")),
        projectId = getString(getColumnIndexOrThrow("project_id")),
        title = getString(getColumnIndexOrThrow("title")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        contextSummary = getString(getColumnIndexOrThrow("context_summary")),
        linkedTaskIds = decodeStringList(getString(getColumnIndexOrThrow("linked_task_ids"))),
        activeAgentId = getStringOrNull("active_agent_id"),
        archived = getInt(getColumnIndexOrThrow("archived")) == 1,
    )

    private fun Cursor.toConversationMessageEntity() = ConversationMessageEntity(
        messageId = getString(getColumnIndexOrThrow("message_id")),
        conversationId = getString(getColumnIndexOrThrow("conversation_id")),
        projectId = getString(getColumnIndexOrThrow("project_id")),
        senderId = getString(getColumnIndexOrThrow("sender_id")),
        senderName = getString(getColumnIndexOrThrow("sender_name")),
        isUser = getInt(getColumnIndexOrThrow("is_user")) == 1,
        content = getString(getColumnIndexOrThrow("content")),
        relatedTaskId = getStringOrNull("related_task_id"),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    private fun Cursor.toTaskEntity() = TaskEntity(
        taskId = getString(getColumnIndexOrThrow("task_id")),
        projectId = getString(getColumnIndexOrThrow("project_id")),
        title = getString(getColumnIndexOrThrow("title")),
        description = getString(getColumnIndexOrThrow("description")),
        status = runCatching { PersistentTaskStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }
            .getOrDefault(PersistentTaskStatus.QUEUED),
        priority = getInt(getColumnIndexOrThrow("priority")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        startedAt = getLongOrNull("started_at"),
        completedAt = getLongOrNull("completed_at"),
        ownerStopRequested = getInt(getColumnIndexOrThrow("owner_stop_requested")) == 1,
        pauseRequested = getInt(getColumnIndexOrThrow("pause_requested")) == 1,
        currentStepId = getStringOrNull("current_step_id"),
        executionMode = runCatching { ExecutionMode.valueOf(getString(getColumnIndexOrThrow("execution_mode"))) }
            .getOrDefault(ExecutionMode.LOCAL_ANDROID),
        retryCount = getInt(getColumnIndexOrThrow("retry_count")),
        lastError = getStringOrNull("last_error"),
        budgetConfiguration = decodeBudget(getString(getColumnIndexOrThrow("budget_json"))),
        completionCriteria = decodeCriteria(getString(getColumnIndexOrThrow("criteria_json"))),
        totalInputTokens = getLong(getColumnIndexOrThrow("total_input_tokens")),
        totalOutputTokens = getLong(getColumnIndexOrThrow("total_output_tokens")),
        actualCostUsd = getDouble(getColumnIndexOrThrow("actual_cost_usd")),
        taskMemory = getString(getColumnIndexOrThrow("task_memory")),
        executionLeaseOwner = getStringOrNull("execution_lease_owner"),
        lastHeartbeatAt = getLong(getColumnIndexOrThrow("last_heartbeat_at")),
    )

    private fun Cursor.toTaskStepEntity() = TaskStepEntity(
        stepId = getString(getColumnIndexOrThrow("step_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        sequenceNumber = getInt(getColumnIndexOrThrow("sequence_number")),
        title = getString(getColumnIndexOrThrow("title")),
        description = getString(getColumnIndexOrThrow("description")),
        assignedAgentId = getString(getColumnIndexOrThrow("assigned_agent_id")),
        status = runCatching { StepStatus.valueOf(getString(getColumnIndexOrThrow("status"))) }
            .getOrDefault(StepStatus.PENDING),
        dependencies = decodeStringList(getString(getColumnIndexOrThrow("dependencies_json"))),
        inputContext = getString(getColumnIndexOrThrow("input_context")),
        outputSummary = getString(getColumnIndexOrThrow("output_summary")),
        targetFiles = decodeStringList(getString(getColumnIndexOrThrow("target_files_json"))),
        requiresReview = getInt(getColumnIndexOrThrow("requires_review")) == 1,
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        startedAt = getLongOrNull("started_at"),
        completedAt = getLongOrNull("completed_at"),
        checkpointId = getStringOrNull("checkpoint_id"),
        retryCount = getInt(getColumnIndexOrThrow("retry_count")),
        idempotencyKey = getString(getColumnIndexOrThrow("idempotency_key")),
    )

    private fun Cursor.toAgentExecutionEntity() = AgentExecutionEntity(
        executionId = getString(getColumnIndexOrThrow("execution_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        stepId = getString(getColumnIndexOrThrow("step_id")),
        agentId = getString(getColumnIndexOrThrow("agent_id")),
        providerId = getString(getColumnIndexOrThrow("provider_id")),
        modelId = getString(getColumnIndexOrThrow("model_id")),
        requestId = getString(getColumnIndexOrThrow("request_id")),
        executionStatus = getString(getColumnIndexOrThrow("execution_status")),
        startedAt = getLong(getColumnIndexOrThrow("started_at")),
        completedAt = getLongOrNull("completed_at"),
        errorType = getStringOrNull("error_type"),
        errorMessage = getStringOrNull("error_message"),
        inputTokens = getLong(getColumnIndexOrThrow("input_tokens")),
        outputTokens = getLong(getColumnIndexOrThrow("output_tokens")),
        estimatedCostUsd = getDouble(getColumnIndexOrThrow("estimated_cost_usd")),
        processPid = getLongOrNull("process_pid"),
    )

    private fun Cursor.toCheckpointEntity() = CheckpointEntity(
        checkpointId = getString(getColumnIndexOrThrow("checkpoint_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        stepId = getString(getColumnIndexOrThrow("step_id")),
        checkpointType = getStringOrNull("checkpoint_type") ?: "STEP_SNAPSHOT",
        workspacePath = getStringOrNull("workspace_path"),
        workspaceSnapshot = decodeStringMap(getString(getColumnIndexOrThrow("workspace_snapshot_json"))),
        modifiedFiles = decodeStringList(getString(getColumnIndexOrThrow("modified_files_json"))),
        gitCommitSha = getStringOrNull("git_commit_sha"),
        contextSummary = getString(getColumnIndexOrThrow("context_summary")),
        isValid = getColumnIndex("is_valid").let { idx -> if (idx >= 0) getInt(idx) == 1 else true },
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    private fun Cursor.toAgentMessageEntity() = AgentMessageEntity(
        messageId = getString(getColumnIndexOrThrow("message_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        fromAgentId = getString(getColumnIndexOrThrow("from_agent_id")),
        toAgentId = getString(getColumnIndexOrThrow("to_agent_id")),
        messageType = runCatching { AgentMessageKind.valueOf(getString(getColumnIndexOrThrow("message_type"))) }
            .getOrDefault(AgentMessageKind.AGENT_TO_AGENT),
        content = getString(getColumnIndexOrThrow("content")),
        relatedStepId = getStringOrNull("related_step_id"),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    private fun Cursor.toTaskEventEntity() = TaskEventEntity(
        eventId = getString(getColumnIndexOrThrow("event_id")),
        taskId = getString(getColumnIndexOrThrow("task_id")),
        stepId = getStringOrNull("step_id"),
        eventType = getString(getColumnIndexOrThrow("event_type")),
        eventPayload = getString(getColumnIndexOrThrow("event_payload")),
        sequenceNumber = getLongOrNull("sequence_number") ?: 0L,
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
    )

    private fun Cursor.getStringOrNull(col: String): String? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun Cursor.getLongOrNull(col: String): Long? {
        val idx = getColumnIndex(col)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private fun encodeStringList(list: List<String>): String = JSONArray(list).toString()

    private fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringMap(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun decodeStringMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val result = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                result[k] = obj.optString(k)
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun encodeBudget(cfg: BudgetConfiguration): String = JSONObject()
        .put("maxTaskBudgetUsd", cfg.maxTaskBudgetUsd)
        .put("dailyBudgetUsd", cfg.dailyBudgetUsd)
        .put("autoPauseOnBudgetExceeded", cfg.autoPauseOnBudgetExceeded)
        .put("maxParallelAgents", cfg.maxParallelAgents)
        .put("maxParallelTasks", cfg.maxParallelTasks)
        .toString()

    private fun decodeBudget(json: String?): BudgetConfiguration {
        if (json.isNullOrBlank()) return BudgetConfiguration()
        return runCatching {
            val obj = JSONObject(json)
            BudgetConfiguration(
                maxTaskBudgetUsd = obj.optDouble("maxTaskBudgetUsd", 10.0),
                dailyBudgetUsd = obj.optDouble("dailyBudgetUsd", 25.0),
                autoPauseOnBudgetExceeded = obj.optBoolean("autoPauseOnBudgetExceeded", true),
                maxParallelAgents = obj.optInt("maxParallelAgents", 2),
                maxParallelTasks = obj.optInt("maxParallelTasks", 2),
            )
        }.getOrDefault(BudgetConfiguration())
    }

    private fun encodeCriteria(c: TaskCompletionCriteria): String = JSONObject()
        .put("requireBuildPass", c.requireBuildPass)
        .put("requireTestsPass", c.requireTestsPass)
        .put("requireReviewerApproval", c.requireReviewerApproval)
        .put("requireDeliverableVerification", c.requireDeliverableVerification)
        .put("acceptanceChecks", JSONArray(c.acceptanceChecks))
        .toString()

    private fun decodeCriteria(json: String?): TaskCompletionCriteria {
        if (json.isNullOrBlank()) return TaskCompletionCriteria()
        return runCatching {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("acceptanceChecks")
            val checks = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) add(s)
                    }
                }
            }
            TaskCompletionCriteria(
                requireBuildPass = obj.optBoolean("requireBuildPass", false),
                requireTestsPass = obj.optBoolean("requireTestsPass", true),
                requireReviewerApproval = obj.optBoolean("requireReviewerApproval", true),
                requireDeliverableVerification = obj.optBoolean("requireDeliverableVerification", true),
                acceptanceChecks = checks,
            )
        }.getOrDefault(TaskCompletionCriteria())
    }
}
