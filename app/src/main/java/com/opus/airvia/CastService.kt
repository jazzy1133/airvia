package com.opus.airvia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.RemoteViews

/**
 * Foreground service that owns the cast pipeline: the MediaProjection
 * (user consent, one per service run), the device-audio capture, the
 * broadcast hub, the HTTP stream server, and the now-playing watcher.
 *
 * [CastEngine] sessions are added on top of the running pipeline —
 * casting to a second speaker does not restart capture or need fresh
 * consent. The service lives while at least one session is active and
 * shuts down when the last one ends.
 */
class CastService : Service() {
    companion object {
        private const val ACTION_START = "com.opus.airvia.START"
        private const val ACTION_STOP = "com.opus.airvia.STOP"
        private const val ACTION_NOTIF_VOL = "com.opus.airvia.NOTIF_VOL"
        private const val ACTION_NOTIF_STOP = "com.opus.airvia.NOTIF_STOP"
        private const val ACTION_NOTIF_MUTE = "com.opus.airvia.NOTIF_MUTE"
        private const val ACTION_NOTIF_TAKE_VOL = "com.opus.airvia.NOTIF_TAKE_VOL"
        private const val EXTRA_SP_KEY = "sp_key"
        private const val EXTRA_DELTA = "delta"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "airvia_cast"

        fun startIntent(
            ctx: Context,
            resultCode: Int,
            data: Intent?,
            speaker: Speaker,
        ): Intent = Intent(ctx, CastService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            if (data != null) putExtra(EXTRA_DATA, data)
            // The speaker crosses into the service flattened to extras;
            // SpeakerWire owns the field set (see its doc: a field it
            // omits silently defaults on the far side).
            for ((key, value) in SpeakerWire.encode(speaker)) {
                when (value) {
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                }
            }
        }

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, CastService::class.java).apply { action = ACTION_STOP }

