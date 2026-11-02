package com.opus.airvia

/** One launchable app, for the per-app capture picker. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Search filter for the Tune tab's per-app capture picker.
 *
 * Pure and Android-free (no Compose, no PackageManager) so the JVM
 * suite can exercise the matching rules directly. A blank query
 * matches everything; otherwise the query must appear in the app's
 * label or its package name, case-insensitively.
 */
fun appMatchesSearch(label: String, packageName: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    return label.lowercase().contains(q) || packageName.lowercase().contains(q)
}

/** The apps the picker should show for [query], order preserved. */
fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    if (query.isBlank()) return apps
    return apps.filter { appMatchesSearch(it.label, it.packageName, query) }
}
