package io.synctuary.android.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 keypair derivation + signing, backed by Bouncy Castle.
 *
 * BC is used (rather than `java.security.KeyPairGenerator("Ed25519")`)
 * because Android only ships Ed25519 in the platform JCE provider from
 * API 33+. minSdk = 26 — BC keeps every supported device on the same
 * implementation.
 *
 * The "seed" form (32-byte Ed25519 private-key seed) is interoperable
 * with Go's `ed25519.NewKeyFromSeed(seed)`.
 */
object Ed25519 {

    const val PUBLIC_KEY_SIZE = 32
    const val PRIVATE_SEED_SIZE = 32
    const val SIGNATURE_SIZE = 64

    /** Result type for [keypairFromSeed]. */
    data class KeyPair(val publicKey: ByteArray, val privateSeed: ByteArray) {
        // Generated equals/hashCode would compare the array references,
        // not contents. Tests need value semantics; users SHOULD NOT
        // compare keys outside of test code.
        override fun equals(other: Any?): Boolean =
            other is KeyPair &&
                publicKey.contentEquals(other.publicKey) &&
                privateSeed.contentEquals(other.privateSeed)

        override fun hashCode(): Int =
            publicKey.contentHashCode() * 31 + privateSeed.contentHashCode()
    }

    /**
     * Derive an Ed25519 keypair from a 32-byte seed. Mirrors Go's
     * `ed25519.NewKeyFromSeed(seed)` byte-for-byte: same RFC 8032
     * derivation, same 32-byte public key.
     */
    fun keypairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == PRIVATE_SEED_SIZE) {
            "Ed25519 seed must be $PRIVATE_SEED_SIZE bytes, got ${seed.size}"
        }
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey()
        val pubBytes = ByteArray(PUBLIC_KEY_SIZE)
        pub.encode(pubBytes, 0)
        return KeyPair(publicKey = pubBytes, privateSeed = seed.copyOf())
    }

    /**
     * Sign `message` with the Ed25519 private seed. Output is exactly
     * [SIGNATURE_SIZE] bytes.
     */
    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        require(privateSeed.size == PRIVATE_SEED_SIZE) {
            "Ed25519 seed must be $PRIVATE_SEED_SIZE bytes, got ${privateSeed.size}"
        }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Verify `signature` over `message` with `publicKey`. Returns false
     * for any size mismatch (no distinct exception path) so callers
     * can't time-side-channel the failure cause.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
