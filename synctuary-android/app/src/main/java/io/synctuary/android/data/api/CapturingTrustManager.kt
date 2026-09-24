package io.synctuary.android.data.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * First-contact [X509TrustManager] for mnemonic pairing (PROTOCOL §4.1
 * step 2): accepts whatever leaf certificate the server presents and
 * records its SHA-256(DER) fingerprint as the authoritative
 * `server_fingerprint`.
 *
 * Accepting an unknown certificate is safe ONLY for the unauthenticated
 * pairing handshake, because the captured fingerprint is signed into the
 * §4.1 pair payload and the server rebuilds that payload with its OWN
 * fingerprint. A man-in-the-middle presenting a different certificate
 * therefore makes /pair/register fail signature verification — it can
 * observe the handshake but never obtain a device_token. Every request
 * after /info is pinned to the captured value via [FingerprintTrustManager].
 *
 * Never use this for authenticated (§6+) traffic.
 */
class CapturingTrustManager : X509TrustManager {

    /** SHA-256(DER) of the most recent server leaf certificate, or null
     *  before the first TLS handshake. */
    @Volatile
    var capturedFingerprint: ByteArray? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client authentication not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty certificate chain")
        }
        capturedFingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
