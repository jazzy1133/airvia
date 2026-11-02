package com.opus.airvia

import com.opus.airvia.ap2.Ap2AudioSession
import com.opus.airvia.ap2.Ap2Pairing
import com.opus.airvia.raop.RaopConnection
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.PI
import kotlin.math.sin

/**
 * Speaker Doctor runner (1.3.0): reachability → pairing probe → L/R
 * test tones, for one AirPlay speaker. Blocking; call off the main
 * thread. Captures nothing and changes no state — the tone session is
 * a standalone network session fed by a synthetic source, so it needs
 * no media-projection consent. Every step is logged to [LogBus] as
 * well as returned.
 */
object SpeakerDoctor {

    fun run(sp: Speaker): List<DoctorStep> {
        val steps = ArrayList<DoctorStep>()
        LogBus.log("[doctor] checking '${sp.name}' (${sp.host}:${sp.port})")

        // 1) Reachability ------------------------------------------------
        val reach = tcpReachable(sp.host, sp.port)
        steps += DoctorStep(
            "Reachable",
            reach,
            if (reach) {
                "TCP ${sp.host}:${sp.port} answers"
            } else {
                "no answer on ${sp.host}:${sp.port} — same Wi-Fi? speaker on?"
            },
        )
        val raopPort = sp.raopPort
        if (raopPort != null && raopPort != sp.port) {
            val r2 = tcpReachable(sp.host, raopPort)
            steps += DoctorStep(
                "AirPlay 1 port",
                r2,
                "RAOP service on port ${sp.raopPort} " +
                    if (r2) "answers" else "does not answer",
            )
        }
        if (!reach) {
            DoctorReport.record(sp.name, steps)
            return steps
        }
        if (sp.kind != SpeakerKind.AIRPLAY) {
            steps += DoctorStep(
                "Pairing", null,
                "Doctor's deep checks cover AirPlay speakers",
            )
            steps += DoctorStep("Test tones", null, "not applicable")
            DoctorReport.record(sp.name, steps)
            return steps
        }

        // 2) Pairing probe -------------------------------------------------
        var established: Ap2Pairing.Established? = null
        var pairingOk = false
        try {
            val identity = CastEngine.identityProvider?.invoke()
            val creds = try {
                CastEngine.credentialsLoader?.invoke(sp)
            } catch (_: Exception) {
                null
            }
            established = if (identity != null && creds != null) {
                Ap2Pairing.connectVerified(sp.host, sp.port, creds, identity)
            } else {
                Ap2Pairing.connect(sp.host, sp.port)
            }
            pairingOk = true
            steps += DoctorStep(
                "Pairing",
                true,
                if (creds != null) {
                    "stored PIN pairing verified"
                } else {
                    "transient pairing accepted"
                },
            )
        } catch (e: Ap2Pairing.PinRequired) {
            steps += DoctorStep(
                "Pairing",
                false,
                "receiver wants a PIN — long-press the speaker and " +
                    "choose “Pair with PIN”",
            )
        } catch (e: Exception) {
            steps += DoctorStep(
                "Pairing", false,
                "pairing failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        // 3) Test tones ----------------------------------------------------
        if (CastEngine.sessionFor(sp) != null) {
            steps += DoctorStep(
                "Test tones", null,
                "speaker is busy casting — stop the cast and run again",
            )
            closeQuietly(established)
        } else if (pairingOk && established != null) {
            val est = established
            try {
                val session = Ap2AudioSession(
                    sp.host, est.localIp, est.channel, est.k64,
                    TonePcmSource(),
                ) {}
                session.run()
                steps += DoctorStep(
                    "Test tones",
                    true,
                    "left tone, then right tone, sent via AirPlay 2 — " +
                        "you should have heard both",
                )
            } catch (e: Exception) {
                steps += DoctorStep(
                    "Test tones", false,
                    "tone session failed: ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                closeQuietly(est)
            }
        } else {
            // AP2 tones impossible — try the RAOP route when plausible.
            steps += raopToneStep(sp)
        }

        DoctorReport.record(sp.name, steps)
        LogBus.log("[doctor] '${sp.name}':\n" + DoctorReport.render(steps))
        return steps
    }

    private fun raopToneStep(sp: Speaker): DoctorStep {
        val targetPort = sp.raopPort ?: sp.port
        val conn = RaopConnection(sp.host, targetPort, NetUtil.localIp(), false)
        return try {
            conn.connect()
            conn.pcmSource = ToneRaopSource()
            conn.startSender()
            // ToneRaopSource exhausts itself; give the sender time to
            // play out both tones, then tear down.
            Thread.sleep(2600)
            conn.teardown()
            DoctorStep(
                "Test tones",
                true,
                "left/right tones sent via AirPlay 1 (RAOP) — " +
                    "you should have heard both",
            )
        } catch (e: Exception) {
            try {
                conn.teardown()
            } catch (_: Exception) {
            }
            DoctorStep(
                "Test tones", false,
                "RAOP tone session failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun tcpReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 2000)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun closeQuietly(est: Ap2Pairing.Established?) {
        try {
            est?.socket?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * AP2 tone source: ~0.8 s of 660 Hz in the LEFT channel only, then
     * ~0.8 s in the RIGHT channel only, in 352-frame chunks at 44.1 kHz.
     */
    class TonePcmSource : Ap2AudioSession.PcmSource {
        private val totalFrames = 44100 * 8 / 5 // 1.6 s
        private var produced = 0

        override val finished: Boolean
            get() = produced >= totalFrames

        override fun nextInterleaved(): ShortArray? {
            if (finished) return null
            val out = ShortArray(BroadcastCapture.CHUNK_FRAMES * 2)
            val half = totalFrames / 2
            for (f in 0 until BroadcastCapture.CHUNK_FRAMES) {
                val idx = produced + f
                if (idx >= totalFrames) break
                val v = (sin(2.0 * PI * 660.0 * idx / 44100.0) * 0.4 * 32767)
                    .toInt().toShort()
                if (idx < half) out[f * 2] = v else out[f * 2 + 1] = v
            }
            produced += BroadcastCapture.CHUNK_FRAMES
            return out
        }
    }

    /** Same L/R tones for the RAOP path. */
    class ToneRaopSource : RaopConnection.PcmSource {
        private val totalFrames = 44100 * 8 / 5
        private var produced = 0

        override fun readFrame(left: ShortArray, right: ShortArray): Int {
            if (produced >= totalFrames) return -1
            val n = minOf(left.size, totalFrames - produced)
            val half = totalFrames / 2
            for (f in 0 until n) {
                val idx = produced + f
                val v = (sin(2.0 * PI * 660.0 * idx / 44100.0) * 0.4 * 32767)
                    .toInt().toShort()
                if (idx < half) left[f] = v else right[f] = v
            }
            produced += n
            return n
        }
    }
}
