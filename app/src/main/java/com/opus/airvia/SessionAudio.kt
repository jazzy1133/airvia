package com.opus.airvia

import com.opus.airvia.ap2.Ap2AudioSession

/**
 * Per-session PCM wrappers (1.3.0) that sit between a hub consumer and a
 * session's [Ap2AudioSession.PcmSource] input:
 *
 *  - [ProfiledConsumer] applies the speaker profile: delay line
 *    (silence-prefixed), per-speaker EQ (own [DspProcessor]), and the
 *    gentle-start fade-in. With a default profile + gentle start done,
 *    frames pass through bit-identical.
 *  - [ResamplingConsumer] adapts a hub-rate source to a different
 *    session rate (48 kHz hub → 44.1 kHz RAOP / AP2-fallback sessions),
 *    re-chunking to exact 352-frame chunks.
 *
 * Both are pure JVM (the delegate is an interface) so the delay/fade/
 * resample math is covered by the JVM suites.
 */
class ProfiledConsumer(
    private val delegate: Ap2AudioSession.PcmSource,
    profile: SpeakerProfile,
    private val sampleRate: Int,
) : Ap2AudioSession.PcmSource {

    companion object {
        const val CHUNK = BroadcastCapture.CHUNK_FRAMES

        /** Gentle-start ramp length in frames: ~1.2 s. */
        fun fadeFrames(sampleRate: Int): Long = sampleRate * 6L / 5L
    }

    private val dsp: DspProcessor? = if (profile.eqIsFlat) {
        null
    } else {
        DspProcessor(sampleRate).also {
            it.setGains(profile.eqGains)
            it.setPreampDb(profile.eqPreampDb)
        }
    }

    @Volatile
    private var delayTargetFrames: Long =
        SpeakerProfile.delayFrames(profile.delayMs, sampleRate)

    @Volatile
    private var delayEmittedFrames: Long = 0L

    private val fadeTotal: Long =
        if (profile.gentleStart) fadeFrames(sampleRate) else 0L

    @Volatile
    private var fadedFrames: Long = 0L

    private val delayRemaining: Long
        get() = maxOf(0L, delayTargetFrames - delayEmittedFrames)

    /** Live delay change (lip-sync slider): raises/lower the target;
     *  silence already emitted cannot be recalled, so a decrease
     *  below what was emitted takes effect only from the new target
     *  onward (i.e. it shortens any remaining silence). */
    fun setDelayMs(delayMs: Int) {
        delayTargetFrames =
            SpeakerProfile.delayFrames(delayMs, sampleRate)
    }

    override val finished: Boolean
        get() = delegate.finished && delayRemaining <= 0

    override fun nextInterleaved(): ShortArray? {
        synchronized(this) {
            if (delayRemaining > 0) {
                delayEmittedFrames += CHUNK
                return ShortArray(CHUNK * 2) // silence chunk
            }
        }
        val chunk = delegate.nextInterleaved() ?: return null
        dsp?.process(chunk)
        applyFade(chunk)
        return chunk
    }

    private fun applyFade(chunk: ShortArray) {
        if (fadeTotal <= 0L || fadedFrames >= fadeTotal) return
        // Per-frame linear ramp across chunk boundaries.
        var frames = fadedFrames
        var i = 0
        while (i < chunk.size && frames < fadeTotal) {
            val g = (frames + 1).toDouble() / fadeTotal.toDouble()
            chunk[i] = (chunk[i] * g).toInt().toShort()
            chunk[i + 1] = (chunk[i + 1] * g).toInt().toShort()
            i += 2
            frames++
        }
        fadedFrames = frames
    }

    /** The underlying hub consumer, when there is one (for release). */
    fun releaseDelegate() {
        (delegate as? BroadcastCapture.Consumer)?.release()
    }
}

/**
 * Rate-adapting wrapper: pulls hub-rate chunks from [delegate],
 * resamples with the existing linear [StreamResampler], and serves
 * exact 352-frame chunks at the target rate.
 */
class ResamplingConsumer(
    private val delegate: Ap2AudioSession.PcmSource,
    srcRate: Int,
    dstRate: Int,
) : Ap2AudioSession.PcmSource {

    private val resampler = StreamResampler(srcRate, dstRate)
    private val pending = ArrayList<Short>()
    private var delegateDone = false

    override val finished: Boolean
        get() = delegateDone && pending.size < CHUNK * 2

    override fun nextInterleaved(): ShortArray? {
        while (pending.size < CHUNK * 2 && !delegateDone) {
            val chunk = delegate.nextInterleaved()
            if (chunk == null) {
                if (delegate.finished) {
                    delegateDone = true
                    break
                }
                return if (pending.size >= CHUNK * 2) emit() else null
            }
            val out = resampler.push(chunk)
            for (s in out) pending.add(s)
        }
        if (pending.size < CHUNK * 2) return null
        return emit()
    }

    private fun emit(): ShortArray {
        val out = ShortArray(CHUNK * 2)
        for (i in out.indices) out[i] = pending.removeAt(0)
        return out
    }

    private companion object {
        const val CHUNK = BroadcastCapture.CHUNK_FRAMES
    }
}
