package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Play-Store-compliant boot & package-update receiver.
 *
 * Strictly obeys Android 12–16 background execution restrictions:
 * - NEVER starts a Foreground Service directly from BOOT_COMPLETED.
 * - ONLY schedules deferred, network-aware reconciliation via [TaskRecoveryJobService].
 * - Cannot and does not attempt to bypass explicit Android Force Stop.
 */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val scheduled = TaskRecoveryJobService.scheduleReconciliation(
                context = context.applicationContext,
                taskId = null,
                requireNetwork = true,
                minLatencyMs = 5_000L,
            )
            Log.i(TAG, "Received $action -> scheduled durable reconciliation=$scheduled")
        }
    }

    companion object {
        private const val TAG = "BootRecoveryReceiver"
    }
}
