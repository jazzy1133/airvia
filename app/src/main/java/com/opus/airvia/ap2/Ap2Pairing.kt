package com.opus.airvia.ap2

import com.opus.airvia.LogBus
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * AirPlay 2 transient pairing + encrypted-channel establishment for Outro's
 * HomePod transport. This is the exact flow proven on-device by the
 * standalone Airvia (2026-10-03: tone audible on a HomePod mini):
 *
 *  1. TCP connect
 *  2. POST /pair-pin-start (pyatv parity; harmless if rejected)
 *  3. POST /pair-setup M1 with the transient-pairing flag, M2 -> SRP -> M3,
 *     M4 server proof verified
 *  4. HKDF-SHA-512 control keys from the SRP session key K
 *  5. Encrypted OPTIONS * on the same connection
 *
 * On success returns an [Established] holding the open socket, the encrypted
 * control channel and K (the audio session derives shk/event keys from it).
 * The caller owns the socket and must close it when the session ends.
 * Throws on any failure — the transport falls back to classic RAOP.
 */
object Ap2Pairing {
    private const val TAG = "airvia"

    class PairingFailed(step: String, reason: String) : Exception("$step: $reason")

    class Established(
        val socket: Socket,
        val channel: Ap2Channel,
        /** SRP session key K = SHA-512(minimal S), 64 bytes. */
        val k64: ByteArray,
        val localIp: String,
    )

