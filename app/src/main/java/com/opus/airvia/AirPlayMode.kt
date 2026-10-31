package com.opus.airvia

/** Which AirPlay stack to use for AirPlay speakers (pure JVM, unit-testable). */
enum class AirPlayMode(
    val label: String,
    /** Attempt AirPlay 2 (HAP transient pairing) first. */
    val usesAirPlay2: Boolean,
    /** If AirPlay 2 never produced audio, retry with AirPlay 1 (RAOP). */
    val fallsBackToRaop: Boolean,
) {
    AUTO("Auto", usesAirPlay2 = true, fallsBackToRaop = true),
    AIRPLAY2("AirPlay 2", usesAirPlay2 = true, fallsBackToRaop = false),
    AIRPLAY1("AirPlay 1", usesAirPlay2 = false, fallsBackToRaop = false);

    companion object {
        /** Parse a stored name; anything unknown or null means [AUTO]. */
        fun fromName(name: String?): AirPlayMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
