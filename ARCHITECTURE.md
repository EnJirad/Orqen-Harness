# ARCHITECTURE: ORQEN Multi-Agent AI Workspace

## 1. System Overview

**ORQEN** is an Android-native multi-agent AI development environment designed to run up to four collaborative AI agents inside a shared project workspace. The system integrates local CLI runtimes (PRoot / Linux guest), remote AI API providers (OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Custom endpoints), and GitHub-driven asynchronous coordination.

```
+-----------------------------------------------------------------------------------+
|                                ORQEN UI Layer                                     |
|  +--------------------+  +--------------------+  +-----------------------------+  |
|  | Team Dashboard     |  | Central Group Chat |  | Multi-Agent Detail & Cards  |  |
|  +--------------------+  +--------------------+  +-----------------------------+  |
|  | Provider Settings  |  | Quota Monitor      |  | Shared Workspace & Locks    |  |
|  +--------------------+  +--------------------+  +-----------------------------+  |
+-----------------------------------------------------------------------------------+
                                          |
+-----------------------------------------------------------------------------------+
|                        Agent Orchestrator & Team Core                             |
|  +-----------------------------------------------------------------------------+  |
|  | TeamManager (Max 4 Agents: Agent 01, 02, 03, 04)                            |  |
|  | - Agent Configs, Roles, Instructions, Permissions, Task Queue, Part Planner |  |
|  +-----------------------------------------------------------------------------+  |
|  | FileLockManager (Ownership, Expirations, Conflict Detection)                |  |
|  | EventBus (TASK_ASSIGNED, FILE_LOCKED, REVIEW_REQUESTED, BUG_FOUND, etc.)    |  |
|  | ContinuousSupervisor (Auto-resume, Checkpoints, Backoff, Timeout Watcher)  |  |
|  | GitCoordinator (Worktree Safety, Checkpoints, Part Merges, Rollback)        |  |
+-----------------------------------------------------------------------------------+
                                          |
+-----------------------------------------------------------------------------------+
|                     Multi-Provider AI Abstraction Layer                           |
|  +----------------------+  +---------------------+  +--------------------------+  |
|  | AntigravityOAuth     |  | AnthropicMessages   |  | OpenAICompatible         |  |
|  +----------------------+  +---------------------+  +--------------------------+  |
|  | GoogleGeminiDevApi   |  | CustomApiAdapter    |  | OpenRouterAdapter        |  |
|  +----------------------+  +---------------------+  +--------------------------+  |
|  | QuotaTracker (Official Quota vs. Estimated Usage vs. Local Usage)           |  |
|  | ApiKeyVault (Android KeyStore AES-GCM Encrypted Storage)                    |  |
+-----------------------------------------------------------------------------------+
                                          |
+-----------------------------------------------------------------------------------+
|                     Android Platform & Linux Guest Runtimes                       |
|  +--------------------+  +--------------------+  +-----------------------------+  |
|  | PRoot Linux Engine |  | Foreground Service |  | Shared Project Storage      |  |
|  | (agy, claude, dsh) |  | & WakeLock Manager |  | (/workspace/<projectSlug>)  |  |
+-----------------------------------------------------------------------------------+
```

---

## 2. Multi-Agent Domain Architecture (Max 4 Agents)

### 2.1 Agent Allocation & Slots
- **`AgentSlot`**: Fixed maximum of 4 concurrent agents:
  - `AGENT_1` ("Agent 01"): Team Leader / System Architect / QA Lead.
  - `AGENT_2` ("Agent 02"): Software Engineer / Full-Stack Developer.
  - `AGENT_3` ("Agent 03"): Specialist / Backend Developer / Researcher.
  - `AGENT_4` ("Agent 04"): Independent Tester / Security & Code Reviewer.
- Enforced constraint: Attempting to instantiate a 5th agent in an active team is rejected by the `TeamManager`.

### 2.2 Independent State Isolation
Each agent maintains:
- Distinct `AgentConfig`: Display name, role definition, system prompt, assigned provider ID, model ID, effort/temperature, allowed tools.
- Distinct `SessionState`: Conversation trajectory, active tool call stack, execution statistics.
- Shared Workspace Access: All 4 agents target the exact same filesystem directory (`/workspace/<projectSlug>`), governed by file locks.

---

## 3. Shared Workspace & Concurrency Control