    fun connect(host: String, port: Int): Established {
        LogBus.log("[$TAG] step 1: TCP connect $host:$port")
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 12000
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw PairingFailed("step 1 (TCP connect)", e.message ?: e.javaClass.simpleName)
        }
        LogBus.log("[$TAG] step 1: connected (local ${socket.localAddress.hostAddress})")
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        try {
            // -- pin-start (pyatv parity) --------------------------------
            LogBus.log("[$TAG] step 2a: POST /pair-pin-start (pyatv parity)")
            val pinResp = runCatching {
                postRaw(host, input, output, "POST /pair-pin-start HTTP/1.1", ByteArray(0))
            }.getOrNull()
            LogBus.log(
                "[$TAG] /pair-pin-start -> ${pinResp?.statusLine ?: "<failed>"} " +
                    "(body ${pinResp?.body?.size ?: 0}B)",
            )

            // -- M1 -------------------------------------------------------
            LogBus.log("[$TAG] step 2: POST /pair-setup M1")
            var resp = postPairSetup(host, input, output, m1Body(), "M1")
            var entries = handlePairResponse(resp, "M1")
            if (entries == null) {
                LogBus.log("[$TAG] M1 rejected — retrying M1 once")
                resp = postPairSetup(host, input, output, m1Body(), "M1-retry")
                entries = handlePairResponse(resp, "M1-retry")
                if (entries == null) {
                    throw PairingFailed("step 2 (M1)", "rejected twice")
                }
            }
            val salt = Tlv8.first(entries, Tlv8.SALT)
                ?: throw PairingFailed("step 2 (M2)", "no salt TLV (0x02)")
            val bBytes = Tlv8.first(entries, Tlv8.PUBLIC_KEY)
                ?: throw PairingFailed("step 2 (M2)", "no pubkey TLV (0x03)")
            LogBus.log(
                "[$TAG] M2: salt=${salt.size}B B=${bBytes.size}B " +
                    "A-prefix=${Tlv8.hex(bBytes.copyOfRange(0, minOf(8, bBytes.size)))}…",
            )
            // Server sends B minimal-length (383B observed); accept 1..384.
            if (salt.size != 16 || bBytes.isEmpty() || bBytes.size > 384) {
                throw PairingFailed(
                    "step 2 (M2)",
                    "unexpected sizes salt=${salt.size} B=${bBytes.size} (want salt=16, 1<=B<=384)",
                )
            }

            // -- M3 -------------------------------------------------------
            LogBus.log("[$TAG] step 3: SRP compute + POST /pair-setup M3")
            val eph = SrpClient.newEphemeral()
            val keys = SrpClient.compute(salt, bBytes, eph.a)
            LogBus.log(
                "[$TAG] SRP: A=${eph.aPub.size}B K=${keys.k64.size}B " +
                    "M1proof=${keys.m1.size}B",
            )
            val m3 = Tlv8.encode(
                listOf(
                    Tlv8.STATE to byteArrayOf(0x03),
                    Tlv8.PUBLIC_KEY to keys.aPub,
                    Tlv8.PROOF to keys.m1,
                ),
            )
            resp = postPairSetup(host, input, output, m3, "M3")
            entries = handlePairResponse(resp, "M3")
                ?: throw PairingFailed("step 3 (M3)", "rejected (see log)")
            val serverProof = Tlv8.first(entries, Tlv8.PROOF)
                ?: throw PairingFailed("step 3 (M4)", "no proof TLV (0x04)")
            val ok = SrpClient.verifyServerProof(keys.aPub, keys.m1, keys.k64, serverProof)
            LogBus.log("[$TAG] M4: server proof ${if (ok) "VERIFIED ✓" else "MISMATCH ✗"}")
            if (!ok) throw PairingFailed("step 3 (M4)", "server proof mismatch")

            // -- keys -----------------------------------------------------
            LogBus.log("[$TAG] step 4: HKDF-SHA-512 control keys")
            val writeKey = Hkdf.derive(
                keys.k64,
                "Control-Salt".toByteArray(),
                "Control-Write-Encryption-Key".toByteArray(),
                32,
            )
            val readKey = Hkdf.derive(
                keys.k64,
                "Control-Salt".toByteArray(),
                "Control-Read-Encryption-Key".toByteArray(),
                32,
            )
            LogBus.log(
                "[$TAG] writeKey=${Tlv8.hex(writeKey.copyOfRange(0, 8))}… " +
                    "readKey=${Tlv8.hex(readKey.copyOfRange(0, 8))}…",
            )

            // -- encrypted OPTIONS ----------------------------------------
            LogBus.log("[$TAG] step 5: encrypted OPTIONS * on the same TCP connection")
            val channel = Ap2Channel(input, output, writeKey, readKey)
            val req =
                "OPTIONS * RTSP/1.0\r\n" +
                    "CSeq: 0\r\n" +
                    "Apple-Challenge: SdX9kFJVxgKVMFof/Znj4Q\r\n" +
                    "\r\n"
            channel.sendFrame(req.toByteArray(Charsets.UTF_8))
            LogBus.log("[$TAG] encrypted OPTIONS sent (${req.length}B plaintext)")
            val plain = channel.readFrame()
            val text = String(plain, Charsets.UTF_8)
            val statusLine = text.lineSequence().firstOrNull() ?: "<empty>"
            LogBus.log("[$TAG] encrypted response: $statusLine")
            LogBus.log("[$TAG] step 5: OPTIONS answered — pairing established")
            return Established(socket, channel, keys.k64, socket.localAddress.hostAddress)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun m1Body(): ByteArray = Tlv8.encode(
        listOf(
            Tlv8.METHOD to byteArrayOf(0x00),
            Tlv8.STATE to byteArrayOf(0x01),
            // Transient flag: without it the receiver expects the full HAP
            // pairing (M5/M6) and never answers the RTSP SETUP.
            Tlv8.FLAGS to byteArrayOf(Tlv8.FLAG_TRANSIENT_PAIRING.toByte()),
        ),
    )

    private data class HttpResp(val statusLine: String, val status: Int, val body: ByteArray)

    private fun postPairSetup(
        host: String,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        body: ByteArray,
        label: String,
    ): HttpResp {
        val resp = postRaw(host, input, output, "POST /pair-setup HTTP/1.1", body)
        LogBus.log(
            "[$TAG] $label -> ${resp.statusLine} (body ${resp.body.size}B) " +
                "TLV[${Tlv8.summary(runCatching { Tlv8.decode(resp.body) }.getOrElse { emptyList() })}]",
        )
        return resp
    }

    private fun postRaw(
        host: String,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        requestLine: String,
        body: ByteArray,
    ): HttpResp {
        val head =
            "$requestLine\r\n" +
                "Host: $host\r\n" +
                // HomePod OS 27 rejects plaintext pairing requests with no
                // User-Agent (HTTP 403 before any TLV). The version must look
                // like a real AirPlay sender; matches Ap2AudioSession stdHeaders().
                "User-Agent: AirPlay/550.10\r\n" +
                "X-Apple-HKP: 4\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n"
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
        return readHttpResponse(input)
    }

    private fun readHttpResponse(input: java.io.InputStream): HttpResp {
        val headBuf = ByteArrayOutputStream()
        val window = ByteArray(4)
        var filled = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed reading response head")
            headBuf.write(b)
            window[filled % 4] = b.toByte()
            filled++
            if (filled >= 4 &&
                window[(filled - 4) % 4] == '\r'.code.toByte() &&
                window[(filled - 3) % 4] == '\n'.code.toByte() &&
                window[(filled - 2) % 4] == '\r'.code.toByte() &&
                window[(filled - 1) % 4] == '\n'.code.toByte()
            ) {
                break
            }
            if (headBuf.size() > 65536) throw java.io.IOException("response head too large")
        }
        val head = String(headBuf.toByteArray(), Charsets.UTF_8)
        val lines = head.split("\r\n")
        val statusLine = lines.firstOrNull() ?: ""
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        var contentLength = -1
        for (ln in lines.drop(1)) {
            val i = ln.indexOf(':')
            if (i > 0 && ln.substring(0, i).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = ln.substring(i + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) throw java.io.IOException("no Content-Length in response ($statusLine)")
        val body = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val r = input.read(body, off, contentLength - off)
            if (r < 0) throw java.io.EOFException("connection closed reading response body")
            off += r
        }
        return HttpResp(statusLine, status, body)
    }

    /**
     * Returns the decoded TLV entries, or null when the response is an
     * error/rejection (already logged).
     */
    private fun handlePairResponse(resp: HttpResp, label: String): List<Pair<Int, ByteArray>>? {
        if (resp.status != 200) {
            LogBus.log("[$TAG] $label: HTTP ${resp.status} — rejected")
            return null
        }
        val entries = runCatching { Tlv8.decode(resp.body) }.getOrElse {
            LogBus.log("[$TAG] $label: body is not TLV8 (${it.message})")
            return null
        }
        val err = Tlv8.first(entries, Tlv8.ERROR)
        if (err != null && err.isNotEmpty()) {
            val code = err[0].toInt() and 0xFF
            LogBus.log("[$TAG] $label: TLV error ${Tlv8.errorName(code)}")
            return null
        }
        val state = Tlv8.first(entries, Tlv8.STATE)?.firstOrNull()?.toInt()?.and(0xFF)
        if (state == null) {
            LogBus.log("[$TAG] $label: no state TLV")
            return null
        }
        LogBus.log("[$TAG] $label: state=$state")
        return entries
    }
}
