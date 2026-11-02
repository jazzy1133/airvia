package com.opus.airvia.ap2

import java.math.BigInteger
import java.security.SecureRandom

/**
 * X25519 Diffie-Hellman (RFC 7748 §5), pure Kotlin Montgomery ladder on
 * [BigInteger]. Needed for HAP pair-verify (the session's control keys
 * come from this shared secret); pair-setup's identity exchange derives
 * from the SRP key instead and does not use it.
 *
 * Pure JVM — verified against the RFC 7748 §5.2/§6.1 known-answer tests
 * in tests/test_pairing.kt.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private const val A24 = 121665L

    /** An ephemeral keypair: 32-byte private scalar (raw, unclamped) + public u. */
    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val priv = ByteArray(32)
        random.nextBytes(priv)
        return KeyPair(priv, publicFromPrivate(priv))
    }

    fun keyPairFromPrivate(privateKey: ByteArray): KeyPair {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        return KeyPair(privateKey.copyOf(), publicFromPrivate(privateKey))
    }

    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        return x25519(privateKey, basePoint())
    }

    /** The shared secret with a peer's public key; null-free 32 bytes. */
    fun exchange(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && peerPublicKey.size == 32) {
            "X25519 keys must be 32 bytes"
        }
        return x25519(privateKey, peerPublicKey)
    }

    private fun basePoint(): ByteArray {
        val b = ByteArray(32)
        b[0] = 9
        return b
    }

    private fun decodeScalar(k: ByteArray): BigInteger {
        val b = k.copyOf()
        b[0] = (b[0].toInt() and 248).toByte()
        b[31] = (b[31].toInt() and 127).toByte()
        b[31] = (b[31].toInt() or 64).toByte()
        return BigInteger(1, b.reversedArray())
    }

    private fun decodeU(u: ByteArray): BigInteger {
        val b = u.copyOf()
        b[31] = (b[31].toInt() and 127).toByte() // mask the top bit (RFC 7748 §5)
        return BigInteger(1, b.reversedArray()).mod(P)
    }

    private fun encodeU(x: BigInteger): ByteArray {
        val raw = x.mod(P).toByteArray()
        val mag = if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(mag.size <= 32) { "u-coordinate overflow" }
        return (ByteArray(32 - mag.size) + mag).reversedArray()
    }

    /** RFC 7748 §5 Montgomery ladder. */
    private fun x25519(scalarBytes: ByteArray, uBytes: ByteArray): ByteArray {
        val k = decodeScalar(scalarBytes)
        val x1 = decodeU(uBytes)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0
        val a24 = BigInteger.valueOf(A24)
        fun m(v: BigInteger): BigInteger = v.mod(P)
        for (t in 254 downTo 0) {
            val bit = if (k.testBit(t)) 1 else 0
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit
            val a = m(x2 + z2)
            val aa = m(a * a)
            val b = m(x2 - z2)
            val bb = m(b * b)
            val e = m(aa - bb)
            val c = m(x3 + z3)
            val d = m(x3 - z3)
            val da = m(d * a)
            val cb = m(c * b)
            x3 = m((da + cb) * (da + cb))
            z3 = m(x1 * (da - cb) * (da - cb))
            x2 = m(aa * bb)
            z2 = m(e * (aa + a24 * e))
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encodeU(m(x2 * z2.modInverse(P)))
    }
}