### 3.1 File Ownership & Conflict Prevention (`FileLockManager`)
- **Lock Acquisition**: Before modifying any file, an agent must request an exclusive lock specifying `(agentId, taskId, filePath, reason)`.
- **Conflict Prevention**: If File A is locked by Agent 02, any edit attempt by Agent 03 is suspended or queued.
- **Lock Auto-Recovery**: Locks carry an expiration TTL (e.g., 10 minutes) with auto-renewal while the agent is alive. If an agent process crashes or terminates, stale locks are cleanly released.
- **Event Notification**: Broadcasts `FILE_LOCKED`, `FILE_UPDATED`, and `FILE_UNLOCKED` over the `TeamEventBus`.

### 3.2 Task Coordinator & Sequential Part Planning
- Projects are decomposed into sequential **Parts** (Part 01 to Part 10):
  - Part 01: Project Analysis & Requirements.
  - Part 02: Architecture & Interface Design.
  - Part 03: Feature Implementation (Parallel allowed where files do not conflict).
  - Part 04: Integration & API Contract Verification.
  - Part 05: Testing & QA Verification.
  - Part 06: Review, Bug Fixes & Git Checkpoint.
- Part transitions require acceptance verification by Agent 01 (Team Leader) or user approval.

---

## 4. Continuous Autonomous Execution

### 4.1 Root Cause Mitigation of 1-Hour Session Halts
1. The static CLI flag `--print-timeout 60m` in `AntigravityRuntimeBridge.kt` is replaced with supervised execution and dynamic extension.
2. The `ContinuousSupervisor` orchestrates an automated loop:
   - Evaluates whether the current task's acceptance criteria are satisfied.
   - Detects recoverable exit conditions (network timeout, rate limit, token exhaustion, CLI turn completion).
   - Generates persistent task checkpoints before and after every execution slice.
   - Applies exponential backoff with jitter on transient failures.
   - Re-launches the session with conversation resumption tokens without requiring manual user intervention.

### 4.2 Foreground Service & Android Resource Management
- `RuntimeExecutionService` maintains a persistent foreground notification displaying real-time agent activities and task progress.
- Partial wake locks (`PowerManager.PARTIAL_WAKE_LOCK`) ensure CPU persistence during long-running builds and model inference.
- Process recovery mechanisms reload active team state if Android terminates the app under extreme memory pressure.

---

## 5. Multi-Provider AI Architecture

### 5.1 Provider Adapter Interface (`ProviderAdapter`)
```kotlin
interface ProviderAdapter {
    val providerId: String
    val displayName: String
    val protocol: ProviderProtocol
    
    suspend fun testConnection(config: ProviderConfig, apiKey: String): ConnectionTestResult
    suspend fun discoverModels(config: ProviderConfig, apiKey: String): List<DiscoveredModel>
    suspend fun sendChatCompletion(request: ChatRequest, apiKey: String): Flow<ChatStreamChunk>
    suspend fun fetchOfficialQuota(config: ProviderConfig, apiKey: String): OfficialQuotaResult
}
```

### 5.2 Supported Provider Adapters
1. **Antigravity Adapter**: Google OAuth loopback flow, `agy` CLI bridge, Google Gemini 3.8/Pro models.
2. **Anthropic Adapter**: Messages API (`/v1/messages`), streaming SSE events, tool invocation format.
3. **OpenAI-Compatible Adapter**: Chat Completions API (`/v1/chat/completions`), streaming delta tokens.
4. **Google Gemini Developer API Adapter**: Direct API key `generateContent` and `streamGenerateContent`.
5. **Custom API Adapter**: Configurable base URL, protocol picker (OpenAI Completions, Anthropic Messages, Gemini), custom headers, and live connectivity verification.

---

## 6. Real Provider Quota Monitor

Quota is strictly stratified into three non-overlapping layers:
1. **Official Quota**: Extracted directly from official provider headers or API endpoints (e.g. `x-ratelimit-*`, OpenRouter `/credits`). If unsupported by the provider, the system displays "Unavailable (Provider does not expose public quota API)".
2. **Estimated Usage**: Token counter (input + output + cached) multiplied by known model pricing tables.
3. **Local Usage**: On-device telemetry (request count, active sessions, completed tasks, error count).

---

## 7. GitHub Communication Hub

- Repository root `.agent/` layout for asynchronous coordination:
  - `communication/inbox/`, `outbox/`, `history/`
  - `management/PROJECT_STATUS.md`, `TASK_QUEUE.md`, `DEVELOPMENT_PLAN.md`
  - `instructions/AGENT_01_LEADER.md` ... `AGENT_04_TESTER.md`
  - `reports/testing/`, `bugs/`, `completed/`, `reviews/`
  - `state/agent-01.json` ... `agent-04.json`
- Synchronized by the `GitCoordinator` to guarantee atomic checkpoints without committing noise or credentials.
