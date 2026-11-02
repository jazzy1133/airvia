package com.opus.airvia

/**
 * Tasker / MacroDroid broadcast interface (1.3.0) — pure parsing.
 *
 * Actions (documented in the Tune settings screen):
 *  - com.opus.airvia.action.CAST_SPEAKER  extra "speaker" (name or host)
 *  - com.opus.airvia.action.STOP_CAST
 *  - com.opus.airvia.action.SET_VOLUME    extra "volume" (0..100)
 *  - com.opus.airvia.action.STEP_VOLUME   extra "delta" (-100..100)
 *
 * The receiver is exported (that's the point of the interface); the
 * blast radius is bounded: no action can bypass the media-projection
 * consent prompt, read data, or change settings. Parsing is defensive:
 * anything unrecognized or out of range parses to null and is ignored.
 */
sealed class AutomationCommand {
    data class CastSpeaker(val query: String) : AutomationCommand()
    object StopCast : AutomationCommand()
    data class SetVolume(val pct: Int) : AutomationCommand()
    data class StepVolume(val delta: Int) : AutomationCommand()
}

object AutomationIntents {
    const val ACTION_CAST_SPEAKER = "com.opus.airvia.action.CAST_SPEAKER"
    const val ACTION_STOP_CAST = "com.opus.airvia.action.STOP_CAST"
    const val ACTION_SET_VOLUME = "com.opus.airvia.action.SET_VOLUME"
    const val ACTION_STEP_VOLUME = "com.opus.airvia.action.STEP_VOLUME"

    const val EXTRA_SPEAKER = "speaker"
    const val EXTRA_VOLUME = "volume"
    const val EXTRA_DELTA = "delta"

    fun parse(action: String?, extras: Map<String, Any?>): AutomationCommand? {
        return when (action) {
            ACTION_STOP_CAST -> AutomationCommand.StopCast
            ACTION_CAST_SPEAKER -> {
                val q = (extras[EXTRA_SPEAKER] as? String)?.trim()
                if (q.isNullOrEmpty()) null else AutomationCommand.CastSpeaker(q)
            }
            ACTION_SET_VOLUME -> {
                val v = asInt(extras[EXTRA_VOLUME]) ?: return null
                AutomationCommand.SetVolume(v.coerceIn(0, 100))
            }
            ACTION_STEP_VOLUME -> {
                val d = asInt(extras[EXTRA_DELTA]) ?: return null
                AutomationCommand.StepVolume(d.coerceIn(-100, 100))
            }
            else -> null
        }
    }

    /** Accept ints and numeric strings (MacroDroid sends strings). */
    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /** Match a speaker against a free-text query (name or host,
     *  case-insensitive; exact host wins over name substring). */
    fun matches(sp: Speaker, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        return sp.host.lowercase() == q ||
            sp.name.lowercase() == q ||
            sp.name.lowercase().contains(q)
    }
}
