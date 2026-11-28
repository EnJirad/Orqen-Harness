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

// Minimalist Monochrome AI Design System — Pure Neutral Charcoal, Slate & Crisp White
val OrqenAccentDark = Color(0xFFF5F5F5)       // Crisp near-white primary for dark surfaces
val OrqenAccentLight = Color(0xFF141414)      // Deep ink black primary for light surfaces
val OrqenSecondarySoft = Color(0xFFA3A3A3)    // Neutral silver-gray secondary
val OrqenSuccessSoft = Color(0xFF739E82)      // Muted sage (subtle, non-neon)
val OrqenWarningSoft = Color(0xFFC4A05A)      // Muted warm sand
val OrqenErrorSoft = Color(0xFFC96A6A)        // Muted terracotta red

// Pure neutral monochrome dark foundation (ChatGPT / minimalist AI inspired)
val OrqenObsidian = Color(0xFF0E0E0E)
val OrqenSurface = Color(0xFF171717)
val OrqenSurfaceVariant = Color(0xFF212121)
val OrqenOutline = Color(0xFF2E2E2E)

// Backward-compatible semantic aliases mapped to minimalist monochrome tokens
val OrqenMint = Color(0xFFD4D4D4)
val OrqenMintSoft = OrqenSecondarySoft
val OrqenCyan = OrqenAccentDark
val PocketOrange = Color(0xFFB5B5B5)
val PocketBlue = OrqenSecondarySoft
val PocketGreen = OrqenSuccessSoft
val PocketBackground = OrqenObsidian
val PocketSurface = OrqenSurface
val PocketSurfaceVariant = OrqenSurfaceVariant
val PocketOutline = OrqenOutline

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0E0E0E),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFA3A3A3),
    onSecondary = Color(0xFF0E0E0E),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFE5E5E5),
    tertiary = Color(0xFFD4D4D4),
    onTertiary = Color(0xFF141414),
    background = OrqenObsidian,
    onBackground = Color(0xFFECECEC),
    surface = OrqenSurface,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = OrqenSurfaceVariant,
    onSurfaceVariant = Color(0xFF9B9B9B),
    outline = Color(0xFF383838),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFE07A7A),
    onError = Color(0xFF1F0B0B),
    errorContainer = Color(0xFF2E1A1A),
    onErrorContainer = Color(0xFFF5C2C2),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF525252),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF262626),
    tertiary = Color(0xFF404040),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFD4D4D4),
    outlineVariant = Color(0xFFE5E5E5),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
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
