package com.opus.airvia

import android.content.Context

/** Small SharedPreferences store: per-speaker volume + last speaker. */
object Prefs {
    private const val FILE = "airvia"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun volumeFor(ctx: Context, sp: Speaker): Int? {
        val key = "vol:${sp.key}"
        return if (prefs(ctx).contains(key)) prefs(ctx).getInt(key, 100) else null
    }

    fun saveVolume(ctx: Context, sp: Speaker, pct: Int) {
        prefs(ctx).edit().putInt("vol:${sp.key}", pct).apply()
    }

    fun saveLastSpeaker(ctx: Context, sp: Speaker) {
        val e = prefs(ctx).edit()
            .putString("last_name", sp.name)
            .putString("last_host", sp.host)
            .putInt("last_port", sp.port)
        // Split-service RAOP endpoint (Kodi/LibreELEC): the reconnect
        // card rebuilds the speaker from these prefs, so the RAOP
        // failover target must be persisted with it (issue #2).
        if (sp.raopPort != null) e.putInt("last_raop_port", sp.raopPort!!)
        else e.remove("last_raop_port")
        if (sp.raopEt != null) e.putString("last_raop_et", sp.raopEt)
        else e.remove("last_raop_et")
        e.apply()
    }

    fun lastSpeaker(ctx: Context): Speaker? {
        val p = prefs(ctx)
        val host = p.getString("last_host", null) ?: return null
        val sp = Speaker(
            p.getString("last_name", host) ?: host,
            host,
            p.getInt("last_port", 7000),
        )
        if (p.contains("last_raop_port")) {
            sp.raopPort = p.getInt("last_raop_port", 0).takeIf { it > 0 }
        }
        sp.raopEt = p.getString("last_raop_et", null)
        return sp
    }

    // --- AirPlay protocol ------------------------------------------------

    fun airplayMode(ctx: Context): AirPlayMode =
        AirPlayMode.fromName(prefs(ctx).getString("airplay_mode", null))

    fun saveAirplayMode(ctx: Context, mode: AirPlayMode) {
        prefs(ctx).edit().putString("airplay_mode", mode.name).apply()
    }

    // --- EQ ------------------------------------------------------------

    fun eqGains(ctx: Context): DoubleArray {
        val raw = prefs(ctx).getString("eq_gains", null)
            ?: return DoubleArray(DspProcessor.BAND_COUNT)
        val parts = raw.split(",")
        if (parts.size != DspProcessor.BAND_COUNT) {
            return DoubleArray(DspProcessor.BAND_COUNT)
        }
        return DoubleArray(DspProcessor.BAND_COUNT) {
            parts[it].toDoubleOrNull() ?: 0.0
        }
    }

    fun eqPreamp(ctx: Context): Double =
        prefs(ctx).getFloat("eq_preamp", 0f).toDouble()

    fun saveEq(ctx: Context, gains: DoubleArray, preampDb: Double) {
        prefs(ctx).edit()
            .putString("eq_gains", gains.joinToString(","))
            .putFloat("eq_preamp", preampDb.toFloat())
            .apply()
    }

    /** Load the persisted EQ into the live processor. */
    fun loadEq(ctx: Context) {
        Eq.apply(eqGains(ctx), eqPreamp(ctx))
    }

    // --- Per-app capture -------------------------------------------------

    /** Packages chosen for per-app capture; empty = capture all apps. */
    fun capturePackages(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("capture_packages", emptySet()) ?: emptySet()

    fun saveCapturePackages(ctx: Context, packages: Set<String>) {
        prefs(ctx).edit().putStringSet("capture_packages", packages).apply()
    }

    // --- Phone silencer (stream volume saved before zeroing) -------------

    /** STREAM_MUSIC index saved by [PhoneSilencer] before it zeroed it. */
    fun silencerSaved(ctx: Context): Int? {
        val p = prefs(ctx)
        return if (p.contains("silencer_saved")) p.getInt("silencer_saved", 0) else null
    }

    fun setSilencerSaved(ctx: Context, index: Int) {
        prefs(ctx).edit().putInt("silencer_saved", index).apply()
    }

    fun clearSilencerSaved(ctx: Context) {
        prefs(ctx).edit().remove("silencer_saved").apply()
    }
}
