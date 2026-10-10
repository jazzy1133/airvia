import com.opus.airvia.Speaker
import com.opus.airvia.mergeDeviceSpeakers

/**
 * JVM self-tests for the per-device merge of NSD AirPlay/RAOP services
 * (Airvia 1.2.4, issue #2): split-service receivers like Kodi/LibreELEC
 * advertise _airplay._tcp and _raop._tcp on DIFFERENT ports, and the
 * RAOP failover must target the RAOP port, not the AirPlay port.
 */
var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else {
        println("FAIL: $name")
        failures++
    }
}

fun svc(name: String, host: String, port: Int, raop: Boolean, et: String? = null): Speaker =
    Speaker(name, host, port).apply {
        isRaopService = raop
        this.et = et
    }

fun main() {
    // 1) Kodi/LibreELEC: one device, AirPlay :36667 (no et), RAOP :36666 (et 0,1).
    val kodi = mergeDeviceSpeakers(
        listOf(
            svc("libreelec", "192.168.123.100", 36667, raop = false),
            svc("libreelec", "192.168.123.100", 36666, raop = true, et = "0,1"),
        ),
    )
    check("kodi: merged to one device", kodi.size == 1)
    check("kodi: primary port is the AirPlay port", kodi[0].port == 36667)
    check("kodi: raopPort remembered", kodi[0].raopPort == 36666)
    check("kodi: raopEt remembered", kodi[0].raopEt == "0,1")
    check("kodi: failover target = raopPort", (kodi[0].raopPort ?: kodi[0].port) == 36666)

    // 2) Same pair, RAOP resolved first (resolve order must not matter).
    val kodiRev = mergeDeviceSpeakers(
        listOf(
            svc("libreelec", "192.168.123.100", 36666, raop = true, et = "0,1"),
            svc("libreelec", "192.168.123.100", 36667, raop = false),
        ),
    )
    check("kodi reversed: still one device, AirPlay primary", kodiRev.size == 1 && kodiRev[0].port == 36667 && kodiRev[0].raopPort == 36666)

    // 3) HomePod: both services on :7000 — merged, no separate RAOP port.
    val pod = mergeDeviceSpeakers(
        listOf(
            svc("Bedroom", "10.0.0.148", 7000, raop = true, et = "0,3,5"),
            svc("Bedroom", "10.0.0.148", 7000, raop = false),
        ),
    )
    check("homepod: merged to one device", pod.size == 1)
    check("homepod: raopPort null (same port)", pod[0].raopPort == null)
    check("homepod: et kept from RAOP TXT", pod[0].et == "0,3,5")
    check("homepod: failover target = own port", (pod[0].raopPort ?: pod[0].port) == 7000)

    // 4) RAOP-only receiver (old AirPort Express style): passes through.
    val raopOnly = mergeDeviceSpeakers(
        listOf(svc("Express", "10.0.0.50", 5000, raop = true, et = "0,1")),
    )
    check("raop-only: single entry, own port", raopOnly.size == 1 && raopOnly[0].port == 5000 && raopOnly[0].raopPort == null)

    // 5) AirPlay-only device: passes through.
    val apOnly = mergeDeviceSpeakers(
        listOf(svc("TV", "10.0.0.60", 7000, raop = false)),
    )
    check("airplay-only: single entry", apOnly.size == 1 && apOnly[0].raopPort == null)

    // 6) Same display name on two different hosts must NOT merge.
    val twoHosts = mergeDeviceSpeakers(
        listOf(
            svc("Speaker", "10.0.0.1", 7000, raop = false),
            svc("Speaker", "10.0.0.2", 7000, raop = false),
        ),
    )
    check("same name, two hosts: two devices", twoHosts.size == 2)

    // 7) First-seen device order is preserved across devices.
    val multi = mergeDeviceSpeakers(
        listOf(
            svc("libreelec", "192.168.123.100", 36667, raop = false),
            svc("Bedroom", "10.0.0.148", 7000, raop = false),
            svc("libreelec", "192.168.123.100", 36666, raop = true, et = "0,1"),
        ),
    )
    check("order: kodi first, homepod second", multi.size == 2 && multi[0].name == "libreelec" && multi[1].name == "Bedroom")

    if (failures > 0) {
        println("RESULT: $failures FAILURES")
        kotlin.system.exitProcess(1)
    }
    println("RESULT: ALL PASS")
}
