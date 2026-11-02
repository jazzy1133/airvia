package com.opus.airvia

/**
 * Pure one-tap per-speaker mute state machine (1.3.2, issue #5):
 * silencing ONE speaker while its track keeps playing, restoring
 * exactly the level it had.
 *
 * Rules (the engine applies these through its volume choke point,
 * so AP2/RAOP/DLNA all honour mute and the profile volume guard
 * still caps the restored level):
 * - Mute when not muted: store the current level as pre-mute, mark
 *   muted. The level sent is 0. Muting at level 0 stores pre-mute 0
 *   (restoring 0 is correct, not a special case).
 * - Mute when already muted: a no-op — the ORIGINAL pre-mute level
 *   is kept, never overwritten by the 0 the speaker is sitting at.
 * - Unmute: restore the stored pre-mute level, clear muted.
 * - Any explicit volume set while muted (slider, notification -/+,
 *   hardware keys): unmute to the requested value. This is the
 *   standard expectation — dragging a slider on a muted speaker
 *   unmutes it to the dragged level.
 * - Mute's own send must NOT trip the unmute rule: the engine's
 *   setVolume performs unmute-on-set, so mute/unmute apply their
 *   level through an internal path that skips it.
 *
 * Android-free so the JVM suites can pin the semantics. The guard
 * cap is deliberately NOT applied here — it lives at the engine's
 * choke point, which is where every volume value passes anyway.
 */
data class MuteState(
    val muted: Boolean = false,
    /** Level to restore on unmute (meaningful only when muted). */
    val preMuteLevel: Int = 100,
) {
    companion object {
        /** Mute transition: returns the new state + the level to send. */
        fun mute(current: MuteState, currentLevel: Int): Pair<MuteState, Int> {
            if (current.muted) return current to 0
            return MuteState(
                muted = true,
                preMuteLevel = currentLevel.coerceIn(0, 100),
            ) to 0
        }

        /** Unmute transition: returns the new state + the level to send. */
        fun unmute(current: MuteState): Pair<MuteState, Int> {
            if (!current.muted) return current to current.preMuteLevel
            return MuteState(
                muted = false,
                preMuteLevel = current.preMuteLevel,
            ) to current.preMuteLevel
        }

        /**
         * An explicit volume set while muted: unmute to the requested
         * value. Returns the new state + the effective level, or null
         * when the state does not change (not muted — the caller just
         * applies the value normally).
         */
        fun volumeSetWhileMuted(
            current: MuteState,
            requested: Int,
        ): Pair<MuteState, Int>? {
            if (!current.muted) return null
            return MuteState(
                muted = false,
                preMuteLevel = current.preMuteLevel,
            ) to requested.coerceIn(0, 100)
        }
    }
}
