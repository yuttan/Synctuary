package io.synctuary.android.data.api

import android.util.Log
import com.squareup.moshi.Moshi
import io.synctuary.android.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Builds [SynctuaryApi] instances bound to a single server URL with
 * optional TLS certificate pinning per PROTOCOL §3.3.
 *
 * Two trust models supported:
 *   - **No pinning** (default): standard system trust store. Fine for
 *     §10.2 production with a real CA-signed cert.
 *   - **SHA-256 fingerprint**: pass the server's leaf-cert SHA-256
 *     fingerprint (32 raw bytes) acquired during the initial /info
 *     exchange. A custom [FingerprintTrustManager] validates every
 *     subsequent TLS handshake against that fingerprint, enabling
 *     self-signed certificates (the typical home-LAN deployment).
 *
 * Plaintext (§10.1 dev) is allowed only via the manifest-level
 * `network_security_config.xml` opt-in; the OkHttp client itself does
 * not gate cleartext.
 */
object NetworkModule {

    /** Build a Retrofit-backed [SynctuaryApi].
     *
     *  @param baseUrl  e.g. `"https://192.168.1.10:8443"` or
     *                  `"https://[2001:db8::1]:8443"` for IPv6 direct —
     *                  must end without a trailing slash; we append `"/"`
     *                  so Retrofit's relative-path resolution works.
     *  @param fingerprint  optional SHA-256(DER cert) to pin. When `null`,
     *                      the system trust store is used unmodified.
     *  @param authInterceptor  optional [AuthInterceptor] for Bearer auth
     *                          on §6+ endpoints.
     */
    fun createOkHttpClient(
        baseUrl: String,
        fingerprint: ByteArray? = null,
        authInterceptor: AuthInterceptor? = null,
    ): OkHttpClient {
        return OkHttpClient.Builder().apply {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(5, TimeUnit.MINUTES)

            if (fingerprint != null) {
                // Use a custom TrustManager that validates the server's
                // leaf certificate by its SHA-256(DER) fingerprint.
                // This enables self-signed certs (typical home LAN setup)
                // without requiring the cert to be in the system trust store.
                val trustManager = FingerprintTrustManager(fingerprint)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustManager)
                // The fingerprint check is sufficient — skip hostname
                // verification so IPv6 literal URLs and LAN IPs work
                // without requiring matching SANs.
                hostnameVerifier { _, _ -> true }
            }

            if (authInterceptor != null) {
                addInterceptor(authInterceptor)
            }

            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor { msg -> Log.d("Synctuary/HTTP", msg) }
                        .apply { level = HttpLoggingInterceptor.Level.HEADERS },
                )
            }
        }.build()
    }

    fun create(
        baseUrl: String,
        fingerprint: ByteArray? = null,
        authInterceptor: AuthInterceptor? = null,
    ): SynctuaryApi {
        val rootUrl = baseUrl.trimEnd('/') + "/"
        val client = createOkHttpClient(baseUrl, fingerprint, authInterceptor)

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(rootUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        return retrofit.create(SynctuaryApi::class.java)
    }

    /** Compute SHA-256(DER) over an X.509 certificate's DER encoding. */
    fun computeFingerprint(certDer: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certDer)
}
