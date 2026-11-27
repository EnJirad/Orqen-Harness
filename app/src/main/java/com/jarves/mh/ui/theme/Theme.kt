package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Phase F: Refined Calm Neutral Palette with Restrained Soft Indigo Accent (Zero Harsh Neon)
val OrqenAccentDark = Color(0xFF8FA2E3)       // Soft periwinkle-indigo for dark surfaces
val OrqenAccentLight = Color(0xFF4F63AC)      // Restrained dusty-indigo for light surfaces
val OrqenSecondarySoft = Color(0xFF6B8E9B)    // Muted slate-teal secondary
val OrqenSuccessSoft = Color(0xFF3E8E68)      // Calm sage-emerald
val OrqenWarningSoft = Color(0xFFC28B37)      // Warm muted amber
val OrqenErrorSoft = Color(0xFFC65A5A)        // Restrained terracotta red

// Deep graphite dark foundation (comfortable for extended reading)
val OrqenObsidian = Color(0xFF121417)
val OrqenSurface = Color(0xFF191C21)
val OrqenSurfaceVariant = Color(0xFF22262D)
val OrqenOutline = Color(0xFF2E343E)

// Backward-compatible semantic aliases mapped to the calm refined palette
val OrqenMint = OrqenAccentLight
val OrqenMintSoft = OrqenSecondarySoft
val OrqenCyan = OrqenSecondarySoft
val PocketOrange = OrqenAccentLight
val PocketBlue = OrqenSecondarySoft
val PocketGreen = OrqenSuccessSoft
val PocketBackground = OrqenObsidian
val PocketSurface = OrqenSurface
val PocketSurfaceVariant = OrqenSurfaceVariant
val PocketOutline = OrqenOutline

private val DarkColors = darkColorScheme(
    primary = OrqenAccentDark,
    onPrimary = Color(0xFF141A2E),
    primaryContainer = Color(0xFF252D47),
    onPrimaryContainer = Color(0xFFDDE3FA),
    secondary = Color(0xFF8FB0BC),
    onSecondary = Color(0xFF132228),
    secondaryContainer = Color(0xFF223036),
    onSecondaryContainer = Color(0xFFD4E5EB),
    tertiary = Color(0xFF7EB69A),
    onTertiary = Color(0xFF12261C),
    background = OrqenObsidian,
    onBackground = Color(0xFFE8EAEE),
    surface = OrqenSurface,
    onSurface = Color(0xFFE8EAEE),
    surfaceVariant = OrqenSurfaceVariant,
    onSurfaceVariant = Color(0xFF9AA1AC),
    outline = OrqenOutline,
    outlineVariant = Color(0xFF262B34),
    error = Color(0xFFE07A7A),
    onError = Color(0xFF2B1212),
)

private val LightColors = lightColorScheme(
    primary = OrqenAccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6EAF8),
    onPrimaryContainer = Color(0xFF1D284D),
    secondary = Color(0xFF537380),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3ECEF),
    onSecondaryContainer = Color(0xFF1B2F37),
    tertiary = Color(0xFF3E7D5E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF1C1E21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1E21),
    surfaceVariant = Color(0xFFEFEFEA),
    onSurfaceVariant = Color(0xFF5C616B),
    outline = Color(0xFFD5D5CE),
    outlineVariant = Color(0xFFE4E4DD),
    error = OrqenErrorSoft,
    onError = Color(0xFFFFFFFF),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        content = content,
    )
}
