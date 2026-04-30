package io.synctuary.android.crypto

import java.util.Base64

/**
 * Base64url-without-padding helpers — the wire encoding for every
 * binary field in PROTOCOL v0.2.3 (§1).
 *
 * Mirrors Go's `base64.RawURLEncoding`: URL-safe alphabet (`-` and `_`
 * instead of `+` and `/`), no `=` padding.
 *
 * `java.util.Base64` lives on API 26+, matching the project's
 * `minSdk = 26`. Picking it over `android.util.Base64` lets the
 * unit-test JVM run these helpers natively without Robolectric.
 */
object B64Url {

    private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    fun decode(s: String): ByteArray = DECODER.decode(s)
}
