// ファイルパス: app/src/main/java/com/nexus/vision/ui/theme/Theme.kt

package com.nexus.vision.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── NEXUS Vision カラーパレット ──
private val NexusDark = darkColorScheme(
    primary = Color(0xFF90CAF9),          // ライトブルー
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1B3A5C),
    secondary = Color(0xFFA5D6A7),        // ライトグリーン
    background = Color(0xFF0D1117),       // ダークネイビー
    surface = Color(0xFF161B22),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

private val NexusLight = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF2E7D32),
    background = Color(0xFFFAFBFC),
    surface = Color.White,
    onBackground = Color(0xFF1F2328),
    onSurface = Color(0xFF1F2328),
)

@Composable
fun NexusVisionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ Dynamic Color 対応
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> NexusDark
        else -> NexusLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}