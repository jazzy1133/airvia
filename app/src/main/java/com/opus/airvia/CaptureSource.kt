package com.opus.airvia

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.concurrent.thread

/**
 * Live device-audio capture for Airvia.
 *
 * Captures the phone's playback mix (AudioPlaybackCapture, Android 10+,
 * gated by the user's MediaProjection consent) at 48 kHz stereo, resamples
 * to the 44.1 kHz the AirPlay stream runs at, runs the result through the
 * EQ ([Eq.processor]), and hands each chunk to [onSamples] — wired to a
 * [BroadcastCapture] that fans it out to every active speaker session and
 * the local HTTP stream.
 *
 * Capture scope: by default all apps' media/game audio; when [matchUids]
 * is non-empty, only those apps (per-app capture, chosen in the UI).
 *
 * Platform limits (by Android design): apps that opt out of playback
 * capture and DRM-protected output are silent here; voice-call audio is
 * never captured.
 */
/** Implemented by PCM sources that can report when sound was last heard. */
interface AudibleSource {
    val lastAudibleMs: Long
}

class CaptureSource(
    private val projection: MediaProjection,
    private val matchUids: List<Int> = emptyList(),
    /** Rate handed to [onSamples]: 44100 (default) or 48000 hi-res. */
    private val outputRate: Int = DST_RATE,
) {
    companion object {
        const val SRC_RATE = 48000
        const val DST_RATE = 44100
    }

    /** Receives resampled (unless hi-res), EQ'd interleaved chunks. */
    @Volatile
    var onSamples: ((ShortArray) -> Unit)? = null

    @Volatile
    var finished: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private val resampler: StreamResampler? =
        if (outputRate == SRC_RATE) null else StreamResampler(SRC_RATE, outputRate)

    fun start() {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
        if (matchUids.isNotEmpty()) {
            for (uid in matchUids) builder.addMatchingUid(uid)
        } else {
            builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
            builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        }
        val config = builder.build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SRC_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SRC_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, SRC_RATE / 5 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (capture unsupported?)"
        }
        record = rec
        rec.startRecording()
        reader = thread(name = "airvia-capture") { readLoop(rec) }
        LogBus.log(
            "[capture] device audio capture live: ${SRC_RATE}Hz -> ${outputRate}Hz" +
                if (matchUids.isNotEmpty()) " (selected apps only)" else "",
        )
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ShortArray(1920) // 960 frames = 20 ms at 48 kHz
        while (!finished) {
            val n = try {
                rec.read(buf, 0, buf.size)
            } catch (_: Exception) {
                break
            }
            if (n < 0) {
                LogBus.log("[capture] read error $n")
                break
            }
            if (n == 0) continue
            val out = resampler?.push(buf.copyOf(n)) ?: buf.copyOf(n)
            if (out.isNotEmpty()) {
                Eq.processor.process(out)
                onSamples?.invoke(out)
            }
        }
        LogBus.log("[capture] reader stopped")
    }

    fun stop() {
        finished = true
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            reader?.join(300)
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        record = null
        reader = null
    }
}
