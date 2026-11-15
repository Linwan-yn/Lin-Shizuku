package moe.shizuku.privileged.api.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF545F70),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF283041),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099)
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF003258),
    background = Color.Black,
    onBackground = Color(0xFFE3E2E6),
    surface = Color.Black,
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099)
)

@Composable
fun LinShizukuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        amoled && darkTheme -> {
            // AMOLED 纯黑背景 + 动态取色共存：动态色仅作用于强调色，背景/表面保持纯黑
            if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(context).copy(
                    background = Color.Black,
                    onBackground = Color(0xFFE3E2E6),
                    surface = Color.Black,
                    onSurface = Color(0xFFE3E2E6),
                    surfaceVariant = Color(0xFF1A1C1E),
                    onSurfaceVariant = Color(0xFFC4C6D0)
                )
            } else {
                AmoledColors
            }
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Enable edge-to-edge: content extends behind system bars, no system background plate
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Enable cross-window blur for backdrop blur effects (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val method = window.javaClass.getMethod("setCrossWindowBlurEnabled", Boolean::class.javaPrimitiveType)
                    method.invoke(window, true)
                } catch (_: Throwable) {}
            }
            // Transparent system bars for edge-to-edge
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()

            // Status bar icon contrast
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme

            // Navigation bar icon contrast (API 27+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }

            // Enable contrast-free navigation bar on Android 15+ for gesture nav
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    window.setNavigationBarContrastEnforced(false)
                } catch (_: Throwable) {}
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
