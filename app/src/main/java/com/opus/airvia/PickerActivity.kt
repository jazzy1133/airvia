package com.opus.airvia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Speaker picker sheet (1.3.0; rebuilt in 1.3.1). Entry points:
 *  - the notification's "Speakers" action,
 *  - a LONG-PRESS on the Quick Settings tile (the manifest maps
 *    QS_TILE_PREFERENCES here — his exact ask in issue #5).
 * Android gives apps no long-press callback on their own notification,
 * so these platform surfaces are the faithful equivalents.
 *
 * 1.3.0 rendered this as a width-only Surface floating at the top of a
 * transparent window — mis-anchored for its bottom-sheet shape, and in
 * the glass theme the sheet colour itself is translucent, so the
 * picker drew as a ghost strip over the app behind it and taps landed
 * nowhere useful ("page displayed incorrectly, can't click anything"
 * — issue #5). 1.3.1 gives it a real modal structure: full-window
 * root, a dim scrim that consumes outside touches and dismisses, and
 * a bottom-anchored sheet with an OPAQUE surface in every theme.
 *
 * Adding a speaker rides the already-running capture pipeline — no
 * new consent. When nothing is casting (stale notification), the
 * picker just offers to open the app.
 */
class PickerActivity : ComponentActivity() {

    private var discovered by mutableStateOf<List<Speaker>>(emptyList())
    private var tick by mutableIntStateOf(0)
    private var discovery: SpeakerDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = SpeakerDiscovery(this) { found -> discovered = found }
        val mode = themeModeOf(this)
        setContent {
            AirviaTheme(mode) {
                LaunchedEffect(Unit) {
                    while (true) {
                        tick++
                        delay(800)
                    }
                }
                // Read engine state fresh on every tick.
                @Suppress("UNUSED_EXPRESSION") tick
                val sessions = CastEngine.snapshot()
                // Opaque in EVERY theme: the glass scheme's surface
                // colour is translucent by design (it is drawn over
                // MainActivity's backdrop, which this window lacks).
                val sheetColor = MaterialTheme.colorScheme.surface.let {
                    if (it.alpha < 1f) it.copy(alpha = 1f) else it
                }
                Box(Modifier.fillMaxSize()) {
                    // Scrim: dims what's behind, consumes outside
                    // touches, tap dismisses — the modal contract the
                    // 1.3.0 strip never had.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { finish() },
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(max = 600.dp)
                            // Swallow taps on empty sheet areas so
                            // they don't fall through to the scrim.
                            .clickable(
                                interactionSource = remember {
                                    MutableInteractionSource()
                                },
                                indication = null,
                            ) {},
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = sheetColor,
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                "Speakers",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (CastEngine.hasLiveSource) {
                                    "Casting now — adjust or add a speaker."
                                } else {
                                    "Nothing is casting right now."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))

                            for (ss in sessions) {
                                key(ss.speaker.key) {
                                    PickerSessionCard(ss)
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            if (sessions.isNotEmpty()) {
                                // Manual volume re-claim (1.3.2): fresh
                                // session, stream untouched — the
                                // stop-restart dance minus the restart.
                                Button(
                                    onClick = { VolumeKeys.retake() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Take volume control")
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            if (CastEngine.hasLiveSource) {
                                val activeKeys = sessions.map { it.speaker.key }.toSet()
                                val others = discovered.filter { it.key !in activeKeys }
                                if (others.isNotEmpty()) {
                                    Text(
                                        "Add a speaker",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    for (sp in others) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement =
                                                Arrangement.SpaceBetween,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(sp.name)
                                                Text(
                                                    "${sp.host}:${sp.port}",
                                                    style =
                                                        MaterialTheme.typography.bodySmall,
                                                    color =
                                                        MaterialTheme.colorScheme
                                                            .onSurfaceVariant,
                                                )
                                            }
                                            Button(onClick = { addSpeaker(sp) }) {
                                                Text("Cast")
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { openApp() }) {
                                    Text("Open Airvia")
                                }
                                TextButton(onClick = { finish() }) {
                                    Text("Done")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        discovery?.start()
    }

    override fun onPause() {
        discovery?.stop()
        super.onPause()
    }

    private fun addSpeaker(sp: Speaker) {
        try {
            startService(CastService.startIntent(this, 0, null, sp))
        } catch (e: Exception) {
            LogBus.log("[picker] add speaker failed: ${e.message}")
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
        )
        finish()
    }
}

/** One live session's row in the picker: name, state, stop, volume. */
@Composable
private fun PickerSessionCard(ss: CastEngine.SpeakerSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ss.speaker.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${ss.protocol} · ${ss.state}" +
                            if (ss.muted) " · Muted" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        CastEngine.setMuted(ss.speaker, !ss.muted)
                    },
                ) { Text(if (ss.muted) "Unmute" else "Mute") }
                TextButton(
                    onClick = { CastEngine.stopSpeaker(ss.speaker) },
                ) { Text("Stop") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Local drag state, same pattern as the main UI
                // (1.3.1 §3): while dragging, the thumb follows the
                // finger, not the polled engine value.
                var dragVolume by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragVolume ?: ss.volumePct.toFloat(),
                    onValueChange = { v ->
                        dragVolume = v
                        CastEngine.setVolume(ss.speaker, v.toInt())
                    },
                    onValueChangeFinished = {
                        dragVolume?.let { CastEngine.setVolume(ss.speaker, it.toInt()) }
                        dragVolume = null
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .padding(horizontal = 10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${dragVolume?.toInt() ?: ss.volumePct}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
