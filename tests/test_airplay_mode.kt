import com.opus.airvia.AirPlayMode

var failures = 0

fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else {
        println("FAIL: $name")
        failures++
    }
}

/** JVM self-tests for the AirPlay protocol selector's decision table. */
fun main() {
    check("auto tries AP2 then falls back", AirPlayMode.AUTO.usesAirPlay2 && AirPlayMode.AUTO.fallsBackToRaop)
    check("AP2-only has no fallback", AirPlayMode.AIRPLAY2.usesAirPlay2 && !AirPlayMode.AIRPLAY2.fallsBackToRaop)
    check("AP1-only skips AP2", !AirPlayMode.AIRPLAY1.usesAirPlay2 && !AirPlayMode.AIRPLAY1.fallsBackToRaop)
    for (m in AirPlayMode.entries) check("round-trip ${m.name}", AirPlayMode.fromName(m.name) == m)
    check("null -> AUTO", AirPlayMode.fromName(null) == AirPlayMode.AUTO)
    check("garbage -> AUTO", AirPlayMode.fromName("nope") == AirPlayMode.AUTO)
    if (failures > 0) System.exit(1)
}
