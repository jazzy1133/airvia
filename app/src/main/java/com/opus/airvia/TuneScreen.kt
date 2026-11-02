package com.opus.airvia

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Tune tab: 5-band EQ + preamp (applied live to the cast stream),
 * now-playing metadata access, and the per-app capture picker.
 * (AppEntry and the picker's search filter live in AppSearch.kt,
 * Android-free so the JVM suite can test them.)
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TuneScreen(
    eqGains: DoubleArray,
    eqPreamp: Double,
    onEqChange: (DoubleArray, Double) -> Unit,
    nowPlaying: NowPlayingInfo?,
    metaAccessGranted: Boolean,
    onGrantMetaAccess: () -> Unit,
    placeholderEnabled: Boolean,
    onTogglePlaceholder: (Boolean) -> Unit,
    apps: List<AppEntry>,
    selectedApps: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onCaptureAllApps: () -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeMode: (ThemeMode) -> Unit = {},
    hiRes: Boolean = false,
    onHiRes: (Boolean) -> Unit = {},
    autoReconnect: Boolean = true,
    onAutoReconnect: (Boolean) -> Unit = {},
    updateStatus: String = "",
    onCheckUpdates: () -> Unit = {},
) {
    // Per-app picker search (1.3.5): filters the list below by app
    // name or package name; filtering never changes the selection.
    var appQuery by remember { mutableStateOf("") }
    val shownApps = filterApps(apps, appQuery)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // --- EQ ------------------------------------------------------
        item {
            Text(
                "Equalizer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        for (preset in Eq.PRESETS) {
                            val selected = preset.preampDb == eqPreamp &&
                                preset.gains.indices.all { preset.gains[it] == eqGains[it] }
                            FilterChip(
                                selected = selected,
                                onClick = { onEqChange(preset.gains.copyOf(), preset.preampDb) },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    for (band in 0 until DspProcessor.BAND_COUNT) {
                        EqBandRow(
                            label = freqLabel(DspProcessor.BAND_FREQS[band]),
                            db = eqGains[band],
                            onChange = { db ->
                                val g = eqGains.copyOf()
                                g[band] = db
                                onEqChange(g, eqPreamp)
                            },
                        )
                    }
                    EqBandRow(
                        label = "Preamp",
                        db = eqPreamp,
                        onChange = { db -> onEqChange(eqGains.copyOf(), db) },
                    )
                    Text(
                        "Shapes the sound on every speaker, live. Applies to " +
                            "AirPlay, DLNA and Chromecast alike (it runs on the " +
                            "phone's captured audio).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Now playing ----------------------------------------------
        item {
            Text(
                "Now playing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (!metaAccessGranted) {
                        Text(
                            "Speakers can show the song title, artist and album " +
                                "art — Android asks you to allow notification " +
                                "access once so Airvia can read what's playing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onGrantMetaAccess) {
                            Text("Allow notification access")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The same access also lets Airvia take the " +
                                "volume buttons back automatically when " +
                                "another app's session goes quiet mid-cast. " +
                                "Without it, use \"Take volume control\" in " +
                                "the notification or speaker picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (nowPlaying != null) {
                        Text(
                            nowPlaying.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            listOf(nowPlaying.artist, nowPlaying.album)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                                .ifEmpty { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This is what your AirPlay speakers display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Nothing playing right now. Start music in any app " +
                                "and its title and artwork will appear on the speaker.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = placeholderEnabled,
                            onCheckedChange = { checked -> onTogglePlaceholder(checked) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Show Airvia as track info when an app provides none",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "The speaker shows the Airvia name and logo " +
                                    "instead of a blank player. Real track " +
                                    "info always replaces it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // --- Per-app capture -------------------------------------------
        item {
            Text(
                "Capture from",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedApps.isEmpty()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (selectedApps.isEmpty()) {
                                "All apps"
                            } else {
                                "${selectedApps.size} app(s) selected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (selectedApps.isEmpty()) {
                                "Every app's audio is cast."
                            } else {
                                "Only the ticked apps are cast. Takes effect " +
                                    "on the next cast."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedApps.isNotEmpty()) {
                        TextButton(onClick = onCaptureAllApps) { Text("Reset") }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                label = {
                    Text(
                        "Search apps",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (appQuery.isNotBlank() && shownApps.isEmpty()) {
            item {
                Text(
                    "No apps match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shownApps, key = { it.packageName }) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = app.packageName in selectedApps,
                    onCheckedChange = { checked -> onToggleApp(app.packageName, checked) },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // --- Appearance (1.3.0) -----------------------------------------
        item {
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (mode in ThemeMode.values()) {
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeMode(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                            ThemeMode.GLASS -> "Glass"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Glass is a frosted deep-navy look with translucent " +
                            "panels. On Android 12+ the glow behind the panels " +
                            "is blurred; on older versions it shows as a soft " +
                            "gradient.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Playback (1.3.0) --------------------------------------------
        item {
            Text(
                "Playback",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = hiRes,
                            onCheckedChange = { onHiRes(it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Hi-res 48 kHz mode",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Keeps the phone's native 48 kHz audio instead " +
                                    "of converting to 44.1 kHz for AirPlay 2. " +
                                    "AirPlay 1 (RAOP) always stays 44.1 kHz. A " +
                                    "speaker that refuses 48 kHz is retried at " +
                                    "44.1 kHz automatically. Applies to the " +
                                    "next cast.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = autoReconnect,
                            onCheckedChange = { onAutoReconnect(it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Auto-reconnect dropped speakers",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "If a speaker drops mid-cast (Wi-Fi hiccup, " +
                                    "speaker restart), Airvia retries it a few " +
                                    "times while the cast is running. A manual " +
                                    "stop never reconnects.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // --- Updates + automation (1.3.0) ----------------------------------
        item {
            Text(
                "Updates & automation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Check for updates",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                updateStatus.ifEmpty {
                                    "Stable releases from GitHub. Test builds " +
                                        "are never offered here."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onCheckUpdates) { Text("Check now") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tasker / MacroDroid: send a broadcast to " +
                            "com.opus.airvia.action.CAST_SPEAKER (extra " +
                            "\"speaker\" = name or IP), STOP_CAST, SET_VOLUME " +
                            "(extra \"volume\" 0-100) or STEP_VOLUME (extra " +
                            "\"delta\"). Casting still shows Android's " +
                            "capture-consent prompt unless a cast is already " +
                            "running — that's a platform rule. Any app on this " +
                            "phone can send these broadcasts; they can only " +
                            "start/stop casts and change speaker volume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EqBandRow(label: String, db: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(76.dp),
        )
        Slider(
            value = db.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = -12f..12f,
            steps = 23,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (db > 0) "+" else "") + "%.0f dB".format(db),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.width(52.dp),
        )
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
