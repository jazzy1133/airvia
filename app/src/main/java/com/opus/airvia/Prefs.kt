package com.opus.airvia

import android.content.Context
import com.opus.airvia.ap2.HapPairing
import java.security.SecureRandom
import java.util.UUID

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

    // --- HAP persistent pairing (PIN pairing, issue #3) ----------------

    /**
     * Our long-term controller identity for HAP pairing: one Ed25519
     * keypair (32-byte seed) + a UUID identifier, generated once and
     * reused for every receiver (pyatv parity). Plain SharedPreferences,
     * like everything else here — EncryptedSharedPreferences is avoided
     * project-wide for Oppo/ColorOS Keystore risk.
     */
    fun controllerIdentity(ctx: Context): HapPairing.ControllerIdentity {
        val p = prefs(ctx)
        val seedHex = p.getString("hap_seed", null)
        val id = p.getString("hap_id", null)
        if (seedHex != null && id != null) {
            val seed = runCatching { TlvHex.decode(seedHex) }.getOrNull()
            if (seed != null && seed.size == 32) {
                return HapPairing.ControllerIdentity(seed, id)
            }
        }
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val newId = UUID.randomUUID().toString()
        p.edit()
            .putString("hap_seed", TlvHex.encode(seed))
            .putString("hap_id", newId)
            .apply()
        return HapPairing.ControllerIdentity(seed, newId)
    }

    /** Stored pairing for one receiver, or null when never PIN-paired. */
    fun credentialsFor(ctx: Context, sp: Speaker): HapPairing.PairCredentials? {
        val raw = prefs(ctx).getString("paircred:${sp.pairKey}", null) ?: return null
        return HapPairing.PairCredentials.parse(raw)
    }

    fun saveCredentials(ctx: Context, sp: Speaker, creds: HapPairing.PairCredentials) {
        prefs(ctx).edit()
            .putString("paircred:${sp.pairKey}", creds.toStoredString())
            .apply()
    }

    fun forgetCredentials(ctx: Context, sp: Speaker) {
        // Clearing the pairing resets the whole receiver relationship
        // — including a remembered placeholder refusal (1.3.1).
        prefs(ctx).edit()
            .remove("paircred:${sp.pairKey}")
            .remove("phrefused:${sp.pairKey}")
            .apply()
    }

    // --- Placeholder refusal memory (1.3.1, issue #5) -------------------

    /**
     * This receiver answered 400 to the placeholder track info once
     * (some Apple TVs refuse what a HomePod accepts) — never offer it
     * again until the pairing is cleared. Keyed like the credentials.
     */
    fun placeholderRefused(ctx: Context, sp: Speaker): Boolean =
        prefs(ctx).getBoolean("phrefused:${sp.pairKey}", false)

    fun savePlaceholderRefused(ctx: Context, sp: Speaker) {
        prefs(ctx).edit().putBoolean("phrefused:${sp.pairKey}", true).apply()
    }

    // --- Per-speaker mute (1.3.2, issue #5) -------------------------------

    /**
     * One-tap mute state for one speaker: whether it is silenced and
     * the level unmute restores. Keyed like the pairing store. The
     * remembered volume key reads 0 while muted (mute's send passes
     * the volume saver) — the pre-mute level lives here, never
     * clobbered by that.
     */
    fun muteStateFor(ctx: Context, sp: Speaker): MuteState? {
        val p = prefs(ctx)
        if (!p.contains("mute:${sp.key}")) return null
        return MuteState(
            muted = p.getBoolean("mute:${sp.key}", false),
            preMuteLevel = p.getInt("mutelevel:${sp.key}", 100),
        )
    }

    fun saveMuteState(ctx: Context, sp: Speaker, state: MuteState) {
        prefs(ctx).edit()
            .putBoolean("mute:${sp.key}", state.muted)
            .putInt("mutelevel:${sp.key}", state.preMuteLevel)
            .apply()
    }

    // --- Placeholder now-playing metadata --------------------------------

    /**
     * When an app provides no track info, show "Airvia" (+ the app logo)
     * as the track info on AirPlay receivers instead of a blank player.
     * Default ON.
     */
    fun placeholderMetadata(ctx: Context): Boolean =
        prefs(ctx).getBoolean("placeholder_metadata", true)

    fun savePlaceholderMetadata(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("placeholder_metadata", enabled).apply()
    }

    // --- Appearance (1.3.0) ------------------------------------------------

    /** Theme mode: SYSTEM / LIGHT / DARK / GLASS (ThemeMode names). */
    fun themeMode(ctx: Context): String =
        prefs(ctx).getString("theme_mode", "SYSTEM") ?: "SYSTEM"

    fun saveThemeMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString("theme_mode", mode).apply()
    }

    // --- Hi-res 48 kHz mode (1.3.0) -----------------------------------------

    /**
     * Run the capture pipeline at the native 48 kHz instead of
     * resampling to 44.1 kHz (AP2 negotiates sr=48000; RAOP consumers
     * are resampled back down — RAOP's wire format never changes).
     * Default OFF; applies when the pipeline next starts.
     */
    fun hiRes48k(ctx: Context): Boolean =
        prefs(ctx).getBoolean("hires_48k", false)

    fun saveHiRes48k(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("hires_48k", enabled).apply()
    }

    // --- Auto-reconnect (1.3.0) ----------------------------------------------

    /** Reconnect speakers whose session dropped unexpectedly while the
     *  capture pipeline is alive. Default ON. */
    fun autoReconnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean("auto_reconnect", true)

    fun saveAutoReconnect(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("auto_reconnect", enabled).apply()
    }

    // --- Per-speaker profiles (1.3.0) -----------------------------------------

    fun profileFor(ctx: Context, sp: Speaker): SpeakerProfile =
        SpeakerProfile.decode(prefs(ctx).getString("profile:${sp.key}", null))

    fun saveProfile(ctx: Context, sp: Speaker, profile: SpeakerProfile) {
        prefs(ctx).edit()
            .putString("profile:${sp.key}", profile.encode())
            .apply()
    }

    // --- Protocol routing (1.3.0, issue #2 follow-up) ---------------------------

    /** User's per-speaker protocol choice, keyed like the pairing store. */
    fun protocolPref(ctx: Context, sp: Speaker): ProtocolPref {
        val raw = prefs(ctx).getString("protopref:${sp.pairKey}", null)
        return runCatching { ProtocolPref.valueOf(raw ?: "AUTO") }
            .getOrDefault(ProtocolPref.AUTO)
    }

    fun saveProtocolPref(ctx: Context, sp: Speaker, pref: ProtocolPref) {
        prefs(ctx).edit()
            .putString("protopref:${sp.pairKey}", pref.name)
            .apply()
    }

    /** The protocol that last streamed successfully to this speaker. */
    fun learnedProtocol(ctx: Context, sp: Speaker): AirProtocol? {
        val raw = prefs(ctx).getString("proto:${sp.pairKey}", null)
            ?: return null
        return runCatching { AirProtocol.valueOf(raw) }.getOrNull()
    }

    fun saveLearnedProtocol(ctx: Context, sp: Speaker, proto: AirProtocol) {
        prefs(ctx).edit()
            .putString("proto:${sp.pairKey}", proto.name)
            .apply()
    }

    // --- Update checker (1.3.0) --------------------------------------------------

    fun lastUpdateCheckMs(ctx: Context): Long =
        prefs(ctx).getLong("update_check_ms", 0L)

    fun saveLastUpdateCheckMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong("update_check_ms", ms).apply()
    }
}

/** Tiny hex codec for stored key material (Prefs-local). */
private object TlvHex {
    fun encode(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) append("%02x".format(x))
    }

    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
