package com.opus.airvia

/**
 * GitHub Releases update checker (1.3.0) — pure selection logic.
 *
 * Rule (research report §10): users are offered only non-draft,
 * NON-PRERELEASE releases newer than the installed version. Test
 * builds (e.g. v1.3.0-pin-test) ship as prereleases and are therefore
 * never offered; a stable release of the same numeric version still
 * outranks a prerelease build of it, because the compare is on the
 * numeric triple parsed from the tag.
 *
 * JSON is read with a tiny built-in parser — no new dependency (the
 * manual pipeline's libs carry no serialization runtime).
 */
object UpdateCheck {

    const val RELEASES_API =
        "https://api.github.com/repos/jazzy1133/airvia/releases?per_page=10"

    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val url: String,
    )

    /** "v1.3.0-pin-test" -> [1,3,0]; "1.2.4" -> [1,2,4]; junk -> null. */
    fun parseVersion(tag: String): IntArray? {
        val t = tag.trim().removePrefix("v").removePrefix("V")
        val numeric = t.takeWhile { it.isDigit() || it == '.' }
        if (numeric.isEmpty() || numeric.startsWith('.')) return null
        val parts = numeric.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = IntArray(parts.size)
        for (i in parts.indices) {
            out[i] = parts[i].toIntOrNull() ?: return null
        }
        return out
    }

    /** Lexicographic compare on numeric triples (missing parts = 0). */
    fun compareVersions(a: IntArray, b: IntArray): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = if (i < a.size) a[i] else 0
            val y = if (i < b.size) b[i] else 0
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** Parse the Releases API array into [ReleaseInfo]s (API order).
     *  Malformed input yields an empty list — a garbled response must
     *  read as "no update data", never as a crash. */
    fun parseReleases(json: String): List<ReleaseInfo> {
        val root = try {
            MiniJson.parse(json)
        } catch (_: Exception) {
            return emptyList()
        } as? List<*> ?: return emptyList()
        val out = ArrayList<ReleaseInfo>()
        for (item in root) {
            val m = item as? Map<*, *> ?: continue
            val tag = m["tag_name"] as? String ?: continue
            out.add(
                ReleaseInfo(
                    tag = tag,
                    name = (m["name"] as? String) ?: tag,
                    prerelease = m["prerelease"] as? Boolean ?: false,
                    draft = m["draft"] as? Boolean ?: false,
                    url = (m["html_url"] as? String) ?: "",
                ),
            )
        }
        return out
    }

    /**
     * The release to offer a user running [currentTag], or null.
     * [releases] in API order (newest first); the first stable release
     * strictly newer than current wins — plus one refinement: a user
     * on a TEST build of a version (tag carries a suffix, e.g.
     * "v1.3.0-pin-test") is offered the STABLE release of that same
     * version, so testers land on the real release when it ships.
     * A user already on the stable of that version gets no offer.
     */
    fun findUpdate(currentTag: String, releases: List<ReleaseInfo>): ReleaseInfo? {
        val cur = parseVersion(currentTag) ?: return null
        val curIsTestBuild = currentTag.trim().removePrefix("v")
            .removePrefix("V").contains('-')
        for (r in releases) {
            if (r.draft || r.prerelease) continue
            val v = parseVersion(r.tag) ?: continue
            val cmp = compareVersions(v, cur)
            if (cmp > 0 || (cmp == 0 && curIsTestBuild)) return r
        }
        return null
    }
}

/**
 * Minimal JSON reader (objects, arrays, strings with escapes, numbers,
 * booleans, null) — just enough for API responses we consume ourselves.
 * Returns Map<String, Any?> / List<Any?> / String / Double / Boolean /
 * null. Throws IllegalArgumentException on malformed input; callers
 * treat that as "no data".
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        return v
    }

    private class Parser(private val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.regionMatches(i, word, 0, word.length)) {
                throw IllegalArgumentException("bad literal at $i")
            }
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            if (start == i) throw IllegalArgumentException("bad value at $i")
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            // s[i] == '"'
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    sb.append(
                                        s.substring(i, i + 4)
                                            .toInt(16).toChar(),
                                    )
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun arr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                ws()
                if (i >= s.length) throw IllegalArgumentException("eof in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') {
                    throw IllegalArgumentException("expected key at $i")
                }
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') {
                    throw IllegalArgumentException("expected : at $i")
                }
                i++
                out[k] = value()
                ws()
                if (i >= s.length) throw IllegalArgumentException("eof in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }
    }
}
