package com.opus.airvia

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** An AirPlay receiver found on the local network. */
data class Speaker(val name: String, val host: String, val port: Int) {
    /** Which sender stack drives this speaker. */
    var kind: SpeakerKind = SpeakerKind.AIRPLAY

    /** RAOP TXT `et` encryption modes (AirPlay discovery), when known. */
    var et: String? = null

    /**
     * mDNS TXT `deviceid` of the receiver, when advertised (AirPlay
     * services carry it). Stable across IP changes, so persistent HAP
     * pairing credentials are keyed by it when present.
     */
    var deviceId: String? = null

    /** Stable key for the persistent-pairing credential store. */
    val pairKey: String
        get() = deviceId?.takeIf { it.isNotBlank() } ?: "$name|$host"

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
 *
 * First-launch robustness (1.3.1, issue #5): a browse that fails to
 * START (the classic cold-process / Wi-Fi warm-up NSD failure) is
 * retried with backoff instead of leaving the app browse-less until
 * the next resume, and a network-available event re-kicks the browse
 * (rate-limited) — first launch behaves like later launches. The
 * merge/dedup logic is untouched by any of this.
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
    private val handler = Handler(Looper.getMainLooper())

    // One listener instance per service type (NSD rejects reusing a
    // single listener for two simultaneous discoveries), tracked per
    // type so a failed browse can be retried on its own (1.3.1).
    private val activeListeners = HashMap<String, NsdManager.DiscoveryListener>()
    private val startAttempts = HashMap<String, Int>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkKickMs = 0L

    private fun makeListener(type: String) = object : NsdManager.DiscoveryListener {
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

        override fun onDiscoveryStarted(t: String) {
            startAttempts.remove(t)
            LogBus.log("[discover] browsing $t")
        }

        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(t: String, code: Int) {
            LogBus.log("[discover] start failed for $t: $code")
            // A failed start used to leave the app with NO browse
            // until the next stop/start cycle — the first-launch
            // "no speakers until I type the IP" failure (1.3.1).
            if (activeListeners[t] === this) activeListeners.remove(t)
            scheduleBrowseRetry(t)
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
        startBrowsing()
        registerNetworkCallback()
    }

    private fun startBrowsing() {
        for (type in SERVICE_TYPES) startBrowseType(type)
    }

    private fun startBrowseType(type: String) {
        if (!running) return
        if (activeListeners.containsKey(type)) return
        try {
            val listener = makeListener(type)
            activeListeners[type] = listener
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            LogBus.log("[discover] cannot browse $type: ${e.message}")
            activeListeners.remove(type)
            scheduleBrowseRetry(type)
        }
    }

    /** Retry a browse that failed to start: 1.5 s, then 4 s, max 3. */
    private fun scheduleBrowseRetry(type: String) {
        if (!running) return
        val attempt = (startAttempts[type] ?: 0) + 1
        if (attempt > MAX_START_RETRIES) {
            LogBus.log("[discover] giving up browsing $type after retries")
            return
        }
        startAttempts[type] = attempt
        handler.postDelayed({ startBrowseType(type) }, if (attempt == 1) 1500L else 4000L)
    }

    /**
     * Stop the current browses and start fresh ones, KEEPING the
     * multicast lock held (releasing/reacquiring it mid-restart is
     * its own source of missed first packets). Used by the network
     * callback, by the UI's zero-results re-check, and by Rescan.
     */
    fun restartBrowse() {
        if (!running) return
        stopBrowsing()
        startAttempts.clear()
        startBrowsing()
    }

    private fun stopBrowsing() {
        for ((_, listener) in activeListeners) {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        activeListeners.clear()
    }

    /**
     * A network arriving after we started (first launch on a still-
     * connecting Wi-Fi is the textbook case) re-kicks the browse.
     * registerDefaultNetworkCallback reports the current network
     * immediately, which doubles as a warm-up re-browse ~1.5 s after
     * every start. Rate-limited to one kick per 10 s.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!running) return
                val now = System.currentTimeMillis()
                if (now - lastNetworkKickMs < 10_000L) return
                lastNetworkKickMs = now
                handler.postDelayed({
                    if (running) {
                        LogBus.log("[discover] network available — re-browsing")
                        restartBrowse()
                    }
                }, 1500L)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            app.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        stopBrowsing()
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private companion object {
        val SERVICE_TYPES = listOf("_raop._tcp", "_airplay._tcp")
        const val MAX_START_RETRIES = 3
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
                        val deviceId = try {
                            info.attributes["deviceid"]?.toString(Charsets.UTF_8)
                        } catch (_: Exception) {
                            null
                        }
                        synchronized(byEndpoint) {
                            val existing = byEndpoint[key]
                            // Prefer the name without the "MAC@" prefix.
                            if (existing == null || existing.name.contains("@")) {
                                byEndpoint[key] = Speaker(name, host, info.port).apply {
                                    this.et = et
                                    this.deviceId = deviceId
                                    this.isRaopService = isRaop
                                }
                            } else {
                                if (existing.et == null && et != null) {
                                    existing.et = et
                                }
                                if (existing.deviceId == null && deviceId != null) {
                                    existing.deviceId = deviceId
                                }
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
        merged.deviceId = primary.deviceId ?: raop?.deviceId
        merged.isRaopService = primary.isRaopService
        if (airplay != null && raop != null && raop.port != primary.port) {
            merged.raopPort = raop.port
            merged.raopEt = raop.et
        }
        out.add(merged)
    }
    return out
}
