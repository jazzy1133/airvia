package com.opus.airvia

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 5-band peaking EQ + preamp for the captured-audio path.
 *
 * RBJ audio-EQ-cookbook peaking biquads at 60 / 230 / 910 / 3600 / 14000 Hz
 * (Q = 1) running on the 44.1 kHz interleaved stereo stream, between the
 * resampler and the broadcast ring. Pure JVM (unit tested); coefficients
 * are recomputed on a gain change and swapped in atomically, so the audio
 * thread never sees a half-updated filter.
 *
 * All gains at 0 dB is bit-transparent to within float rounding (a peaking
 * filter with A = 1 has identical numerator and denominator).
 */
class DspProcessor(val sampleRate: Int = 44100) {
    companion object {
        val BAND_FREQS = intArrayOf(60, 230, 910, 3600, 14000)
        const val BAND_COUNT = 5
        const val MIN_DB = -12.0
        const val MAX_DB = 12.0
        const val MIN_PREAMP_DB = -12.0
        const val MAX_PREAMP_DB = 12.0
    }

    /** One biquad's coefficients (already normalized by a0). */
    private class Coef(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double,
    )

    private class BandState {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
    }

    @Volatile
    private var bandGainsDb = DoubleArray(BAND_COUNT)

    @Volatile
    var preampDb: Double = 0.0
        private set

    /** Active coefficient set; replaced wholesale on any gain change. */
    @Volatile
    private var coefs: Array<Coef> = computeCoefs(bandGainsDb)

    @Volatile
    private var preampLinear: Double = 1.0

    // Per-channel filter memory: [band][channel].
    private val states = Array(BAND_COUNT) { Array(2) { BandState() } }

    fun bandGainDb(band: Int): Double = bandGainsDb[band]

    fun gainsDb(): DoubleArray = bandGainsDb.copyOf()

    fun setBandGain(band: Int, db: Double) {
        val gains = bandGainsDb.copyOf()
        gains[band] = db.coerceIn(MIN_DB, MAX_DB)
        bandGainsDb = gains
        coefs = computeCoefs(gains)
    }

    fun setGains(gains: DoubleArray) {
        require(gains.size == BAND_COUNT)
        val g = DoubleArray(BAND_COUNT) { gains[it].coerceIn(MIN_DB, MAX_DB) }
        bandGainsDb = g
        coefs = computeCoefs(g)
    }

    fun setPreampDb(db: Double) {
        preampDb = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
        preampLinear = 10.0.pow(preampDb / 20.0)
    }

    fun resetState() {
        for (band in states) for (ch in band) {
            ch.x1 = 0.0; ch.x2 = 0.0; ch.y1 = 0.0; ch.y2 = 0.0
        }
    }

    private fun computeCoefs(gains: DoubleArray): Array<Coef> =
        Array(BAND_COUNT) { band ->
            val freq = BAND_FREQS[band].toDouble()
            val q = 1.0
            val a = 10.0.pow(gains[band] / 40.0)
            val w0 = 2.0 * Math.PI * freq / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosW = cos(w0)
            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosW
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cosW
            val a2 = 1.0 - alpha / a
            Coef(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

    /**
     * Process interleaved stereo PCM16 in place. Called from the capture
     * reader thread only (filter state is not thread-safe by design).
     */
    fun process(samples: ShortArray) {
        val cs = coefs
        val pre = preampLinear
        val flat = pre == 1.0 && bandGainsDb.all { it == 0.0 }
        if (flat) return
        for (i in samples.indices) {
            val ch = i and 1
            var x = samples[i].toDouble() * pre
            for (band in 0 until BAND_COUNT) {
                val c = cs[band]
                val st = states[band][ch]
                val y = c.b0 * x + c.b1 * st.x1 + c.b2 * st.x2 -
                    c.a1 * st.y1 - c.a2 * st.y2
                st.x2 = st.x1; st.x1 = x
                st.y2 = st.y1; st.y1 = y
                x = y
            }
            samples[i] = x.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}

/**
 * Process-wide handle on the live EQ so the UI and the capture path share
 * one [DspProcessor]. Settings persist through [Prefs] (see Prefs.eqGains).
 */
object Eq {
    var processor = DspProcessor()
        private set

    /** Recreate the processor at a new sample rate (hi-res mode),
     *  keeping the current gains/preamp. No-op at the current rate. */
    fun useRate(rate: Int) {
        if (processor.sampleRate == rate) return
        val g = processor.gainsDb()
        val p = processor.preampDb
        processor = DspProcessor(rate)
        apply(g, p)
    }

    data class Preset(val name: String, val gains: DoubleArray, val preampDb: Double)

    val PRESETS = listOf(
        Preset("Flat", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0), 0.0),
        Preset("Bass", doubleArrayOf(6.0, 4.0, 1.0, 0.0, 0.0), -2.0),
        Preset("Treble", doubleArrayOf(0.0, 0.0, 1.0, 4.0, 6.0), -2.0),
        Preset("Vocal", doubleArrayOf(-2.0, 1.0, 4.0, 3.0, 0.0), 0.0),
        Preset("Party", doubleArrayOf(5.0, 3.0, 0.0, 3.0, 5.0), -3.0),
    )

    fun apply(gains: DoubleArray, preampDb: Double) {
        processor.setGains(gains)
        processor.setPreampDb(preampDb)
    }

    fun applyPreset(p: Preset) = apply(p.gains, p.preampDb)

    /** Name of the preset matching the current settings, or null (custom). */
    fun currentPresetName(): String? {
        val g = processor.gainsDb()
        val pre = processor.preampDb
        return PRESETS.firstOrNull { p ->
            p.preampDb == pre && p.gains.indices.all { p.gains[it] == g[it] }
        }?.name
    }
}
