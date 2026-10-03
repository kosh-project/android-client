package project.kosh.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton


/**
 * Dagger/Hilt qualifier identifying the unauthenticated [OkHttpClient] instance.
 *
 * Injected into public API clients such as [AuthClient] that must communicate with
 * `/api/auth` endpoints without attaching bearer tokens.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicOkHttpClient

/**
 * Dagger/Hilt qualifier identifying the authenticated [OkHttpClient] instance.
 *
 * Configured with [AuthInterceptor] to inject bearer authentication headers.
 * Injected into protected vault clients such as [AssetClient].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

/**
 * Dagger Hilt dependency injection module providing configured [OkHttpClient] instances.
 *
 * ### Architectural Architecture: Dual-Client Design
 * The network architecture maintains two distinct HTTP client pipelines:
 * 1. **Public Client ([PublicOkHttpClient]):** Dedicated to anonymous authentication flows (challenge requests,
 *    registration, login). It does not hold token interceptors, preventing stale or invalid bearer headers
 *    from reaching unauthenticated server endpoints.
 * 2. **Authenticated Client ([AuthOkHttpClient]):** Inherits socket pools and TLS settings from the public
 *    client but layers on [AuthInterceptor] to inject the 118-byte bearer token for vault transactions.
 *
 * ### Hardware Realities & Heap Protection
 * The logging interceptor is explicitly configured with [HttpLoggingInterceptor.Level.HEADERS].
 * Setting this to `Level.BODY` is strictly forbidden because it forces OkHttp to buffer entire request and
 * response bodies into Java heap memory. In a personal cloud client handling multi-gigabyte Content-Addressable
 * Storage (CAS) payloads, `Level.BODY` causes immediate `java.lang.OutOfMemoryError` crashes on Android.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provides the singleton unauthenticated [OkHttpClient].
     *
     * Configured with header-only logging, 15-second connect timeout, and 30-second read/write timeouts.
     *
     * @param authInterceptor Injected dependency available in the Hilt graph.
     * @return The configured base [OkHttpClient].
     */
    @Provides
    @Singleton
    @PublicOkHttpClient
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Provides the singleton authenticated [OkHttpClient].
     *
     * Extends [publicClient] via [OkHttpClient.newBuilder] to share connection pooling, thread executors,
     * and TLS state while appending [AuthInterceptor] for session token management.
     *
     * @param publicClient The base unauthenticated client.
     * @param authInterceptor The bearer token interceptor.
     * @return The authenticated [OkHttpClient].
     */
    @Provides
    @Singleton
    @AuthOkHttpClient
    fun provideAuthOkHttpClient(
        @PublicOkHttpClient publicClient: OkHttpClient,
        authInterceptor: AuthInterceptor
    ): OkHttpClient {
        return publicClient.newBuilder()
            .addInterceptor(authInterceptor)
            .build()
    }
}

