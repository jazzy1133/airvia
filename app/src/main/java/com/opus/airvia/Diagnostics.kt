package com.opus.airvia

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap share diagnostics (1.3.0): a header (app/device/network
 * facts + the last Speaker Doctor result) followed by the full log.
 * Used by the Log tab's share/copy and the Doctor dialog, so a tester
 * can send one bundle that says everything.
 */
object Diagnostics {

    fun bundle(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Airvia diagnostics\n")
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            val vc = pi.versionCode
            sb.append("App: ${pi.versionName} (vc$vc)\n")
        } catch (_: Exception) {
        }
        sb.append(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n",
        )
        sb.append(
            "Time: " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(Date()) + "\n",
        )
        sb.append("Network: ${networkSummary(ctx)}\n")
        val sessions = CastEngine.snapshot()
        if (sessions.isEmpty()) {
            sb.append("Casting: no active sessions\n")
        } else {
            sb.append("Casting:\n")
            for (ss in sessions) {
                sb.append(
                    "  - ${ss.speaker.name} (${ss.speaker.host}:${ss.speaker.port}) " +
                        "${ss.protocol} ${ss.state} vol=${ss.volumePct}%\n",
                )
            }
        }
        if (DoctorReport.lastSteps.isNotEmpty()) {
            sb.append(
                "Speaker Doctor (${DoctorReport.lastSpeakerName}, " +
                    SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(Date(DoctorReport.lastRunMs)) + "):\n",
            )
            sb.append(DoctorReport.render(DoctorReport.lastSteps)).append('\n')
        }
        sb.append("\n--- log ---\n")
        sb.append(LogBus.snapshot().joinToString("\n"))
        return sb.toString()
    }

    private fun networkSummary(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(
                android.net.ConnectivityManager::class.java,
            )
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "unknown"
                caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_WIFI,
                ) -> "Wi-Fi"
                caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                ) -> "cellular"
                caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
                ) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
