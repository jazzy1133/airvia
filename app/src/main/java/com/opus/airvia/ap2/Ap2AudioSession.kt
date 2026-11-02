package com.opus.airvia.ap2

import com.opus.airvia.Dmap
import com.opus.airvia.LogBus
import com.opus.airvia.PlaceholderGate
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.random.Random

/**
 * AirPlay 2 audio session for the standalone probe (Probe 1.3+).
 *
 * Runs after transient pairing + encrypted OPTIONS succeed:
 *   encrypted GET /info
 *   -> local audio/control/timing UDP sockets
 *   -> binary-plist session SETUP (timingProtocol=NTP, sessionUUID, identity keys)
 *   -> event-channel TCP connect + encrypted event responder
 *   -> RECORD (empty, owntone order: before stream SETUP)
 *   -> binary-plist stream SETUP (realtime 0x60, ALAC, shk = first 32B of K)
 *   -> NTP timing responder, initial sync, SET_PARAMETER volume 0 dB
 *   -> 10 s 440 Hz tone as ChaCha20-Poly1305-encrypted uncompressed-ALAC RTP
 *   -> /feedback keepalive every 2 s, sync packets every ~1 s
 *   -> TEARDOWN
 *
 * Request shape follows akustikrausch/airplay2-sender-cpp (owntone/pyatv parity):
 * standard headers on every request, Content-Type on SETUPs (no X-Apple-StreamID: the
 * real iPhone capture and pyatv both omit it)
 * on SETUP, empty RECORD, volume via SET_PARAMETER text/parameters.
 */
