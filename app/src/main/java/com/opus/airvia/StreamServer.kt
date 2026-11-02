package com.opus.airvia

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local HTTP server that serves the live capture as an endless WAV
 * stream (44.1 kHz stereo PCM16) on [PORT] — the feed DLNA renderers and
 * Chromecast pull from. Each client gets its own [BroadcastCapture]
 * consumer, so HTTP clients never disturb the AirPlay sessions.
 *
 * The WAV header uses the 0xFFFFFFFF RIFF/data sizes of a streaming WAV;
 * ffmpeg-based renderers (Sonos, most DLNA stacks) play it indefinitely.
 */
class StreamServer(private val broadcast: BroadcastCapture) {
    companion object {
        const val PORT = 8899
        const val PATH = "/stream.wav"
    }

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = mutableSetOf<Socket>()

    fun streamUrl(): String = "http://${NetUtil.localIp()}:$PORT$PATH"

    fun start() {
        if (running) return
        running = true
        thread(name = "airvia-http") {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                LogBus.log("[http] stream server on :$PORT$PATH")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    synchronized(clients) { clients.add(client) }
                    thread(name = "airvia-http-client", isDaemon = true) {
                        serve(client)
                    }
                }
            } catch (e: Exception) {
                LogBus.log("[http] server failed: ${e.message}")
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            // Consume the request headers before replying.
            val input = client.getInputStream()
            val raw = java.io.ByteArrayOutputStream()
            var window = 0L
            var count = 0
            while (true) {
                val b = input.read()
                if (b < 0) return
                raw.write(b)
                count++
                window = ((window shl 8) or b.toLong()) and 0xFFFFFFFFL
                if (window == 0x0D0A0D0AL) break // "\r\n\r\n"
                if (count > 16384) return
            }
            val requestLine = raw.toByteArray().toString(Charsets.US_ASCII)
                .lineSequence().firstOrNull() ?: ""
            LogBus.log("[http] $requestLine from ${client.inetAddress?.hostAddress}")
            val out = client.getOutputStream()
            if (!requestLine.contains(PATH)) {
                out.write(
                    ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
                out.flush()
                return
            }
            out.write(httpHeaders().toByteArray(Charsets.US_ASCII))
            out.write(wavHeader())
            out.flush()
            streamAudio(out)
        } catch (_: Exception) {
            // client went away
        } finally {
            synchronized(clients) { clients.remove(client) }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun streamAudio(out: OutputStream) {
        val consumer = broadcast.newConsumer()
        val byteBuf = ByteArray(352 * 2 * 2 * 4)
        while (running && !broadcast.finished) {
            val frames = consumer.readFrames(352 * 4, 1000)
            if (frames.isEmpty()) continue
            var bytes = 0
            for (s in frames) {
                byteBuf[bytes++] = (s.toInt() and 0xFF).toByte()
                byteBuf[bytes++] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
            out.write(byteBuf, 0, bytes)
            out.flush()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        synchronized(clients) {
            for (c in clients) try { c.close() } catch (_: Exception) {}
            clients.clear()
        }
    }

    private fun httpHeaders(): String =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: audio/wav\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            "\r\n"

    /** Canonical 44-byte WAV header with streaming (max) sizes. */
    private fun wavHeader(): ByteArray {
        val sampleRate = broadcast.sampleRate
        val channels = 2
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) h[off + i] = s[i].code.toByte()
        }
        fun putU32(off: Int, v: Long) {
            for (i in 0..3) h[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
        fun putU16(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putU32(4, 0xFFFFFFFFL)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putU32(16, 16)
        putU16(20, 1) // PCM
        putU16(22, channels)
        putU32(24, sampleRate.toLong())
        putU32(28, byteRate.toLong())
        putU16(32, blockAlign)
        putU16(34, bits)
        putStr(36, "data")
        putU32(40, 0xFFFFFFFFL)
        return h
    }
}
