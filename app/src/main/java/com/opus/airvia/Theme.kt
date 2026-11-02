package com.opus.airvia

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** App appearance (1.3.0). SYSTEM is the pre-1.3.0 behaviour and the
 *  default; GLASS is the frosted deep-navy look. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, GLASS }

/** Read the persisted theme mode (Prefs stores the enum name). */
fun themeModeOf(ctx: Context): ThemeMode =
    runCatching { ThemeMode.valueOf(Prefs.themeMode(ctx)) }
        .getOrDefault(ThemeMode.SYSTEM)

// Airvia's fallback palette (used when Material You dynamic color is
// unavailable): deep blue-black with a sonic teal accent.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0E3B34),
    onPrimaryContainer = Color(0xFF9FF5E3),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF0B2B4D),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11161C),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B232C),
    onSurfaceVariant = Color(0xFF9FB0BE),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3D0500),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF7FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFF52606D),
)

// Glass: the approved brand direction (deep navy #161634, teal gradient
// #a7f3ea→#2dd4bf→#0d9488) made literal — surfaces carry alpha so the
// GlassBackdrop shows through every card and bar, frosted-glass style.
private val GlassScheme = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF06281F),
    primaryContainer = Color(0x8C0D9488),
    onPrimaryContainer = Color(0xFFA7F3EA),
    secondary = Color(0xFFA7F3EA),
    onSecondary = Color(0xFF0B2B4D),
    background = Color(0xD9161634),
    onBackground = Color(0xFFF2F4FF),
    surface = Color(0xB31B1B3D),
    onSurface = Color(0xFFF2F4FF),
    surfaceVariant = Color(0x80222248),
    onSurfaceVariant = Color(0xFFB9C2E8),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3D0500),
    errorContainer = Color(0xB35B1D1D),
    onErrorContainer = Color(0xFFFFDAD4),
)

@Composable
fun AirviaTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when (mode) {
        ThemeMode.GLASS -> GlassScheme
        ThemeMode.LIGHT ->
            if (Build.VERSION.SDK_INT >= 31) {
                dynamicLightColorScheme(context)
            } else {
                LightScheme
            }
        ThemeMode.DARK ->
            if (Build.VERSION.SDK_INT >= 31) {
                dynamicDarkColorScheme(context)
            } else {
                DarkScheme
            }
        ThemeMode.SYSTEM -> when {
            Build.VERSION.SDK_INT >= 31 && systemDark ->
                dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
            systemDark -> DarkScheme
            else -> LightScheme
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * The backdrop painted behind the app in GLASS mode: flat deep navy
 * with soft teal glows and a faint waveform motif. The glow layer is
 * blurred where the platform allows (RenderEffect, API 31+); on
 * API 29/30 the unblurred gradient is the designed fallback (the
 * translucent scheme colours above it still read as "glass").
 */
@Composable
fun GlassBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF161634)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= 31) {
                        Modifier.blur(30.dp)
                    } else {
                        Modifier
                    },
                ),
        ) {
            val w = size.width
            val h = size.height
            // Teal glows (the approved gradient stops).
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF2DD4BF), Color.Transparent),
                ),
                radius = w * 0.75f,
                center = Offset(w * 0.85f, h * 0.08f),
                alpha = 0.5f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF0D9488), Color.Transparent),
                ),
                radius = w * 0.9f,
                center = Offset(w * 0.05f, h * 0.95f),
                alpha = 0.55f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFA7F3EA), Color.Transparent),
                ),
                radius = w * 0.4f,
                center = Offset(w * 0.55f, h * 0.55f),
                alpha = 0.18f,
            )
        }
        // Waveform motif: a row of soft vertical bars across the middle.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val bars = 28
            val barW = w / (bars * 2.2f)
            val heights = floatArrayOf(
                0.18f, 0.34f, 0.52f, 0.78f, 0.62f, 0.9f, 0.7f, 0.44f,
                0.58f, 0.95f, 0.8f, 0.5f, 0.3f, 0.42f, 0.66f, 0.88f,
                0.6f, 0.38f, 0.55f, 0.75f, 0.92f, 0.64f, 0.4f, 0.26f,
                0.48f, 0.7f, 0.5f, 0.3f,
            )
            for (i in 0 until bars) {
                val bh = h * 0.16f * heights[i % heights.size]
                val x = (i + 0.6f) * (w / bars) - barW / 2
                drawRoundRect(
                    color = Color(0xFF2DD4BF),
                    topLeft = Offset(x, h * 0.52f - bh / 2),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                    alpha = 0.10f,
                )
            }
        }
    }
}
