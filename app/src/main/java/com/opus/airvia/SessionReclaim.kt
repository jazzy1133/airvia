package com.opus.airvia

/**
 * Pure decision logic for automatic volume-key re-claim (1.3.2,
 * issue #5 follow-up): when does Airvia re-assert its remote-volume
 * media session after another app's session took the keys?
 *
 * Background: Android re-picks the default volume session
 * continuously, ranking by active flag, playback state, and state
 * recency. The 1.3.1 heartbeat (fresh STATE_PLAYING every 4 s) fixes
 * staleness — a long cast no longer decays — but it cannot displace
 * a foreground app's session while that app is actively playing.
 * GlebOlden's log_2 proves the shape: a ~90 s window with no volume
 * command reaching the TV after switching source apps mid-cast,
 * recovery only when the new app's session went quiet.
 *
 * So the re-assert fires ONLY on the transition foreign-playing ->
 * not-playing while a cast is live. While a foreign app IS playing,
 * doing nothing is the correct behaviour (OS arbitration); while
 * nothing is casting, the tracker resets so idle session churn
 * never arms a stale transition.
 *
 * Android-free so the JVM suites can pin the matrix.
 */
object SessionReclaim {

    /** Stateful tracker: feed it the foreign-playing flag on every
     *  active-session-list change; it answers whether to re-assert
     *  the volume session now. */
    class Tracker {
        /** Last observed foreign-playing value (null = never seen). */
        private var lastForeignPlaying: Boolean? = null

        /**
         * @param casting whether any cast session is live right now.
         * @param foreignPlaying whether any non-Airvia session is
         *   currently PLAYING.
         * @return true when the caller should re-assert the volume
         *   session (VolumeKeys.refresh()).
         */
        fun update(casting: Boolean, foreignPlaying: Boolean): Boolean {
            if (!casting) {
                lastForeignPlaying = null
                return false
            }
            val prev = lastForeignPlaying
            lastForeignPlaying = foreignPlaying
            // Re-assert exactly once, on the falling edge.
            return prev == true && !foreignPlaying
        }

        /** For tests: what the tracker currently believes. */
        fun lastSeen(): Boolean? = lastForeignPlaying
    }
}
