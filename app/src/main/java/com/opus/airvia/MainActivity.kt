package com.opus.airvia

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.opus.airvia.dlna.Dlna
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

/**
 * Airvia — stream ALL your Android audio to AirPlay 2, DLNA/Sonos and
 * Chromecast speakers, several at once, each with its own volume.
 *
 * Compose/Material 3 UI over the multi-speaker cast engine: pick
 * speakers (or enter an IP), grant the one-time capture consent Android
 * requires, and every app's sound plays on them.
 */
class MainActivity : ComponentActivity() {
    companion object {
        private const val REQ_NOTIF = 43

        /** Intent extra (String): speaker name/IP to auto-cast to —
         *  set by the Tasker/MacroDroid receiver (1.3.0). */
        const val EXTRA_AUTO_CAST = "com.opus.airvia.AUTO_CAST"

        /** Splash plays once per process, not per activity recreate. */
        private var splashShownThisProcess = false
    }

    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var hiRes by mutableStateOf(false)
    private var autoReconnect by mutableStateOf(true)
    private var protocolPrefs by mutableStateOf<Map<String, ProtocolPref>>(emptyMap())
    private var pairings by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var optionsSpeaker by mutableStateOf<Speaker?>(null)
    private var optGains by mutableStateOf(DoubleArray(DspProcessor.BAND_COUNT))
    private var optPreamp by mutableStateOf(0.0)
    private var optDelayMs by mutableStateOf(0)
    private var optMaxVol by mutableStateOf(100)
    private var optGentle by mutableStateOf(true)
    private var doctorSpeaker by mutableStateOf<Speaker?>(null)
    private var doctorSteps by mutableStateOf<List<DoctorStep>>(emptyList())
    private var doctorRunning by mutableStateOf(false)
    private var updateOffer by mutableStateOf<UpdateCheck.ReleaseInfo?>(null)
    private var updateStatus by mutableStateOf("")
    private var showSplash by mutableStateOf(false)
    private var pendingAutoCast by mutableStateOf<String?>(null)
    private var autoCastDeadlineMs: Long = 0

    // Compose-observable UI state (written from engine/discovery callbacks).
    private var tab by mutableStateOf(0)
    private var airplaySpeakers by mutableStateOf<List<Speaker>>(emptyList())
    private var dlnaSpeakers by mutableStateOf<List<Speaker>>(emptyList())
    private var castSpeakers by mutableStateOf<List<Speaker>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var castState by mutableStateOf(CastEngine.State.IDLE)
    private var activeSpeakers by mutableStateOf<List<Speaker>>(emptyList())
    private var sessionStates by mutableStateOf<Map<String, CastEngine.State>>(emptyMap())
    private var sessionVolumes by mutableStateOf<Map<String, Int>>(emptyMap())
    private var sessionProtocols by mutableStateOf<Map<String, String>>(emptyMap())
    private var sessionMuted by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var castError by mutableStateOf<String?>(null)
    private var logLines by mutableStateOf<List<String>>(emptyList())
    private var hostInput by mutableStateOf("")
    private var portInput by mutableStateOf("7000")
    private var sleepMinutes by mutableStateOf(CastEngine.sleepMinutes)
    private var lastSpeaker by mutableStateOf<Speaker?>(null)
    private var eqGains by mutableStateOf(DoubleArray(DspProcessor.BAND_COUNT))
    private var eqPreamp by mutableStateOf(0.0)
    private var nowPlaying by mutableStateOf<NowPlayingInfo?>(null)
    private var metaAccessGranted by mutableStateOf(false)
    private var installedApps by mutableStateOf<List<AppEntry>>(emptyList())
    private var selectedApps by mutableStateOf<Set<String>>(emptySet())
    private var placeholderMeta by mutableStateOf(true)

    // HAP PIN prompt (driven by PinRequestBus from the engine thread).
    private var pinPromptSpeaker by mutableStateOf<String?>(null)
    private var pinText by mutableStateOf("")

    private var discovery: SpeakerDiscovery? = null
    private var pendingSpeaker: Speaker? = null

