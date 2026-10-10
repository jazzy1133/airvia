package com.opus.airvia

import com.opus.airvia.ap2.Ap2AudioSession
import com.opus.airvia.ap2.Ap2Pairing
import com.opus.airvia.dlna.Dlna
import com.opus.airvia.raop.AlacEncoder
import com.opus.airvia.raop.RaopConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Multi-speaker cast engine.
 *
 * One shared [BroadcastCapture] (device audio) feeds any number of
 * concurrent speaker sessions, each with its own volume:
 *  - AirPlay speakers: AirPlay 2 first (transient pairing + encrypted
 *    ALAC), automatic fallback to classic AirPlay 1 / RAOP when the AP2
 *    handshake fails before streaming starts (Outro's proven pattern).
 *  - DLNA/Sonos renderers: pointed at the local HTTP WAV stream.
 *  - Chromecast: handed to a starter the service registers (Cast SDK).
 *
 * Aggregate state accessors ([state], [speaker], [volumePct]) describe
 * the "primary" (first active) session for the notification, QS tile
 * and legacy UI paths; per-speaker state lives in [SpeakerSession].
 */
object CastEngine {
    enum class State { IDLE, CONNECTING, STREAMING, ERROR }

    private const val SILENCE_STOP_MS = 300_000L

    /** A running sender session for one speaker. */
    interface LiveSession {
        fun setVolumePct(pct: Int)
        fun setMetadata(info: NowPlayingInfo?)
        fun stop()
    }

    class SpeakerSession(val speaker: Speaker) {
        @Volatile
        var state: State = State.CONNECTING

        @Volatile
        var volumePct: Int = 100

        @Volatile
        var error: String? = null

        /** Human label of the stack in use: "AirPlay 2", "AirPlay 1", … */
        @Volatile
        var protocol: String = ""

        @Volatile
        var live: LiveSession? = null

        @Volatile
        var stopRequested: Boolean = false
    }

    private val sessions = LinkedHashMap<String, SpeakerSession>()
    private val sessionsLock = Any()
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    private var broadcast: BroadcastCapture? = null

    @Volatile
    var lastError: String? = null
        private set

    /** Set by the service: persists per-speaker volume on every change. */
    @Volatile
    var volumeSaver: ((Speaker, Int) -> Unit)? = null

    /** Set by the service: loads a speaker's remembered volume. */
    @Volatile
    var volumeLoader: ((Speaker) -> Int?)? = null

    /** Set by the service: URL of the local HTTP audio stream. */
    @Volatile
    var streamUrlProvider: (() -> String)? = null

    /**
     * Set by the service when the Cast SDK is present: builds a live
     * Chromecast session for [Speaker] pulling the HTTP stream at the
     * given URL. Returns null when Cast is unavailable.
     */
    @Volatile
    var chromecastStarter: ((Speaker, String) -> LiveSession?)? = null

    private val ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "airvia-engine-io").apply { isDaemon = true }
    }

    // --- aggregate state ------------------------------------------------

    val state: State
        get() {
            val ss = snapshot()
            return when {
                ss.any { it.state == State.STREAMING } -> State.STREAMING
                ss.any { it.state == State.CONNECTING } -> State.CONNECTING
                lastError != null -> State.ERROR
                else -> State.IDLE
            }
        }

    /** Primary (first active) speaker, for notification/tile/legacy UI. */
    val speaker: Speaker?
        get() = snapshot().firstOrNull()?.speaker

    val volumePct: Int
        get() = snapshot().firstOrNull()?.volumePct ?: 100

    val isActive: Boolean
        get() = state == State.CONNECTING || state == State.STREAMING

    val activeCount: Int
        get() = synchronized(sessionsLock) { sessions.size }

    val hasLiveSource: Boolean
        get() = broadcast != null

    fun snapshot(): List<SpeakerSession> =
        synchronized(sessionsLock) { sessions.values.toList() }

    fun sessionFor(sp: Speaker): SpeakerSession? =
        synchronized(sessionsLock) { sessions[sp.key] }

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        val s = state
        for (l in listeners) {
            try {
                l(s)
            } catch (_: Exception) {
            }
        }
    }

    // --- source lifecycle (owned by CastService) -------------------------

    fun attachSource(source: BroadcastCapture) {
        broadcast = source
        NowPlaying.addListener(metadataListener)
        thread(name = "airvia-watchdog", isDaemon = true) { watchdog(source) }
    }

    fun detachSource() {
        NowPlaying.removeListener(metadataListener)
        broadcast = null
    }

    private val metadataListener: (NowPlayingInfo?) -> Unit = { info ->
        // Metadata pushes do network I/O — never on the caller's thread.
        ioExecutor.execute {
            for (ss in snapshot()) {
                try {
                    ss.live?.setMetadata(info)
                } catch (_: Exception) {
                }
            }
        }
    }

    // --- sleep timer ------------------------------------------------------

    @Volatile
    var sleepMinutes: Int = 0
        private set

    @Volatile
    private var sleepDeadlineMs: Long = 0

    /** Arm (or clear, with 0) the global sleep timer. */
    fun setSleepTimer(minutes: Int) {
        sleepMinutes = minutes
        sleepDeadlineMs = if (minutes > 0) {
            System.currentTimeMillis() + minutes * 60_000L
        } else {
            0
        }
        LogBus.log(
            "[engine] sleep timer " + if (minutes > 0) "$minutes min" else "off",
        )
    }

    private fun watchdog(source: BroadcastCapture) {
        while (broadcast === source) {
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                return
            }
            if (snapshot().isEmpty()) continue
            val dl = sleepDeadlineMs
            if (dl > 0 && System.currentTimeMillis() >= dl) {
                LogBus.log("[engine] sleep timer reached — stopping all casts")
                stopAll()
                return
            }
            if (state == State.STREAMING &&
                System.currentTimeMillis() - source.lastAudibleMs > SILENCE_STOP_MS
            ) {
                LogBus.log("[engine] silent for 5 minutes — auto-stopping all casts")
                stopAll()
                return
            }
        }
    }

    // --- session control --------------------------------------------------

    /** Start casting the shared source to [sp] (no-op if already casting). */
    fun startSpeaker(sp: Speaker) {
        val source = broadcast
        if (source == null) {
            lastError = "capture not running"
            LogBus.log("[engine] cannot cast to '${sp.name}': no live capture")
            notifyChanged()
            return
        }
        val ss = SpeakerSession(sp)
        synchronized(sessionsLock) {
            if (sessions.containsKey(sp.key)) return
            sessions[sp.key] = ss
        }
        lastError = null
        ss.volumePct = try {
            volumeLoader?.invoke(sp)
        } catch (_: Exception) {
            null
        } ?: 100
        LogBus.log("[engine] connecting to '${sp.name}' (${sp.kind})")
        notifyChanged()
        thread(name = "airvia-cast-${sp.key}", isDaemon = true) {
            runSession(ss, source)
        }
    }

    private fun runSession(ss: SpeakerSession, source: BroadcastCapture) {
        val sp = ss.speaker
        try {
            when (sp.kind) {
                SpeakerKind.AIRPLAY -> runAirPlay(ss, source)
                SpeakerKind.DLNA -> runDlna(ss)
                SpeakerKind.CHROMECAST -> runChromecast(ss)
            }
        } catch (e: Exception) {
            if (!ss.stopRequested) {
                ss.error = e.message ?: e.javaClass.simpleName
                ss.state = State.ERROR
                lastError = ss.error
                LogBus.log("[engine] '${sp.name}' failed: ${ss.error}")
            }
        } finally {
            // If the user stopped this speaker while its thread was still
            // connecting (live not yet set when stop was requested), make
            // sure nothing keeps playing behind the UI's back.
            if (ss.stopRequested) {
                try {
                    ss.live?.stop()
                } catch (_: Exception) {
                }
            }
            val removed = synchronized(sessionsLock) {
                sessions.remove(sp.key) === ss
            }
            if (removed) notifyChanged()
        }
    }

    /** Stop one speaker's session (no-op when not casting to it). */
    fun stopSpeaker(sp: Speaker) {
        val ss = synchronized(sessionsLock) { sessions[sp.key] } ?: return
        ss.stopRequested = true
        try {
            ss.live?.stop()
        } catch (_: Exception) {
        }
        synchronized(sessionsLock) { sessions.remove(sp.key) }
        notifyChanged()
    }

    /** Stop every session. */
    fun stopAll() {
        sleepMinutes = 0
        sleepDeadlineMs = 0
        val all = snapshot()
        for (ss in all) {
            ss.stopRequested = true
            try {
                ss.live?.stop()
            } catch (_: Exception) {
            }
        }
        synchronized(sessionsLock) { sessions.clear() }
        lastError = null
        notifyChanged()
    }

    /** Clear a displayed error (error card dismissed). */
    fun clearError() {
        if (snapshot().isEmpty()) {
            lastError = null
            notifyChanged()
        }
    }

    // --- volume -----------------------------------------------------------

    /** Set one speaker's volume, 0..100, and remember it. */
    fun setVolume(sp: Speaker, pct: Int) {
        val ss = sessionFor(sp) ?: return
        val v = pct.coerceIn(0, 100)
        ss.volumePct = v
        try {
            volumeSaver?.invoke(sp, v)
        } catch (_: Exception) {
        }
        try {
            ss.live?.setVolumePct(v)
        } catch (_: Exception) {
        }
        if (ss === snapshot().firstOrNull()) VolumeKeys.updateVolume(v)
    }

    /** Legacy single-speaker setter: drives the primary session. */
    fun setVolume(pct: Int) {
        val primary = snapshot().firstOrNull() ?: run {
            VolumeKeys.updateVolume(pct.coerceIn(0, 100))
            return
        }
        setVolume(primary.speaker, pct)
    }

    /** Set every active speaker to the same volume (hardware keys). */
    fun setAllVolumes(pct: Int) {
        for (ss in snapshot()) setVolume(ss.speaker, pct)
    }

    /** Step every active speaker's volume by [delta] (hardware keys). */
    fun stepAllVolumes(delta: Int) {
        for (ss in snapshot()) setVolume(ss.speaker, ss.volumePct + delta)
    }

    private fun pctToDb(pct: Int): Float =
        (-30.0 + (pct.coerceIn(0, 100) / 100.0) * 30.0).toFloat()

    // --- AirPlay: AP2 with RAOP fallback ----------------------------------

    private fun runAirPlay(ss: SpeakerSession, source: BroadcastCapture) {
        val sp = ss.speaker
        val consumer = source.newConsumer()
        val streamed = AtomicBoolean(false)
        val audioRef = java.util.concurrent.atomic.AtomicReference<Ap2AudioSession?>()
        var failure: Throwable? = null
        var est: Ap2Pairing.Established? = null
        try {
            est = Ap2Pairing.connect(sp.host, sp.port)
            if (ss.stopRequested) return
            val audio = Ap2AudioSession(
                sp.host, est.localIp, est.channel, est.k64, consumer,
            ) {
                streamed.set(true)
                ss.state = State.STREAMING
                ss.protocol = "AirPlay 2"
                val a = audioRef.get()
                if (ss.volumePct != 100) a?.setVolumeDb(pctToDb(ss.volumePct))
                NowPlaying.current?.let { info ->
                    a?.setMetadata(info.title, info.artist, info.album, info.artworkJpeg)
                }
                notifyChanged()
            }
            audioRef.set(audio)
            ss.live = Ap2Live(audio)
            if (ss.volumePct != 100) audio.setVolumeDb(pctToDb(ss.volumePct))
            audio.run()
        } catch (e: Exception) {
            failure = e
        } finally {
            try {
                est?.socket?.close()
            } catch (_: Exception) {
            }
        }
        if (ss.stopRequested) return
        if (streamed.get()) {
            LogBus.log("[engine] AP2 session to '${sp.name}' ended")
            return
        }
        // AP2 never got audio flowing — fall back to classic RAOP.
        LogBus.log(
            "[engine] AirPlay 2 to '${sp.name}' failed " +
                "(${failure?.message ?: "ended early"}) — trying AirPlay 1 (RAOP)",
        )
        ss.live = null
        runRaop(ss, source.newConsumer())
    }

    private class Ap2Live(private val session: Ap2AudioSession) : LiveSession {
        override fun setVolumePct(pct: Int) {
            session.setVolumeDb((-30.0 + (pct.coerceIn(0, 100) / 100.0) * 30.0).toFloat())
        }

        override fun setMetadata(info: NowPlayingInfo?) {
            if (info == null) return
            session.setMetadata(info.title, info.artist, info.album, info.artworkJpeg)
        }

        override fun stop() {
            session.requestStop()
        }
    }

    private fun runRaop(ss: SpeakerSession, consumer: BroadcastCapture.Consumer) {
        val sp = ss.speaker
        // Split-service receivers (Kodi/LibreELEC, shairport) advertise
        // RAOP on a different port than AirPlay — the failover must aim
        // there, not at the AirPlay port that just rejected AP2 pairing.
        val targetPort = sp.raopPort ?: sp.port
        if (targetPort != sp.port) {
            LogBus.log(
                "[engine] '${sp.name}' RAOP service is on port $targetPort " +
                    "(AirPlay port ${sp.port}) — failing over there",
            )
        }
        val etModes = (sp.raopEt ?: sp.et)?.split(',')?.map { it.trim() }?.toSet() ?: emptySet()
        val attempts = when {
            "1" in etModes && "0" in etModes -> listOf(true, false)
            "1" in etModes -> listOf(true)
            "0" in etModes -> listOf(false)
            else -> listOf(true, false)
        }
        var lastFailure: Exception? = null
        for (encrypt in attempts) {
            if (ss.stopRequested) return
            val conn = RaopConnection(sp.host, targetPort, NetUtil.localIp(), encrypt)
            try {
                conn.connect()
                if (ss.stopRequested) {
                    try {
                        conn.teardown()
                    } catch (_: Exception) {
                    }
                    return
                }
                conn.pcmSource = RaopSourceAdapter(consumer)
                val live = RaopLive(conn)
                ss.live = live
                conn.startSender()
                if (ss.volumePct != 100) conn.setVolume(ss.volumePct)
                NowPlaying.current?.let {
                    conn.setMetadata(it.title, it.artist, it.album)
                }
                ss.protocol = "AirPlay 1 (RAOP)"
                ss.state = State.STREAMING
                LogBus.log(
                    "[engine] RAOP streaming to '${sp.name}' " +
                        "(${if (encrypt) "RSA" else "clear"} mode)",
                )
                notifyChanged()
                live.awaitStop()
                return
            } catch (e: Exception) {
                lastFailure = e
                LogBus.log(
                    "[engine] RAOP (${if (encrypt) "RSA" else "clear"}) to " +
                        "'${sp.name}' failed: ${e.message}",
                )
                try {
                    conn.teardown()
                } catch (_: Exception) {
                }
                ss.live = null
            }
        }
        throw lastFailure ?: IllegalStateException("RAOP failed")
    }

    private class RaopLive(private val conn: RaopConnection) : LiveSession {
        private val stopLatch = CountDownLatch(1)

        override fun setVolumePct(pct: Int) {
            conn.setVolume(pct)
        }

        override fun setMetadata(info: NowPlayingInfo?) {
            if (info == null) return
            conn.setMetadata(info.title, info.artist, info.album)
        }

        override fun stop() {
            try {
                conn.teardown()
            } catch (_: Exception) {
            }
            stopLatch.countDown()
        }

        fun awaitStop() {
            // RAOP has no natural end for a live source; park until stop.
            while (stopLatch.count > 0L) {
                try {
                    stopLatch.await(60, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Adapts a broadcast consumer to RAOP's split-channel PCM source. */
    private class RaopSourceAdapter(
        private val consumer: BroadcastCapture.Consumer,
    ) : RaopConnection.PcmSource {
        override fun readFrame(left: ShortArray, right: ShortArray): Int {
            if (consumer.finished) return -1
            val chunk = consumer.nextInterleaved() ?: return -2
            val n = minOf(AlacEncoder.FRAME_SAMPLES, chunk.size / 2)
            for (i in 0 until n) {
                left[i] = chunk[2 * i]
                right[i] = chunk[2 * i + 1]
            }
            return n
        }
    }

    // --- DLNA ---------------------------------------------------------------

    private fun runDlna(ss: SpeakerSession) {
        val sp = ss.speaker
        val device = sp.dlna ?: throw IllegalStateException("missing DLNA endpoints")
        val url = streamUrlProvider?.invoke()
            ?: throw IllegalStateException("stream server not running")
        Dlna.play(device, url, "Airvia — device audio")
        val live = DlnaLive(device)
        ss.live = live
        if (ss.stopRequested) {
            live.stop()
            return
        }
        if (ss.volumePct != 100) live.setVolumePct(ss.volumePct)
        ss.protocol = "DLNA"
        ss.state = State.STREAMING
        LogBus.log("[engine] DLNA streaming to '${sp.name}' via $url")
        notifyChanged()
        live.awaitStop()
    }

    private class DlnaLive(private val device: Dlna.DlnaDevice) : LiveSession {
        private val stopLatch = CountDownLatch(1)
        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "airvia-dlna-io").apply { isDaemon = true }
        }

        override fun setVolumePct(pct: Int) {
            io.execute {
                try {
                    Dlna.setVolume(device, pct)
                } catch (e: Exception) {
                    LogBus.log("[engine] DLNA volume failed: ${e.message}")
                }
            }
        }

        override fun setMetadata(info: NowPlayingInfo?) {
            // The stream's DIDL title is fixed at Play time; live metadata
            // updates would need a new SetAVTransportURI (audible restart).
        }

        override fun stop() {
            stopLatch.countDown()
            io.execute {
                try {
                    Dlna.stop(device)
                } catch (e: Exception) {
                    LogBus.log("[engine] DLNA stop failed: ${e.message}")
                }
            }
        }

        fun awaitStop() {
            while (stopLatch.count > 0L) {
                try {
                    stopLatch.await(60, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    // --- Chromecast (via the service-registered starter) ---------------------

    private fun runChromecast(ss: SpeakerSession) {
        val sp = ss.speaker
        val starter = chromecastStarter
            ?: throw IllegalStateException("Chromecast support unavailable")
        val url = streamUrlProvider?.invoke()
            ?: throw IllegalStateException("stream server not running")
        val live = starter(sp, url)
            ?: throw IllegalStateException("couldn't start Chromecast session")
        ss.live = live
        if (ss.stopRequested) {
            live.stop()
            return
        }
        ss.protocol = "Chromecast"
        ss.state = State.STREAMING
        LogBus.log("[engine] Chromecast streaming to '${sp.name}' via $url")
        notifyChanged()
        // The starter's session lives until stop() is called on it; park
        // this thread while the session is registered.
        while (!ss.stopRequested && sessionFor(sp) === ss) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
