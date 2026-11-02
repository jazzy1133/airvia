package com.opus.airvia.ap2

/**
 * AirPlay 2 realtime-audio packet builders (pure functions, unit-tested).
 *
 * Audio path after the encrypted control channel is up:
 *  - RTP audio packets -> UDP dataPort from the stream-SETUP reply
 *  - sync packets      -> UDP controlPort from the stream-SETUP reply
 *  - timing responder  -> UDP localTimingPort (advertised in session SETUP)
 */
object Ap2AudioPackets {
    const val SAMPLE_RATE = 44100
    const val FRAMES_PER_PACKET = 352
    /** Fixed RAOP latency model: 22050 + 44100 frames. */
    const val LATENCY = 22050 + 44100

    /** 64-bit NTP from the wall clock (seconds since 1900 in the high word). */
    fun ntpNow(): ULong {
        val us = System.currentTimeMillis().toULong() * 1000UL +
            ((System.nanoTime() % 1_000_000L).toULong() / 1000UL)
        val sec = us / 1_000_000UL
        val frac = us % 1_000_000UL
        return ((sec + 0x83AA7E80UL) shl 32) or ((frac shl 32) / 1_000_000UL)
    }

    /** NTP (u64) -> RTP-timestamp units at [rate]. */
    fun ntp2ts(ntp: ULong, rate: Int): ULong = ((ntp shr 16) * rate.toULong()) shr 16

    /** RTP-timestamp units at [rate] -> NTP (u64). */
    fun ts2ntp(ts: ULong, rate: Int): ULong {
        val sec = ts / rate.toULong()
        val frac = ts % rate.toULong()
        return (sec shl 32) or ((frac shl 32) / rate.toULong())
    }

    /**
     * The session sync mapping: NTP time for RTP timestamp [rtpTs] under
     * the FIXED epoch captured at stream start ([epochNtp] is the wall
     * clock bound to RTP timestamp [epochTs]).
     *
     * This is the mapping every known-good sender uses (pyatv
     * ControlClient._sync_task derives the sync NTP from the head RTP
     * timestamp through the StreamContext epoch; owntone
     * sync_packet_ntp_make does the same). Pairing the head timestamp
     * with a FRESH wall-clock sample instead — the pre-1.2.2 behaviour —
     * tells the receiver its playout anchor is "now" while the data
     * timeline lags behind by every source stall the sender absorbed,
     * so the receiver's lead erodes until it gives up and closes the
     * session (~32 s in the Oppo/HomePod logs of 2026-10-03).
     */
    fun syncNtp(
        epochNtp: ULong, epochTs: ULong, rtpTs: ULong,
        rate: Int = SAMPLE_RATE,
    ): ULong =
        epochNtp + ts2ntp(rtpTs - epochTs, rate)

    /** Playout latency in frames at [rate] (0.5 s + 1 s, as LATENCY). */
    fun latencyFor(rate: Int): Int = rate / 2 + rate

    /**
     * Frames that must have been sent [elapsedNs] after stream start to
     * hold the RTP timeline on the wall clock. The sender paces against
     * this and pads SILENCE when the PCM source starves, so the timeline
     * never stalls (pyatv sends padding packets the same way).
     */
    fun framesDue(elapsedNs: Long, rate: Int = SAMPLE_RATE): Long =
        if (elapsedNs <= 0L) 0L else elapsedNs * rate / 1_000_000_000L

    private fun putBe16(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 8).toByte()
        out[off + 1] = v.toByte()
    }

    private fun putBe32(out: ByteArray, off: Int, v: Long) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }

    private fun putBe64(out: ByteArray, off: Int, v: ULong) {
        putBe32(out, off, (v shr 32).toLong())
        putBe32(out, off + 4, (v and 0xFFFFFFFFUL).toLong())
    }

    /** 12-byte RTP header: 0x80, marker|0x60, seq, rtpTime, ssrc (all BE). */
    fun rtpHeader(seq: Int, rtpTime: Long, ssrc: Long, first: Boolean): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = (if (first) 0xE0 else 0x60).toByte()
        putBe16(h, 2, seq and 0xFFFF)
        putBe32(h, 4, rtpTime)
        putBe32(h, 8, ssrc)
        return h
    }

    /**
     * Encrypts one ALAC payload with the audio key (first 32B of the raw SRP
     * secret). AAD = RTP header bytes 4..12; nonce = 8-byte LE counter padded
     * to 12 with 4 leading zero bytes. Returns ciphertext || tag || nonce8
     * (the 8-byte nonce is appended after the tag on the wire).
     */
    fun encryptAudioPayload(
        audioKey: ByteArray,
        counter: Long,
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        require(audioKey.size == 32) { "audio key must be 32 bytes" }
        val nonce12 = ByteArray(12)
        for (i in 0..7) nonce12[4 + i] = (counter ushr (8 * i)).toByte()
        val aad = header.copyOfRange(4, 12)
        val sealed = ChaCha20Poly1305.seal(audioKey, nonce12, payload, aad)
        val nonce8 = nonce12.copyOfRange(4, 12)
        return sealed + nonce8
    }

    /**
     * 20-byte sync packet to the control port.
     * @param rtpNow latency-shifted RTP time (latency + framesSent)
     * @param curNtp NTP wall time for (startTs + framesSent)
     */
    fun syncPacket(first: Boolean, rtpNow: Long, latency: Long, curNtp: ULong): ByteArray {
        val p = ByteArray(20)
        p[0] = (if (first) 0x90 else 0x80).toByte()
        p[1] = 0xD4.toByte()
        putBe16(p, 2, 0x0007)
        putBe32(p, 4, rtpNow - latency)
        putBe64(p, 8, curNtp)
        putBe32(p, 16, rtpNow)
        return p
    }

    /**
     * 32-byte timing response to a 32-byte timing request: echo proto byte,
     * 0xD3, 0x0007, padding, reftime = request sendtime, recv/send = now.
     */
    fun timingResponse(request: ByteArray, nowNtp: ULong): ByteArray {
        require(request.size >= 32) { "timing request too short" }
        val r = ByteArray(32)
        r[0] = request[0]
        r[1] = 0xD3.toByte()
        putBe16(r, 2, 0x0007)
        // bytes 4..7 stay zero (padding)
        request.copyInto(r, 8, 24, 32) // reftime = request's sendtime
        putBe64(r, 16, nowNtp) // recvtime
        putBe64(r, 24, nowNtp) // sendtime
        return r
    }
}
