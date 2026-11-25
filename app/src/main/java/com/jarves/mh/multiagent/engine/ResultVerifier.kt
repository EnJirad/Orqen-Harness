package com.jarves.mh.multiagent.engine

import com.jarves.mh.multiagent.persistence.WorkspacePathValidator
import java.io.File

enum class VerificationMode {
    AUTOMATED_VERIFICATION,
    COMMAND_VERIFICATION,
    FILE_VERIFICATION,
    TEST_VERIFICATION,
    MANUAL_APPROVAL,
}

data class VerificationSpec(
    val mode: VerificationMode = VerificationMode.AUTOMATED_VERIFICATION,
    val expectedFiles: List<String> = emptyList(),
    val commandExitCode: Int? = null,
    val testsPassed: Boolean? = null,
    val manualApproved: Boolean = false,
    val requireNonEmptySummary: Boolean = true,
)

data class VerificationOutcome(
    val passed: Boolean,
    val mode: VerificationMode,
    val details: String,
    val verifiedFiles: List<String> = emptyList(),
)

/**
 * Enforces real deliverable verification before any step or task may transition to COMPLETED.
 * Never treats an HTTP 200 / empty model reply as sufficient proof of completion.
 */
object ResultVerifier {
    fun verify(
        workspaceDir: File,
        outputSummary: String,
        spec: VerificationSpec,
    ): VerificationOutcome {
        if (spec.requireNonEmptySummary && outputSummary.isBlank()) {
            return VerificationOutcome(
                passed = false,
                mode = spec.mode,
                details = "Verification failed: empty execution summary.",
            )
        }

        return when (spec.mode) {
            VerificationMode.FILE_VERIFICATION -> {
                val missing = mutableListOf<String>()
                val unsafe = mutableListOf<String>()
                val verified = mutableListOf<String>()
                for (rel in spec.expectedFiles) {
                    if (!WorkspacePathValidator.isPathInsideRoot(workspaceDir, rel)) {
                        unsafe.add(rel)
                        continue
                    }
                    val f = File(workspaceDir, rel)
                    if (!f.isFile || f.length() == 0L) {
                        missing.add(rel)
                    } else {
                        verified.add(rel)
                    }
                }
                when {
                    unsafe.isNotEmpty() -> VerificationOutcome(false, spec.mode, "Unsafe paths rejected: ${unsafe.joinToString()}")
                    missing.isNotEmpty() -> VerificationOutcome(false, spec.mode, "Expected files missing or empty: ${missing.joinToString()}")
                    else -> VerificationOutcome(true, spec.mode, "Verified ${verified.size} file(s) on disk.", verified)
                }
            }
            VerificationMode.COMMAND_VERIFICATION -> {
                val code = spec.commandExitCode
                if (code == 0) {
                    VerificationOutcome(true, spec.mode, "Command exited with code 0.")
                } else {
                    VerificationOutcome(false, spec.mode, "Command verification failed (exitCode=$code).")
                }
            }
            VerificationMode.TEST_VERIFICATION -> {
                if (spec.testsPassed == true) {
                    VerificationOutcome(true, spec.mode, "Automated tests verified passing.")
                } else {
                    VerificationOutcome(false, spec.mode, "Test verification failed or tests did not pass.")
                }
            }
            VerificationMode.MANUAL_APPROVAL -> {
                if (spec.manualApproved) {
                    VerificationOutcome(true, spec.mode, "Explicit owner approval recorded.")
                } else {
                    VerificationOutcome(false, spec.mode, "Awaiting explicit owner approval.")
                }
            }
            VerificationMode.AUTOMATED_VERIFICATION -> {
                if (spec.expectedFiles.isNotEmpty()) {
                    val missing = spec.expectedFiles.filter { rel ->
                        !WorkspacePathValidator.isPathInsideRoot(workspaceDir, rel) || !File(workspaceDir, rel).isFile
                    }
                    if (missing.isNotEmpty()) {
                        return VerificationOutcome(false, spec.mode, "Missing expected output files: ${missing.joinToString()}")
                    }
                }
                VerificationOutcome(true, spec.mode, "Automated output verification passed.", spec.expectedFiles)
            }
        }
    }
}
