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

    /** First-connect volume (issue #6): a speaker with no remembered
     *  volume starts here instead of 100, so a new speaker — or one
     *  whose remembered volume was lost — can never blast at full
     *  volume. 15% = -25.5 dB by pctToDb: clearly audible on a
     *  HomePod, safe on a full-gain amplifier. */
    private const val FIRST_CONNECT_VOLUME_PCT = 15

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

        /** One-tap mute (1.3.2): true while this speaker is silenced
         *  with its track still playing; [preMuteLevel] is the level
         *  unmute restores. */
        @Volatile
        var muted: Boolean = false

        @Volatile
        var preMuteLevel: Int = 100

        @Volatile
        var error: String? = null

        /** Human label of the stack in use: "AirPlay 2", "AirPlay 1", … */
        @Volatile
        var protocol: String = ""

        @Volatile
        var live: LiveSession? = null

        @Volatile
        var stopRequested: Boolean = false

        /** The profiled PCM chain feeding this session (AirPlay). */
        @Volatile
        var fx: ProfiledConsumer? = null

        /** True once audio actually streamed in this session. */
        @Volatile
        var streamedOnce: Boolean = false
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

    /** Set by the service: persists per-speaker mute state (1.3.2). */
    @Volatile
    var muteSaver: ((Speaker, MuteState) -> Unit)? = null

    /** Set by the service: loads a speaker's persisted mute state. */
    @Volatile
    var muteLoader: ((Speaker) -> MuteState?)? = null

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

    // --- HAP persistent pairing (PIN pairing) plumbing ------------------
    // All set by the service from Prefs; the engine stays Android-free.

    /** Our long-term HAP controller identity (seed + identifier). */
    @Volatile
    var identityProvider: (() -> com.opus.airvia.ap2.HapPairing.ControllerIdentity)? = null

    /** Stored PIN pairing for a speaker, or null when never paired. */
    @Volatile
    var credentialsLoader: ((Speaker) -> com.opus.airvia.ap2.HapPairing.PairCredentials?)? = null

    /** Persist a fresh pairing after a successful PIN pair-setup. */
    @Volatile
    var credentialsSaver: ((Speaker, com.opus.airvia.ap2.HapPairing.PairCredentials) -> Unit)? = null

    /**
     * Ask the user for the code shown on the speaker's screen; blocks
     * the cast thread, returns null on cancel/timeout.
     */
    @Volatile
    var pinProvider: ((Speaker) -> String?)? = null

    /** Placeholder track info for metadata-less sessions (null = off). */
    @Volatile
    var placeholderProvider: (() -> PlaceholderTrack?)? = null

    /**
     * Placeholder refusal memory (1.3.1): whether this speaker's
     * receiver refused the placeholder before (400) — when true the
     * placeholder is not offered again. Set by the service from Prefs.
     */
    @Volatile
    var placeholderRefusedLoader: ((Speaker) -> Boolean)? = null

    /** Persist a fresh placeholder refusal for a speaker (1.3.1). */
    @Volatile
    var placeholderRefusedSaver: ((Speaker) -> Unit)? = null

    /** Set by the service: loads a speaker's sound profile (1.3.0). */
    @Volatile
    var profileLoader: ((Speaker) -> SpeakerProfile)? = null

    /** Set by the service: per-speaker protocol preference (1.3.0). */
    @Volatile
    var protocolPrefLoader: ((Speaker) -> ProtocolPref)? = null

    /** Set by the service: learned-protocol persistence (1.3.0). */
    @Volatile
    var learnedLoader: ((Speaker) -> AirProtocol?)? = null

    @Volatile
    var learnedSaver: ((Speaker, AirProtocol) -> Unit)? = null

    /** Set by the service: auto-reconnect master switch (1.3.0). */
    @Volatile
    var reconnectEnabled: (() -> Boolean)? = null

    /** Placeholder now-playing info: title/artist + PNG artwork bytes. */
    class PlaceholderTrack(
        val title: String,
        val artist: String,
        val artworkPng: ByteArray?,
    )

    /** Speakers whose next connect must go straight to PIN pairing. */
    private val forcePinKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Mark [sp] so its next connect runs the PIN pairing flow first. */
    fun requestForcePin(sp: Speaker) {
        forcePinKeys.add(sp.key)
    }

    /**
     * The PIN flow was entered (receiver demanded it, or the user asked
     * for it) and did not complete — abort the connect instead of the
     * RAOP fallback, which cannot succeed on a PIN-only receiver.
     */
    class PinFlowAborted(reason: String) : Exception(reason)

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
            processReconnects()
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
        // First connect to a speaker with no remembered volume: start
        // safe (issue #6) — never blast a new speaker at 100%. Known
        // speakers keep their stored level (including 100 and
        // 0-for-muted). The default is computed, not stored, so a
        // speaker connected at 15% and never touched gets 15% again
        // next time. Coerced to the profile cap, which the initial
        // assignment otherwise bypasses (applyVolume is the only
        // other place the cap is applied).
        val profileCap = try {
            profileLoader?.invoke(sp)?.maxVolumePct
        } catch (_: Exception) {
            null
        } ?: 100
        ss.volumePct = (try {
            volumeLoader?.invoke(sp)
        } catch (_: Exception) {
            null
        } ?: FIRST_CONNECT_VOLUME_PCT).coerceAtMost(profileCap)
        // Mute restore (1.3.2): a persisted mute survives a restart
        // (saved level reads 0 while muted, which is consistent).
        try {
            muteLoader?.invoke(sp)?.let { m ->
                ss.muted = m.muted
                ss.preMuteLevel = m.preMuteLevel
            }
        } catch (_: Exception) {
        }
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
            try {
                ss.fx?.releaseDelegate()
            } catch (_: Exception) {
            }
            scheduleReconnect(ss)
        }
    }

    /** Stop one speaker's session (no-op when not casting to it). */
    fun stopSpeaker(sp: Speaker) {
        reconnects.remove(sp.key)
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
        reconnects.clear()
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

    /** Set one speaker's volume, 0..100, and remember it. The
     *  per-speaker profile's volume guard caps the level at send time
     *  (the stored value is untouched, so lifting the cap restores
     *  it) — this is the single choke point every volume path uses.
     *
     *  Mute rule (1.3.2): an explicit set to a non-zero level while
     *  muted UNMUTES to the new value (the standard expectation —
     *  dragging a slider on a muted speaker unmutes it). Setting 0
     *  while muted keeps the mute. Mute's own send bypasses this
     *  through [applyVolume] so it never trips its own unmute. */
    fun setVolume(sp: Speaker, pct: Int) {
        val ss = sessionFor(sp) ?: return
        val v = pct.coerceIn(0, 100)
        if (ss.muted && v != 0) {
            ss.muted = false
            try {
                muteSaver?.invoke(sp, MuteState(false, ss.preMuteLevel))
            } catch (_: Exception) {
            }
        }
        applyVolume(ss, v)
    }

    /** The choke-point body: cap, store, send. No mute semantics. */
    private fun applyVolume(ss: SpeakerSession, pct: Int) {
        val sp = ss.speaker
        val cap = try {
            profileLoader?.invoke(sp)?.maxVolumePct
        } catch (_: Exception) {
            null
        } ?: 100
        val v = pct.coerceIn(0, 100).coerceAtMost(cap)
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

    /**
     * One-tap per-speaker mute (1.3.2, issue #5): silences ONLY this
     * speaker while its track keeps playing; unmute restores exactly
     * the level it had (guard-capped at the choke point). Muting an
     * already-muted speaker is a no-op — the original pre-mute level
     * is kept, never overwritten by the 0 it sits at. Mute persists
     * per speaker (see [muteSaver]) so a restart mid-mute does not
     * blast audio; stopping a speaker does not clear it.
     */
    fun setMuted(sp: Speaker, muted: Boolean) {
        val ss = sessionFor(sp) ?: return
        if (muted) {
            if (ss.muted) return
            ss.preMuteLevel = ss.volumePct
            ss.muted = true
            try {
                muteSaver?.invoke(sp, MuteState(true, ss.preMuteLevel))
            } catch (_: Exception) {
            }
            applyVolume(ss, 0)
        } else {
            if (!ss.muted) return
            val restore = ss.preMuteLevel
            ss.muted = false
            try {
                muteSaver?.invoke(sp, MuteState(false, restore))
            } catch (_: Exception) {
            }
            applyVolume(ss, restore)
        }
        notifyChanged()
    }

    /** Live muted flag for one speaker (false when not casting). */
    fun isMuted(sp: Speaker): Boolean = sessionFor(sp)?.muted ?: false

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

    /**
     * Push a (possibly edited) profile into a live session: the delay
     * line adjusts immediately; a lowered volume cap re-clamps the
     * current level. EQ changes apply from the next session (the
     * filter chain is built at connect time).
     */
    fun applyProfile(sp: Speaker, profile: SpeakerProfile) {
        val ss = sessionFor(sp) ?: return
        ss.fx?.setDelayMs(profile.delayMs)
        if (ss.volumePct > profile.maxVolumePct) {
            setVolume(sp, ss.volumePct)
        }
    }

    private fun pctToDb(pct: Int): Float =
        (-30.0 + (pct.coerceIn(0, 100) / 100.0) * 30.0).toFloat()

    // --- auto-reconnect (1.3.0) -------------------------------------------

    private class ReconnectEntry(
        val speaker: Speaker,
        var attempts: Int,
        var nextMs: Long,
    )

    private val reconnects =
        java.util.concurrent.ConcurrentHashMap<String, ReconnectEntry>()

    private val reconnectDelaysMs = longArrayOf(5_000L, 15_000L, 60_000L)

    /**
     * A session that actually streamed and then ended WITHOUT the user
     * stopping it (network drop, receiver teardown, control-channel
     * failure) is retried with backoff while the capture pipeline is
     * alive — max one retry per delay step (5 s, 15 s, 60 s), then we
     * give up until the user acts. User stops never land here
     * (stopRequested), and a successful re-stream clears the entry.
     */
    private fun scheduleReconnect(ss: SpeakerSession) {
        if (ss.stopRequested || !ss.streamedOnce) return
        val enabled = try {
            reconnectEnabled?.invoke()
        } catch (_: Exception) {
            null
        } ?: true
        if (!enabled) return
        val key = ss.speaker.key
        val entry = reconnects.getOrPut(key) {
            ReconnectEntry(ss.speaker, 0, 0L)
        }
        if (entry.attempts >= reconnectDelaysMs.size) {
            reconnects.remove(key)
            LogBus.log(
                "[engine] '${ss.speaker.name}' dropped again — " +
                    "giving up auto-reconnect",
            )
            return
        }
        val delay = reconnectDelaysMs[entry.attempts]
        entry.attempts += 1
        entry.nextMs = System.currentTimeMillis() + delay
        LogBus.log(
            "[engine] '${ss.speaker.name}' dropped unexpectedly — " +
                "reconnect attempt ${entry.attempts} in ${delay / 1000}s",
        )
    }

    private fun processReconnects() {
        if (reconnects.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((key, entry) in reconnects) {
            if (now < entry.nextMs) continue
            if (broadcast == null) {
                reconnects.remove(key)
                continue
            }
            if (sessionFor(entry.speaker) != null) continue // live again
            LogBus.log(
                "[engine] reconnecting to '${entry.speaker.name}' " +
                    "(attempt ${entry.attempts})",
            )
            entry.nextMs = now + 60_000L // re-arms on drop via schedule
            startSpeaker(entry.speaker)
        }
    }

    /** Nudge pending reconnects to fire on the next watchdog tick
     *  (e.g. the network just came back). */
    fun kickReconnects() {
        val now = System.currentTimeMillis()
        for (e in reconnects.values) {
            if (e.nextMs > now) e.nextMs = now
        }
    }

    private fun noteLearned(sp: Speaker, proto: AirProtocol) {
        try {
            learnedSaver?.invoke(sp, proto)
        } catch (_: Exception) {
        }
    }

    // --- AirPlay: AP2 with RAOP fallback ----------------------------------

    /**
     * Authenticate to an AirPlay receiver, in order:
     *  1. stored PIN pairing -> pair-verify (Apple TV / Mac path),
     *  2. transient pairing, exactly as before (HomePod path) — unless
     *     a manual PIN pairing was requested for this speaker,
     *  3. when the receiver refuses transient with 470 (or PIN pairing
     *     was requested manually): the PIN flow — ask for the on-screen
     *     code, pair-setup, store the credentials, pair-verify.
     * PIN-flow failures abort via [PinFlowAborted] (no RAOP fallback).
     */
    private fun establishAp2(
        ss: SpeakerSession,
        budgetMs: Int = 0,
    ): Ap2Pairing.Established {
        val sp = ss.speaker
        val identity = try {
            identityProvider?.invoke()
        } catch (_: Exception) {
            null
        }
        val forced = forcePinKeys.remove(sp.key)
        if (identity != null && !forced) {
            val creds = try {
                credentialsLoader?.invoke(sp)
            } catch (_: Exception) {
                null
            }
            if (creds != null) {
                try {
                    LogBus.log("[engine] '${sp.name}' has a stored PIN pairing — pair-verifying")
                    return Ap2Pairing.connectVerified(sp.host, sp.port, creds, identity)
                } catch (e: Exception) {
                    LogBus.log(
                        "[engine] pair-verify for '${sp.name}' failed " +
                            "(${e.message}) — trying the regular pairing",
                    )
                }
            }
        }
        if (!forced) {
            try {
                // Capped first attempt (RoutePolicy): only when a RAOP
                // fallback exists — a silent AP2 port then costs the
                // budget, not the full 12 s read timeout (issue #2).
                return Ap2Pairing.connect(
                    sp.host, sp.port,
                    if (budgetMs > 0) budgetMs else 12000,
                )
            } catch (e: Ap2Pairing.PinRequired) {
                LogBus.log(
                    "[engine] '${sp.name}' requires PIN pairing — asking for the code",
                )
            }
        } else {
            LogBus.log("[engine] PIN pairing requested for '${sp.name}' — asking for the code")
        }
        // PIN flow: the only remaining path. Any failure here aborts.
        if (identity == null) {
            throw PinFlowAborted("PIN pairing is unavailable (no identity provider)")
        }
        val newCreds = try {
            Ap2Pairing.pairSetupWithPin(sp.host, sp.port, identity) {
                if (ss.stopRequested) {
                    null
                } else {
                    try {
                        pinProvider?.invoke(sp)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        } catch (e: Ap2Pairing.PairingFailed) {
            throw PinFlowAborted(e.message ?: "PIN pairing failed")
        }
        try {
            credentialsSaver?.invoke(sp, newCreds)
            LogBus.log("[engine] stored the PIN pairing for '${sp.name}'")
        } catch (e: Exception) {
            LogBus.log("[engine] could not store the pairing for '${sp.name}': ${e.message}")
        }
        try {
            return Ap2Pairing.connectVerified(sp.host, sp.port, newCreds, identity)
        } catch (e: Exception) {
            throw PinFlowAborted("paired, but pair-verify failed: ${e.message}")
        }
    }

    /** Build the per-session PCM chain: hub consumer (group-aligned
     *  when another AirPlay speaker is already streaming) wrapped in
     *  the speaker-profile effects (delay / EQ / gentle start). */
    private fun buildChain(
        ss: SpeakerSession,
        source: BroadcastCapture,
    ): Ap2AudioSession.PcmSource {
        val sp = ss.speaker
        val groupActive = snapshot().any {
            it !== ss && it.speaker.kind == SpeakerKind.AIRPLAY &&
                it.state == State.STREAMING
        }
        val raw = if (groupActive) {
            LogBus.log(
                "[engine] '${sp.name}' joins the sync group " +
                    "(aligned to the group's timeline)",
            )
            source.newAlignedConsumer()
        } else {
            source.newConsumer()
        }
        val profile = try {
            profileLoader?.invoke(sp)
        } catch (_: Exception) {
            null
        } ?: SpeakerProfile()
        val fx = ProfiledConsumer(raw, profile, source.sampleRate)
        ss.fx = fx
        return fx
    }

    private class Ap2Outcome(
        val streamed: Boolean,
        val failure: Throwable?,
    )

    /** One AP2 attempt at [rate] feeding on [pcm]. Throws
     *  [PinFlowAborted] through (the PIN flow is terminal by design). */
    private fun attemptAp2(
        ss: SpeakerSession,
        pcm: Ap2AudioSession.PcmSource,
        rate: Int,
        capMs: Int,
    ): Ap2Outcome {
        val sp = ss.speaker
        val streamed = AtomicBoolean(false)
        val audioRef = java.util.concurrent.atomic.AtomicReference<Ap2AudioSession?>()
        var failure: Throwable? = null
        var est: Ap2Pairing.Established? = null
        try {
            est = establishAp2(ss, capMs)
            if (ss.stopRequested) return Ap2Outcome(false, null)
            val audio = Ap2AudioSession(
                sp.host, est.localIp, est.channel, est.k64, pcm, rate,
            ) {
                streamed.set(true)
                ss.streamedOnce = true
                ss.state = State.STREAMING
                ss.protocol = "AirPlay 2"
                reconnects.remove(sp.key)
                val a = audioRef.get()
                if (ss.volumePct != 100) a?.setVolumeDb(pctToDb(ss.volumePct))
                val info = NowPlaying.current
                if (info != null) {
                    a?.setMetadata(info.title, info.artist, info.album, info.artworkJpeg)
                } else {
                    // No track info from the source app: optionally send
                    // the placeholder so the receiver still opens its
                    // player UI (issue #4 suspect). Real metadata, when
                    // it exists, always wins and is never delayed. A
                    // receiver that refused the placeholder before
                    // (400 — some Apple TVs) is not offered it again
                    // (1.3.1, issue #5).
                    val refused = try {
                        placeholderRefusedLoader?.invoke(sp)
                    } catch (_: Exception) {
                        null
                    } ?: false
                    val ph = if (PlaceholderGate.shouldOffer(true, refused)) {
                        try {
                            placeholderProvider?.invoke()
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (ph != null) {
                        a?.setPlaceholderMetadata(ph.title, ph.artist, ph.artworkPng)
                    }
                }
                notifyChanged()
            }
            audioRef.set(audio)
            audio.onPlaceholderRefused = {
                try {
                    placeholderRefusedSaver?.invoke(sp)
                } catch (_: Exception) {
                }
            }
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
        if (failure is PinFlowAborted) throw failure
        return Ap2Outcome(streamed.get(), failure)
    }

    private fun runAirPlay(ss: SpeakerSession, source: BroadcastCapture) {
        val sp = ss.speaker
        val chain = buildChain(ss, source)
        // RAOP always runs at 44.1 kHz (its wire format is fixed and
        // rig-verified); from a 48 kHz hub it is fed via a resampler.
        val raopPcm: Ap2AudioSession.PcmSource =
            if (source.sampleRate != 44100) {
                ResamplingConsumer(chain, source.sampleRate, 44100)
            } else {
                chain
            }
        val decision = RoutePolicy.decide(
            hasCreds = try {
                credentialsLoader?.invoke(sp) != null
            } catch (_: Exception) {
                false
            },
            pref = try {
                protocolPrefLoader?.invoke(sp)
            } catch (_: Exception) {
                null
            } ?: ProtocolPref.AUTO,
            learned = try {
                learnedLoader?.invoke(sp)
            } catch (_: Exception) {
                null
            },
            raopSplitPort = sp.raopPort != null,
        )
        LogBus.log(
            "[engine] route for '${sp.name}': first=${decision.first} " +
                "fallback=${decision.fallback} cap=${decision.capFirstAp2Ms}ms",
        )

        if (decision.first == AirProtocol.RAOP) {
            try {
                runRaop(ss, raopPcm)
                noteLearned(sp, AirProtocol.RAOP)
                return
            } catch (e: Exception) {
                if (ss.stopRequested) return
                if (decision.fallback != AirProtocol.AP2) throw e
                LogBus.log(
                    "[engine] AirPlay 1 first for '${sp.name}' failed " +
                        "(${e.message}) — falling through to AirPlay 2",
                )
                ss.live = null
            }
        }

        var outcome = attemptAp2(ss, chain, source.sampleRate, decision.capFirstAp2Ms)
        if (!outcome.streamed && !ss.stopRequested && source.sampleRate == 48000) {
            // A receiver that refuses sr=48000 gets one retry at the
            // proven 44.1 kHz through a resampling consumer (hi-res
            // mode only — the default path never reaches this).
            LogBus.log(
                "[engine] 48 kHz AirPlay 2 to '${sp.name}' did not " +
                    "start — retrying at 44.1 kHz",
            )
            ss.live = null
            outcome = attemptAp2(
                ss, ResamplingConsumer(chain, 48000, 44100), 44100, 0,
            )
        }
        if (outcome.streamed) {
            noteLearned(sp, AirProtocol.AP2)
            LogBus.log("[engine] AP2 session to '${sp.name}' ended")
            return
        }
        if (ss.stopRequested) return
        if (decision.fallback != AirProtocol.RAOP) {
            throw outcome.failure
                ?: IllegalStateException("AirPlay 2 failed")
        }
        // AP2 never got audio flowing — fall back to classic RAOP.
        LogBus.log(
            "[engine] AirPlay 2 to '${sp.name}' failed " +
                "(${outcome.failure?.message ?: "ended early"}) " +
                "— trying AirPlay 1 (RAOP)",
        )
        ss.live = null
        runRaop(ss, raopPcm)
        noteLearned(sp, AirProtocol.RAOP)
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

    private fun runRaop(ss: SpeakerSession, pcm: Ap2AudioSession.PcmSource) {
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
                conn.pcmSource = RaopSourceAdapter(pcm)
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

    /** Adapts a PCM chunk source to RAOP's split-channel PCM source. */
    private class RaopSourceAdapter(
        private val source: Ap2AudioSession.PcmSource,
    ) : RaopConnection.PcmSource {
        override fun readFrame(left: ShortArray, right: ShortArray): Int {
            if (source.finished) return -1
            val chunk = source.nextInterleaved() ?: return -2
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
        // Issue #6: set the volume BEFORE Play so the renderer never
        // starts at its own level. Best-effort — a renderer without
        // RenderingControl ignores it (Dlna.setVolume returns
        // silently) — and the post-play send below stays as
        // belt-and-braces.
        if (ss.volumePct != 100) {
            try {
                Dlna.setVolume(device, ss.volumePct)
            } catch (e: Exception) {
                LogBus.log("[engine] DLNA pre-play volume failed: ${e.message}")
            }
        }
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
        // Issue #6: send the session volume on connect like the other
        // three paths — a new Chromecast speaker used to start at the
        // receiver's own volume with nothing sent at all.
        if (ss.volumePct != 100) live.setVolumePct(ss.volumePct)
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
