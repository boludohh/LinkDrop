package com.noklishare.smartphone.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.noklishare.smartphone.settings.ThemeMode
import com.noklishare.smartphone.settings.ThemeModeRepository

private val LightColors = lightColorScheme(
    primary = LinkBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF51606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4D8DD),
    onSecondaryContainer = Color(0xFF43474E),
    tertiary = LinkGreenDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4F0C7),
    onTertiaryContainer = Color(0xFF002106),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightCardHigh,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightCardHigh,
    outline = LightOutline,
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2E3033),
    inverseOnSurface = Color(0xFFEFF0F7),
    scrim = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = LinkBlueLight,
    onPrimary = Color(0xFF003155),
    primaryContainer = Color(0xFF00497B),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB9C8D9),
    onSecondary = Color(0xFF24323F),
    secondaryContainer = Color(0xFF3A4856),
    onSecondaryContainer = Color(0xFFD5E4F6),
    tertiary = LinkGreenLight,
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF00531C),
    onTertiaryContainer = Color(0xFFC4F0C7),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCardHigh,
    outline = DarkOutline,
    outlineVariant = Color(0xFF44474E),
    inverseSurface = DarkOnSurface,
    inverseOnSurface = Color(0xFF2E3033),
    scrim = Color.Black
)

/**
 * Tema raíz de LinkDrop.
 *
 * Resuelve el modo claro/oscuro observando el [ThemeModeRepository]
 * (claro, oscuro o seguir al sistema) y aplica la paleta fija de marca
 * correspondiente. También sincroniza las barras del sistema con el
 * fondo de la aplicación para una apariencia uniforme.
 \*/
@Composable
fun LinkDropTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val themeModeRepository = remember(context) { ThemeModeRepository(context) }
    val themeMode by themeModeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}