package com.opus.airvia.ap2

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signatures (RFC 8032), pure Kotlin on [BigInteger] field math.
 *
 * Needed for HAP persistent pairing (pair-setup M5/M6 identity exchange
 * and pair-verify), which the transient HomePod flow never uses — the app
 * had no Ed25519 anywhere before this. Points are held in extended
 * twisted-Edwards coordinates (a = -1) so scalar multiplication needs no
 * field inversions; encoding/decoding follows RFC 8032 §5.1.
 *
 * Pure JVM — verified against the RFC 8032 §7.1 known-answer tests and
 * Python `cryptography` in tests/test_pairing.kt.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    /** Group order L = 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    /** d = -121665/121666 mod p. */
    private val D: BigInteger =
        BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")

    /** I = sqrt(-1) mod p = 2^((p-1)/4) mod p. */
    private val I: BigInteger =
        BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752")

    private val BX: BigInteger =
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY: BigInteger =
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    /** Extended point (X, Y, Z, T): x = X/Z, y = Y/Z, T = XY/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE = Pt(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))

    private fun mod(x: BigInteger): BigInteger = x.mod(P)

    /** Unified addition (add-2008-hwcd, a = -1); also valid for doubling. */
    private fun add(p1: Pt, p2: Pt): Pt {
        val a = mod((p1.y - p1.x) * (p2.y - p2.x))
        val b = mod((p1.y + p1.x) * (p2.y + p2.x))
        val c = mod(D * p1.t * p2.t * BigInteger.TWO)
        val d = mod(p1.z * p2.z * BigInteger.TWO)
        val e = mod(b - a)
        val f = mod(d - c)
        val g = mod(d + c)
        val h = mod(b + a)
        return Pt(mod(e * f), mod(g * h), mod(f * g), mod(e * h))
    }

    /** Doubling (dbl-2008-hwcd, a = -1). */
    private fun dbl(p1: Pt): Pt {
        val a = mod(p1.x * p1.x)
        val b = mod(p1.y * p1.y)
        val c = mod(p1.z * p1.z * BigInteger.TWO)
        val d = mod(a.negate())
        val e = mod((p1.x + p1.y) * (p1.x + p1.y) - a - b)
        val g = mod(d + b)
        val f = mod(g - c)
        val h = mod(d - b)
        return Pt(mod(e * f), mod(g * h), mod(f * g), mod(e * h))
    }

    /** Scalar multiplication by double-and-add over the scalar's bits. */
    private fun scalarmult(pt: Pt, scalar: BigInteger): Pt {
        var result = IDENTITY
        var i = scalar.bitLength() - 1
        while (i >= 0) {
            result = dbl(result)
            if (scalar.testBit(i)) result = add(result, pt)
            i--
        }
        return result
    }

    private fun pointsEqual(p1: Pt, p2: Pt): Boolean =
        mod(p1.x * p2.z) == mod(p2.x * p1.z) && mod(p1.y * p2.z) == mod(p2.y * p1.z)

    // -- byte encodings ---------------------------------------------------

    private fun leToBig(bytes: ByteArray): BigInteger =
        BigInteger(1, bytes.reversedArray())

    private fun bigToLe(x: BigInteger, length: Int = 32): ByteArray {
        val raw = x.toByteArray()
        val mag = if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(mag.size <= length) { "value does not fit in $length bytes" }
        return (ByteArray(length - mag.size) + mag).reversedArray()
    }

    /** RFC 8032 point encoding: y little-endian, x's low bit in the top bit. */
    private fun encodePoint(pt: Pt): ByteArray {
        val zInv = pt.z.modInverse(P)
        val x = mod(pt.x * zInv)
        val y = mod(pt.y * zInv)
        val out = bigToLe(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Decode a 32-byte point encoding; null when not a valid point. */
    private fun decodePoint(bytes: ByteArray): Pt? {
        if (bytes.size != 32) return null
        val sign = (bytes[31].toInt() and 0x80) != 0
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = leToBig(yBytes)
        if (y >= P) return null
        // x = sqrt((y^2 - 1) / (d y^2 + 1)) with the p = 5 (mod 8) method.
        val yy = mod(y * y)
        val u = mod(yy - BigInteger.ONE)
        val v = mod(D * yy + BigInteger.ONE)
        val v2 = mod(v * v)
        val v3 = mod(v2 * v)
        val v7 = mod(v3 * v2 * v2)
        var x = mod(u * v3 * mod(u * v7).modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P))
        val vx2 = mod(v * x * x)
        if (vx2 == mod(u.negate())) {
            x = mod(x * I)
        } else if (vx2 != u) {
            return null
        }
        if (x.signum() == 0 && sign) return null
        if (x.testBit(0) != sign) x = P - x
        return Pt(x, y, BigInteger.ONE, mod(x * y))
    }

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    // -- public API ---------------------------------------------------------

    /** A keypair: [secretSeed] is the 32-byte private seed, [publicKey] 32B. */
    class KeyPair(val secretSeed: ByteArray, val publicKey: ByteArray)

    private fun clampAndPrefix(seed: ByteArray): Pair<BigInteger, ByteArray> {
        val h = sha512(seed)
        val aBytes = h.copyOfRange(0, 32)
        aBytes[0] = (aBytes[0].toInt() and 248).toByte()
        aBytes[31] = (aBytes[31].toInt() and 63).toByte()
        aBytes[31] = (aBytes[31].toInt() or 64).toByte()
        return leToBig(aBytes) to h.copyOfRange(32, 64)
    }

    /** Derive the keypair for a 32-byte seed (RFC 8032 §5.1.5). */
    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val (a, _) = clampAndPrefix(seed)
        return KeyPair(seed.copyOf(), encodePoint(scalarmult(BASE, a)))
    }

    /** Sign [message] with the keypair belonging to [seed] (RFC 8032 §5.1.6). */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val (a, prefix) = clampAndPrefix(seed)
        val pub = encodePoint(scalarmult(BASE, a))
        val r = leToBig(sha512(prefix, message)).mod(L)
        val rEnc = encodePoint(scalarmult(BASE, r))
        val k = leToBig(sha512(rEnc, pub, message)).mod(L)
        val s = r.add(k.multiply(a)).mod(L)
        return rEnc + bigToLe(s)
    }

    /** Verify a 64-byte [signature] on [message] under [publicKey]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val aPt = decodePoint(publicKey) ?: return false
        val rEnc = signature.copyOfRange(0, 32)
        val rPt = decodePoint(rEnc) ?: return false
        val s = leToBig(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = leToBig(sha512(rEnc, publicKey, message)).mod(L)
        val left = scalarmult(BASE, s)
        val right = add(rPt, scalarmult(aPt, k))
        return pointsEqual(left, right)
    }
}
