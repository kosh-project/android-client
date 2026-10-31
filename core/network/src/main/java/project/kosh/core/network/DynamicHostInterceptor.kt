package project.kosh.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp [Interceptor] that dynamically rewrites the destination scheme, host, and port
 * of all outgoing HTTP requests using the active configuration from [ServerConfigurationStore].
 *
 * This allows the client to adapt seamlessly to DHCP IP address changes, mDNS service resolution,
 * and user switching between local network (`192.168.x.x`), emulator bridge (`10.0.2.2`), and HTTPS/HTTP
 * modes without having to recreate the OkHttp connection pool or TLS socket factories.
 *
 * @param serverConfigStore Store providing the active server network endpoint.
 */
@Singleton
class DynamicHostInterceptor @Inject constructor(
    private val serverConfigStore: ServerConfigurationStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val config = serverConfigStore.getServerConfig()

        val targetScheme = if (config.useTls) "https" else "http"
        val rawHost = config.host.ifBlank { ServerConfig.DEFAULT_HOST }
        val targetHost = rawHost.removePrefix("[").removeSuffix("]")
        val targetPort = if (config.port in 1..65535) config.port else ServerConfig.DEFAULT_PORT

        val originalUrl = originalRequest.url
        if (originalUrl.scheme == targetScheme &&
            originalUrl.host.equals(targetHost, ignoreCase = true) &&
            originalUrl.port == targetPort
        ) {
            return chain.proceed(originalRequest)
        }

        val newUrl = originalUrl.newBuilder()
            .scheme(targetScheme)
            .host(targetHost)
            .port(targetPort)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
