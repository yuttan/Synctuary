package io.synctuary.android.data.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Custom [X509TrustManager] that validates a server's leaf certificate by
 * comparing its SHA-256(DER) fingerprint against a known-good value.
 *
 * This implements the TOFU (trust on first use) model described in
 * PROTOCOL section 3.3: the server's TLS fingerprint is captured during the
 * initial pairing handshake and stored in [SecretStore]. All subsequent
 * connections pin against that fingerprint rather than relying on the
 * system CA trust store.
 *
 * Why not [okhttp3.CertificatePinner]?  CertificatePinner runs *after*
 * the standard TLS handshake completes, which means the system trust
 * store must already accept the certificate chain.  Self-signed certs
 * (the typical Synctuary home-LAN setup) are rejected before the pin
 * check ever fires.  A custom TrustManager short-circuits the chain
 * validation entirely when the fingerprint matches.
 *
 * Security note: this trusts exactly ONE leaf certificate identified by
 * its SHA-256 hash (256-bit preimage resistance). Clients that pair
 * without TLS (dev mode) store a null fingerprint and fall back to the
 * system trust store via [NetworkModule.createOkHttpClient].
 */
class FingerprintTrustManager(
    private val expectedFingerprint: ByteArray,
) : X509TrustManager {

    init {
        require(expectedFingerprint.size == 32) {
            "TLS fingerprint must be 32 bytes (SHA-256), got ${expectedFingerprint.size}"
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client authentication not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty certificate chain")
        }

        // Verify the leaf certificate's SHA-256(DER) fingerprint.
        val leaf = chain[0]
        val actualFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(leaf.encoded)

        if (!actualFingerprint.contentEquals(expectedFingerprint)) {
            val expected = expectedFingerprint.toHex()
            val actual = actualFingerprint.toHex()
            throw CertificateException(
                "TLS fingerprint mismatch: expected=$expected, actual=$actual"
            )
        }

        // Fingerprint matches — also verify the cert is not expired.
        // This catches the "self-signed cert silently expired" scenario
        // described in deploy/tls/README.md.
        try {
            leaf.checkValidity()
        } catch (e: Exception) {
            throw CertificateException("Certificate fingerprint matches but cert is expired", e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun ByteArray.toHex(): String =
        joinToString(":") { "%02x".format(it) }
}
