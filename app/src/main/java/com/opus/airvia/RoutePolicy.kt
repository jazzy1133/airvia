package com.opus.airvia

/**
 * AirPlay protocol routing policy (1.3.0) — pure decision logic for the
 * connection-timeout handling the user fixed on 2026-10-19 (issue #2:
 * tbart's ~28 s Kodi connect was ~24 s of AP2 timeouts against a port
 * that never answers; RAOP itself connects in ~1 s).
 *
 * Rules (see research reports §13 / 1.3.1 §2):
 *  - An EXPLICIT per-speaker choice (AirPlay 1 first / AirPlay 2 first)
 *    is honoured literally: it picks which protocol goes FIRST — even
 *    when PIN credentials are stored (1.3.1: the choice was silently
 *    ignored in 1.3.0 whenever a pairing existed; the fallback then
 *    pair-verifies / streams the other protocol as before). A failure
 *    always falls through to the other protocol when it exists (never
 *    a lockout).
 *  - AUTO + stored PIN credentials starts with AP2 / pair-verify,
 *    uncapped — the credential path is never the slow one and Auto
 *    must not lose it by accident.
 *  - AUTO otherwise follows the learned protocol (what worked last
 *    time).
 *  - No history + no credentials + a RAOP service was discovered on a
 *    DIFFERENT port than the AirPlay service (the split-service Kodi
 *    signature — its AirPlay port is a toy that never answers): the
 *    first AP2 attempt is CAPPED, so the silent port costs ~4 s, not
 *    ~24 s. Same-port RAOP (every Apple receiver advertises it) does
 *    NOT engage the cap: there the AP2 port is the real one and the
 *    proven flow keeps its full timeouts.
 *  - The RAOP fallback itself always exists (targeting the split port
 *    when known, else the AirPlay port) — that is today's behaviour;
 *    the policy only orders attempts and sets the cap.
 */

enum class AirProtocol { AP2, RAOP }

enum class ProtocolPref { AUTO, AP2, RAOP }

data class RouteDecision(
    /** Protocol to attempt first. */
    val first: AirProtocol,
    /** Protocol to fall through to on failure, or null. */
    val fallback: AirProtocol?,
    /**
     * Cap for the first AP2 attempt's read timeouts, ms; 0 = uncapped
     * (today's constants — byte-identical behaviour).
     */
    val capFirstAp2Ms: Int,
)

object RoutePolicy {
    /** Capped first-AP2 budget when a RAOP fallback exists (ms). */
    const val CAPPED_AP2_MS = 4000

    fun decide(
        hasCreds: Boolean,
        pref: ProtocolPref,
        learned: AirProtocol?,
        /** A `_raop._tcp` service was discovered on a port different
         *  from the AirPlay port (split-service receiver). */
        raopSplitPort: Boolean,
    ): RouteDecision {
        return when (pref) {
            // Explicit choices win outright (1.3.1) — including over a
            // stored pairing. The fallback is unchanged: AP2 there
            // pair-verifies when credentials exist.
            ProtocolPref.AP2 -> RouteDecision(
                AirProtocol.AP2, AirProtocol.RAOP, 0,
            )
            ProtocolPref.RAOP -> RouteDecision(
                AirProtocol.RAOP, AirProtocol.AP2, 0,
            )
            // Auto + stored PIN credentials: AP2/verify first,
            // uncapped (the Oct-19 guardrail, Auto-scoped since 1.3.1).
            ProtocolPref.AUTO -> if (hasCreds) {
                RouteDecision(AirProtocol.AP2, AirProtocol.RAOP, 0)
            } else when (learned) {
                AirProtocol.RAOP -> RouteDecision(
                    AirProtocol.RAOP, AirProtocol.AP2, 0,
                )
                AirProtocol.AP2 -> RouteDecision(
                    AirProtocol.AP2, AirProtocol.RAOP, 0,
                )
                null -> RouteDecision(
                    AirProtocol.AP2,
                    AirProtocol.RAOP,
                    if (raopSplitPort) CAPPED_AP2_MS else 0,
                )
            }
        }
    }
}
