package com.antitheft.admin.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class CustomSheColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentContainer: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val isDark: Boolean
)

val LocalSheColors = staticCompositionLocalOf {
    CustomSheColors(
        background = LightBackground,
        surface = LightSurface,
        surfaceVariant = LightSurfaceVariant,
        border = LightBorder,
        textPrimary = LightTextPrimary,
        textSecondary = LightTextSecondary,
        textTertiary = LightTextTertiary,
        accent = LightAccent,
        accentContainer = LightAccentContainer,
        success = LightSuccess,
        successContainer = LightSuccessContainer,
        warning = LightWarning,
        warningContainer = LightWarningContainer,
        danger = LightDanger,
        dangerContainer = LightDangerContainer,
        isDark = false
    )
}

private val LightSheColors = CustomSheColors(
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    border = LightBorder,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textTertiary = LightTextTertiary,
    accent = LightAccent,
    accentContainer = LightAccentContainer,
    success = LightSuccess,
    successContainer = LightSuccessContainer,
    warning = LightWarning,
    warningContainer = LightWarningContainer,
    danger = LightDanger,
    dangerContainer = LightDangerContainer,
    isDark = false
)

private val DarkSheColors = CustomSheColors(
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    border = DarkBorder,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textTertiary = DarkTextTertiary,
    accent = DarkAccent,
    accentContainer = DarkAccentContainer,
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    warning = DarkWarning,
    warningContainer = DarkWarningContainer,
    danger = DarkDanger,
    dangerContainer = DarkDangerContainer,
    isDark = true
)

private val MaterialLightScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = LightAccentContainer,
    onPrimaryContainer = LightAccent,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder
)

private val MaterialDarkScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color.Black,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkAccent,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder
)

@Composable
fun SheTheme(
    forceThemeMode: ThemeMode? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themePrefs = ThemePreferences.getInstance(context)
    val currentMode by themePrefs.themeMode.collectAsState()

    val effectiveMode = forceThemeMode ?: currentMode
    val isDark = when (effectiveMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val customColors = if (isDark) DarkSheColors else LightSheColors
    val materialScheme = if (isDark) MaterialDarkScheme else MaterialLightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = customColors.background.toArgb()
            window.navigationBarColor = customColors.background.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalSheColors provides customColors
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = SheTypography,
            content = content
        )
    }
}

object SheTheme {
    val colors: CustomSheColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSheColors.current
}
