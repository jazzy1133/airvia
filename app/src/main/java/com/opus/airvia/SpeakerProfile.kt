package com.opus.airvia

/**
 * Per-speaker sound profile (1.3.0): EQ + delay + volume guard + gentle
 * start, persisted in Prefs as one string per speaker. Pure data +
 * codec so the JVM suites can pin the format.
 *
 * Format: "g0,g1,g2,g3,g4;preampDb;delayMs;maxVolumePct;gentleStart"
 * (gains comma-joined doubles; gentleStart 0/1). Anything unparsable
 * decodes to the default profile — a corrupt pref must never break a
 * cast.
 */
data class SpeakerProfile(
    val eqGains: DoubleArray = DoubleArray(DspProcessor.BAND_COUNT),
    val eqPreampDb: Double = 0.0,
    /** Sender-side delay line, ms (lip-sync / sync trim). Only adds. */
    val delayMs: Int = 0,
    /** Volume guard: engine clamps this speaker's volume to this cap. */
    val maxVolumePct: Int = 100,
    /** Fade the first ~1.2 s of audio in from silence (PCM-domain). */
    val gentleStart: Boolean = true,
) {
    val eqIsFlat: Boolean
        get() = eqPreampDb == 0.0 && eqGains.all { it == 0.0 }

    /** True when the PCM path is bit-identical apart from gentle start. */
    val isDefault: Boolean
        get() = eqIsFlat && delayMs == 0 && maxVolumePct == 100

    fun encode(): String = buildString {
        append(eqGains.joinToString(","))
        append(';')
        append(eqPreampDb)
        append(';')
        append(delayMs)
        append(';')
        append(maxVolumePct)
        append(';')
        append(if (gentleStart) '1' else '0')
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeakerProfile) return false
        return eqGains.contentEquals(other.eqGains) &&
            eqPreampDb == other.eqPreampDb &&
            delayMs == other.delayMs &&
            maxVolumePct == other.maxVolumePct &&
            gentleStart == other.gentleStart
    }

    override fun hashCode(): Int {
        var r = eqGains.contentHashCode()
        r = 31 * r + eqPreampDb.hashCode()
        r = 31 * r + delayMs
        r = 31 * r + maxVolumePct
        r = 31 * r + gentleStart.hashCode()
        return r
    }

    companion object {
        fun decode(raw: String?): SpeakerProfile {
            if (raw.isNullOrBlank()) return SpeakerProfile()
            return try {
                val parts = raw.split(';')
                require(parts.size == 5)
                val gains = parts[0].split(',').map { it.toDouble() }
                require(gains.size == DspProcessor.BAND_COUNT)
                SpeakerProfile(
                    eqGains = gains.toDoubleArray(),
                    eqPreampDb = parts[1].toDouble(),
                    delayMs = parts[2].toInt().coerceIn(0, 2000),
                    maxVolumePct = parts[3].toInt().coerceIn(1, 100),
                    gentleStart = parts[4] == "1",
                )
            } catch (_: Exception) {
                SpeakerProfile()
            }
        }

        /** Frames of delay for [delayMs] at [sampleRate] (pure math). */
        fun delayFrames(delayMs: Int, sampleRate: Int): Long =
            (delayMs.toLong() * sampleRate) / 1000L
    }
}
