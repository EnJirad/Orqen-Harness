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

// ORQEN Signature Palette: Obsidian Black + Mint-Cyan
val OrqenMint = Color(0xFF00E5C4)
val OrqenMintSoft = Color(0xFF2DD4BF)
val OrqenCyan = Color(0xFF38BDF8)
val OrqenObsidian = Color(0xFF070A0D)
val OrqenSurface = Color(0xFF0E1319)
val OrqenSurfaceVariant = Color(0xFF151D26)
val OrqenOutline = Color(0xFF22303D)

// Keep legacy symbol aliases mapped to ORQEN Black + Mint-Cyan so every screen adopts the new theme seamlessly
val PocketOrange = OrqenMint
val PocketBlue = OrqenCyan
val PocketGreen = Color(0xFF10B981)
val PocketBackground = OrqenObsidian
val PocketSurface = OrqenSurface
val PocketSurfaceVariant = OrqenSurfaceVariant
val PocketOutline = OrqenOutline

private val DarkColors = darkColorScheme(
    primary = OrqenMint,
    onPrimary = Color(0xFF031F1B),
    primaryContainer = Color(0xFF0D2E2A),
    onPrimaryContainer = Color(0xFFB8FFF3),
    secondary = OrqenCyan,
    onSecondary = Color(0xFF042235),
    tertiary = OrqenMintSoft,
    onTertiary = Color(0xFF03211E),
    background = OrqenObsidian,
    onBackground = Color(0xFFEAF4F4),
    surface = OrqenSurface,
    onSurface = Color(0xFFEAF4F4),
    surfaceVariant = OrqenSurfaceVariant,
    onSurfaceVariant = Color(0xFF8FA6AD),
    outline = OrqenOutline,
    outlineVariant = Color(0xFF1E2B36),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00A890),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFFAF2),
    onPrimaryContainer = Color(0xFF003830),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF3F8F8),
    onBackground = Color(0xFF0F171A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F171A),
    surfaceVariant = Color(0xFFE5F0EF),
    onSurfaceVariant = Color(0xFF475C63),
    outline = Color(0xFFC4D6D6),
    outlineVariant = Color(0xFFD5E3E3),
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
