package com.jarves.mh.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectType

data class ProjectIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Phase A — Local Project Icon Catalog.
 * Maps every [ProjectType] to a distinct default icon and provides built-in selectable icons
 * without downloading external assets.
 */
object ProjectIconCatalog {
    val builtInIcons: List<ProjectIconOption> = listOf(
        ProjectIconOption("android", "Android", Icons.Default.Android),
        ProjectIconOption("smartphone", "Smartphone", Icons.Default.Smartphone),
        ProjectIconOption("globe", "Web / Globe", Icons.Default.Language),
        ProjectIconOption("desktop", "Desktop", Icons.Default.Computer),
        ProjectIconOption("server", "Backend / Server", Icons.Default.Dns),
        ProjectIconOption("folder", "Folder", Icons.Default.Folder),
        ProjectIconOption("code", "Code", Icons.Default.Code),
        ProjectIconOption("terminal", "Terminal", Icons.Default.Terminal),
        ProjectIconOption("rocket", "Launch", Icons.Default.RocketLaunch),
        ProjectIconOption("build", "Tools", Icons.Default.Build),
        ProjectIconOption("storage", "Database", Icons.Default.Storage),
        ProjectIconOption("hub", "AI / Hub", Icons.Default.Hub),
        ProjectIconOption("layers", "Architecture", Icons.Default.Layers),
        ProjectIconOption("palette", "Design", Icons.Default.Palette),
        ProjectIconOption("science", "Lab", Icons.Default.Science),
        ProjectIconOption("widgets", "Custom", Icons.Default.Widgets),
    )

    fun defaultIconKeyFor(type: ProjectType): String = when (type) {
        ProjectType.ANDROID -> "android"
        ProjectType.IOS -> "smartphone"
        ProjectType.WEB -> "globe"
        ProjectType.DESKTOP -> "desktop"
        ProjectType.BACKEND -> "server"
        ProjectType.GENERAL -> "folder"
        ProjectType.CUSTOM -> "widgets"
    }

    fun resolveIcon(project: Project): ImageVector {
        val key = project.iconKey.trim().ifBlank { defaultIconKeyFor(project.type) }
        return builtInIcons.firstOrNull { it.key == key }?.icon ?: Icons.Default.Folder
    }

    fun resolveIconByKey(key: String, fallbackType: ProjectType = ProjectType.GENERAL): ImageVector {
        val resolvedKey = key.trim().ifBlank { defaultIconKeyFor(fallbackType) }
        return builtInIcons.firstOrNull { it.key == resolvedKey }?.icon ?: Icons.Default.Folder
    }
}
