package com.opus.airvia

/**
 * Speaker Doctor (1.3.0) — stepwise speaker checks with a structured
 * result the UI renders as a checklist and the diagnostics bundle
 * quotes. This file holds the pure model/verdict text; the network
 * runner lives in SpeakerDoctor.kt.
 */
data class DoctorStep(
    val name: String,
    /** true = passed, false = failed, null = skipped/not applicable. */
    val ok: Boolean?,
    val detail: String,
)

object DoctorReport {
    @Volatile
    var lastSpeakerName: String? = null
        private set

    @Volatile
    var lastRunMs: Long = 0L
        private set

    @Volatile
    var lastSteps: List<DoctorStep> = emptyList()
        private set

    fun record(speakerName: String, steps: List<DoctorStep>) {
        lastSpeakerName = speakerName
        lastSteps = steps
        lastRunMs = System.currentTimeMillis()
    }

    /** One-line-per-step plain-text rendering (dialog + bundle). */
    fun render(steps: List<DoctorStep>): String =
        steps.joinToString("\n") { s ->
            val mark = when (s.ok) {
                true -> "✓"
                false -> "✗"
                null -> "–"
            }
            "$mark ${s.name} — ${s.detail}"
        }

    /** Overall verdict: all non-skipped steps passed. */
    fun allPassed(steps: List<DoctorStep>): Boolean =
        steps.isNotEmpty() && steps.all { it.ok != false }
}
