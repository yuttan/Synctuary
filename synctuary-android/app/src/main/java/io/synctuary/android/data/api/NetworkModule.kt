package io.synctuary.android.data.api

import android.util.Log
import com.squareup.moshi.Moshi
import io.synctuary.android.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Builds [SynctuaryApi] instances bound to a single server URL with
 * optional TLS certificate pinning per PROTOCOL §3.3.
 *
 * Two pinning models supported:
 *   - **No pinning** (default): standard system trust store. Fine for
 *     §10.2 production with a real CA-signed cert.
 *   - **SHA-256 pin**: pass the server's leaf-cert SHA-256 fingerprint
 *     (32 raw bytes) acquired during the very first /info exchange.
 *     OkHttp's CertificatePinner enforces it on every subsequent call.
 *
 * Plaintext (§10.1 dev) is allowed only via the manifest-level
 * `network_security_config.xml` opt-in; the OkHttp client itself does
 * not gate cleartext.
 */
object NetworkModule {

    /** Build a Retrofit-backed [SynctuaryApi].
     *
     *  @param baseUrl  e.g. `"https://192.168.1.10:8443"` — must end without
     *                  a trailing slash; we append `"/"` so Retrofit's
     *                  relative-path resolution works.
     *  @param fingerprint  optional SHA-256(DER cert) to pin. When `null`,
     *                      the system trust store is used unmodified.
     *  @param authInterceptor  optional [AuthInterceptor] for Bearer auth
     *                          on §6+ endpoints.
     */
    fun create(
        baseUrl: String,
        fingerprint: ByteArray? = null,
        authInterceptor: AuthInterceptor? = null,
    ): SynctuaryApi {
        val rootUrl = baseUrl.trimEnd('/') + "/"
        val parsed = rootUrl.toHttpUrl()

        val client = OkHttpClient.Builder().apply {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(5, TimeUnit.MINUTES)

            if (fingerprint != null) {
                require(fingerprint.size == 32) {
                    "TLS fingerprint must be 32 bytes (SHA-256), got ${fingerprint.size}"
                }
                certificatePinner(
                    CertificatePinner.Builder()
                        .add(parsed.host, "sha256/" + base64Std(fingerprint))
                        .build(),
                )
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

        val moshi = Moshi.Builder().build()
        val retrofit = Retrofit.Builder()
            .baseUrl(rootUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        return retrofit.create(SynctuaryApi::class.java)
    }

    /** Compute SHA-256(DER) over an X.509 certificate's DER encoding —
     *  the input format that OkHttp's CertificatePinner expects (after
     *  base64 encoding). Caller passes raw bytes. */
    fun computeFingerprint(certDer: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certDer)

    /** Standard (NOT base64url) base64 with padding — the format
     *  CertificatePinner consumes. The cert pin grammar is documented
     *  at https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/ */
    private fun base64Std(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)
}