        fun speakerFromIntent(intent: Intent): Speaker? {
            val extras = intent.extras ?: return null
            val m = HashMap<String, Any?>()
            for (key in extras.keySet()) {
                @Suppress("DEPRECATION")
                m[key] = extras.get(key)
            }
            return SpeakerWire.decode(m)
        }
    }

    /** The app logo's exact PNG bytes (res/raw is stored verbatim in
     *  the APK — the file is a byte-identical copy of the launcher
     *  icon), used as placeholder artwork. Loaded once, lazily. */
    private val logoBytes: ByteArray? by lazy {
        try {
            // Looked up by name: this build pipeline generates the app R
            // class only after the Kotlin step, so sources never
            // reference R directly (no other file does).
            val id = resources.getIdentifier("airvia_logo", "raw", packageName)
            check(id != 0) { "raw/airvia_logo missing" }
            resources.openRawResource(id).use { it.readBytes() }
        } catch (e: Exception) {
            LogBus.log("[service] placeholder logo unavailable: ${e.message}")
            null
        }
    }

    private fun logoPngBytes(): ByteArray? = logoBytes

    private var projection: MediaProjection? = null
    private var capture: CaptureSource? = null
    private var broadcast: BroadcastCapture? = null
    private var streamServer: StreamServer? = null
    private var watcher: NowPlayingWatcher? = null
    private var shuttingDown = false

    private val engineListener: (CastEngine.State) -> Unit = {
        updateNotification()
        // Keep the remote-volume session the OEM stack's volume
        // target (issue #5) and the QS tile fresh on every change.
        VolumeKeys.refresh()
        try {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, CastTileService::class.java),
            )
        } catch (_: Exception) {
        }
        if (CastEngine.activeCount == 0 && projection != null) {
            LogBus.log("[service] last session ended — shutting down")
            shutdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Heal a phone volume a previous (killed) run left zeroed.
        PhoneSilencer.healIfStale(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                CastEngine.stopAll()
                shutdown()
            }
            ACTION_NOTIF_VOL -> {
                val key = intent.getStringExtra(EXTRA_SP_KEY)
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                val ss = CastEngine.snapshot().firstOrNull { it.speaker.key == key }
                if (ss != null && delta != 0) {
                    CastEngine.setVolume(ss.speaker, ss.volumePct + delta)
                    updateNotification()
                }
            }
            ACTION_NOTIF_STOP -> {
                val key = intent.getStringExtra(EXTRA_SP_KEY)
                val ss = CastEngine.snapshot().firstOrNull { it.speaker.key == key }
                if (ss != null) CastEngine.stopSpeaker(ss.speaker)
            }
            ACTION_NOTIF_MUTE -> {
                val key = intent.getStringExtra(EXTRA_SP_KEY)
                val ss = CastEngine.snapshot().firstOrNull { it.speaker.key == key }
                if (ss != null) {
                    CastEngine.setMuted(ss.speaker, !ss.muted)
                    updateNotification()
                }
            }
            ACTION_NOTIF_TAKE_VOL -> {
                // Manual volume re-claim (1.3.2): fresh session, same
                // stream — the stop-restart dance minus the restart.
                VolumeKeys.retake()
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val speaker = speakerFromIntent(intent) ?: run {
            LogBus.log("[service] start intent missing speaker")
            if (projection == null) shutdown()
            return
        }
        shuttingDown = false
        if (projection == null) {
            // First speaker of this run: bring the whole pipeline up.
            startForeground(
                NOTIF_ID,
                buildNotification("Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            try {
                setupPipeline(intent)
            } catch (e: Exception) {
                LogBus.log("[service] start failed: ${e.message}")
                shutdown()
                return
            }
        }
        // Per-speaker memory: last speaker (AirPlay reconnect card).
        if (speaker.kind == SpeakerKind.AIRPLAY) {
            Prefs.saveLastSpeaker(this, speaker)
        }
        CastEngine.startSpeaker(speaker)
        updateNotification()
    }

    /** Projection + capture + broadcast + stream server + watcher. */
    private fun setupPipeline(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            ?: throw IllegalStateException("missing capture consent data")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data)
        // Android 14 requires a registered callback before capture.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                LogBus.log("[service] projection stopped by system")
                shutdown()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        Prefs.loadEq(this)
        val pipelineRate = if (Prefs.hiRes48k(this)) 48000 else 44100
        Eq.useRate(pipelineRate)
        if (pipelineRate != 44100) {
            LogBus.log("[service] hi-res mode: pipeline at 48 kHz")
        }

        // Per-app capture selection (empty set = all apps).
        val selectedPackages = Prefs.capturePackages(this)
        val uids = selectedPackages.mapNotNull { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
                null
            }
        }
        val cap = CaptureSource(proj, uids, pipelineRate)
        val hub = BroadcastCapture(pipelineRate)
        cap.onSamples = { samples -> hub.push(samples) }
        cap.start()
        capture = cap
        broadcast = hub

        val server = StreamServer(hub)
        server.start()
        streamServer = server

        VolumeKeys.init(this)
        VolumeKeys.stopHandler = {
            CastEngine.stopAll()
            shutdown()
        }
        VolumeKeys.setCasting(true)

        // Silence the phone itself while casting (capture is pre-fader,
        // so the speaker keeps full level). Released in teardownCast.
        PhoneSilencer.engage(this)

        CastEngine.volumeSaver = { sp, pct -> Prefs.saveVolume(this, sp, pct) }
        CastEngine.volumeLoader = { sp -> Prefs.volumeFor(this, sp) }
        // 1.3.2: per-speaker mute persistence.
        CastEngine.muteSaver = { sp, state -> Prefs.saveMuteState(this, sp, state) }
        CastEngine.muteLoader = { sp -> Prefs.muteStateFor(this, sp) }
        CastEngine.streamUrlProvider = { server.streamUrl() }
        // HAP persistent pairing (PIN pairing): identity + credentials
        // from Prefs, PIN entry bridged to the activity's dialog, and
        // the placeholder track info for metadata-less sessions.
        CastEngine.identityProvider = { Prefs.controllerIdentity(this) }
        CastEngine.credentialsLoader = { sp -> Prefs.credentialsFor(this, sp) }
        CastEngine.credentialsSaver = { sp, creds -> Prefs.saveCredentials(this, sp, creds) }
        CastEngine.pinProvider = { sp -> PinRequestBus.requestPin(sp.name) }
        // 1.3.0: profiles, routing memory, auto-reconnect.
        CastEngine.profileLoader = { sp -> Prefs.profileFor(this, sp) }
        CastEngine.protocolPrefLoader = { sp -> Prefs.protocolPref(this, sp) }
        CastEngine.learnedLoader = { sp -> Prefs.learnedProtocol(this, sp) }
        CastEngine.learnedSaver = { sp, proto ->
            Prefs.saveLearnedProtocol(this, sp, proto)
        }
        CastEngine.reconnectEnabled = { Prefs.autoReconnect(this) }
        registerNetworkCallback()
        CastEngine.placeholderProvider = {
            if (Prefs.placeholderMetadata(this)) {
                CastEngine.PlaceholderTrack(
                    Dmap.PLACEHOLDER_TITLE, Dmap.PLACEHOLDER_ARTIST, logoPngBytes(),
                )
            } else {
                null
            }
        }
        CastEngine.placeholderRefusedLoader = { sp ->
            Prefs.placeholderRefused(this, sp)
        }
        CastEngine.placeholderRefusedSaver = { sp ->
            Prefs.savePlaceholderRefused(this, sp)
        }
        registerChromecastStarter()
        CastEngine.addListener(engineListener)
        CastEngine.attachSource(hub)

        watcher = NowPlayingWatcher(this).also { it.start() }
    }

    /**
     * Chromecast hook: present only when the Cast SDK made it into the
     * build (see cast/CastIntegration). Reflection keeps the service
     * compiling and running identically when it is absent.
     */
    private fun registerChromecastStarter() {
        try {
            val cls = Class.forName("com.opus.airvia.cast.CastIntegration")
            val method = cls.getMethod(
                "starter", Context::class.java,
            )
            @Suppress("UNCHECKED_CAST")
            CastEngine.chromecastStarter =
                method.invoke(null, this) as? (Speaker, String) -> CastEngine.LiveSession?
            if (CastEngine.chromecastStarter != null) {
                LogBus.log("[service] Chromecast support registered")
            }
        } catch (_: ClassNotFoundException) {
            // Cast SDK not in this build — Chromecast speakers stay hidden.
        } catch (e: Exception) {
            LogBus.log("[service] Chromecast unavailable: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val picker = PendingIntent.getActivity(
            this, 2,
            Intent(this, PickerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Manual volume re-claim (1.3.2): fresh session, stream untouched.
        val takeVol = PendingIntent.getService(
            this, 3,
            Intent(this, CastService::class.java).apply {
                action = ACTION_NOTIF_TAKE_VOL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Airvia")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Stop", stop,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_play, "Speakers", picker,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_lock_silent_mode_off, "Take volume", takeVol,
                ).build(),
            )
            .setOngoing(true)
        // Expanded view: per-speaker volume/stop rows (1.3.0). Android
        // gives apps no long-press callback on their own notification,
        // so the expanded view + the Speakers action (picker sheet)
        // are the faithful mechanisms — see research report §3.
        val big = buildSpeakersView()
        if (big != null) {
            builder.setCustomBigContentView(big)
            builder.setStyle(Notification.DecoratedCustomViewStyle())
        }
        return builder.build()
    }

    private fun resId(name: String, type: String): Int =
        resources.getIdentifier(name, type, packageName)

    /** RemoteViews with up to 3 live-session rows (name, −, %, +, stop). */
    private fun buildSpeakersView(): RemoteViews? {
        val layoutId = resId("notification_speakers", "layout")
        if (layoutId == 0) return null
        val sessions = CastEngine.snapshot()
        if (sessions.isEmpty()) return null
        val rv = RemoteViews(packageName, layoutId)
        for (row in 1..3) {
            val rowId = resId("notif_row$row", "id")
            if (row - 1 >= sessions.size) {
                rv.setViewVisibility(rowId, View.GONE)
                continue
            }
            val ss = sessions[row - 1]
            rv.setViewVisibility(rowId, View.VISIBLE)
            rv.setTextViewText(
                resId("notif_name$row", "id"),
                "${ss.speaker.name} · ${ss.protocol}",
            )
            rv.setTextViewText(
                resId("notif_pct$row", "id"),
                "${ss.volumePct}%",
            )
            fun svcIntent(action: String, delta: Int): PendingIntent {
                val intent = Intent(this, CastService::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_SP_KEY, ss.speaker.key)
                    putExtra(EXTRA_DELTA, delta)
                }
                return PendingIntent.getService(
                    this,
                    100 + row * 10 + (if (action == ACTION_NOTIF_STOP) 3 else delta / 5 + 1),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE,
                )
            }
            rv.setOnClickPendingIntent(
                resId("notif_down$row", "id"),
                svcIntent(ACTION_NOTIF_VOL, -5),
            )
            rv.setOnClickPendingIntent(
                resId("notif_up$row", "id"),
                svcIntent(ACTION_NOTIF_VOL, 5),
            )
            // One-tap mute (1.3.2): silences this speaker only; the %
            // readout already shows the sent level (0% while muted).
            rv.setTextViewText(
                resId("notif_mute$row", "id"),
                if (ss.muted) "Unmute" else "Mute",
            )
            val muteIntent = Intent(this, CastService::class.java).apply {
                action = ACTION_NOTIF_MUTE
                putExtra(EXTRA_SP_KEY, ss.speaker.key)
            }
            rv.setOnClickPendingIntent(
                resId("notif_mute$row", "id"),
                PendingIntent.getService(
                    this,
                    100 + row * 10 + 7,
                    muteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            rv.setOnClickPendingIntent(
                resId("notif_stop$row", "id"),
                svcIntent(ACTION_NOTIF_STOP, 0),
            )
        }
        val moreId = resId("notif_more", "id")
        if (sessions.size > 3) {
            rv.setViewVisibility(moreId, View.VISIBLE)
            rv.setTextViewText(
                moreId,
                "+${sessions.size - 3} more — tap Speakers for all",
            )
        } else {
            rv.setViewVisibility(moreId, View.GONE)
        }
        return rv
    }

    // --- network-change assist for auto-reconnect -----------------------

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogBus.log("[service] network available — nudging reconnects")
                CastEngine.kickReconnects()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            LogBus.log("[service] network callback unavailable: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun updateNotification() {
        if (projection == null) return
        val sessions = CastEngine.snapshot()
        val text = when {
            sessions.isEmpty() -> "Casting"
            sessions.size == 1 -> {
                val ss = sessions[0]
                val verb = if (ss.state == CastEngine.State.STREAMING) {
                    "Streaming all device audio"
                } else {
                    "Connecting…"
                }
                "${ss.speaker.name} — $verb"
            }
            else -> "Casting to ${sessions.size} speakers: " +
                sessions.joinToString(", ") { it.speaker.name }
        }
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun teardownCast() {
        try {
            CastEngine.removeListener(engineListener)
        } catch (_: Exception) {
        }
        CastEngine.stopAll()
        CastEngine.detachSource()
        CastEngine.chromecastStarter = null
        CastEngine.streamUrlProvider = null
        CastEngine.identityProvider = null
        CastEngine.credentialsLoader = null
        CastEngine.credentialsSaver = null
        CastEngine.pinProvider = null
        CastEngine.placeholderProvider = null
        CastEngine.placeholderRefusedLoader = null
        CastEngine.placeholderRefusedSaver = null
        CastEngine.muteSaver = null
        CastEngine.muteLoader = null
        CastEngine.profileLoader = null
        CastEngine.protocolPrefLoader = null
        CastEngine.learnedLoader = null
        CastEngine.learnedSaver = null
        CastEngine.reconnectEnabled = null
        unregisterNetworkCallback()
        try {
            watcher?.stop()
        } catch (_: Exception) {
        }
        watcher = null
        try {
            streamServer?.stop()
        } catch (_: Exception) {
        }
        streamServer = null
        try {
            capture?.stop()
        } catch (_: Exception) {
        }
        capture = null
        try {
            broadcast?.finish()
        } catch (_: Exception) {
        }
        broadcast = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        PhoneSilencer.release(this)
        VolumeKeys.stopHandler = null
        VolumeKeys.setCasting(false)
    }

    @Synchronized
    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        teardownCast()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
