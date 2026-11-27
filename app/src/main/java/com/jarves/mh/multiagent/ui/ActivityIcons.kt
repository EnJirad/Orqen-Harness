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
        ActivityType.ANALYZING -> Color(0xFF7B8FA6)
        ActivityType.PLANNING -> Color(0xFF8E86B8)
        ActivityType.READING_FILES -> Color(0xFF7B8FA6)
        ActivityType.WRITING_CODE -> Color(0xFF6B9E78)
        ActivityType.FIXING_BUG -> Color(0xFFC49449)
        ActivityType.TESTING -> Color(0xFF7B8FA6)
        ActivityType.REVIEWING -> Color(0xFF8E86B8)
        ActivityType.BUILDING_APK -> Color(0xFF6B9E78)
        ActivityType.INSTALLING_DEPS -> Color(0xFFC49449)
        ActivityType.COMMITTING -> Color(0xFF7B8FA6)
        ActivityType.PUSHING -> Color(0xFF7B8FA6)
        ActivityType.MERGING -> Color(0xFF8E86B8)
        ActivityType.WAITING -> Color(0xFF8B909A)
        ActivityType.PAUSED -> Color(0xFFC49449)
        ActivityType.COMPLETED -> Color(0xFF6B9E78)
        ActivityType.ERROR -> Color(0xFFC96868)
    }
}
