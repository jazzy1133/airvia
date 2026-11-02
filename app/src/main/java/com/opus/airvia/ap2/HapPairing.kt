package com.opus.airvia.ap2

/**
 * Pure protocol math for HAP persistent pairing (pair-setup M5/M6 and
 * pair-verify), mirroring pyatv's `auth/hap_srp.py` construction exactly:
 *
 * Pair-setup (all derivations over the SRP session key K — there is NO
 * X25519 in pair-setup; pyatv/report agree the SRP secret is the root):
 *  - sign material X = HKDF-SHA512(K, "Pair-Setup-Controller-Sign-Salt",
 *    "Pair-Setup-Controller-Sign-Info", 32) for us, and the
 *    "Pair-Setup-Accessory-Sign-*" pair for the accessory
 *  - our M5 signature = Ed25519_sign(our seed, X || our-id || our-LTPK)
 *  - accessory M6 signature = Ed25519_sign(accessory, X' || its-id || its-LTPK)
 *  - sub-TLV encryption key = HKDF-SHA512(K, "Pair-Setup-Encrypt-Salt",
 *    "Pair-Setup-Encrypt-Info", 32); ChaCha nonces "PS-Msg05"/"PS-Msg06"
 *    (8-byte labels, zero-padded to the 12-byte AEAD nonce)
 *
 * Pair-verify (derivations over the X25519 shared secret):
 *  - sub-TLV key = HKDF-SHA512(shared, "Pair-Verify-Encrypt-Salt",
 *    "Pair-Verify-Encrypt-Info", 32); nonces "PV-Msg02"/"PV-Msg03"
 *  - accessory signature covers (accessory-ephemeral || accessory-id ||
 *    our-ephemeral); ours covers (our-ephemeral || our-id ||
 *    accessory-ephemeral) — raw concatenations, no HKDF sign material
 *  - control keys = HKDF-SHA512(shared, "Control-Salt",
 *    "Control-Write/Read-Encryption-Key", 32) — same labels as transient
 *
 * Everything here is socket-free and Android-free so the JVM suites can
 * pin it against Python `cryptography` golden vectors and a scripted
 * fake receiver. No secret material is ever logged by this file.
 */
object HapPairing {
    class HapException(message: String) : Exception(message)

    /** Our long-term controller identity: one Ed25519 keypair + identifier,
     *  generated once and reused for every receiver (pyatv parity). */
    class ControllerIdentity(val seed: ByteArray, val identifier: String) {
        init {
            require(seed.size == 32) { "controller seed must be 32 bytes" }
        }

        val publicKey: ByteArray by lazy { Ed25519.keyPairFromSeed(seed).publicKey }
        val identifierBytes: ByteArray get() = identifier.toByteArray(Charsets.UTF_8)
    }

