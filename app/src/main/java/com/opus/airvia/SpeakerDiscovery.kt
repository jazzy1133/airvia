package com.opus.airvia

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

/** An AirPlay receiver found on the local network. */
data class Speaker(val name: String, val host: String, val port: Int) {
    /** Which sender stack drives this speaker. */
    var kind: SpeakerKind = SpeakerKind.AIRPLAY

    /** RAOP TXT `et` encryption modes (AirPlay discovery), when known. */
    var et: String? = null

    /**
     * Port of the same device's `_raop._tcp` service when it differs from
     * [port] (split-service receivers like Kodi/LibreELEC advertise AirPlay
     * and RAOP on different ports). The RAOP failover must target this port;
     * null means RAOP lives on [port] itself (Apple receivers, RAOP-only
     * devices).
     */
    var raopPort: Int? = null

    /** `et` modes taken from the `_raop._tcp` TXT, when a sibling exists. */
    var raopEt: String? = null

    /** Discovery-internal: this raw entry came from `_raop._tcp`. */
    var isRaopService: Boolean = false

    /** DLNA control endpoints (kind == DLNA). */
    var dlna: com.opus.airvia.dlna.Dlna.DlnaDevice? = null

    /** Chromecast MediaRouter route id (kind == CHROMECAST). */
    var castRouteId: String? = null

    /** Stable identity used for session maps and per-speaker prefs. */
    val key: String
        get() = when (kind) {
            SpeakerKind.DLNA -> "DLNA|${dlna?.udn ?: "$host:$port"}"
            SpeakerKind.CHROMECAST -> "CHROMECAST|${castRouteId ?: name}"
            SpeakerKind.AIRPLAY -> "AIRPLAY|$host:$port"
        }
}

enum class SpeakerKind { AIRPLAY, DLNA, CHROMECAST }

/**
 * NSD discovery of AirPlay receivers (`_raop._tcp` and `_airplay._tcp`).
 * Raw services are keyed by endpoint; the published list merges the two
 * services of one physical device (same host + cleaned name) into a
 * single [Speaker] whose [Speaker.port] is the AirPlay endpoint and
 * whose [Speaker.raopPort] remembers the RAOP endpoint when it differs
 * (Kodi/LibreELEC: AirPlay on one port, RAOP on another — the RAOP
 * failover breaks if it reuses the AirPlay port). Resolves services one
 * at a time (NSD resolves are not parallel-safe on older Android),
 * preferring the clean `_airplay` display name over the `MAC@Name` form
 * `_raop` uses.
 */
class SpeakerDiscovery(
    context: Context,
    private val onChanged: (List<Speaker>) -> Unit,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val byEndpoint = LinkedHashMap<String, Speaker>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    @Volatile
    private var resolving = false
    private var running = false
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()

    // One listener instance per service type (NSD rejects reusing a
    // single listener for two simultaneous discoveries).
    private fun makeListener() = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(resolveQueue) { resolveQueue.add(info) }
            resolveNext()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(byEndpoint) {
                val gone = byEndpoint.values.filter {
                    it.name == cleanName(info.serviceName)
                }
                gone.forEach { byEndpoint.remove(endpoint(it.host, it.port)) }
                if (gone.isNotEmpty()) publish()
            }
        }

        override fun onDiscoveryStarted(type: String) {
            LogBus.log("[discover] browsing $type")
        }

        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            LogBus.log("[discover] start failed for $type: $code")
        }

        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        try {
            multicastLock = wifi.createMulticastLock("airvia-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        for (type in listOf("_raop._tcp", "_airplay._tcp")) {
            try {
                val listener = makeListener()
                listeners.add(listener)
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                LogBus.log("[discover] cannot browse $type: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        for (listener in listeners) {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        listeners.clear()
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(resolveQueue) {
            if (resolving) return
            next = resolveQueue.poll() ?: return
            resolving = true
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    val host = info.host?.hostAddress
                    if (host != null) {
                        val name = cleanName(info.serviceName)
                        val key = endpoint(host, info.port)
                        val isRaop = info.serviceType?.contains("_raop") == true
                        val et = try {
                            info.attributes["et"]?.toString(Charsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                        synchronized(byEndpoint) {
                            val existing = byEndpoint[key]
                            // Prefer the name without the "MAC@" prefix.
                            if (existing == null || existing.name.contains("@")) {
                                byEndpoint[key] = Speaker(name, host, info.port).apply {
                                    this.et = et
                                    this.isRaopService = isRaop
                                }
                            } else if (existing.et == null && et != null) {
                                existing.et = et
                            }
                            publish()
                        }
                        LogBus.log("[discover] '$name' @ $host:${info.port}")
                    }
                    resolveNext()
                }

                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    resolving = false
                    resolveNext()
                }
            })
        } catch (_: Exception) {
            resolving = false
            resolveNext()
        }
    }

    private fun publish() {
        val raw = synchronized(byEndpoint) { byEndpoint.values.toList() }
        onChanged(mergeDeviceSpeakers(raw))
    }

    private fun endpoint(host: String, port: Int) = "$host:$port"

    private fun cleanName(raw: String): String =
        if (raw.contains("@")) raw.substringAfterLast("@").trim() else raw.trim()
}

/**
 * Merge the raw `_airplay._tcp` / `_raop._tcp` entries of one physical
 * device (same host + cleaned display name) into a single [Speaker]:
 * the AirPlay entry is primary ([Speaker.port] is the AP2 target), and
 * a RAOP sibling on a different port is remembered in
 * [Speaker.raopPort] / [Speaker.raopEt] so the RAOP failover can aim at
 * the right endpoint. Devices advertising only one service pass through
 * unchanged. First-seen device order is preserved.
 */
internal fun mergeDeviceSpeakers(raw: List<Speaker>): List<Speaker> {
    val groups = LinkedHashMap<String, MutableList<Speaker>>()
    for (sp in raw) {
        val key = "${sp.host.lowercase()}|${sp.name.lowercase()}"
        groups.getOrPut(key) { ArrayList() }.add(sp)
    }
    val out = ArrayList<Speaker>(groups.size)
    for (group in groups.values) {
        val airplay = group.firstOrNull { !it.isRaopService }
        val raop = group.firstOrNull { it.isRaopService }
        val primary = airplay ?: raop ?: continue
        val merged = Speaker(primary.name, primary.host, primary.port)
        merged.kind = primary.kind
        merged.et = primary.et ?: raop?.et
        merged.isRaopService = primary.isRaopService
        if (airplay != null && raop != null && raop.port != primary.port) {
            merged.raopPort = raop.port
            merged.raopEt = raop.et
        }
        out.add(merged)
    }
    return out
}