class Ap2AudioSession(
    private val host: String,
    private val localIp: String,
    private val channel: Ap2Channel,
    /** SRP session key K = SHA-512(minimal S), 64 bytes. shk = first 32. */
    private val k64: ByteArray,
    /** Live PCM source (352-frame interleaved chunks) for Outro playback. */
    private val pcm: PcmSource,
    /** Stream sample rate: 44100 (default, proven) or 48000 hi-res. */
    private val sampleRate: Int = 44100,
    /** Called once when audio streaming actually starts. */
    private val onStreaming: () -> Unit = {},
) {
    /** Supplies interleaved 44.1 kHz stereo PCM in 352-frame chunks. */
    interface PcmSource {
        /** Next 704-short chunk, or null when no data is ready yet. */
        fun nextInterleaved(): ShortArray?

        /** True once the source is exhausted (end of track). */
        val finished: Boolean
    }

    @Volatile
    private var stopRequested = false

    /** Ask the streaming loop to finish (TEARDOWN follows in [run]). */
    fun requestStop() {
        stopRequested = true
    }

    // Serializes request/response pairs on the control channel: the
    // streaming thread (feedback) and the UI thread (volume) both send
    // RTSP requests, and crossed responses would desync the channel.
    private val rtspLock = Any()

    @Volatile
    private var volumeDb: Float = 0.0f

    /** Set by [setVolumeDb]; consumed by the streaming thread. */
    private val volumeDirty = AtomicBoolean(false)

    /** Set by [setMetadata]; consumed by the streaming thread. */
    private val metadataDirty = AtomicBoolean(false)

    @Volatile
    private var streamReady = false

    /**
     * Set the receiver volume in dB (0 = max, -30 = min, -144 = mute).
     *
     * NON-BLOCKING since 1.2.1: this only records the level. The
     * streaming thread — the control channel's sole owner — transmits
     * it within ~200 ms, coalescing bursts (a slider drag becomes one
     * request with the latest value).
     *
     * Before, this did a synchronous RTSP exchange on the CALLER's
     * thread, which for every volume path (in-app slider, foreground
     * keys, VolumeProvider callbacks) is the main thread: each change
     * blocked the UI for a full network round trip (socket timeout
     * 12 s), changes queued up on [rtspLock], and a single read that
     * timed out mid-frame permanently desynced the HAP channel
     * ([Ap2Channel] consumes frame bytes incrementally but only
     * advances its nonce counter after a full frame decrypts), so
     * /feedback could never be delivered again and the receiver tore
     * the session down — "changing the volume stops the speaker".
     */
    fun setVolumeDb(db: Float) {
        volumeDb = db
        volumeDirty.set(true)
    }

    /**
     * Streaming-thread only: transmit a queued volume change.
     * Returns false when the exchange itself failed (no response) —
     * the caller counts consecutive control-channel failures.
     */
    private fun flushVolumeNow(): Boolean {
        val db = volumeDb
        volumeDirty.set(false)
        return try {
            val resp = rtsp(
                "SET_PARAMETER", rtspUri,
                "volume: ${"%.6f".format(java.util.Locale.US, db)}"
                    .toByteArray(Charsets.US_ASCII),
                "text/parameters",
            )
            LogBus.log("[$TAG] SET_PARAMETER volume $db -> ${resp.status}")
            true
        } catch (e: Exception) {
            LogBus.log("[$TAG] SET_PARAMETER volume failed: ${e.message}")
            false
        }
    }

    /** Latest now-playing metadata, (re)sent when streaming starts. */
    @Volatile
    private var pendingMetadata: Triple<String, String, String>? = null

    @Volatile
    private var pendingArtwork: ByteArray? = null

    /** Content-Type for [pendingArtwork]: real cover art is JPEG; the
     *  placeholder logo is the app's PNG, sent byte-verbatim. */
    @Volatile
    private var pendingArtworkType: String = "image/jpeg"

    /** True while [pendingMetadata] is the placeholder, not a real track. */
    @Volatile
    private var pendingPlaceholder: Boolean = false

    /**
     * Set when this receiver answers 400 to the placeholder (1.3.1):
     * some receivers (an Apple TV in issue #5) refuse the placeholder
     * SET_PARAMETERs that a HomePod accepts. Once refused, the
     * placeholder is never queued again in this session, and the
     * engine is told via [onPlaceholderRefused] so it can remember the
     * refusal per receiver across sessions. Real metadata is a
     * different path ([setMetadata]) and is never affected.
     */
    private val placeholderRefusedHere =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /** Invoked once when the receiver refuses the placeholder (400). */
    @Volatile
    var onPlaceholderRefused: (() -> Unit)? = null

    /**
     * Push now-playing metadata (DMAP) + artwork (JPEG) to the receiver.
     * Non-blocking since 1.2.1 (see [setVolumeDb]): this only records
     * the values; the streaming thread transmits them. Strictly
     * best-effort — failures are logged, never thrown, and the stream
     * is never delayed by them.
     */
    fun setMetadata(title: String, artist: String, album: String, artworkJpeg: ByteArray?) {
        pendingMetadata = Triple(title, artist, album)
        pendingArtwork = artworkJpeg
        pendingArtworkType = "image/jpeg"
        pendingPlaceholder = false
        metadataDirty.set(true)
    }

    /**
     * Queue the placeholder track info ("Airvia" + the app logo) used
     * when the source app provides no now-playing metadata at stream
     * start. Only ever queued when there is NO real metadata — a later
     * [setMetadata] replaces it wholesale.
     */
    fun setPlaceholderMetadata(title: String, artist: String, artworkPng: ByteArray?) {
        if (placeholderRefusedHere.get()) return
        pendingMetadata = Triple(title, artist, "")
        pendingArtwork = artworkPng
        pendingArtworkType = "image/png"
        pendingPlaceholder = true
        metadataDirty.set(true)
    }

    /** Streaming-thread only: a 400 on the placeholder path. Log once,
     *  drop the queued placeholder for this session, and tell the
     *  engine so the refusal is remembered for this receiver. */
    private fun notePlaceholderRefused(status: Int) {
        if (!placeholderRefusedHere.compareAndSet(false, true)) return
        LogBus.log(
            "[$TAG] receiver refused placeholder track info " +
                "(HTTP $status) — remembering it; not sending again",
        )
        try {
            onPlaceholderRefused?.invoke()
        } catch (_: Exception) {
        }
    }

    /**
     * Streaming-thread only: transmit queued metadata + artwork.
     * Returns false when an exchange failed at the transport level.
     */
    private fun flushMetadataNow(): Boolean {
        val meta = pendingMetadata ?: return true
        val art = pendingArtwork
        val artType = pendingArtworkType
        val isPlaceholder = pendingPlaceholder
        metadataDirty.set(false)
        return try {
            var metaStatus: Int? = null
            var artStatus: Int? = null
            val body = Dmap.metadataBody(meta.first, meta.second, meta.third)
            if (body.isNotEmpty()) {
                val resp = rtsp(
                    "SET_PARAMETER", rtspUri, body, "application/x-dmap-tagged",
                )
                metaStatus = resp.status
                LogBus.log(
                    "[$TAG] SET_PARAMETER metadata '${meta.first}' -> ${resp.status}")
            }
            if (art != null && art.isNotEmpty()) {
                val resp = rtsp("SET_PARAMETER", rtspUri, art, artType)
                artStatus = resp.status
                LogBus.log("[$TAG] SET_PARAMETER artwork (${art.size}B) -> ${resp.status}")
            }
            if (isPlaceholder) {
                val refused = listOfNotNull(metaStatus, artStatus)
                    .firstOrNull { PlaceholderGate.isRefusal(it) }
                if (refused != null) {
                    notePlaceholderRefused(refused)
                } else {
                    LogBus.log("[$TAG] placeholder metadata sent (no track info from source app)")
                }
            }
            true
        } catch (e: Exception) {
            LogBus.log("[$TAG] SET_PARAMETER metadata failed: ${e.message}")
            false
        }
    }
    data class RtspResp(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private class AudioProbeFailed(step: String, reason: String) : Exception("$step: $reason")

    private val TAG = "airvia"
    private val sessionId = Random.nextInt(1, Int.MAX_VALUE)
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private val rtspUri = "rtsp://$localIp/$sessionId"
    private val audioKey = k64.copyOf(32)
    private val eventInKey = Hkdf.derive(
        k64, "Events-Salt".toByteArray(), "Events-Write-Encryption-Key".toByteArray(), 32)
    private val eventOutKey = Hkdf.derive(
        k64, "Events-Salt".toByteArray(), "Events-Read-Encryption-Key".toByteArray(), 32)
    private var cseq = 1

    // Diagnostic counters (logged at end of streaming).
    private val timingAnswered = AtomicInteger(0)
    private val controlPacketsSeen = AtomicInteger(0)
    private val retransmitsServed = AtomicInteger(0)
    private val eventsAnswered = AtomicInteger(0)

    // Reference identity headers (owntone/pyatv parity).
    private val dacpId = "A1B2C3D4E5F60718"
    private val activeRemote = "123456789"

    private fun stdHeaders(): LinkedHashMap<String, String> = linkedMapOf(
        "User-Agent" to "AirPlay/550.10",
        "DACP-ID" to dacpId,
        "Active-Remote" to activeRemote,
        "Client-Instance" to dacpId,
        "X-Apple-Client-Name" to "Airvia",
    )

    /** Sends an encrypted RTSP request over the paired control channel. */
    private fun rtsp(
        method: String,
        uri: String,
        body: ByteArray,
        contentType: String?,
        extra: Map<String, String> = emptyMap(),
        logRaw: Boolean = false,
    ): RtspResp = synchronized(rtspLock) {
        rtspLocked(method, uri, body, contentType, extra, logRaw)
    }

    private fun rtspLocked(
        method: String,
        uri: String,
        body: ByteArray,
        contentType: String?,
        extra: Map<String, String> = emptyMap(),
        logRaw: Boolean = false,
    ): RtspResp {
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${cseq++}\r\n")
        for ((k, v) in stdHeaders()) sb.append("$k: $v\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        if (body.isNotEmpty()) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.US_ASCII)
        channel.sendFrame(head + body)
        val resp = readRtspResponse()
        if (logRaw || resp.size < 16) {
            val hex = resp.take(96).joinToString("") { "%02x".format(it) }
            val ascii = resp.take(96).map {
                val c = it.toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString("")
            LogBus.log("[$TAG] raw reply (${resp.size}B): hex=$hex")
            LogBus.log("[$TAG] raw reply ascii: $ascii")
        }
        val text = resp.toString(Charsets.ISO_8859_1)
        // Bare-plist reply (no RTSP wrapper): treat the whole frame as the body.
        if (resp.size >= 8 && resp.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00") {
            LogBus.log("[$TAG] reply is a bare bplist (${resp.size}B, no RTSP wrapper)")
            return RtspResp(200, emptyMap(), resp)
        }
        val statusLine = text.lineSequence().firstOrNull() ?: ""
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val headers = mutableMapOf<String, String>()
        for (line in text.lineSequence().drop(1)) {
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val headerEnd = text.indexOf("\r\n\r\n")
        val bodyBytes = if (headerEnd >= 0) resp.copyOfRange(headerEnd + 4, resp.size) else ByteArray(0)
        return RtspResp(status, headers, bodyBytes)
    }

    /**
     * Reads decrypted frames until a complete response is assembled:
     * headers + Content-Length body, like the reference's rxBuf_ loop.
     * A first frame that is a bare bplist is returned as-is.
     */
    private fun readRtspResponse(): ByteArray {
        val buf = ByteArrayOutputStream()
        while (true) {
            val frame = channel.readFrame() // throws on timeout / EOF / tag mismatch
            if (buf.size() == 0 && frame.size >= 8 &&
                frame.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "bplist00"
            ) {
                return frame
            }
            buf.write(frame)
            val bytes = buf.toByteArray()
            val text = bytes.toString(Charsets.ISO_8859_1)
            val headEnd = text.indexOf("\r\n\r\n")
            if (headEnd >= 0) {
                var contentLen = 0
                for (line in text.substring(0, headEnd).lineSequence().drop(1)) {
                    val i = line.indexOf(':')
                    if (i > 0 && line.substring(0, i).trim().equals("content-length", true)) {
                        contentLen = line.substring(i + 1).trim().toIntOrNull() ?: 0
                    }
                }
                if (contentLen < 0) contentLen = 0
                if (bytes.size >= headEnd + 4 + contentLen) {
                    return bytes.copyOf(headEnd + 4 + contentLen)
                }
                LogBus.log(
                    "[$TAG] response split across frames " +
                        "(${bytes.size}B so far, need ${headEnd + 4 + contentLen}B)")
            }
            // else: no complete headers yet — read the next frame.
        }
    }

    fun run() {
        var audioSock: DatagramSocket? = null
        var controlSock: DatagramSocket? = null
        var timingSock: DatagramSocket? = null
        var eventSock: Socket? = null
        val eventRunning = AtomicBoolean(false)
        var eventThread: Thread? = null
        val timingRunning = AtomicBoolean(false)
        var timingThread: Thread? = null
        try {
            // -- step 1: GET /info --------------------------------------
            LogBus.log("[$TAG] audio step 1: encrypted GET /info")
            val info = rtsp("GET", "/info", ByteArray(0), null)
            LogBus.log("[$TAG] GET /info -> ${info.status} (body ${info.body.size}B)")
            if (info.status / 100 != 2) throw AudioProbeFailed("audio GET /info", "status ${info.status}")

            // -- step 2: local UDP sockets -------------------------------
            audioSock = DatagramSocket()
            controlSock = DatagramSocket()
            timingSock = DatagramSocket()
            val audioPort = audioSock.localPort
            val controlPort = controlSock.localPort
            val timingPort = timingSock.localPort
            LogBus.log("[$TAG] UDP bound: audio=$audioPort control=$controlPort timing=$timingPort")

            // -- step 2b: timing responder (owntone parity) ----------------
            // owntone runs its NTP timing service from init, long before any
            // SETUP. The HomePod starts NTP sync as soon as it sees
            // timingPort in the session SETUP; if nobody answers, the SETUP
            // itself can stall. Start responding now, keep running through
            // the whole session.
            timingRunning.set(true)
            timingThread = thread(name = "ap2-timing", isDaemon = true) {
                val tSock = timingSock
                val buf = ByteArray(32)
                tSock.soTimeout = 500
                while (timingRunning.get()) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        tSock.receive(pkt)
                        if (pkt.length < 32) continue
                        val req = pkt.data.copyOfRange(0, 32)
                        val resp = Ap2AudioPackets.timingResponse(req, Ap2AudioPackets.ntpNow())
                        tSock.send(DatagramPacket(resp, resp.size, pkt.socketAddress))
                        if (timingAnswered.incrementAndGet() == 1) {
                            LogBus.log("[$TAG] timing: first request from ${pkt.address}:${pkt.port} answered")
                        }
                    } catch (_: java.net.SocketTimeoutException) { /* loop */ }
                    catch (_: Exception) { break }
                }
            }
            LogBus.log("[$TAG] timing responder live on port $timingPort")

            // -- step 3: session SETUP (binary plist) --------------------
            LogBus.log("[$TAG] audio step 2: session SETUP (bplist, uuid=$sessionUuid)")
            val deviceId = "5C:96:9D:88:11:22"
            val setupPlist = BplistWriter.write(mapOf(
                "deviceID" to deviceId,
                "sessionUUID" to sessionUuid,
                "timingPort" to timingPort,
                "timingProtocol" to "NTP",
                "isMultiSelectAirPlay" to true,
                "groupContainsGroupLeader" to false,
                "macAddress" to deviceId,
                "model" to "iPhone16,2",
                "name" to "Airvia",
                "osBuildVersion" to "20F66",
                "osName" to "iPhone OS",
                "osVersion" to "16.5",
                "senderSupportsRelay" to false,
                "sourceVersion" to "690.7.1",
                "statsCollectionEnabled" to false,
            ))
            val setupResp = rtsp(
                "SETUP", rtspUri, setupPlist,
                "application/x-apple-binary-plist",
                emptyMap(),
                logRaw = true,
            )
            LogBus.log(
                "[$TAG] session SETUP -> ${setupResp.status} (body ${setupResp.body.size}B)")
            if (setupResp.status / 100 != 2) {
                throw AudioProbeFailed(
                    "audio session SETUP",
                    "status ${setupResp.status} body=${setupResp.body.size}B")
            }
            val setupRoot = BplistReader.read(setupResp.body) as? BplistReader.Val.Dict
                ?: throw AudioProbeFailed("audio session SETUP", "response not a bplist dict")
            val eventPort = ((setupRoot.int("eventPort")?.toInt() ?: 0) and 0xFFFF)
            LogBus.log("[$TAG] session SETUP ok, eventPort=$eventPort")
            if (eventPort == 0) throw AudioProbeFailed("audio session SETUP", "no eventPort")

            // -- step 4: event channel -----------------------------------
            LogBus.log("[$TAG] audio step 3: event channel -> $host:$eventPort")
            eventSock = Socket()
            eventSock.tcpNoDelay = true
            eventSock.connect(InetSocketAddress(host, eventPort), 8000)
            eventSock.soTimeout = 500
            LogBus.log("[$TAG] event channel open")
            eventRunning.set(true)
            val evSock = eventSock
            eventThread = thread(name = "ap2-event", isDaemon = true) {
                eventResponder(evSock, eventRunning)
            }

            // -- step 5: RECORD (empty; owntone order, before stream SETUP)
            LogBus.log("[$TAG] audio step 4: RECORD")
            val recordResp = rtsp("RECORD", rtspUri, ByteArray(0), null)
            LogBus.log("[$TAG] RECORD -> ${recordResp.status}")
            if (recordResp.status / 100 != 2) {
                throw AudioProbeFailed("audio RECORD", "status ${recordResp.status}")
            }

            // -- step 6: stream SETUP (binary plist) ---------------------
            LogBus.log("[$TAG] audio step 5: stream SETUP (bplist)")
            val streamPlist = BplistWriter.write(mapOf(
                "streams" to listOf(mapOf(
                    "audioFormat" to 0x40000,
                    "audioMode" to "default",
                    "controlPort" to controlPort,
                    "ct" to 2,
                    "isMedia" to true,
                    "latencyMax" to sampleRate * 2,
                    "latencyMin" to sampleRate / 4,
                    "shk" to audioKey,
                    "spf" to 352,
                    "sr" to sampleRate,
                    "type" to 0x60,
                    "supportsDynamicStreamID" to false,
                    "streamConnectionID" to sessionId.toLong(),
                )),
            ))
            val streamResp = rtsp(
                "SETUP", rtspUri, streamPlist,
                "application/x-apple-binary-plist",
                emptyMap(),
            )
            LogBus.log(
                "[$TAG] stream SETUP -> ${streamResp.status} (body ${streamResp.body.size}B)")
            if (streamResp.status / 100 != 2) {
                throw AudioProbeFailed(
                    "audio stream SETUP",
                    "status ${streamResp.status} body=${streamResp.body.size}B")
            }
            val streamRoot = BplistReader.read(streamResp.body) as? BplistReader.Val.Dict
                ?: throw AudioProbeFailed("audio stream SETUP", "response not a bplist dict")
            val s0 = (streamRoot.arr("streams")?.list?.firstOrNull()
                as? BplistReader.Val.Dict)
                ?: throw AudioProbeFailed("audio stream SETUP", "no streams array")
            val dataPort = ((s0.int("dataPort")?.toInt() ?: 0) and 0xFFFF)
            var serverControlPort = ((s0.int("controlPort")?.toInt() ?: 0) and 0xFFFF)
            if (dataPort == 0) throw AudioProbeFailed("audio stream SETUP", "no dataPort")
            if (serverControlPort == 0) serverControlPort = dataPort
            LogBus.log(
                "[$TAG] stream SETUP ok, dataPort=$dataPort controlPort=$serverControlPort")

            // -- step 7: timing responder + streaming --------------------
            val ssrc = Random.nextInt().toLong() and 0xFFFFFFFFL
            // RTP timestamps are small 32-bit values (reference: latency +
            // framesSent; owntone: 88200 + pos), NOT NTP-derived. The huge
            // NTP value truncated to 32 bits is garbage the HomePod can't
            // correlate with sync packets -> silence.
            val startTs = Ap2AudioPackets.latencyFor(sampleRate).toULong()
            LogBus.log("[$TAG] audio step 6: streaming live PCM (ssrc=$ssrc ts0=$startTs)")
            streamAudio(
                audioSock, controlSock, timingSock,
                dataPort, serverControlPort, ssrc, startTs,
            )

            // -- step 8: TEARDOWN -----------------------------------------
            LogBus.log("[$TAG] audio step 7: TEARDOWN")
            try {
                val td = rtsp("TEARDOWN", rtspUri, ByteArray(0), null)
                LogBus.log("[$TAG] TEARDOWN -> ${td.status}")
            } catch (e: Exception) {
                LogBus.log("[$TAG] TEARDOWN failed (session already gone?): ${e.message}")
            }
            LogBus.log("[$TAG] AP2 session ended — audio streamed to HomePod")
        } finally {
            eventRunning.set(false)
            timingRunning.set(false)
            try { eventThread?.join(1000) } catch (_: Exception) {}
            try { timingThread?.join(1000) } catch (_: Exception) {}
            try { eventSock?.close() } catch (_: Exception) {}
            try { audioSock?.close() } catch (_: Exception) {}
            try { controlSock?.close() } catch (_: Exception) {}
            try { timingSock?.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // Event-channel responder: decrypt pushed events with the Events-Write
    // key, answer each RTSP request with an encrypted bare 200 OK using the
    // Events-Read key. Same HAP framing as the control channel, independent
    // per-direction counters. The receiver tears the session down after ~25 s
    // if its events go unanswered.
    // ------------------------------------------------------------------
    private fun eventResponder(sock: Socket, running: AtomicBoolean) {
        var recvCtr = 0L
        var sendCtr = 0L
        val encBuf = ByteArrayOutputStream()
        val plainBuf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        try {
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()
            while (running.get()) {
                // The socket has a 500 ms read timeout so the loop can
                // notice teardown. A timeout is NOT end-of-stream: the
                // pre-1.2.2 code treated it as EOF, so this responder
                // died ~0.5 s into every session and every later event
                // the receiver pushed went unanswered.
                val n = try { inp.read(tmp) } catch (_: java.net.SocketTimeoutException) { 0 }
                    catch (_: Exception) { -1 }
                if (n < 0) break
                if (n == 0) continue
                encBuf.write(tmp, 0, n)
                // Frame-parse: [2B LE len][cipher][16B tag], AAD = len bytes.
                while (encBuf.size() >= 2) {
                    val eb = encBuf.toByteArray()
                    val len = (eb[0].toInt() and 0xFF) or ((eb[1].toInt() and 0xFF) shl 8)
                    if (eb.size < 2 + len + 16) break
                    val aad = eb.copyOfRange(0, 2)
                    val ctTag = eb.copyOfRange(2, 2 + len + 16)
                    // HAP framing nonce: 4 zero bytes + u64 LE counter in
                    // bytes 4..11 (same as Ap2Channel). Counter in bytes 0..7
                    // made every pushed event undecryptable.
                    val nonce12 = ByteArray(12)
                    for (i in 0 until 8) nonce12[4 + i] = ((recvCtr ushr (8 * i)) and 0xFF).toByte()
                    recvCtr++
                    val dec = ChaCha20Poly1305.open(eventInKey, nonce12, ctTag, aad)
                    if (dec == null) {
                        LogBus.log("[$TAG] event channel: decrypt failed, dropping")
                        encBuf.reset()
                        break
                    }
                    plainBuf.write(dec)
                    val rest = eb.copyOfRange(2 + len + 16, eb.size)
                    encBuf.reset()
                    encBuf.write(rest)
                }
                // Answer each complete RTSP request with an encrypted 200 OK.
                while (true) {
                    val pb = plainBuf.toByteArray().toString(Charsets.ISO_8859_1)
                    val headEnd = pb.indexOf("\r\n\r\n")
                    if (headEnd < 0) break
                    var contentLen = 0
                    var cseqEv = ""
                    for (line in pb.substring(0, headEnd).split("\r\n").drop(1)) {
                        val i = line.indexOf(':')
                        if (i <= 0) continue
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        if (k == "content-length") contentLen = v.toIntOrNull() ?: 0
                        else if (k == "cseq") cseqEv = v
                    }
                    val total = headEnd + 4 + contentLen
                    val raw = plainBuf.toByteArray()
                    if (raw.size < total) break
                    val firstLine = pb.substring(0, headEnd).lineSequence().firstOrNull() ?: ""
                    LogBus.log("[$TAG] event channel: decrypted request: $firstLine")
                    plainBuf.reset()
                    plainBuf.write(raw, total, raw.size - total)
                    val resp = buildString {
                        append("RTSP/1.0 200 OK\r\n")
                        append("Server: AirTunes/550.10\r\n")
                        if (cseqEv.isNotEmpty()) append("CSeq: $cseqEv\r\n")
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    val lp = byteArrayOf(
                        (resp.size and 0xFF).toByte(), ((resp.size shr 8) and 0xFF).toByte())
                    val wnonce = ByteArray(12)
                    for (i in 0 until 8) wnonce[4 + i] = ((sendCtr ushr (8 * i)) and 0xFF).toByte()
                    sendCtr++
                    val sealed = ChaCha20Poly1305.seal(eventOutKey, wnonce, resp, lp)
                    out.write(lp + sealed)
                    out.flush()
                    LogBus.log(
                        "[$TAG] event channel: answered pushed request (200 OK) " +
                            "#${eventsAnswered.incrementAndGet()}")
                }
            }
        } catch (_: Exception) { /* socket closed on teardown */ }
    }

    // ------------------------------------------------------------------
    // Audio streaming: sync packets, NTP timing responder, volume, RTP.
    // ------------------------------------------------------------------
    private fun streamAudio(
        audioSock: DatagramSocket,
        controlSock: DatagramSocket,
        timingSock: DatagramSocket,
        dataPort: Int,
        serverControlPort: Int,
        ssrc: Long,
        startTs: ULong,
    ) {
        val serverAddr = InetSocketAddress(host, dataPort)
        val serverControlAddr = InetSocketAddress(host, serverControlPort)

        // Timing responder already live (started before session SETUP).

        run {
            LogBus.log("[$TAG] streaming live PCM from pipeline")

            // Control-socket drain: retransmit requests (0xD5) and anything
            // else the receiver sends to our advertised control port. Replies
            // go out on the CONTROL socket to the requester, prefixed with
            // 0x80 0xD6 + original seq (pyatv/owntone parity). Every packet is
            // counted/logged: silence here is diagnostic too.
            controlSock.soTimeout = 5
            // Insertion-ordered so eviction drops the OLDEST packet even
            // after the 16-bit sequence number wraps (a min-key eviction
            // throws away fresh packets post-wrap). 1024 deep = ~8.2 s of
            // audio (pyatv's backlog is 1000).
            val backlog = LinkedHashMap<Int, ByteArray>()
            fun drainRetransmits() {
                val buf = ByteArray(512)
                while (true) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        controlSock.receive(p)
                        val seen = controlPacketsSeen.incrementAndGet()
                        val type = if (p.length >= 2) buf[1].toInt() and 0xFF else -1
                        if (p.length >= 8 && buf[0] == 0x80.toByte() && type == 0xD5) {
                            val first = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
                            val count = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
                            LogBus.log(
                                "[$TAG] control: retransmit request first=$first count=$count from port ${p.port}")
                            for (i in 0 until count) {
                                val s2 = (first + i) and 0xFFFF
                                backlog[s2]?.let { pkt ->
                                    val resp = byteArrayOf(
                                        0x80.toByte(), 0xD6.toByte(),
                                        (s2 ushr 8).toByte(), s2.toByte()) + pkt
                                    controlSock.send(DatagramPacket(resp, resp.size, p.socketAddress))
                                    retransmitsServed.incrementAndGet()
                                }
                            }
                        } else {
                            LogBus.log(
                                "[$TAG] control: packet type=0x${"%02x".format(type)} " +
                                    "len=${p.length} from port ${p.port} (#$seen)")
                        }
                    } catch (_: java.net.SocketTimeoutException) { break }
                    catch (_: Exception) { break }
                }
            }

            // Initial sync (marker bit) to the control port. MUST leave from
            // the advertised control socket (pyatv ControlClient / owntone
            // control service parity): sync sent from the audio socket comes
            // from an unknown source port and the receiver ignores it, so
            // playout is never scheduled -> total silence.
            // Session timeline epoch: RTP timestamp startTs is bound to
            // this one wall-clock instant for the WHOLE session. Every
            // sync packet derives its NTP field from the RTP timestamp
            // through this fixed mapping (pyatv/owntone parity — see
            // Ap2AudioPackets.syncNtp). The pre-1.2.2 code paired the
            // stream head with a fresh ntpNow() each second while the
            // pacing loop let source stalls push the data timeline
            // behind the wall clock; the receiver's playout lead eroded
            // until it closed the control channel at ~36 s.
            val epochNtp = Ap2AudioPackets.ntpNow()
            fun syncNtpFor(rtpTs: ULong): ULong =
                Ap2AudioPackets.syncNtp(epochNtp, startTs, rtpTs, sampleRate)

            val sync0 = Ap2AudioPackets.syncPacket(true, startTs.toLong(), Ap2AudioPackets.latencyFor(sampleRate).toLong(), syncNtpFor(startTs))
            run {
                val hex = sync0.joinToString("") { "%02x".format(it) }
                LogBus.log("[$TAG] DIAG sync0 hex20=$hex")
            }
            controlSock.send(DatagramPacket(sync0, sync0.size, serverControlAddr))
            LogBus.log("[$TAG] initial sync sent (marker)")

            // Volume via SET_PARAMETER text/parameters (AP1+AP2 parity);
            // uses the level set through setVolumeDb (default 0 dB = max).
            // Capture + clear the dirty flag BEFORE sending so a change
            // landing mid-send is not lost (it flushes in the loop).
            streamReady = true
            val initialDb = volumeDb
            volumeDirty.set(false)
            val vol = rtsp("SET_PARAMETER", rtspUri,
                "volume: ${"%.6f".format(java.util.Locale.US, initialDb)}"
                    .toByteArray(Charsets.US_ASCII), "text/parameters")
            LogBus.log("[$TAG] SET_PARAMETER volume -> ${vol.status}")
            if (pendingMetadata != null) flushMetadataNow()

            var lastSyncMs = System.currentTimeMillis()
            var lastFeedbackMs = 0L
            var lastControlFlushMs = 0L
            var controlFailures = 0
            var seq = 1
            // Nonce = RTP seqnum (owntone parity: "Using seqnum as nonce").
            // The receiver derives the ChaCha nonce from the packet's sequence
            // number; a separate counter desyncs decryption -> silence.
            var framesSent = 0L
            var silenceSent = 0L
            val t0 = System.nanoTime()
            var pending: ShortArray? = null
            val silenceChunk = ShortArray(352 * 2)
            onStreaming()
            while (!stopRequested) {
                if (pending == null) {
                    if (pcm.finished) break
                    pending = pcm.nextInterleaved()
                    if (pending == null && pcm.finished) break
                }
                // Pace strictly off the wall clock from t0 — the RTP
                // timeline NEVER waits for the source. When the source
                // starves, silence fills the gap so the head timestamp
                // keeps tracking the epoch (pyatv sends padding packets
                // the same way). The pre-1.2.2 loop subtracted source
                // wait time from the pacing clock, permanently shifting
                // the timeline behind the wall clock on every hiccup.
                val due = Ap2AudioPackets.framesDue(System.nanoTime() - t0, sampleRate)
                var sentThisTick = 0
                while (framesSent + 352 <= due && sentThisTick < 16 && !stopRequested) {
                    val chunk: ShortArray
                    if (pending != null) {
                        chunk = pending!!
                        pending = null
                    } else if (pcm.finished) {
                        break
                    } else {
                        chunk = silenceChunk
                        silenceSent++
                    }
                    val ts = startTs + framesSent.toULong()
                    val hdr = Ap2AudioPackets.rtpHeader(seq, ts.toLong(), ssrc, framesSent == 0L)
                    val payload = AlacFrame.buildUncompressed(chunk)
                    val wire = Ap2AudioPackets.encryptAudioPayload(audioKey, seq.toLong(), hdr, payload)
                    val pkt = hdr + wire
                    // DIAG: dump first 2 RTP packets for byte-level comparison
                    if (framesSent == 0L || framesSent == 352L) {
                        val hex = pkt.take(48).joinToString("") { "%02x".format(it) }
                        LogBus.log("[$TAG] DIAG rtp#${framesSent / 352} seq=$seq ts=$ts ssrc=$ssrc len=${pkt.size} hex48=$hex")
                        val payHex = payload.take(16).joinToString("") { "%02x".format(it) }
                        LogBus.log("[$TAG] DIAG alac payload16=$payHex")
                    }
                    backlog[seq] = pkt
                    if (backlog.size > 1024) backlog.remove(backlog.keys.first())
                    audioSock.send(DatagramPacket(pkt, pkt.size, serverAddr))
                    framesSent += 352
                    seq = (seq + 1) and 0xFFFF
                    sentThisTick++
                    if (pending == null && !pcm.finished) {
                        pending = pcm.nextInterleaved()
                    }
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastSyncMs >= 1000) {
                    lastSyncMs = nowMs
                    val rtpTs = startTs + framesSent.toULong()
                    val sp = Ap2AudioPackets.syncPacket(
                        false, rtpTs.toLong(), Ap2AudioPackets.latencyFor(sampleRate).toLong(), syncNtpFor(rtpTs))
                    controlSock.send(DatagramPacket(sp, sp.size, serverControlAddr))
                }
                if (nowMs - lastFeedbackMs >= 2000) {
                    lastFeedbackMs = nowMs
                    try {
                        val fb = rtsp("POST", "/feedback", ByteArray(0), null)
                        LogBus.log("[$TAG] POST /feedback -> ${fb.status}")
                        controlFailures = 0
                    } catch (e: Exception) {
                        LogBus.log("[$TAG] POST /feedback failed: ${e.message}")
                        controlFailures++
                    }
                }
                // Queued volume/metadata changes are flushed HERE, on the
                // streaming thread — since 1.2.1 the control channel has a
                // single owner, so requests can never cross, queue up
                // behind a foreign thread's round trip, or desync the
                // HAP framing via another thread's timed-out read.
                if (nowMs - lastControlFlushMs >= 200) {
                    lastControlFlushMs = nowMs
                    var exchanged = false
                    var ok = true
                    if (volumeDirty.get()) {
                        exchanged = true
                        if (!flushVolumeNow()) ok = false
                    }
                    if (metadataDirty.get()) {
                        exchanged = true
                        if (!flushMetadataNow()) ok = false
                    }
                    if (exchanged) {
                        if (ok) controlFailures = 0 else controlFailures++
                    }
                }
                if (controlFailures >= 3) {
                    // A control channel that keeps failing at the
                    // transport level is desynced beyond recovery (HAP
                    // framing has no resync). End the session honestly
                    // instead of zombie-streaming audio the receiver is
                    // about to drop anyway when /feedback stops landing.
                    LogBus.log(
                        "[$TAG] control channel failed $controlFailures times " +
                            "in a row — ending session")
                    break
                }
                drainRetransmits()
                Thread.sleep(2)
            }
            // Drain tail: let the last packets arrive before TEARDOWN.
            Thread.sleep(1500)
            LogBus.log("[$TAG] streamed $framesSent frames (${framesSent / 352} packets, $silenceSent silence)")
            LogBus.log(
                "[$TAG] counters: timingRequests=${timingAnswered.get()} " +
                    "controlPackets=${controlPacketsSeen.get()} " +
                    "retransmits=${retransmitsServed.get()} " +
                    "eventsAnswered=${eventsAnswered.get()} " +
                    "silencePackets=$silenceSent")
        }
    }
}
