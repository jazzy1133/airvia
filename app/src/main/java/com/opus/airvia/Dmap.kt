package com.opus.airvia

import java.io.ByteArrayOutputStream

/**
 * DMAP (iTunes-style) metadata encoding for AirPlay SET_PARAMETER
 * requests — the format receivers display as now-playing info.
 *
 * Body layout: a sequence of tagged fields, each
 * `4-byte tag | 4-byte big-endian length | UTF-8 content`, sent with
 * Content-Type `application/x-dmap-tagged`. Pure JVM (unit tested).
 */
object Dmap {
    /** Cap per field so lengths stay < 128 (single low byte). */
    private const val MAX_FIELD_BYTES = 120

    private fun field(out: ByteArrayOutputStream, tag: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        var bytes = trimmed.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FIELD_BYTES) {
            // Truncate on a char boundary by decoding the prefix.
            var n = MAX_FIELD_BYTES
            while (n > 0 && (bytes[n].toInt() and 0xC0) == 0x80) n--
            bytes = bytes.copyOf(n)
        }
        out.write(tag.toByteArray(Charsets.US_ASCII))
        out.write(
            byteArrayOf(
                0, 0, 0, bytes.size.toByte(),
            ),
        )
        out.write(bytes)
    }

    /** Build the SET_PARAMETER body for a track; empty when all blank. */
    fun metadataBody(title: String, artist: String, album: String): ByteArray {
        val out = ByteArrayOutputStream()
        field(out, "minm", title)
        field(out, "asar", artist)
        field(out, "asal", album)
        return out.toByteArray()
    }

    /**
     * Placeholder track info sent when the source app provides no
     * now-playing metadata at all: some receivers (macOS especially —
     * issue #4) never open their player UI for a metadata-less session,
     * so the speaker shows "Airvia" with the app logo instead of
     * nothing. Real metadata always wins; this is only a fallback.
     */
    const val PLACEHOLDER_TITLE = "Airvia"
    const val PLACEHOLDER_ARTIST = "Airvia"

    fun placeholderBody(): ByteArray =
        metadataBody(PLACEHOLDER_TITLE, PLACEHOLDER_ARTIST, "")
}
