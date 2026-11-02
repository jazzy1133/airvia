package com.opus.airvia

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Routes the hardware volume buttons to the casting speaker from
 * anywhere (lock screen, other apps, screen off) while a cast session
 * is active.
 *
 * Android sends volume keys to the active media session whose playback
 * is "remote" ([MediaSession.setPlaybackToRemote] with a
 * [VolumeProvider]). Airvia's playback lives in a foreground service,
 * so while casting we activate this small dedicated session with an
 * absolute VolumeProvider (0..100) whose callbacks feed [CastEngine]'s
 * volume. Inactive whenever nothing is casting, so the buttons control
 * the phone again as soon as the cast stops. (Same mechanism Outro
 * uses.)
 */
object VolumeKeys {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var provider: VolumeProvider? = null

    /** Set by CastService: media-button stop/pause lands here. */
    @Volatile
    var stopHandler: (() -> Unit)? = null

    fun init(context: Context) {
        if (session != null) return
        val app = context.applicationContext
        appContext = app
        main.post {
            if (session != null) return@post
            createSession(app)
        }
    }

    /** Build the volume session + provider. Main thread only. */
    private fun createSession(app: Context) {
        val vp = object : VolumeProvider(
            VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
            100,
            CastEngine.volumePct,
        ) {
            override fun onSetVolumeTo(volume: Int) {
                CastEngine.setAllVolumes(volume)
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) {
                    CastEngine.stepAllVolumes(direction * 5)
                }
            }
        }
        val s = MediaSession(app, "airvia-cast-volume")
        @Suppress("DEPRECATION")
        s.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        s.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                stopHandler?.invoke()
            }

            override fun onStop() {
                stopHandler?.invoke()
            }
        })
        provider = vp
        session = s
    }

    /**
     * Manual re-claim (1.3.2, issue #5): replace the volume session
     * with a FRESH registration — same provider callbacks, same
     * volume — without touching the stream. A fresh registration
     * resets the session's creation recency, the strongest claim an
     * app can make short of being the foreground app; the heartbeat
     * then keeps it fresh. This is the stop-restart dance minus the
     * restart. No-op when nothing is casting.
     */
    fun retake() {
        if (!casting) return
        main.post {
            if (!casting) return@post
            val appSession = session
            try {
                appSession?.isActive = false
            } catch (_: Exception) {
            }
            try {
                appSession?.release()
            } catch (_: Exception) {
            }
            // createSession needs an application Context; recover it
            // from the old session's context via the stored app ref.
            val app = appContext ?: return@post
            createSession(app)
            assertRemote()
            LogBus.log("[volume] volume control re-taken (fresh session)")
        }
    }

    /** Application context kept for [retake]; set by [init]. */
    @Volatile
    private var appContext: Context? = null

    /** Re-claim tracker (1.3.2): fires only on the falling edge of
     *  foreign playback while casting (see SessionReclaim). */
    private val reclaimTracker = SessionReclaim.Tracker()

    /**
     * Called by the media-session watcher on every active-session
     * change: whether any non-Airvia session is currently PLAYING.
     * Re-asserts the volume session exactly when a foreign session
     * goes quiet mid-cast. Inert while nothing is casting (the
     * tracker resets) and harmless without notification access (the
     * watcher never calls this then).
     */
    fun onForeignSessionsChanged(foreignPlaying: Boolean) {
        if (!casting) {
            reclaimTracker.update(false, foreignPlaying)
            return
        }
        if (reclaimTracker.update(true, foreignPlaying)) {
            LogBus.log("[volume] re-claimed keys (other app's session went quiet)")
            refresh()
        }
    }

    @Volatile
    private var casting: Boolean = false

    /**
     * Heartbeat re-assertion (1.3.1, issue #5): the system re-picks
     * the default volume session continuously, and state RECENCY is
     * one of its ranking inputs. A long cast has no engine transitions
     * for minutes at a time, so a foreground source app's session —
     * actively playing, timestamps fresh — out-ranks ours on HyperOS
     * and the volume keys leak to the phone stream (which
     * PhoneSilencer then swallows). Re-publishing identical state
     * with a fresh timestamp every few seconds keeps our claim as
     * strong as the platform lets an app make it. On OEMs where the
     * session already wins, this changes nothing observable.
     */
    private const val HEARTBEAT_MS = 4_000L

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!casting) return
            assertRemote()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** Activate/deactivate remote-volume routing with the cast state. */
    fun setCasting(active: Boolean) {
        val was = casting
        casting = active
        if (active && !was) {
            main.removeCallbacks(heartbeat)
            main.postDelayed(heartbeat, HEARTBEAT_MS)
        } else if (!active) {
            main.removeCallbacks(heartbeat)
        }
        main.post {
            val s = session ?: return@post
            val vp = provider ?: return@post
            try {
                if (active) {
                    vp.setCurrentVolume(CastEngine.volumePct)
                    s.setPlaybackToRemote(vp)
                    s.isActive = true
                    publishPlaying(s)
                } else {
                    publishStopped(s)
                    s.isActive = false
                    s.setPlaybackToLocal(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /** One re-assertion pass (shared by [refresh] and the heartbeat). */
    private fun assertRemote() {
        val s = session ?: return
        val vp = provider ?: return
        try {
            vp.setCurrentVolume(CastEngine.volumePct)
            s.setPlaybackToRemote(vp)
            s.isActive = true
            publishPlaying(s)
        } catch (_: Exception) {
        }
    }

    /**
     * Re-assert the remote-volume session (1.3.0, issue #5): some OEM
     * session stacks — Xiaomi HyperOS/MIUI in particular — rank
     * sessions by playback state, and a session that never published a
     * PlaybackState loses volume-key routing to the phone stream (whose
     * changes PhoneSilencer then swallows, so the buttons appear
     * dead). Publishing STATE_PLAYING + metadata and re-asserting on
     * every engine transition keeps this session the stack's volume
     * target. On OEMs where routing already worked (e.g. ColorOS) this
     * is a no-op addition.
     */
    fun refresh() {
        if (!casting) return
        main.post { assertRemote() }
    }

    private fun publishPlaying(s: MediaSession) {
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE,
                )
                .build(),
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Airvia")
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    "Casting device audio",
                )
                .build(),
        )
    }

    private fun publishStopped(s: MediaSession) {
        try {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0.0f)
                    .build(),
            )
        } catch (_: Exception) {
        }
    }

    /** Keep the provider's notion of the current volume in sync. */
    fun updateVolume(percent: Int) {
        try {
            provider?.setCurrentVolume(percent.coerceIn(0, 100))
        } catch (_: Exception) {
        }
    }
}