    /**
     * Stored pairing with one receiver, in pyatv's credential layout:
     * `hex(accessory-ltpk):hex(controller-ltsk):hex(accessory-id):hex(controller-id)`
     * where the ids are the UTF-8 bytes of the identifier strings.
     */
    class PairCredentials(
        val accessoryLtpk: ByteArray,
        val controllerSeed: ByteArray,
        val accessoryId: String,
        val controllerId: String,
    ) {
        fun toStoredString(): String = listOf(
            Tlv8.hex(accessoryLtpk),
            Tlv8.hex(controllerSeed),
            Tlv8.hex(accessoryId.toByteArray(Charsets.UTF_8)),
            Tlv8.hex(controllerId.toByteArray(Charsets.UTF_8)),
        ).joinToString(":")

        companion object {
            fun parse(stored: String): PairCredentials? {
                val parts = stored.split(":")
                if (parts.size != 4) return null
                return try {
                    val ltpk = unhex(parts[0])
                    val seed = unhex(parts[1])
                    val atvId = String(unhex(parts[2]), Charsets.UTF_8)
                    val clientId = String(unhex(parts[3]), Charsets.UTF_8)
                    if (ltpk.size != 32 || seed.size != 32) return null
                    PairCredentials(ltpk, seed, atvId, clientId)
                } catch (_: Exception) {
                    null
                }
            }

            private fun unhex(s: String): ByteArray {
                require(s.length % 2 == 0) { "odd hex" }
                return ByteArray(s.length / 2) { i ->
                    s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
        }
    }

    /** HAP message nonces: the 8-byte ASCII label behind 4 zero bytes. */
    fun hapNonce(label: String): ByteArray {
        val l = label.toByteArray(Charsets.US_ASCII)
        require(l.size == 8) { "HAP nonce label must be 8 bytes: $label" }
        return ByteArray(4) + l
    }

    // -- pair-setup M5/M6 ---------------------------------------------------

    fun setupEncryptKey(k64: ByteArray): ByteArray = Hkdf.derive(
        k64,
        "Pair-Setup-Encrypt-Salt".toByteArray(),
        "Pair-Setup-Encrypt-Info".toByteArray(),
        32,
    )

    fun controllerSignMaterial(k64: ByteArray): ByteArray = Hkdf.derive(
        k64,
        "Pair-Setup-Controller-Sign-Salt".toByteArray(),
        "Pair-Setup-Controller-Sign-Info".toByteArray(),
        32,
    )

    fun accessorySignMaterial(k64: ByteArray): ByteArray = Hkdf.derive(
        k64,
        "Pair-Setup-Accessory-Sign-Salt".toByteArray(),
        "Pair-Setup-Accessory-Sign-Info".toByteArray(),
        32,
    )

    /** The M5 sub-TLV plaintext: Identifier || PublicKey || Signature. */
    fun buildM5Plaintext(identity: ControllerIdentity, k64: ByteArray): ByteArray {
        val signature = Ed25519.sign(
            identity.seed,
            controllerSignMaterial(k64) + identity.identifierBytes + identity.publicKey,
        )
        return Tlv8.encode(
            listOf(
                Tlv8.IDENTIFIER to identity.identifierBytes,
                Tlv8.PUBLIC_KEY to identity.publicKey,
                Tlv8.SIGNATURE to signature,
            ),
        )
    }

    /** M5 EncryptedData value: the sub-TLV sealed under the PS-Msg05 nonce. */
    fun buildM5Encrypted(identity: ControllerIdentity, k64: ByteArray): ByteArray =
        ChaCha20Poly1305.seal(
            setupEncryptKey(k64), hapNonce("PS-Msg05"), buildM5Plaintext(identity, k64),
        )

    /** What M6 must yield: the accessory's identifier + long-term public key. */
    class AccessoryIdentity(val identifier: String, val ltpk: ByteArray)

    /**
     * Decrypt and authenticate M6's EncryptedData. The accessory signature
     * is verified (pyatv skips this — a TODO in hap_srp.py — the HAP spec
     * requires it) over (accessory sign material || id || LTPK).
     */
    fun parseM6Encrypted(k64: ByteArray, encrypted: ByteArray): AccessoryIdentity {
        val plain = ChaCha20Poly1305.open(
            setupEncryptKey(k64), hapNonce("PS-Msg06"), encrypted,
        ) ?: throw HapException("M6: encrypted data does not decrypt (bad tag)")
        val entries = try {
            Tlv8.decode(plain)
        } catch (e: Exception) {
            throw HapException("M6: sub-TLV does not parse: ${e.message}")
        }
        val idBytes = Tlv8.first(entries, Tlv8.IDENTIFIER)
            ?: throw HapException("M6: no identifier TLV")
        val ltpk = Tlv8.first(entries, Tlv8.PUBLIC_KEY)
            ?: throw HapException("M6: no public key TLV")
        val signature = Tlv8.first(entries, Tlv8.SIGNATURE)
            ?: throw HapException("M6: no signature TLV")
        if (ltpk.size != 32) throw HapException("M6: accessory key is ${ltpk.size}B, want 32")
        val material = accessorySignMaterial(k64) + idBytes + ltpk
        if (!Ed25519.verify(ltpk, material, signature)) {
            throw HapException("M6: accessory signature does not verify")
        }
        return AccessoryIdentity(String(idBytes, Charsets.UTF_8), ltpk)
    }

    // -- pair-verify ----------------------------------------------------------

    fun verifyEncryptKey(sharedSecret: ByteArray): ByteArray = Hkdf.derive(
        sharedSecret,
        "Pair-Verify-Encrypt-Salt".toByteArray(),
        "Pair-Verify-Encrypt-Info".toByteArray(),
        32,
    )

    /**
     * Decrypt and authenticate the accessory's M2 sub-TLV: identifier must
     * match the stored pairing, and its signature over (accessory
     * ephemeral || accessory id || our ephemeral) must verify under the
     * stored accessory LTPK.
     */
    fun parseVerifyM2(
        creds: PairCredentials,
        sharedSecret: ByteArray,
        ourEphemeralPublic: ByteArray,
        accessoryEphemeralPublic: ByteArray,
        encrypted: ByteArray,
    ) {
        val plain = ChaCha20Poly1305.open(
            verifyEncryptKey(sharedSecret), hapNonce("PV-Msg02"), encrypted,
        ) ?: throw HapException("pair-verify M2: encrypted data does not decrypt (bad tag)")
        val entries = try {
            Tlv8.decode(plain)
        } catch (e: Exception) {
            throw HapException("pair-verify M2: sub-TLV does not parse: ${e.message}")
        }
        val idBytes = Tlv8.first(entries, Tlv8.IDENTIFIER)
            ?: throw HapException("pair-verify M2: no identifier TLV")
        val signature = Tlv8.first(entries, Tlv8.SIGNATURE)
            ?: throw HapException("pair-verify M2: no signature TLV")
        val accessoryId = String(idBytes, Charsets.UTF_8)
        if (accessoryId != creds.accessoryId) {
            throw HapException("pair-verify M2: accessory identifier does not match the stored pairing")
        }
        val material = accessoryEphemeralPublic + idBytes + ourEphemeralPublic
        if (!Ed25519.verify(creds.accessoryLtpk, material, signature)) {
            throw HapException("pair-verify M2: accessory signature does not verify")
        }
    }

    /** Our M3 EncryptedData: (Identifier || Signature) sealed under PV-Msg03. */
    fun buildVerifyM3Encrypted(
        identity: ControllerIdentity,
        sharedSecret: ByteArray,
        ourEphemeralPublic: ByteArray,
        accessoryEphemeralPublic: ByteArray,
    ): ByteArray {
        val signature = Ed25519.sign(
            identity.seed,
            ourEphemeralPublic + identity.identifierBytes + accessoryEphemeralPublic,
        )
        val tlv = Tlv8.encode(
            listOf(
                Tlv8.IDENTIFIER to identity.identifierBytes,
                Tlv8.SIGNATURE to signature,
            ),
        )
        return ChaCha20Poly1305.seal(
            verifyEncryptKey(sharedSecret), hapNonce("PV-Msg03"), tlv,
        )
    }

    /** Control-channel keys from the pair-verify shared secret. */
    fun controlKeys(sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        val write = Hkdf.derive(
            sharedSecret,
            "Control-Salt".toByteArray(),
            "Control-Write-Encryption-Key".toByteArray(),
            32,
        )
        val read = Hkdf.derive(
            sharedSecret,
            "Control-Salt".toByteArray(),
            "Control-Read-Encryption-Key".toByteArray(),
            32,
        )
        return write to read
    }
}
