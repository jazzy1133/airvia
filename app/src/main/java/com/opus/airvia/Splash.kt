package com.opus.airvia

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Animated startup splash (1.3.0) — the approved design: flat #161634,
 * the logo cutout, its three arcs lighting inner→outer twice, then
 * resting lit, with the tagline and the privacy line at the bottom.
 * The layer PNGs are the user's approved cutout assets verbatim
 * (res/drawable-nodpi/splash_*.png). Resource ids are resolved by
 * name: this pipeline generates R after the Kotlin step.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val layers = remember {
        listOf("splash_a", "splash_arc1", "splash_arc2", "splash_arc3").map { name ->
            try {
                val id = context.resources.getIdentifier(
                    name, "drawable", context.packageName,
                )
                if (id == 0) {
                    null
                } else {
                    BitmapFactory.decodeResource(context.resources, id)
                        ?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    var lit by remember { mutableIntStateOf(0) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        // Arcs light in sequence twice (~1.4 s), rest lit, fade out.
        repeat(2) { round ->
            for (i in 1..3) {
                lit = i
                delay(150)
            }
            if (round == 0) {
                lit = 0
                delay(130)
            }
        }
        delay(300)
        fade.animateTo(0f, animationSpec = tween(280))
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF161634))
            .graphicsLayer { alpha = fade.value },
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(210.dp)) {
                layers.getOrNull(0)?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                for (arc in 1..3) {
                    if (lit >= arc) {
                        layers.getOrNull(arc)?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Airvia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF2F4FF),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Your phone's sound, on your best speakers.",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFA7F3EA),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "No account. No ads. No tracking. Just your music, in your home.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB9C2E8),
                textAlign = TextAlign.Center,
            )
        }
    }
}
