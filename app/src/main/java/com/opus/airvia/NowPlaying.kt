package com.opus.airvia

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The phone's current now-playing info, watched from the system's media
 * sessions and pushed to the AirPlay receivers as DMAP metadata.
 *
 * Reading media sessions requires notification-listener access (the
 * [NowPlayingListenerService] component must be enabled by the user);
 * without it everything here stays empty and casting works as before —
 * metadata is strictly best-effort.
 */
data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String,
    val artworkJpeg: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? NowPlayingInfo ?: return false
        return title == o.title && artist == o.artist && album == o.album &&
            artworkJpeg.contentEquals(o.artworkJpeg)
    }

    override fun hashCode(): Int {
        var h = title.hashCode()
        h = 31 * h + artist.hashCode()
        h = 31 * h + album.hashCode()
        h = 31 * h + (artworkJpeg?.contentHashCode() ?: 0)
        return h
    }
}

object NowPlaying {
    @Volatile
    var current: NowPlayingInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<(NowPlayingInfo?) -> Unit>()

    fun addListener(l: (NowPlayingInfo?) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (NowPlayingInfo?) -> Unit) {
        listeners.remove(l)
    }

    fun update(info: NowPlayingInfo?) {
        if (info == current) return
        current = info
        for (l in listeners) {
            try {
                l(info)
            } catch (_: Exception) {
            }
        }
    }

    /** True when the user has enabled our notification listener. */
    fun accessGranted(ctx: Context): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners",
            )
        } catch (_: Exception) {
            null
        } ?: return false
        return enabled.split(':').any {
            it.startsWith(ctx.packageName + "/")
        }
    }
}

/** Declared in the manifest; existing is what grants media-session read. */
class NowPlayingListenerService :
    android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() {
        LogBus.log("[meta] notification listener connected")
    }
}

/**
 * Watches [MediaSessionManager] active sessions and keeps [NowPlaying]
 * current. Started by CastService; safe to start without listener
 * access (it just logs once and stays idle).
 */
class NowPlayingWatcher(context: Context) {
    private val app = context.applicationContext
    private val component = ComponentName(app, NowPlayingListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val compressExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airvia-meta").apply { isDaemon = true }
    }

    private var msm: MediaSessionManager? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    @Volatile
    private var started = false
    @Volatile
    private var lastArtworkId = -1

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val list = controllers ?: emptyList()
            refresh(list)
            // 1.3.2 volume re-claim: the same list change that feeds
            // metadata also tells us when another app's session goes
            // quiet mid-cast (see SessionReclaim).
            reclaimCheck(list)
        }

    /**
     * Volume-key re-claim evaluation (1.3.2, issue #5): is any
     * NON-Airvia session currently PLAYING? The answer feeds
     * VolumeKeys' falling-edge tracker, which re-asserts our session
     * exactly when a foreign session goes quiet while casting. Dead
     * controllers throw on access and are treated as not playing.
     */
    private fun reclaimCheck(controllers: List<MediaController>) {
        var foreignPlaying = false
        for (c in controllers) {
            try {
                if (c.packageName != app.packageName &&
                    c.playbackState?.state == PlaybackState.STATE_PLAYING
                ) {
                    foreignPlaying = true
                    break
                }
            } catch (_: Exception) {
            }
        }
        try {
            VolumeKeys.onForeignSessionsChanged(foreignPlaying)
        } catch (_: Exception) {
        }
    }

    /** Same check over the currently tracked controllers (state changes). */
    private fun reclaimCheckFromCallbacks() {
        val controllers = synchronized(callbacks) { callbacks.keys.toList() }
        reclaimCheck(controllers)
    }

    fun start() {
        if (started) return
        started = true
        try {
            val mgr = app.getSystemService(MediaSessionManager::class.java)
            msm = mgr
            mgr.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            refresh(mgr.getActiveSessions(component))
            LogBus.log("[meta] media-session watcher live")
        } catch (e: SecurityException) {
            LogBus.log("[meta] no notification access — now-playing metadata off")
        } catch (e: Exception) {
            LogBus.log("[meta] watcher failed: ${e.message}")
        }
    }

    fun stop() {
        started = false
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        synchronized(callbacks) {
            for ((controller, cb) in callbacks) {
                try {
                    controller.unregisterCallback(cb)
                } catch (_: Exception) {
                }
            }
            callbacks.clear()
        }
        NowPlaying.update(null)
    }

    private fun refresh(controllers: List<MediaController>) {
        synchronized(callbacks) {
            val wanted = controllers.toSet()
            val iter = callbacks.entries.iterator()
            while (iter.hasNext()) {
                val (controller, cb) = iter.next()
                if (controller !in wanted) {
                    try {
                        controller.unregisterCallback(cb)
                    } catch (_: Exception) {
                    }
                    iter.remove()
                }
            }
            for (controller in controllers) {
                if (controller !in callbacks) {
                    val cb = object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            evaluate()
                        }

                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            evaluate()
                            // A state change is exactly when a foreign
                            // session may have gone quiet mid-cast.
                            reclaimCheckFromCallbacks()
                        }

                        override fun onSessionDestroyed() {
                            evaluate()
                        }
                    }
                    callbacks[controller] = cb
                    try {
                        controller.registerCallback(cb, main)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        evaluate()
    }

    /** Pick the most relevant session and publish its metadata. */
    private fun evaluate() {
        val controllers = synchronized(callbacks) { callbacks.keys.toList() }
        var best: MediaController? = null
        var bestScore = -1
        for (c in controllers) {
            val md = try {
                c.metadata
            } catch (_: Exception) {
                null
            } ?: continue
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (title.isNullOrBlank()) continue
            val playing = try {
                c.playbackState?.state == PlaybackState.STATE_PLAYING
            } catch (_: Exception) {
                false
            }
            val score = if (playing) 2 else 1
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        val controller = best
        if (controller == null) {
            NowPlaying.update(null)
            return
        }
        val md = controller.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artId = art?.generationId ?: -1
        if (art != null && artId != lastArtworkId) {
            lastArtworkId = artId
            val snapshot = try {
                Bitmap.createBitmap(art)
            } catch (_: Exception) {
                null
            }
            if (snapshot != null) {
                compressExecutor.execute {
                    val jpeg = compressJpeg(snapshot)
                    val cur = NowPlaying.current
                    // Publish artwork with the track it belongs to.
                    if (cur != null && cur.title == title) {
                        NowPlaying.update(cur.copy(artworkJpeg = jpeg))
                    }
                }
            }
        }
        NowPlaying.update(
            NowPlayingInfo(
                title = title,
                artist = artist,
                album = album,
                artworkJpeg = if (artId == lastArtworkId) {
                    NowPlaying.current?.takeIf {
                        it.title == title && it.artist == artist
                    }?.artworkJpeg
                } else {
                    null
                },
            ),
        )
    }

    private fun compressJpeg(bmp: Bitmap): ByteArray? {
        return try {
            val maxDim = 512
            val scaled = if (maxOf(bmp.width, bmp.height) > maxDim) {
                val scale = maxDim.toDouble() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }
}
