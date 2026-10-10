package com.opus.airvia.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.opus.airvia.CastEngine
import com.opus.airvia.LogBus
import com.opus.airvia.NowPlayingInfo
import com.opus.airvia.Speaker
import com.opus.airvia.SpeakerKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Chromecast support via the official Cast SDK, feeding the receiver
 * Airvia's local HTTP WAV stream through the Default Media Receiver.
 *
 * This class is referenced ONLY reflectively (from CastService and
 * MainActivity): if the Cast SDK dependencies are ever dropped from the
 * build, the rest of the app compiles and runs unchanged and
 * Chromecast speakers simply never appear.
 *
 * Adapted from Outro's device-proven ChromecastTransport (MediaRouter
 * discovery, route-select + session latch, RemoteMediaClient LOAD);
 * the media is Airvia's endless live stream instead of a track URL.
 *
 * Runtime behavior on a real Chromecast is NOT verified from the build
 * VM (no device route); every entry point fails soft with a log line.
 */
object CastIntegration {

    /**
     * Set once the Cast framework module turns out to be absent (phones
     * without Google Play services, e.g. LineageOS): discovery is polled
     * on a timer, and without this latch every poll re-threw and spammed
     * the log with a multi-line DynamiteModule stack message that reads
     * like a discovery failure even though AirPlay/DLNA are unaffected.
     */
    private val moduleMissing = AtomicBoolean(false)