    /**
     * Zero-results re-check (1.3.1): ~6 s after a resume, if discovery
     * still shows no AirPlay speakers, re-kick the browse once — with
     * the discovery-level start retries this makes a cold first launch
     * behave like a warm one (issue #5).
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val zeroResultsRecheck = Runnable {
        if (scanning && airplaySpeakers.isEmpty()) {
            LogBus.log("[ui] no speakers found yet — re-browsing")
            discovery?.restartBrowse()
        }
    }
    private val dlnaScanRunning = AtomicBoolean(false)
    private val castScanRunning = AtomicBoolean(false)

    private val recordPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            requestCaptureConsent()
        } else {
            LogBus.log("[ui] microphone permission denied — needed by Android for audio capture")
        }
    }

    private val captureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val sp = pendingSpeaker
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null && sp != null) {
            startForegroundService(CastService.startIntent(this, result.resultCode, data, sp))
        } else {
            LogBus.log("[ui] capture consent denied — cannot cast without it")
        }
        pendingSpeaker = null
    }

    private val engineListener: (CastEngine.State) -> Unit = { syncFromEngine() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneSilencer.healIfStale(this)
        Prefs.loadEq(this)
        eqGains = Prefs.eqGains(this)
        eqPreamp = Prefs.eqPreamp(this)
        selectedApps = Prefs.capturePackages(this)
        placeholderMeta = Prefs.placeholderMetadata(this)
        themeMode = themeModeOf(this)
        hiRes = Prefs.hiRes48k(this)
        autoReconnect = Prefs.autoReconnect(this)
        handleAutoCastIntent(intent)
        showSplash = !splashShownThisProcess
        CastEngine.addListener(engineListener)
        discovery = SpeakerDiscovery(this) { found ->
            airplaySpeakers = found
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        loadInstalledApps()
        LogBus.log("Airvia ready — pick a speaker to cast all device audio")
        syncFromEngine()
        setContent {
            AirviaTheme(themeMode) {
                LaunchedEffect(Unit) {
                    maybeCheckUpdates(force = false)
                    while (true) {
                        syncFromEngine()
                        nowPlaying = NowPlaying.current
                        pinPromptSpeaker = PinRequestBus.pendingSpeaker
                        if (pinPromptSpeaker == null && pinText.isNotEmpty()) {
                            pinText = ""
                        }
                        if (tab == 2) logLines = LogBus.snapshot()
                        resolvePendingAutoCast()
                        refreshProtocolPrefs()
                        delay(400)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (themeMode == ThemeMode.GLASS) GlassBackdrop()
                    AirviaApp()
                    if (showSplash) {
                        SplashScreen(onDone = {
                            splashShownThisProcess = true
                            showSplash = false
                        })
                    }
                }
                PinPromptDialog()
                SpeakerOptionsDialog()
                DoctorDialog()
                UpdateDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutoCastIntent(intent)
    }

    private fun handleAutoCastIntent(intent: Intent?) {
        val q = intent?.getStringExtra(EXTRA_AUTO_CAST)?.trim()
        if (!q.isNullOrEmpty()) {
            pendingAutoCast = q
            autoCastDeadlineMs = System.currentTimeMillis() + 20_000
            LogBus.log("[ui] automation cast request: '$q'")
        }
    }

    /** Resolve a pending automation cast once discovery (or the
     *  remembered last speaker) can match the query. */
    private fun resolvePendingAutoCast() {
        val q = pendingAutoCast ?: return
        if (System.currentTimeMillis() > autoCastDeadlineMs) {
            LogBus.log("[ui] automation cast: '$q' not found in time")
            pendingAutoCast = null
            return
        }
        val all = airplaySpeakers + dlnaSpeakers + castSpeakers +
            listOfNotNull(lastSpeaker)
        val match = all.firstOrNull { AutomationIntents.matches(it, q) }
        if (match != null) {
            pendingAutoCast = null
            LogBus.log("[ui] automation cast -> '${match.name}'")
            beginCast(match)
        }
    }

    private fun refreshProtocolPrefs() {
        val all = airplaySpeakers + dlnaSpeakers + castSpeakers + activeSpeakers
        if (all.isEmpty()) return
        val m = HashMap<String, ProtocolPref>()
        val paired = HashMap<String, Boolean>()
        for (sp in all) {
            m[sp.key] = Prefs.protocolPref(this, sp)
            paired[sp.key] = sp.kind == SpeakerKind.AIRPLAY &&
                Prefs.credentialsFor(this, sp) != null
        }
        if (m != protocolPrefs) protocolPrefs = m
        if (paired != pairings) pairings = paired
    }

