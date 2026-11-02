package com.opus.airvia

import com.opus.airvia.dlna.Dlna

/**
 * Pure speaker <-> key/value codec behind [CastService]'s Intent extras.
 *
 * The UI thread holds the merged [Speaker] objects published by
 * [SpeakerDiscovery], but a cast crosses into [CastService] through an
 * Intent, so the speaker is flattened to extras on one side and rebuilt
 * on the other. Keeping that flatten/rebuild here (instead of inline in
 * the service) makes the round-trip JVM-testable — and load-bearing:
 * any [Speaker] field missing from this codec silently reverts to its
 * default on the far side (this is exactly how the 1.2.4 RAOP failover
 * lost [Speaker.raopPort]: the merge set it, the Intent dropped it, and
 * `CastEngine.runRaop` fell back to the AirPlay port).
 */
internal object SpeakerWire {
    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_NAME = "name"
    const val KEY_KIND = "kind"
    const val KEY_ET = "et"
    const val KEY_DEVICE_ID = "deviceId"
    const val KEY_RAOP_PORT = "raopPort"
    const val KEY_RAOP_ET = "raopEt"
    const val KEY_DLNA_UDN = "dlnaUdn"
    const val KEY_DLNA_LOCATION = "dlnaLocation"
    const val KEY_DLNA_AV = "dlnaAv"
    const val KEY_DLNA_RC = "dlnaRc"
    const val KEY_CAST_ROUTE = "castRoute"

    fun encode(sp: Speaker): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m[KEY_HOST] = sp.host
        m[KEY_PORT] = sp.port
        m[KEY_NAME] = sp.name
        m[KEY_KIND] = sp.kind.name
        m[KEY_ET] = sp.et
        // Pairing-credential key: without it the service-side speaker
        // falls back to name|host keying and stored HAP credentials
        // keyed by deviceid would never be found.
        m[KEY_DEVICE_ID] = sp.deviceId
        // Split-service RAOP endpoint (Kodi/LibreELEC): without these the
        // rebuilt speaker defaults them to null and the RAOP failover
        // dials the AirPlay port that just rejected it (issue #2).
        sp.raopPort?.let { m[KEY_RAOP_PORT] = it }
        sp.raopEt?.let { m[KEY_RAOP_ET] = it }
        sp.dlna?.let { d ->
            m[KEY_DLNA_UDN] = d.udn
            m[KEY_DLNA_LOCATION] = d.location
            m[KEY_DLNA_AV] = d.avTransportUrl
            m[KEY_DLNA_RC] = d.renderingControlUrl
        }
        m[KEY_CAST_ROUTE] = sp.castRouteId
        return m
    }

    fun decode(m: Map<String, Any?>): Speaker? {
        val host = m[KEY_HOST] as? String ?: return null
        val sp = Speaker(
            m[KEY_NAME] as? String ?: "Speaker",
            host,
            m[KEY_PORT] as? Int ?: 7000,
        )
        sp.kind = try {
            SpeakerKind.valueOf(m[KEY_KIND] as? String ?: "AIRPLAY")
        } catch (_: Exception) {
            SpeakerKind.AIRPLAY
        }
        sp.et = m[KEY_ET] as? String
        sp.deviceId = m[KEY_DEVICE_ID] as? String
        sp.raopPort = m[KEY_RAOP_PORT] as? Int
        sp.raopEt = m[KEY_RAOP_ET] as? String
        sp.castRouteId = m[KEY_CAST_ROUTE] as? String
        val udn = m[KEY_DLNA_UDN] as? String
        val av = m[KEY_DLNA_AV] as? String
        if (udn != null && av != null) {
            sp.dlna = Dlna.DlnaDevice(
                udn = udn,
                name = sp.name,
                location = m[KEY_DLNA_LOCATION] as? String ?: "",
                avTransportUrl = av,
                renderingControlUrl = m[KEY_DLNA_RC] as? String,
            )
        }
        return sp
    }
}
