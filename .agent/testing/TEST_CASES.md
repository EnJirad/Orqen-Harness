# Test Cases

- **TC-01: Max 4 Agent Slots**
  - Input: Attempt to instantiate `TeamGroup` with 5 agents.
  - Expected: `IllegalArgumentException` thrown; strict limit of 4 enforced.
- **TC-02: File Lock Acquisition & Conflict**
  - Input: Agent 02 acquires lock on file A; Agent 03 attempts acquisition.
  - Expected: Agent 03 rejected; `isLockedByOther` returns true.
- **TC-03: Quota Stratification**
  - Input: Check OfficialQuota vs EstimatedUsage vs LocalUsage.
  - Expected: Three distinct tiers; unavailable quota clearly flagged without fabricated estimates.
- **TC-04: Supervisor Stop & Resume**
  - Input: Stop supervisor with reason; inspect StateFlow.
  - Expected: `isContinuousRunning` is false; `lastStopReason` contains title and detail.
- **TC-05: Thai Localization**
  - Input: Test `LocaleHelper` with "th".
  - Expected: Correct Thai locale resolved and applied.