    // ------------------------------------------------------------------
    // Update checker (1.3.0): stable GitHub releases only; test
    // pre-releases are never offered (UpdateCheck owns the rule).
    // ------------------------------------------------------------------

    private fun maybeCheckUpdates(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.lastUpdateCheckMs(this) < 24 * 60 * 60 * 1000L) {
            return
        }
        Prefs.saveLastUpdateCheckMs(this, now)
        if (force) updateStatus = "Checking…"
        thread(name = "airvia-update-check", isDaemon = true) {
            try {
                val conn = URL(UpdateCheck.RELEASES_API)
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Airvia")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val json = conn.inputStream.bufferedReader().readText()
                val releases = UpdateCheck.parseReleases(json)
                val curTag = "v" + (try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) {
                    null
                } ?: "1.3.0")
                val offer = UpdateCheck.findUpdate(curTag, releases)
                runOnUiThread {
                    if (offer != null) {
                        updateOffer = offer
                        updateStatus = "${offer.tag} is available"
                    } else if (force) {
                        updateStatus = "You're on the latest stable version."
                    }
                }
            } catch (e: Exception) {
                if (force) {
                    runOnUiThread {
                        updateStatus = "Couldn't check (no connection?)"
                    }
                }
                LogBus.log("[ui] update check failed: ${e.message}")
            }
        }
    }

    @Composable
    private fun UpdateDialog() {
        val offer = updateOffer ?: return
        AlertDialog(
            onDismissRequest = { updateOffer = null },
            title = { Text("Update available") },
            text = {
                Text(
                    "${offer.name} is on GitHub. Airvia never installs " +
                        "anything itself — the release page has the APK.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse(offer.url),
                            ),
                        )
                    } catch (_: Exception) {
                    }
                    updateOffer = null
                }) { Text("Open release page") }
            },
            dismissButton = {
                TextButton(onClick = { updateOffer = null }) { Text("Later") }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        scanning = true
        lastSpeaker = Prefs.lastSpeaker(this)
        metaAccessGranted = NowPlaying.accessGranted(this)
        discovery?.start()
        mainHandler.removeCallbacks(zeroResultsRecheck)
        mainHandler.postDelayed(zeroResultsRecheck, 6000)
        runDlnaScan()
        runCastScan()
    }

    override fun onPause() {
        scanning = false
        mainHandler.removeCallbacks(zeroResultsRecheck)
        discovery?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        CastEngine.removeListener(engineListener)
        super.onDestroy()
    }

    private fun syncFromEngine() {
        castState = CastEngine.state
        castError = CastEngine.lastError
        sleepMinutes = CastEngine.sleepMinutes
        val sessions = CastEngine.snapshot()
        activeSpeakers = sessions.map { it.speaker }
        sessionStates = sessions.associate { it.speaker.key to it.state }
        sessionVolumes = sessions.associate { it.speaker.key to it.volumePct }
        sessionProtocols = sessions.associate { it.speaker.key to it.protocol }
        sessionMuted = sessions.associate { it.speaker.key to it.muted }
    }

    // ------------------------------------------------------------------
    // Extra discovery: DLNA (SSDP) + Chromecast (Cast SDK, if present)
    // ------------------------------------------------------------------

    private fun runDlnaScan() {
        if (!dlnaScanRunning.compareAndSet(false, true)) return
        thread(name = "airvia-dlna-scan", isDaemon = true) {
            try {
                val devices = Dlna.discover(4000)
                dlnaSpeakers = devices.map { dev ->
                    val loc = try {
                        URL(dev.location)
                    } catch (_: Exception) {
                        null
                    }
                    Speaker(
                        dev.name,
                        loc?.host ?: "",
                        loc?.port ?: 80,
                    ).apply {
                        kind = SpeakerKind.DLNA
                        dlna = dev
                    }
                }
                if (devices.isNotEmpty()) {
                    LogBus.log("[discover] DLNA: ${devices.joinToString { it.name }}")
                }
            } catch (e: Exception) {
                LogBus.log("[discover] DLNA scan failed: ${e.message}")
            } finally {
                dlnaScanRunning.set(false)
            }
        }
    }

    private fun runCastScan() {
        if (!castScanRunning.compareAndSet(false, true)) return
        thread(name = "airvia-cast-scan", isDaemon = true) {
            try {
                val cls = Class.forName("com.opus.airvia.cast.CastIntegration")
                val method = cls.getMethod("discover", Context::class.java)
                @Suppress("UNCHECKED_CAST")
                val found = method.invoke(null, this@MainActivity) as? List<Speaker>
                if (found != null) castSpeakers = found
            } catch (_: ClassNotFoundException) {
                // Cast SDK not in this build.
            } catch (e: Exception) {
                LogBus.log("[discover] Chromecast scan failed: ${e.message}")
            } finally {
                castScanRunning.set(false)
            }
        }
    }

    private fun loadInstalledApps() {
        thread(name = "airvia-apps", isDaemon = true) {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val pm = packageManager
                val infos = pm.queryIntentActivities(intent, 0)
                val seen = HashSet<String>()
                val apps = ArrayList<AppEntry>()
                for (info in infos) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName || !seen.add(pkg)) continue
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    apps.add(AppEntry(pkg, label))
                }
                apps.sortBy { it.label.lowercase() }
                installedApps = apps
            } catch (e: Exception) {
                LogBus.log("[ui] app list failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // UI shell
    // ------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AirviaApp() {
        Scaffold(
            containerColor = if (themeMode == ThemeMode.GLASS) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.background
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Airvia",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { SpeakerIcon() },
                        label = { Text("Cast") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = {
                            tab = 1
                            metaAccessGranted = NowPlaying.accessGranted(this@MainActivity)
                        },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Tune") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = {
                            tab = 2
                            logLines = LogBus.snapshot()
                        },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("Log") },
                    )
                }
            },
        ) { inner ->
            Box(Modifier.padding(inner)) {
                when (tab) {
                    0 -> CastScreen(
                        castState = castState,
                        activeSpeakers = activeSpeakers,
                        castError = castError,
                        sessionStates = sessionStates,
                        sessionVolumes = sessionVolumes,
                        sessionProtocols = sessionProtocols,
                        speakers = airplaySpeakers + dlnaSpeakers + castSpeakers,
                        scanning = scanning,
                        hostInput = hostInput,
                        portInput = portInput,
                        onHostChange = { hostInput = it },
                        onPortChange = { portInput = it },
                        onCast = { beginCast(it) },
                        onPairWithPin = { sp -> pairWithPin(sp) },
                        protocolPrefs = protocolPrefs,
                        pairings = pairings,
                        onProtocolPref = { sp, pref ->
                            Prefs.saveProtocolPref(this@MainActivity, sp, pref)
                            refreshProtocolPrefs()
                            LogBus.log(
                                "[ui] connection for '${sp.name}': $pref " +
                                    "(applies on next connect)",
                            )
                        },
                        onSpeakerOptions = { sp ->
                            val prof = Prefs.profileFor(this@MainActivity, sp)
                            optGains = prof.eqGains.copyOf()
                            optPreamp = prof.eqPreampDb
                            optDelayMs = prof.delayMs
                            optMaxVol = prof.maxVolumePct
                            optGentle = prof.gentleStart
                            optionsSpeaker = sp
                        },
                        onDoctor = { sp ->
                            doctorSteps = emptyList()
                            doctorSpeaker = sp
                        },
                        onStopSpeaker = { sp ->
                            CastEngine.stopSpeaker(sp)
                            syncFromEngine()
                        },
                        onStopAll = { stopCasting() },
                        onRescan = {
                            discovery?.stop()
                            discovery?.start()
                            runDlnaScan()
                            runCastScan()
                            LogBus.log("[ui] rescan")
                        },
                        onVolumeSpeaker = { sp, pct -> CastEngine.setVolume(sp, pct) },
                        mutedSpeakers = sessionMuted,
                        onMuteSpeaker = { sp ->
                            CastEngine.setMuted(sp, !CastEngine.isMuted(sp))
                            syncFromEngine()
                        },
                        sleepMinutes = sleepMinutes,
                        onSleepTimer = { CastEngine.setSleepTimer(it) },
                        lastSpeaker = lastSpeaker,
                    )
                    1 -> TuneScreen(
                        eqGains = eqGains,
                        eqPreamp = eqPreamp,
                        onEqChange = { gains, preamp ->
                            eqGains = gains
                            eqPreamp = preamp
                            Eq.apply(gains, preamp)
                            Prefs.saveEq(this@MainActivity, gains, preamp)
                        },
                        nowPlaying = nowPlaying,
                        metaAccessGranted = metaAccessGranted,
                        placeholderEnabled = placeholderMeta,
                        onTogglePlaceholder = { enabled ->
                            placeholderMeta = enabled
                            Prefs.savePlaceholderMetadata(this@MainActivity, enabled)
                            LogBus.log(
                                "[ui] placeholder track info " +
                                    if (enabled) "on" else "off",
                            )
                        },
                        onGrantMetaAccess = {
                            try {
                                startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                )
                            } catch (_: Exception) {
                            }
                        },
                        apps = installedApps,
                        selectedApps = selectedApps,
                        onToggleApp = { pkg, checked ->
                            val updated = selectedApps.toMutableSet()
                            if (checked) updated.add(pkg) else updated.remove(pkg)
                            selectedApps = updated
                            Prefs.saveCapturePackages(this@MainActivity, updated)
                        },
                        onCaptureAllApps = {
                            selectedApps = emptySet()
                            Prefs.saveCapturePackages(this@MainActivity, emptySet())
                        },
                        themeMode = themeMode,
                        onThemeMode = { mode ->
                            themeMode = mode
                            Prefs.saveThemeMode(this@MainActivity, mode.name)
                        },
                        hiRes = hiRes,
                        onHiRes = { enabled ->
                            hiRes = enabled
                            Prefs.saveHiRes48k(this@MainActivity, enabled)
                            LogBus.log(
                                "[ui] hi-res 48 kHz " +
                                    if (enabled) "on (next cast)" else "off",
                            )
                        },
                        autoReconnect = autoReconnect,
                        onAutoReconnect = { enabled ->
                            autoReconnect = enabled
                            Prefs.saveAutoReconnect(this@MainActivity, enabled)
                        },
                        updateStatus = updateStatus,
                        onCheckUpdates = { maybeCheckUpdates(force = true) },
                    )
                    else -> LogScreen(
                        lines = logLines,
                        onCopy = { copyLog() },
                        onShare = { shareLog() },
                        onClear = {
                            LogBus.clear()
                            logLines = emptyList()
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Speaker options (1.3.0): per-speaker EQ / delay / volume guard /
    // gentle start, persisted as a SpeakerProfile.
    // ------------------------------------------------------------------

    @Composable
    private fun SpeakerOptionsDialog() {
        val sp = optionsSpeaker ?: return
        AlertDialog(
            onDismissRequest = { optionsSpeaker = null },
            title = { Text("Speaker options — ${sp.name}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Sound for this speaker only. EQ and gentle start " +
                            "apply to AirPlay; delay trims AirPlay lip-sync; " +
                            "the volume cap applies everywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    for (band in 0 until DspProcessor.BAND_COUNT) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                when (band) {
                                    0 -> "60 Hz"
                                    1 -> "230 Hz"
                                    2 -> "910 Hz"
                                    3 -> "3.6 kHz"
                                    else -> "14 kHz"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.width(76.dp),
                            )
                            Slider(
                                value = optGains[band].toFloat(),
                                onValueChange = { v ->
                                    val g = optGains.copyOf()
                                    g[band] = v.toDouble()
                                    optGains = g
                                },
                                valueRange = -12f..12f,
                                steps = 23,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                (if (optGains[band] > 0) "+" else "") +
                                    "%.0f".format(optGains[band]),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "Delay",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.width(76.dp),
                        )
                        Slider(
                            value = optDelayMs.toFloat(),
                            onValueChange = { optDelayMs = it.toInt() },
                            valueRange = 0f..1000f,
                            steps = 99,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$optDelayMs ms",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.width(52.dp),
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "Max vol",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.width(76.dp),
                        )
                        Slider(
                            value = optMaxVol.toFloat(),
                            onValueChange = { optMaxVol = it.toInt() },
                            valueRange = 10f..100f,
                            steps = 17,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$optMaxVol%",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.width(52.dp),
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = optGentle,
                            onCheckedChange = { optGentle = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Gentle start (fade in over ~1 second)",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val profile = SpeakerProfile(
                        eqGains = optGains.copyOf(),
                        eqPreampDb = optPreamp,
                        delayMs = optDelayMs,
                        maxVolumePct = optMaxVol,
                        gentleStart = optGentle,
                    )
                    Prefs.saveProfile(this@MainActivity, sp, profile)
                    CastEngine.applyProfile(sp, profile)
                    LogBus.log("[ui] profile saved for '${sp.name}'")
                    optionsSpeaker = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { optionsSpeaker = null }) { Text("Cancel") }
            },
        )
    }

    // ------------------------------------------------------------------
    // Speaker Doctor (1.3.0): stepwise checks + L/R test tones.
    // ------------------------------------------------------------------

    @Composable
    private fun DoctorDialog() {
        val sp = doctorSpeaker ?: return
        LaunchedEffect(sp) {
            if (!doctorRunning && doctorSteps.isEmpty()) {
                doctorRunning = true
                thread(name = "airvia-doctor", isDaemon = true) {
                    val steps = SpeakerDoctor.run(sp)
                    runOnUiThread {
                        doctorSteps = steps
                        doctorRunning = false
                    }
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!doctorRunning) doctorSpeaker = null },
            title = { Text("Speaker Doctor — ${sp.name}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (doctorRunning) {
                        Text("Running checks… listen for a tone in the left, then the right channel.")
                    } else {
                        Text(DoctorReport.render(doctorSteps))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { shareLog() },
                    enabled = !doctorRunning,
                ) { Text("Share diagnostics") }
            },
            dismissButton = {
                TextButton(
                    onClick = { doctorSpeaker = null },
                    enabled = !doctorRunning,
                ) { Text("Close") }
            },
        )
    }

    // ------------------------------------------------------------------
    // HAP PIN prompt: the engine blocks on PinRequestBus while a
    // receiver (Apple TV, Mac) waits for its on-screen code to be
    // entered here. Shown over whatever tab is active.
    // ------------------------------------------------------------------

    @Composable
    private fun PinPromptDialog() {
        val speakerName = pinPromptSpeaker ?: return
        AlertDialog(
            onDismissRequest = { PinRequestBus.submit(null) },
            title = { Text("Enter the code on $speakerName") },
            text = {
                Column {
                    Text(
                        "$speakerName is showing a pairing code. Enter it " +
                            "here to pair — you only do this once; Airvia " +
                            "remembers the pairing.",
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { v ->
                            pinText = v.filter { it.isDigit() }.take(8)
                        },
                        label = { Text("Code") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { PinRequestBus.submit(pinText.ifBlank { null }) },
                    enabled = pinText.length >= 4,
                ) { Text("Pair") }
            },
            dismissButton = {
                TextButton(onClick = { PinRequestBus.submit(null) }) { Text("Cancel") }
            },
        )
    }

    // ------------------------------------------------------------------
    // Cast flow: (first cast) permission -> consent -> foreground service;
    // further speakers ride the already-running capture, no new consent.
    // ------------------------------------------------------------------

    private fun beginCast(sp: Speaker) {
        if (CastEngine.sessionFor(sp) != null) return
        if (CastEngine.hasLiveSource) {
            try {
                startService(CastService.startIntent(this, 0, null, sp))
            } catch (e: Exception) {
                LogBus.log("[ui] add speaker failed: ${e.message}")
            }
            return
        }
        pendingSpeaker = sp
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestCaptureConsent()
    }

    /**
     * Long-press "Pair with PIN": force the HAP PIN pairing flow for
     * this speaker on its next connect (for receivers that accept the
     * regular pairing silently but never play — e.g. a Mac).
     */
    private fun pairWithPin(sp: Speaker) {
        CastEngine.requestForcePin(sp)
        if (CastEngine.sessionFor(sp) != null) {
            CastEngine.stopSpeaker(sp)
        }
        LogBus.log("[ui] PIN pairing requested for '${sp.name}'")
        beginCast(sp)
    }

    private fun requestCaptureConsent() {
        val sp = pendingSpeaker ?: return
        LogBus.log("[ui] casting to '${sp.name}' — approve the capture prompt")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCasting() {
        try {
            startService(CastService.stopIntent(this))
        } catch (_: Exception) {
        }
        CastEngine.stopAll()
        syncFromEngine()
    }

    // ------------------------------------------------------------------
    // Volume keys in the foreground step every speaker together
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && CastEngine.isActive
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val delta = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 5 else -5
                CastEngine.stepAllVolumes(delta)
                syncFromEngine()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------
    // Log actions
    // ------------------------------------------------------------------

    private fun logText(): String = Diagnostics.bundle(this)

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("airvia-log", logText()))
        LogBus.log("[ui] log copied")
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText())
        }
        startActivity(Intent.createChooser(send, "Share Airvia diagnostics"))
    }
}
