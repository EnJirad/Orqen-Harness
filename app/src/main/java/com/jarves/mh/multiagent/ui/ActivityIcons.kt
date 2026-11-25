package com.jarves.mh.multiagent.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarves.mh.multiagent.model.ActivityType

object ActivityVisuals {
    fun iconFor(type: ActivityType): ImageVector = when (type) {
        ActivityType.ANALYZING -> Icons.Default.AutoAwesome
        ActivityType.PLANNING -> Icons.Default.EditNote
        ActivityType.READING_FILES -> Icons.Default.MenuBook
        ActivityType.WRITING_CODE -> Icons.Default.Description
        ActivityType.FIXING_BUG -> Icons.Default.Build
        ActivityType.TESTING -> Icons.Default.Science
        ActivityType.REVIEWING -> Icons.Default.FactCheck
        ActivityType.BUILDING_APK -> Icons.Default.Android
        ActivityType.INSTALLING_DEPS -> Icons.Default.Download
        ActivityType.COMMITTING -> Icons.Default.Save
        ActivityType.PUSHING -> Icons.Default.CloudUpload
        ActivityType.MERGING -> Icons.Default.CallMerge
        ActivityType.WAITING -> Icons.Default.HourglassEmpty
        ActivityType.PAUSED -> Icons.Default.PauseCircle
        ActivityType.COMPLETED -> Icons.Default.CheckCircle
        ActivityType.ERROR -> Icons.Default.ErrorOutline
    }

    fun colorFor(type: ActivityType): Color = when (type) {
        ActivityType.ANALYZING -> Color(0xFF64B5F6) // Blue
        ActivityType.PLANNING -> Color(0xFFBA68C8) // Purple
        ActivityType.READING_FILES -> Color(0xFF4DD0E1) // Cyan
        ActivityType.WRITING_CODE -> Color(0xFF81C784) // Green
        ActivityType.FIXING_BUG -> Color(0xFFFFB74D) // Orange
        ActivityType.TESTING -> Color(0xFF4FC3F7) // Light Blue
        ActivityType.REVIEWING -> Color(0xFFAED581) // Light Green
        ActivityType.BUILDING_APK -> Color(0xFF81C784) // Android Green
        ActivityType.INSTALLING_DEPS -> Color(0xFFFFD54F) // Yellow
        ActivityType.COMMITTING -> Color(0xFF90CAF9)
        ActivityType.PUSHING -> Color(0xFF80CBC4)
        ActivityType.MERGING -> Color(0xFFB39DDB)
        ActivityType.WAITING -> Color(0xFF9E9E9E) // Gray
        ActivityType.PAUSED -> Color(0xFFFFB74D) // Amber
        ActivityType.COMPLETED -> Color(0xFF66BB6A) // Green
        ActivityType.ERROR -> Color(0xFFE57373) // Red
    }
}
