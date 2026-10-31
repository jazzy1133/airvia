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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.opus.airvia.dlna.Dlna
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
    }

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
    private var castError by mutableStateOf<String?>(null)
    private var logLines by mutableStateOf<List<String>>(emptyList())
    private var hostInput by mutableStateOf("")
    private var portInput by mutableStateOf("7000")
    private var sleepMinutes by mutableStateOf(CastEngine.sleepMinutes)
    private var lastSpeaker by mutableStateOf<Speaker?>(null)
    private var airplayMode by mutableStateOf(AirPlayMode.AUTO)
    private var eqGains by mutableStateOf(DoubleArray(DspProcessor.BAND_COUNT))
    private var eqPreamp by mutableStateOf(0.0)
    private var nowPlaying by mutableStateOf<NowPlayingInfo?>(null)
    private var metaAccessGranted by mutableStateOf(false)
    private var installedApps by mutableStateOf<List<AppEntry>>(emptyList())
    private var selectedApps by mutableStateOf<Set<String>>(emptySet())

    private var discovery: SpeakerDiscovery? = null
    private var pendingSpeaker: Speaker? = null
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
        airplayMode = Prefs.airplayMode(this)
        CastEngine.airplayMode = airplayMode
        eqGains = Prefs.eqGains(this)
        eqPreamp = Prefs.eqPreamp(this)
        selectedApps = Prefs.capturePackages(this)
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
            AirviaTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        syncFromEngine()
                        nowPlaying = NowPlaying.current
                        if (tab == 2) logLines = LogBus.snapshot()
                        delay(400)
                    }
                }
                AirviaApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanning = true
        lastSpeaker = Prefs.lastSpeaker(this)
        metaAccessGranted = NowPlaying.accessGranted(this)
        discovery?.start()
        runDlnaScan()
        runCastScan()
    }

    override fun onPause() {
        scanning = false
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
                        sleepMinutes = sleepMinutes,
                        onSleepTimer = { CastEngine.setSleepTimer(it) },
                        lastSpeaker = lastSpeaker,
                        airplayMode = airplayMode,
                        onAirplayMode = {
                            airplayMode = it
                            CastEngine.airplayMode = it
                            Prefs.saveAirplayMode(this@MainActivity, it)
                            LogBus.log("[ui] AirPlay mode: ${it.label}")
                        },
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

    private fun logText(): String = LogBus.snapshot().joinToString("\n")

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
        startActivity(Intent.createChooser(send, "Share Airvia log"))
    }
}
