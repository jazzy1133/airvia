package com.opus.airvia

import com.opus.airvia.ap2.Ap2AudioSession

/**
 * Fan-out hub for the single device-audio capture.
 *
 * [CaptureSource] pushes resampled+DSP'd 44.1 kHz frames in; any number of
 * [Consumer]s pull 352-frame chunks at their own pace (one per speaker
 * session, plus the HTTP stream server). A consumer that falls more than
 * the ring holds behind snaps forward to the oldest retained frame —
 * live audio prefers "now" over a growing delay.
 */
class BroadcastCapture(
    /** Hub sample rate: 44100 normally, 48000 in hi-res mode (1.3.0). */
    val sampleRate: Int = 44100,
) : AudibleSource {
    companion object {
        const val CHUNK_FRAMES = 352
    }

    /** ~8 s of stereo audio at the hub rate. */
    val capacityFrames: Int = sampleRate * 8

    private val ring = ShortArray(capacityFrames * 2)
    private val lock = Object()

    /** Absolute frame index of the next frame to be written. */
    private var writePos = 0L

    @Volatile
    var finished: Boolean = false
        private set

    @Volatile
    override var lastAudibleMs: Long = System.currentTimeMillis()

    /** Push interleaved samples (whole frames) from the capture thread. */
    fun push(samples: ShortArray) {
        if (samples.isEmpty()) return
        // Audibility tracking for the engine's silence auto-stop.
        var peak = 0
        for (s in samples) {
            val v = s.toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        if (peak > 800) lastAudibleMs = System.currentTimeMillis()
        synchronized(lock) {
            var src = 0
            while (src < samples.size) {
                val frameIdx = writePos
                val ringOff = ((frameIdx % capacityFrames) * 2).toInt()
                val framesToEnd = capacityFrames - (frameIdx % capacityFrames).toInt()
                val framesAvail = (samples.size - src) / 2
                val n = minOf(framesToEnd, framesAvail)
                System.arraycopy(samples, src, ring, ringOff, n * 2)
                src += n * 2
                writePos += n
            }
            lock.notifyAll()
        }
    }

    fun finish() {
        finished = true
        synchronized(lock) { lock.notifyAll() }
    }

    private val liveConsumers =
        java.util.concurrent.CopyOnWriteArrayList<Consumer>()

    fun newConsumer(): Consumer {
        val c = Consumer()
        liveConsumers.add(c)
        return c
    }

    /**
     * A consumer aligned to the sync group (1.3.0): starts at the
     * earliest cursor among currently-active consumers instead of the
     * live edge, so a speaker joining a group already playing starts
     * on the group's timeline, not "now". Falls back to the live edge
     * when no group is active.
     */
    fun newAlignedConsumer(): Consumer {
        val c = newConsumer()
        groupCursor()?.let { c.seekTo(it) }
        return c
    }

    /** Earliest read position among live (not released/finished)
     *  consumers, or null when none qualify. */
    fun groupCursor(): Long? {
        var best: Long? = null
        for (c in liveConsumers) {
            if (c.released || c.finished) continue
            val cur = c.cursorPosition
            if (best == null || cur < best) best = cur
        }
        return best
    }

    internal fun unregister(c: Consumer) {
        liveConsumers.remove(c)
    }

    inner class Consumer : Ap2AudioSession.PcmSource {
        /**
         * Absolute frame index this consumer reads next. Starts at the
         * live edge as of construction: subscribing consumers hear what
         * comes next, not what already played.
         */
        private var cursor: Long = synchronized(lock) { writePos }

        override val finished: Boolean
            get() = this@BroadcastCapture.finished && cursor >= writePos

        @Volatile
        var released: Boolean = false
            private set

        /** Absolute frame index this consumer reads next. */
        val cursorPosition: Long
            get() = synchronized(lock) { cursor }

        /** Stop tracking this consumer for group alignment. */
        fun release() {
            released = true
            unregister(this)
        }

        /** Move the read cursor (group alignment at join time). */
        fun seekTo(frame: Long) {
            synchronized(lock) {
                cursor = maxOf(frame, writePos - capacityFrames)
            }
        }

        /** Snap forward if the ring has overwritten what we still needed. */
        private fun snapLocked() {
            val oldest = writePos - capacityFrames
            if (cursor < oldest) cursor = oldest
        }

        override fun nextInterleaved(): ShortArray? {
            synchronized(lock) {
                snapLocked()
                if (writePos - cursor < CHUNK_FRAMES) return null
                val out = ShortArray(CHUNK_FRAMES * 2)
                copyFramesLocked(cursor, out)
                cursor += CHUNK_FRAMES
                return out
            }
        }

        /**
         * Bulk read for streaming consumers (HTTP server): up to
         * [maxFrames] frames, waiting up to [waitMs] for data to arrive.
         * Returns an empty array on timeout/finish with nothing left.
         */
        fun readFrames(maxFrames: Int, waitMs: Long): ShortArray {
            synchronized(lock) {
                snapLocked()
                var remaining = waitMs
                while (writePos - cursor <= 0 && !this@BroadcastCapture.finished && remaining > 0) {
                    val t0 = System.currentTimeMillis()
                    try {
                        lock.wait(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                    remaining -= System.currentTimeMillis() - t0
                    snapLocked()
                }
                val avail = (writePos - cursor).toInt()
                if (avail <= 0) return ShortArray(0)
                val n = minOf(avail, maxFrames)
                val out = ShortArray(n * 2)
                copyFramesLocked(cursor, out)
                cursor += n
                return out
            }
        }

        private fun copyFramesLocked(fromFrame: Long, dest: ShortArray) {
            var copiedFrames = 0
            val totalFrames = dest.size / 2
            while (copiedFrames < totalFrames) {
                val idx = fromFrame + copiedFrames
                val ringFrame = (idx % capacityFrames).toInt()
                val n = minOf(capacityFrames - ringFrame, totalFrames - copiedFrames)
                System.arraycopy(ring, ringFrame * 2, dest, copiedFrames * 2, n * 2)
                copiedFrames += n
            }
        }
    }
}
