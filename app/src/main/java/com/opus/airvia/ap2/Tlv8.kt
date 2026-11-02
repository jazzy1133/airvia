package com.opus.airvia.ap2

import java.io.ByteArrayOutputStream

/**
 * TLV8 codec (HomeKit/HAP §14.1.1): Type(1 byte) | Length(1 byte) | Value.
 * Values longer than 255 bytes are fragmented into repeated entries of the
 * same type (255-byte chunks, then the remainder); decoding concatenates
 * consecutive same-type fragments back together.
 *
 * Pure JVM — no Android APIs — so the JVM unit tests can exercise it.
 */
object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val NAME = 0x11
    const val FLAGS = 0x13 // HAP pair-setup Flags TLV type (Apple: 0x13)
    const val SEPARATOR = 0xFF

    /** HAP pair-setup Flags values. */
    const val FLAG_TRANSIENT_PAIRING = 0x10

    /** HAP kTLVError_* names for the 0x07 error TLV. */
    fun errorName(code: Int): String = when (code) {
        0x01 -> "unknown"
        0x02 -> "authentication"
        0x03 -> "backoff"
        0x04 -> "maxTries"
        0x05 -> "maxPeers"
        0x06 -> "unavailable"
        0x07 -> "busy"
        else -> "code $code"
    }

    /** Encode entries; values > 255 bytes are fragmented. */
    fun encode(entries: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in entries) {
            require(type in 0..255) { "TLV type out of range: $type" }
            var off = 0
            // Zero-length values still emit exactly one (type, 0) entry
            // (e.g. the 0xFF separator).
            do {
                val chunk = minOf(255, value.size - off)
                out.write(type)
                out.write(chunk)
                out.write(value, off, chunk)
                off += chunk
            } while (off < value.size)
        }
        return out.toByteArray()
    }

    /**
     * Decode; consecutive fragments carrying the same type are concatenated
     * into a single entry (first-seen order preserved).
     */
    fun decode(data: ByteArray): List<Pair<Int, ByteArray>> {
        val frags = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            require(i + 2 + len <= data.size) { "TLV8 truncated at offset $i" }
            frags.add(type to data.copyOfRange(i + 2, i + 2 + len))
            i += 2 + len
        }
        require(i == data.size) { "TLV8 trailing byte at offset $i" }
        val pending = LinkedHashMap<Int, ByteArrayOutputStream>()
        for ((t, v) in frags) {
            pending.getOrPut(t) { ByteArrayOutputStream() }.write(v)
        }
        return pending.map { (t, b) -> t to b.toByteArray() }
    }

    fun first(entries: List<Pair<Int, ByteArray>>, type: Int): ByteArray? =
        entries.firstOrNull { it.first == type }?.second

    fun hex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) append("%02x".format(x))
    }

    /** One-line "0x02=16B, 0x03=384B" summary for the log. */
    fun summary(entries: List<Pair<Int, ByteArray>>): String =
        entries.joinToString(", ") { (t, v) -> "0x%02x=%dB".format(t, v.size) }
}
