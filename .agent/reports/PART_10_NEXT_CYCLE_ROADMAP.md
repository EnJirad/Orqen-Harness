# ORQEN Multi-Agent Workspace — Part 10 Next Development Cycle & Roadmap

**Current Baseline:** `v1.0.0-orqen-multiagent` (Max 4 Agents, Production-Ready)  
**Target Next Cycles:** `v1.1.0` & `v2.0.0`

---

## 1. Cycle v1.1.0 — Cloud Sync & Automated GitHub PR Gates
1. **Live GitHub Pull Request Creation & Review Comments**:
   - Allow Agent 04 (Reviewer) to post inline diff comments directly onto GitHub PRs and approve/request changes via GitHub REST API.
2. **Real-Time Streaming Token Meter**:
   - Display live tokens/sec throughput and real-time streaming response previews on each Agent Card in `TeamDashboardScreen`.
3. **Exportable Team Session Transcripts & Audit Bundles**:
   - Export `.agent/` state, group chat transcripts, and quota cost summaries as a single ZIP report.

## 2. Cycle v2.0.0 — Elastic Agent Scaling & Extensibility
1. **Elastic Agent Pool Beyond 4 Agents**:
   - Architectural contracts (`AgentSlot`, `IFileLockCoordinator`, `ProviderOrchestrator`) are designed to lift the 4-agent cap to configurable N-agent swarms on high-RAM devices or remote cloud workers.
2. **Plugin & Custom MCP Tool Marketplace**:
   - Allow users to attach per-agent MCP servers and custom shell/API tool definitions directly from `AgentDetailScreen`.
3. **Multimodal & Voice Command Coordinator**:
   - Voice-driven task delegation to specific named agents (e.g., *"Hey Nova, assign the login refactor to Bolt"*).
