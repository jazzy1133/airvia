package com.opus.airvia.ap2

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a client for the AirPlay 2 transient pair-setup handshake
 * (HAP-style /pair-setup with SRP).
 *
 * - 3072-bit group, RFC 5054 Appendix A (N below, g = 5), hash SHA-512
 * - Identity is the literal "Pair-Setup", PIN is "3939"
 * - k  = SHA512(N_min || g_pad384)            (srptools: prime minimal, gen padded)
 * - x  = SHA512(salt || SHA512("Pair-Setup:3939"))
 * - u  = SHA512(A_pad384 || B_pad384)
 * - S  = (B - k*g^x)^(a + u*x) mod N
 * - K  = SHA512(S_minimal)                    (64 bytes; srptools hashes the
 *                                            minimal-length int encoding)
 * - M1 = SHA512( (H(N) XOR H(g))_minimal || H("Pair-Setup")_minimal ||
 *                salt || A_minimal || B_minimal || K )   (64 bytes)
 *   where H(N)=SHA512(N_minimal), H(g)=SHA512(g_minimal=0x05) — NOTE: g is
 *   hashed in its 1-byte minimal form here, NOT padded (this differs from k).
 * - server proof (M2) = SHA512(A_minimal || M1 || K)
 *
 * Integer hashing uses the minimal unsigned big-endian encoding (leading
 * zeros stripped), exactly like srptools (pyatv's SRP backend). A, B stay
 * 384-byte padded on the WIRE (TLV), but minimal inside hashes.
 *
 * Pure JVM — byte-verified against srptools 1.0.1 with fixed test vectors.
 */
object SrpClient {
    const val IDENTITY = "Pair-Setup"
    const val PIN = "3939"

    /** RFC 5054 Appendix A, 3072-bit group prime (768 hex chars). */
    private const val N_HEX = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
    val N: BigInteger = BigInteger(N_HEX, 16)
    val G: BigInteger = BigInteger.valueOf(5)

    /** Wire length of N, g, A, B: 384 bytes. */
    const val KEY_LEN = 384

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** Unsigned big-endian, left-padded with zeros to 384 bytes. */
    fun pad(x: BigInteger): ByteArray {
        require(x.signum() >= 0) { "negative value cannot be padded" }
        val raw = x.toByteArray() // may carry a leading sign byte
        val mag = if (raw.isNotEmpty() && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        require(mag.size <= KEY_LEN) { "value exceeds 384 bytes" }
        return ByteArray(KEY_LEN - mag.size) + mag
    }

    /** Unsigned big-endian, minimal length (no leading zero bytes). Matches
     *  srptools' int encoding used inside hashes. */
    fun minimal(x: BigInteger): ByteArray {
        require(x.signum() >= 0) { "negative value cannot be minimal-encoded" }
        val raw = x.toByteArray() // may carry a leading sign byte
        var start = 0
        while (start < raw.size - 1 && raw[start] == 0.toByte()) start++
        return raw.copyOfRange(start, raw.size)
    }

    fun bi(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    /** k = SHA512(N_pad || g_pad), the SRP-6a multiplier. */
    fun k(): BigInteger = bi(sha512(pad(N), pad(G)))

    /** x = SHA512(salt || SHA512("Pair-Setup:<password>")). The transient
     *  flow uses the default [PIN]; persistent PIN pairing passes the
     *  user-entered code. */
    fun x(salt: ByteArray, password: String = PIN): BigInteger =
        bi(sha512(salt, sha512("$IDENTITY:$password".toByteArray(Charsets.UTF_8))))

    data class Ephemeral(val a: BigInteger, val aPub: ByteArray)

    /** Fresh client ephemeral: a = 256-bit random, A = g^a mod N. */
    fun newEphemeral(random: SecureRandom = SecureRandom()): Ephemeral {
        val a = BigInteger(256, random)
        return Ephemeral(a, pad(G.modPow(a, N)))
    }

    data class SessionKeys(
        val k64: ByteArray,
        val m1: ByteArray,
        val aPub: ByteArray,
        /** Raw SRP shared secret S (minimal-length bytes); shk = first 32B. */
        val sRaw: ByteArray,
    )

    /**
     * Client side of M2->M3 given the server's salt and B, and our
     * ephemeral [a]. Returns the session key K (64B), our M1 proof (64B)
     * and A (384B) for the M3 body and later server-proof verification.
     */
    fun compute(
        salt: ByteArray,
        bBytes: ByteArray,
        a: BigInteger,
        password: String = PIN,
    ): SessionKeys {
        val aPub = pad(G.modPow(a, N))
        val b = bi(bBytes)
        require(b.mod(N) != BigInteger.ZERO) { "B mod N == 0 (invalid server key)" }
        val kk = k()
        val xx = x(salt, password)
        val u = bi(sha512(aPub, pad(b)))
        val base = (b - kk * G.modPow(xx, N)).mod(N)
        val s = base.modPow(a + u * xx, N)
        // srptools: K = SHA512(minimal(S)); M1/M2 use minimal int encodings.
        val k64 = sha512(minimal(s))
        val hN = bi(sha512(minimal(N)))
        val hg = bi(sha512(minimal(G))) // 0x05 as a single byte — NOT padded
        val xorMin = minimal(hN xor hg)
        val hImin = minimal(bi(sha512(IDENTITY.toByteArray(Charsets.UTF_8))))
        val m1 = sha512(
            xorMin,
            hImin,
            salt,
            minimal(bi(aPub)),
            minimal(b),
            k64,
        )
        return SessionKeys(k64, m1, aPub, minimal(s))
    }

    /** Expected server M2 proof: SHA512(A_minimal || M1 || K). */
    fun expectedServerProof(aPub: ByteArray, m1: ByteArray, k64: ByteArray): ByteArray =
        sha512(minimal(bi(aPub)), m1, k64)

    fun verifyServerProof(
        aPub: ByteArray,
        m1: ByteArray,
        k64: ByteArray,
        serverProof: ByteArray,
    ): Boolean = expectedServerProof(aPub, m1, k64).contentEquals(serverProof)
}
