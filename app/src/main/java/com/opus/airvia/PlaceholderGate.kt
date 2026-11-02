package com.opus.airvia

/**
 * Placeholder track-info refusal gate (1.3.1, issue #5 follow-up).
 *
 * The placeholder ("Airvia" + app logo, sent when the source app has
 * no now-playing info) is receiver-dependent: the user's HomePod
 * accepts the SET_PARAMETERs, GlebOlden's Apple TV answers 400 to
 * both the DMAP metadata and the artwork. Sending it again at every
 * stream start is pointless and noisy, so a 400 is remembered per
 * receiver (Prefs key `phrefused:<pairKey>`, the same store family
 * as the pairing credentials) and the placeholder is never offered
 * to that receiver again until its pairing is cleared.
 *
 * Pure decision logic only — Android/Prefs wiring lives in Prefs /
 * CastService, the wire handling in Ap2AudioSession. Real now-playing
 * metadata never passes through here and is unaffected by a refusal.
 */
object PlaceholderGate {
    /** An HTTP status on the placeholder path counts as a refusal. */
    fun isRefusal(status: Int): Boolean = status == 400

    /** Offer the placeholder only when the user toggle is on AND the
     *  receiver has not refused it before. */
    fun shouldOffer(toggleOn: Boolean, refused: Boolean): Boolean =
        toggleOn && !refused
}
