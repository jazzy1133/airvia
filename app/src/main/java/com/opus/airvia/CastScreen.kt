package com.opus.airvia

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** The Cast tab: now-casting card, speaker list, IP connect. */
@Composable
fun CastScreen(
    castState: CastEngine.State,
    activeSpeakers: List<Speaker>,
    castError: String?,
    sessionStates: Map<String, CastEngine.State>,
    sessionVolumes: Map<String, Int>,
    sessionProtocols: Map<String, String>,
    mutedSpeakers: Map<String, Boolean> = emptyMap(),
    speakers: List<Speaker>,
    scanning: Boolean,
    hostInput: String,
    portInput: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onCast: (Speaker) -> Unit,
    onPairWithPin: (Speaker) -> Unit,
    protocolPrefs: Map<String, ProtocolPref> = emptyMap(),
    pairings: Map<String, Boolean> = emptyMap(),
    onProtocolPref: (Speaker, ProtocolPref) -> Unit = { _, _ -> },
    onSpeakerOptions: (Speaker) -> Unit = {},
    onDoctor: (Speaker) -> Unit = {},
    onStopSpeaker: (Speaker) -> Unit,
    onStopAll: () -> Unit,
    onRescan: () -> Unit,
    onVolumeSpeaker: (Speaker, Int) -> Unit,
    onMuteSpeaker: (Speaker) -> Unit = {},
    sleepMinutes: Int,
    onSleepTimer: (Int) -> Unit,
    lastSpeaker: Speaker?,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (castState != CastEngine.State.IDLE) {
            item {
                NowCastingCard(
                    state = castState,
                    activeSpeakers = activeSpeakers,
                    protocols = sessionProtocols,
                    error = castError,
                    onStopAll = onStopAll,
                    onDismissError = onStopAll,
                    sleepMinutes = sleepMinutes,
                    onSleepTimer = onSleepTimer,
                )
            }
        }

        if (castState == CastEngine.State.IDLE && lastSpeaker != null) {
            item {
                ReconnectCard(speaker = lastSpeaker, onCast = { onCast(lastSpeaker) })
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Speakers",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onRescan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                }
            }
        }

        if (speakers.isEmpty()) {
            item { EmptySpeakersCard(scanning) }
        } else {
            items(speakers, key = { it.key }) { sp ->
                val sState = sessionStates[sp.key]
                SpeakerCard(
                    speaker = sp,
                    active = sState == CastEngine.State.STREAMING ||
                        sState == CastEngine.State.CONNECTING,
                    connecting = sState == CastEngine.State.CONNECTING,
                    volumePct = sessionVolumes[sp.key] ?: 100,
                    protocol = sessionProtocols[sp.key],
                    onCast = { onCast(sp) },
                    onPairWithPin = { onPairWithPin(sp) },
                    protocolPref = protocolPrefs[sp.key] ?: ProtocolPref.AUTO,
                    hasPairing = pairings[sp.key] ?: false,
                    onProtocolPref = { onProtocolPref(sp, it) },
                    onSpeakerOptions = { onSpeakerOptions(sp) },
                    onDoctor = { onDoctor(sp) },
                    onStop = { onStopSpeaker(sp) },
                    onVolume = { onVolumeSpeaker(sp, it) },
                    muted = mutedSpeakers[sp.key] ?: false,
                    onMute = { onMuteSpeaker(sp) },
                )
            }
        }

        item {
            IpConnectCard(
                hostInput = hostInput,
                portInput = portInput,
                onHostChange = onHostChange,
                onPortChange = onPortChange,
                onConnect = {
                    val host = hostInput.trim()
                    if (host.isNotEmpty()) {
                        onCast(Speaker(host, host, portInput.toIntOrNull() ?: 7000))
                    }
                },
            )
        }

        item {
            Text(
                "Android only lets apps capture audio that allows it — some apps " +
                    "and DRM content stay silent (a platform rule every caster has). " +
                    "AirPlay adds a second or so of delay: made for music and " +
                    "podcasts, not lip-sync video. Multi-speaker playback is " +
                    "near-synchronized, not sample-exact.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun NowCastingCard(
    state: CastEngine.State,
    activeSpeakers: List<Speaker>,
    protocols: Map<String, String>,
    error: String?,
    onStopAll: () -> Unit,
    onDismissError: () -> Unit,
    sleepMinutes: Int,
    onSleepTimer: (Int) -> Unit,
) {
    val isError = state == CastEngine.State.ERROR
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == CastEngine.State.STREAMING) {
                    EqualizerBars()
                } else if (state == CastEngine.State.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    SpeakerIcon(modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            CastEngine.State.CONNECTING -> "Connecting…"
                            CastEngine.State.STREAMING ->
                                if (activeSpeakers.size > 1) {
                                    "Casting to ${activeSpeakers.size} speakers"
                                } else {
                                    "Casting all audio"
                                }
                            CastEngine.State.ERROR -> "Couldn't cast"
                            CastEngine.State.IDLE -> ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        activeSpeakers.joinToString("  ·  ") { sp ->
                            val proto = protocols[sp.key]
                            if (proto.isNullOrEmpty()) sp.name else "${sp.name} ($proto)"
                        }.ifEmpty { error ?: "" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = if (isError) onDismissError else onStopAll,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    StopIcon(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isError) "Dismiss" else "Stop all")
                }
            }
            if (isError && error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (!isError) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Each speaker has its own volume — use its card below. " +
                        "While casting, the phone's side buttons step every " +
                        "speaker together, even from the lock screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Sleep",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    for (m in listOf(0, 15, 30, 60)) {
                        androidx.compose.material3.FilterChip(
                            selected = sleepMinutes == m,
                            onClick = { onSleepTimer(m) },
                            label = { Text(if (m == 0) "Off" else "$m min") },
                        )
                    }
                }
                Text(
                    "Casting also stops itself after 5 silent minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Three little bars dancing while audio streams. */
@Composable
private fun EqualizerBars() {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(22.dp),
    ) {
        for (i in 0..3) {
            val f by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420 + i * 130),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + 14 * f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SpeakerCard(
    speaker: Speaker,
    active: Boolean,
    connecting: Boolean,
    volumePct: Int,
    protocol: String?,
    onCast: () -> Unit,
    onPairWithPin: () -> Unit,
    protocolPref: ProtocolPref = ProtocolPref.AUTO,
    hasPairing: Boolean = false,
    onProtocolPref: (ProtocolPref) -> Unit = {},
    onSpeakerOptions: () -> Unit = {},
    onDoctor: () -> Unit = {},
    onStop: () -> Unit,
    onVolume: (Int) -> Unit,
    muted: Boolean = false,
    onMute: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!active) onCast() },
                // Long-press opens the speaker menu: PIN pairing (the
                // way in on receivers that demand a code, and on ones
                // that connect silently but never play — Mac), the
                // connection protocol, sound options, Speaker Doctor.
                onLongClick = { menuOpen = true },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    SpeakerIcon(
                        tint = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        speaker.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        when (speaker.kind) {
                            SpeakerKind.AIRPLAY -> "${speaker.host}:${speaker.port}"
                            SpeakerKind.DLNA -> "DLNA / Sonos"
                            SpeakerKind.CHROMECAST -> "Chromecast"
                        } + when {
                            // While active this is the protocol the
                            // live session actually streams with.
                            active && !protocol.isNullOrEmpty() -> " · $protocol"
                            // Idle honesty (1.3.1): a saved PIN pairing
                            // exists for this receiver.
                            !active && speaker.kind == SpeakerKind.AIRPLAY &&
                                hasPairing -> " · Paired"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!active) {
                        Text(
                            "Long-press for pairing, connection & sound options",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when {
                    connecting -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp,
                    )
                    active -> TextButton(onClick = onStop) { Text("Stop") }
                    else -> Button(onClick = onCast) {
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Cast")
                    }
                }
            }
            if (active && !connecting) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VolumeDownIcon()
                    // Local drag state (1.3.1): while the finger is
                    // down the slider renders the FINGER's value, not
                    // the engine-polled one — binding straight to the
                    // polled value made the thumb fight the drag in
                    // polling steps ("sluggish/intermittent"). Moves
                    // still forward to the engine immediately (it and
                    // the sessions coalesce sends); the finish commits
                    // the final value, then external changes (keys,
                    // notification) own the thumb again. Horizontal
                    // padding keeps the whole thumb inside the gesture
                    // bounds at 0 %/100 % so the ends are grabbable,
                    // and the 48 dp strip is a human-sized touch area.
                    var dragVolume by remember(speaker.key) {
                        mutableStateOf<Float?>(null)
                    }
                    Slider(
                        value = dragVolume ?: volumePct.toFloat(),
                        onValueChange = { v ->
                            dragVolume = v
                            onVolume(v.toInt())
                        },
                        onValueChangeFinished = {
                            dragVolume?.let { onVolume(it.toInt()) }
                            dragVolume = null
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .padding(horizontal = 10.dp),
                    )
                    VolumeUpIcon()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$volumePct%",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(44.dp),
                    )
                    // One-tap mute (1.3.2): silences this speaker
                    // only; the track keeps playing. The % readout
                    // already shows the sent level (0% while muted).
                    TextButton(onClick = onMute) {
                        Text(if (muted) "Unmute" else "Mute")
                    }
                }
            }
        }
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
    ) {
        if (speaker.kind == SpeakerKind.AIRPLAY) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (hasPairing) "Pair with PIN ✓ (saved)" else "Pair with PIN",
                    )
                },
                onClick = {
                    menuOpen = false
                    onPairWithPin()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Connection: " + when (protocolPref) {
                            ProtocolPref.AUTO -> "Auto"
                            ProtocolPref.AP2 -> "AirPlay 2 first"
                            ProtocolPref.RAOP -> "AirPlay 1 first"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {},
                enabled = false,
            )
            for (pref in ProtocolPref.values()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (pref == protocolPref) "✓ " else "    ") +
                                when (pref) {
                                    ProtocolPref.AUTO -> "Auto (recommended)"
                                    ProtocolPref.AP2 -> "AirPlay 2 first"
                                    ProtocolPref.RAOP -> "AirPlay 1 first"
                                },
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onProtocolPref(pref)
                    },
                )
            }
            if (hasPairing) {
                // Pairing precedence, stated where the choice is made
                // (1.3.1): Auto verifies first; an explicit choice
                // wins, with the pairing as the AirPlay 2 fallback.
                DropdownMenuItem(
                    text = {
                        Text(
                            if (protocolPref == ProtocolPref.AUTO) {
                                "PIN pairing saved — Auto uses AirPlay 2 (verified) first"
                            } else {
                                "Your choice connects first; the saved pairing is the AirPlay 2 fallback"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
        }
        DropdownMenuItem(
            text = { Text("Speaker options (EQ, delay, max volume)") },
            onClick = {
                menuOpen = false
                onSpeakerOptions()
            },
        )
        if (speaker.kind == SpeakerKind.AIRPLAY) {
            DropdownMenuItem(
                text = { Text("Speaker Doctor") },
                onClick = {
                    menuOpen = false
                    onDoctor()
                },
            )
        }
    }
    }
}

@Composable
private fun EmptySpeakersCard(scanning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                SpeakerIcon(
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (scanning) "Looking for speakers…" else "No speakers found",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Make sure the speaker is on and on the same Wi-Fi as this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReconnectCard(speaker: Speaker, onCast: () -> Unit) {
    Card(
        onClick = onCast,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerIcon(tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Jump back in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    speaker.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(onClick = onCast) { Text("Cast") }
        }
    }
}

@Composable
private fun IpConnectCard(
    hostInput: String,
    portInput: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Connect by IP (AirPlay)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = onHostChange,
                    label = { Text("Speaker IP") },
                    placeholder = { Text("10.0.0.148") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
        }
    }
}
