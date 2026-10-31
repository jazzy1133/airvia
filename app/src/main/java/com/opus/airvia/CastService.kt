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
import android.os.Handler
import android.os.IBinder
import android.os.Looper

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

    private var projection: MediaProjection? = null
    private var capture: CaptureSource? = null
    private var broadcast: BroadcastCapture? = null
    private var streamServer: StreamServer? = null
    private var watcher: NowPlayingWatcher? = null
    private var shuttingDown = false

    private val engineListener: (CastEngine.State) -> Unit = {
        updateNotification()
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
        // The service can start without MainActivity (QS tile, restart), so
        // load the saved AirPlay protocol choice here too.
        CastEngine.airplayMode = Prefs.airplayMode(this)
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
            ?: throw IllegalStateException("MediaProjection unavailable")
        // Android 14 requires a registered callback before capture.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                LogBus.log("[service] projection stopped by system")
                shutdown()
            }
        }, Handler(Looper.getMainLooper()))
        projection = proj

        Prefs.loadEq(this)

        // Per-app capture selection (empty set = all apps).
        val selectedPackages = Prefs.capturePackages(this)
        val uids = selectedPackages.mapNotNull { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
                null
            }
        }
        val cap = CaptureSource(proj, uids)
        val hub = BroadcastCapture()
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
        CastEngine.streamUrlProvider = { server.streamUrl() }
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Airvia")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause, "Stop", stop,
                ).build(),
            )
            .setOngoing(true)
            .build()
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
