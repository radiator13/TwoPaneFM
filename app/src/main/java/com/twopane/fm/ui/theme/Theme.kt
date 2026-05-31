package com.twopane.fm.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF1E3A5F),
    secondary = Color(0xFF80CBC4),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFF252525),
    surfaceContainer = Color(0xFF2A2A2A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    error = Color(0xFFEF5350)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    secondary = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFE8E8E8),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    error = Color(0xFFD32F2F)
)

@Composable
fun TwoPaneFMTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color on Android 12+ (Material You)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
