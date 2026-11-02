package com.opus.airvia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tasker / MacroDroid entry point (1.3.0). Exported by design (that's
 * the interface); commands are parsed by the pure [AutomationIntents]
 * parser and the blast radius is bounded — casting still needs the
 * pipeline running or the user's on-screen consent via MainActivity,
 * and volume/stop only affect Airvia's own sessions.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val extras = HashMap<String, Any?>()
        intent.extras?.let { b ->
            for (k in b.keySet()) {
                @Suppress("DEPRECATION")
                extras[k] = b.get(k)
            }
        }
        val cmd = AutomationIntents.parse(intent.action, extras)
        if (cmd == null) {
            LogBus.log("[automation] ignored broadcast ${intent.action}")
            return
        }
        LogBus.log("[automation] $cmd")
        when (cmd) {
            is AutomationCommand.StopCast -> {
                if (CastEngine.hasLiveSource) {
                    try {
                        context.startService(CastService.stopIntent(context))
                    } catch (_: Exception) {
                    }
                    CastEngine.stopAll()
                }
            }
            is AutomationCommand.SetVolume -> {
                if (CastEngine.isActive) {
                    CastEngine.setAllVolumes(cmd.pct)
                }
            }
            is AutomationCommand.StepVolume -> {
                if (CastEngine.isActive) {
                    CastEngine.stepAllVolumes(cmd.delta)
                }
            }
            is AutomationCommand.CastSpeaker -> {
                // Pipeline live: resolve against active speakers and
                // the remembered last speaker, then start directly.
                val candidates = CastEngine.snapshot().map { it.speaker } +
                    listOfNotNull(
                        try {
                            Prefs.lastSpeaker(context)
                        } catch (_: Exception) {
                            null
                        },
                    )
                val match = candidates.firstOrNull {
                    AutomationIntents.matches(it, cmd.query)
                }
                if (CastEngine.hasLiveSource && match != null) {
                    try {
                        context.startService(
                            CastService.startIntent(context, 0, null, match),
                        )
                    } catch (e: Exception) {
                        LogBus.log("[automation] start failed: ${e.message}")
                    }
                } else {
                    // No live pipeline (or speaker unknown): open the
                    // app, which resolves the name via discovery and
                    // runs the normal consent flow.
                    val open = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_AUTO_CAST, cmd.query)
                    }
                    try {
                        context.startActivity(open)
                    } catch (e: Exception) {
                        LogBus.log("[automation] open app failed: ${e.message}")
                    }
                }
            }
        }
    }
}