    private fun isModuleMissing(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val n = cur.javaClass.name
            if (n.contains("ModuleUnavailable") || n.contains("DynamiteModule")) return true
            cur = cur.cause
        }
        return false
    }

    /** Note a missing Cast module once; returns true when [t] is that case. */
    private fun noteModuleMissing(t: Throwable): Boolean {
        if (!isModuleMissing(t)) return false
        if (moduleMissing.compareAndSet(false, true)) {
            LogBus.log(
                "[cast] Chromecast unavailable on this phone (no Google Play " +
                    "services) — AirPlay, Sonos and DLNA are unaffected",
            )
        }
        return true
    }

    /** Entry point for CastService's reflection registration. */
    @JvmStatic
    fun starter(context: Context): ((Speaker, String) -> CastEngine.LiveSession?)? {
        return try {
            // Fail fast here when Play services / Cast is unusable.
            CastContext.getSharedInstance(context.applicationContext)
            ({ speaker, streamUrl ->
                startSession(context.applicationContext, speaker, streamUrl)
            })
        } catch (t: Throwable) {
            if (!noteModuleMissing(t)) {
                LogBus.log("[cast] Cast SDK unavailable: ${t.message}")
            }
            null
        }
    }

    /** Entry point for MainActivity's reflection discovery. */
    @JvmStatic
    fun discover(context: Context): List<Speaker> {
        if (moduleMissing.get()) return emptyList()
        val app = context.applicationContext
        return try {
            discoverRoutes(app, 3500)
        } catch (t: Throwable) {
            if (!noteModuleMissing(t)) {
                LogBus.log("[cast] discovery failed: ${t.message}")
            }
            emptyList()
        }
    }

    // ------------------------------------------------------------------

    private fun discoverRoutes(app: Context, timeoutMs: Long): List<Speaker> {
        val main = Handler(Looper.getMainLooper())
        // CastContext.getSharedInstance() (and the MediaRouter singleton)
        // are main-thread-only APIs — acquiring them on the scan thread
        // threw "Must be called from the main thread" and discovery
        // silently returned nothing. Acquire both on main, then run the
        // same callback-based scan as before.
        val selectorRef = AtomicReference<MediaRouteSelector?>()
        val routerRef = AtomicReference<MediaRouter?>()
        val acquireError = AtomicReference<Throwable?>()
        runOnMainSync(main) {
            try {
                val castContext = CastContext.getSharedInstance(app)
                selectorRef.set(castContext.mergedSelector)
                routerRef.set(MediaRouter.getInstance(app))
            } catch (t: Throwable) {
                acquireError.set(t)
            }
        }
        acquireError.get()?.let { throw it }
        val selector = selectorRef.get()
            ?: throw IllegalStateException("Cast selector unavailable")
        val mediaRouter = routerRef.get()
            ?: throw IllegalStateException("MediaRouter unavailable")
        val found = linkedMapOf<String, Speaker>()
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                addRoute(route)
            }

            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                addRoute(route)
            }

            private fun addRoute(route: MediaRouter.RouteInfo) {
                try {
                    if (!route.isEnabled) return
                    val device = route.extras?.let {
                        com.google.android.gms.cast.CastDevice.getFromBundle(it)
                    } ?: return
                    val routeId = route.id ?: return
                    val name = route.name?.toString()?.takeIf { it.isNotBlank() }
                        ?: device.friendlyName ?: "Chromecast"
                    found.putIfAbsent(
                        routeId,
                        Speaker(name, "", 0).apply {
                            kind = SpeakerKind.CHROMECAST
                            castRouteId = routeId
                        },
                    )
                } catch (_: Exception) {
                }
            }
        }
        runOnMainSync(main) {
            mediaRouter.addCallback(
                selector, callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
            )
        }
        try {
            Thread.sleep(timeoutMs)
        } catch (_: InterruptedException) {
        } finally {
            runOnMainSync(main) { mediaRouter.removeCallback(callback) }
        }
        return found.values.toList()
    }

    private fun startSession(
        app: Context,
        speaker: Speaker,
        streamUrl: String,
    ): CastEngine.LiveSession? {
        return try {
            val session = ensureSession(app, speaker)
            val client = session.remoteMediaClient
                ?: throw IllegalStateException("No remote media client")
            val nowPlaying = com.opus.airvia.NowPlaying.current
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(
                    MediaMetadata.KEY_TITLE,
                    nowPlaying?.title?.takeIf { it.isNotBlank() } ?: "Airvia — device audio",
                )
                nowPlaying?.artist?.takeIf { it.isNotBlank() }?.let {
                    putString(MediaMetadata.KEY_ARTIST, it)
                }
            }
            val mediaInfo = MediaInfo.Builder(streamUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("audio/wav")
                .setMetadata(metadata)
                .build()
            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
            val latch = CountDownLatch(1)
            val err = AtomicReference<Exception?>()
            Handler(Looper.getMainLooper()).post {
                try {
                    client.load(request).setResultCallback { result ->
                        try {
                            if (!result.status.isSuccess) {
                                err.set(
                                    IllegalStateException(
                                        "Load failed: ${result.status.statusCode}",
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            err.set(e)
                        } finally {
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    err.set(e)
                    latch.countDown()
                }
            }
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out loading media")
            }
            err.get()?.let { throw it }
            LogBus.log("[cast] streaming to '${speaker.name}'")
            CastLiveSession(app)
        } catch (t: Throwable) {
            LogBus.log("[cast] start failed for '${speaker.name}': ${t.message}")
            null
        }
    }

    private class CastLiveSession(private val app: Context) : CastEngine.LiveSession {
        override fun setVolumePct(pct: Int) {
            try {
                val session = CastContext.getSharedInstance(app)
                    .sessionManager.currentCastSession
                session?.setVolume(pct.coerceIn(0, 100) / 100.0)
            } catch (_: Exception) {
            }
        }

        override fun setMetadata(info: NowPlayingInfo?) {
            // A metadata change would need a fresh LOAD (audible
            // restart of the stream); the title set at load stands.
        }

        override fun stop() {
            val main = Handler(Looper.getMainLooper())
            runOnMainSync(main) {
                try {
                    CastContext.getSharedInstance(app)
                        .sessionManager.endCurrentSession(true)
                } catch (_: Exception) {
                }
                try {
                    val router = MediaRouter.getInstance(app)
                    router.selectRoute(router.defaultRoute)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Select the route for [speaker] and wait for its session (Outro pattern). */
    private fun ensureSession(app: Context, speaker: Speaker): CastSession {
        val castContext = CastContext.getSharedInstance(app)
        castContext.sessionManager.currentCastSession?.let { return it }
        val routeId = speaker.castRouteId
            ?: throw IllegalStateException("Chromecast route unknown")
        val mediaRouter = MediaRouter.getInstance(app)
        val sessionRef = AtomicReference<CastSession?>()
        val latch = CountDownLatch(1)
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                sessionRef.set(session)
                latch.countDown()
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                latch.countDown()
            }

            override fun onSessionEnded(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                sessionRef.set(session)
                latch.countDown()
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                latch.countDown()
            }

            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        val sm = castContext.sessionManager
        val main = Handler(Looper.getMainLooper())
        runOnMainSync(main) {
            sm.addSessionManagerListener(listener, CastSession::class.java)
        }
        try {
            var selected = false
            runOnMainSync(main) {
                val route = mediaRouter.routes.firstOrNull { it.id == routeId }
                if (route != null) {
                    mediaRouter.selectRoute(route)
                    selected = true
                }
            }
            if (!selected) throw IllegalStateException("Chromecast route gone")
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out connecting to ${speaker.name}")
            }
            return sessionRef.get()
                ?: throw IllegalStateException("Couldn't start cast session")
        } finally {
            runOnMainSync(main) {
                try {
                    sm.removeSessionManagerListener(listener, CastSession::class.java)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun runOnMainSync(main: Handler, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
    }
}
