package io.synctuary.android.data.api

import io.synctuary.android.data.secret.SecretStore
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val secretStore: SecretStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val paired = secretStore.loadPairedDevice() ?: return chain.proceed(original)
        val request = original.newBuilder()
            .header("Authorization", paired.bearerHeader())
            .build()
        return chain.proceed(request)
    }
}
