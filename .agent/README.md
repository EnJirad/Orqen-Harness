# ORQEN Multi-Agent Workspace Hub

This repository coordination hub manages asynchronous communication, task distribution, sequential part planning, and code review across up to four autonomous AI agents.

## Directory Structure
- `communication/`: Inter-agent message queues (inbox/outbox per agent) and communication history.
- `management/`: Project-level coordination files (`PROJECT_STATUS.md`, `CURRENT_TASK.md`, `TASK_QUEUE.md`, `DEVELOPMENT_PLAN.md`).
- `instructions/`: Specialized system prompts and operational directives per agent role.
- `reports/`: Comprehensive reports covering audits, completed milestones, bug disclosures, and code reviews.
- `testing/`: Test plans, test cases, and verification logs.
- `state/`: Real-time serialized JSON state for each agent slot (Agent 01 - 04).
