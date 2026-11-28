package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.DevStack
import com.jarves.mh.ui.localizedLabel
import java.io.File

/**
 * Handles Android notification actions when ORQEN AI detects a missing toolchain
 * (e.g., Android SDK, Android NDK & CMake, Python, C/C++, PHP) and asks the user
 * whether to install it in the background.
 */
class ToolInstallPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val stackName = intent.getStringExtra(EXTRA_STACK) ?: return
        val stack = runCatching { DevStack.valueOf(stackName) }.getOrNull() ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, notificationIdFor(stack))

        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)

        if (action == ACTION_ALLOW_INSTALL) {
            RuntimeSetupService.startInstallStack(context.applicationContext, stack)
        }
    }

    companion object {
        const val ACTION_ALLOW_INSTALL = "com.jarves.mh.ACTION_ALLOW_TOOL_INSTALL"
        const val ACTION_DENY_INSTALL = "com.jarves.mh.ACTION_DENY_TOOL_INSTALL"
        const val EXTRA_STACK = "extra_stack"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        private const val CHANNEL_ID = "tool-permission"
        private const val BASE_NOTIFICATION_ID = 6200

        private fun notificationIdFor(stack: DevStack): Int = BASE_NOTIFICATION_ID + stack.ordinal

        fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Tool Installation Requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Requests permission when ORQEN AI needs to install a missing toolchain in the background."
            }
            manager.createNotificationChannel(channel)
        }

        /**
         * Checks whether an Android project requires native CMake/NDK compilation.
         */
        fun projectNeedsNativeNdk(projectRoot: File?): Boolean {
            if (projectRoot == null || !projectRoot.isDirectory) return false
            val gradleFiles = listOf(
                File(projectRoot, "app/build.gradle.kts"),
                File(projectRoot, "app/build.gradle"),
            )
            return gradleFiles.any { file ->
                if (!file.isFile) return@any false
                val text = runCatching { file.readText() }.getOrDefault("")
                text.contains("externalNativeBuild") || text.contains("CMakeLists.txt")
            }
        }

        /**
         * If [stack] is not installed:
         * - When `autoInstallMissingTools` is enabled in Settings, immediately starts
         *   background installation via [RuntimeSetupService] and returns `true`.
         * - Otherwise, posts an Android notification with Allow / Deny buttons and returns `false`.
         */
        fun requestOrAutoInstallTool(
            context: Context,
            stack: DevStack,
            reason: String? = null,
        ): Boolean {
            val appContext = context.applicationContext
            val installer = RuntimeInstaller(appContext)
            if (installer.isStackInstalled(stack)) return true

            val currentActive = DevStackInstallController.snapshot.value.activeStack
            if (currentActive == stack) return true

            val prefs = AppPreferences(appContext)
            if (prefs.autoInstallMissingTools) {
                RuntimeSetupService.startInstallStack(appContext, stack)
                return true
            }

            ensureNotificationChannel(appContext)
            val stackLabel = stack.localizedLabel(appContext)
            val isThai = prefs.languageCode.equals("th", ignoreCase = true) ||
                ((prefs.languageCode.isBlank() || prefs.languageCode == "system") && java.util.Locale.getDefault().language.equals("th", ignoreCase = true))
            val title = if (isThai) {
                "ORQEN AI ขออนุญาตติดตั้ง $stackLabel"
            } else {
                "ORQEN AI requests installing $stackLabel"
            }
            val body = reason?.takeIf { it.isNotBlank() } ?: if (isThai) {
                "โปรเจกต์นี้ต้องใช้ $stackLabel เพื่อทำงานและบิลด์ให้สมบูรณ์ อนุญาตให้ติดตั้งในพื้นหลังหรือไม่?"
            } else {
                "This project requires $stackLabel to build and run. Allow background installation?"
            }
            val allowLabel = if (isThai) "อนุญาตและติดตั้ง" else "Allow & Install"
            val denyLabel = if (isThai) "ปฏิเสธ" else "Deny"

            val notificationId = notificationIdFor(stack)
            val allowIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId * 2,
                Intent(appContext, ToolInstallPermissionReceiver::class.java).apply {
                    action = ACTION_ALLOW_INSTALL
                    putExtra(EXTRA_STACK, stack.name)
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val denyIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId * 2 + 1,
                Intent(appContext, ToolInstallPermissionReceiver::class.java).apply {
                    action = ACTION_DENY_INSTALL
                    putExtra(EXTRA_STACK, stack.name)
                    putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val openAppIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .addAction(0, allowLabel, allowIntent)
                .addAction(0, denyLabel, denyIntent)
                .build()

            appContext.getSystemService(NotificationManager::class.java)
                ?.notify(notificationId, notification)
            return false
        }
    }
}
