# ORQEN Sequential Development Plan (Part 01 to Part 10)

## Part 01 — Analyze Existing Project
- **Part ID**: `PART-01`
- **Goal**: Full codebase inspection, architecture mapping, and requirement verification.
- **Scope**: Source tree (`app/src/`), native carrier (`cpp/`), Gradle scripts, runtime bridges, and brand assets.
- **Tasks**:
  1. `TASK-01-01`: Codebase & Dependency Mapping
  2. `TASK-01-02`: Architecture & Core Runtime Diagnostic
  3. `TASK-01-03`: Security, Safety & Resource Analysis
  4. `TASK-01-04`: Comprehensive Baseline Report Synthesis
  5. `TASK-01-05`: Initialize `.agent/` GitHub Management Structure & Task Queue
- **Assigned Agents**: Agent 01, Agent 02, Agent 03, Agent 04
- **Dependencies**: None.
- **Acceptance Criteria**:
  - Full codebase map and dependency tree documented.
  - Root-cause diagnosis of 1-hour session limit and background process lifecycle.
  - Standardized `.agent/` directory deployed and verified.
  - 100% unit tests passing.
- **Deliverable**: `.agent/reports/PART_01_ANALYSIS_REPORT.md`

## Part 02 — Design Architecture
- **Part ID**: `PART-02`
- **Goal**: Define formal component contracts, data schemas, and event bus protocols.
- **Scope**: Data models, serialization formats, IPC interfaces, and FileLock state machines.
- **Assigned Agents**: Agent 01 (Lead), Agent 02 (Dev)
- **Dependencies**: `PART-01` approved.

## Part 03 — Frontend Development
- **Part ID**: `PART-03`
- **Goal**: Implement and refine Compose UI screens for multi-agent interaction.
- **Scope**: Dashboard screen, Agent cards, Central Group Chat, Private Chat, Provider config UI.
- **Assigned Agents**: Agent 02 (Dev), Agent 01 (Review)
- **Dependencies**: `PART-02`.

## Part 04 — Backend Development
- **Part ID**: `PART-04`
- **Goal**: Complete runtime adapters, quota fetchers, and continuous supervisor loop.
- **Scope**: Provider adapters, API clients, token calculators, and supervisor backoff.
- **Assigned Agents**: Agent 02 (Dev), Agent 03 (DevOps)
- **Dependencies**: `PART-02`.

## Part 05 — Integration
- **Part ID**: `PART-05`
- **Goal**: Wire ViewModel state flows to UI, runtime services, and event bus.
- **Scope**: `MainViewModel`, `PocketDevApp`, and service bindings.
- **Assigned Agents**: Agent 01 (Lead), Agent 02 (Dev)
- **Dependencies**: `PART-03`, `PART-04`.

## Part 06 — Testing
- **Part ID**: `PART-06`
- **Goal**: Rigorous unit, integration, and UI test suite execution.
- **Scope**: Offline test suites, Mock provider responses, Lock contention tests.
- **Assigned Agents**: Agent 04 (Reviewer), Agent 01 (QA)
- **Dependencies**: `PART-05`.

## Part 07 — Bug Fixing
- **Part ID**: `PART-07`
- **Goal**: Resolve all regressions, UI edge-cases, and exception handling gaps.
- **Scope**: Bug triage and targeted hotfixes.
- **Assigned Agents**: Agent 02 (Dev), Agent 04 (QA)
- **Dependencies**: `PART-06`.

## Part 08 — Final Review
- **Part ID**: `PART-08`
- **Goal**: Architectural sign-off, security vulnerability audit, and performance check.
- **Scope**: Complete codebase scan, memory profiling, and token safety.
- **Assigned Agents**: Agent 01 (Lead), Agent 04 (Reviewer)
- **Dependencies**: `PART-07`.

## Part 09 — Git Checkpoint
- **Part ID**: `PART-09`
- **Goal**: Commit, tag, package APK, and push release to GitHub remote.
- **Scope**: Git tags, changelogs, release notes, and binary packaging.
- **Assigned Agents**: Agent 03 (DevOps), Agent 01 (Lead)
- **Dependencies**: `PART-08`.

## Part 10 — Next Development Cycle
- **Part ID**: `PART-10`
- **Goal**: Archive completed milestone and outline future enhancements.
- **Scope**: Roadmaps (MCP server support, expanded agent rosters, plugin architecture).
- **Assigned Agents**: Agent 01 (Lead)
- **Dependencies**: `PART-09`.
