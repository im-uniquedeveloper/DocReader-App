package com.underrated.docreader.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF174A91),
    secondary = Color(0xFF42648F), secondaryContainer = Color(0xFFE8F0FC), onSecondaryContainer = Color(0xFF173F70),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF0F5FC),
    background = Color(0xFFFAFCFF),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    secondary = Color(0xFFB4C9E6), secondaryContainer = Color(0xFF233B59), onSecondaryContainer = Color(0xFFE1EDFF),
    surfaceContainer = Color(0xFF192B45), surfaceContainerLow = Color(0xFF14243B),
    background = Color(0xFF0C1728),
    surface = Color(0xFF14243B),
)

@Composable
fun DocReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "System Default")!!) }
    val selectedDark = when (mode) { "Light" -> false; "Dark" -> true; else -> darkTheme }
    CompositionLocalProvider(LocalAppearance provides mode, LocalSetAppearance provides { choice -> mode = choice; prefs.edit().putString("mode", choice).apply() }, LocalDocReaderDark provides selectedDark) {
    val view = LocalView.current
    SideEffect {
        val activity = context as? android.app.Activity
        if (activity != null) WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !selectedDark
            isAppearanceLightNavigationBars = !selectedDark
        }
    }
    val colors = if (selectedDark) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

}
val LocalAppearance = staticCompositionLocalOf { "System Default" }
val LocalSetAppearance = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalDocReaderDark = staticCompositionLocalOf { false }
