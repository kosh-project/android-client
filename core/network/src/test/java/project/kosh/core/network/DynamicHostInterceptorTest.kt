package project.kosh.core.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DynamicHostInterceptorTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: ServerConfigurationStore
    private lateinit var interceptor: DynamicHostInterceptor

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        store = ServerConfigurationStore(fakePrefs)
        interceptor = DynamicHostInterceptor(store)
    }

    @Test
    fun `intercept rewrites scheme host and port to active server configuration`() {
        store.updateServer(
            host = "192.168.1.200",
            port = 6969,
            useTls = true
        )

        val originalRequest = Request.Builder()
            .url("http://placeholder/api/auth/challenge?action=login")
            .addHeader("X-File-Name", "test.jpg")
            .post("test payload".toRequestBody("text/plain".toMediaType()))
            .build()

        val chain = mockk<Interceptor.Chain>()
        val capturedRequest = slot<Request>()
        val mockResponse = mockk<Response>()

        every { chain.request() } returns originalRequest
        every { chain.proceed(capture(capturedRequest)) } returns mockResponse

        val response = interceptor.intercept(chain)
        assertEquals(mockResponse, response)

        val rewritten = capturedRequest.captured
        assertEquals("https://192.168.1.200:6969/api/auth/challenge?action=login", rewritten.url.toString())
        assertEquals("POST", rewritten.method)
        assertEquals("test.jpg", rewritten.header("X-File-Name"))
    }

    @Test
    fun `intercept dynamically switches between HTTP and HTTPS when store updates`() {
        store.updateServer(
            host = "localhost",
            port = 23342,
            useTls = false
        )

        val request = Request.Builder()
            .url("https://initial-host/api/v1/assets")
            .get()
            .build()

        val chain = mockk<Interceptor.Chain>()
        val capturedRequest = slot<Request>()
        val mockResponse = mockk<Response>()

        every { chain.request() } returns request
        every { chain.proceed(capture(capturedRequest)) } returns mockResponse

        interceptor.intercept(chain)
        assertEquals("http://localhost:23342/api/v1/assets", capturedRequest.captured.url.toString())

        // Update to HTTPS on new host
        store.updateServer(
            host = "kosh-nas.local",
            port = 8443,
            useTls = true
        )

        interceptor.intercept(chain)
        assertEquals("https://kosh-nas.local:8443/api/v1/assets", capturedRequest.captured.url.toString())
    }

    @Test
    fun `intercept handles IPv6 addresses without throwing invalid host error`() {
        store.updateServer(
            host = "[::1]",
            port = 6969,
            useTls = true
        )

        val request = Request.Builder()
            .url("http://old-host:80/api/auth/challenge")
            .get()
            .build()

        val chain = mockk<Interceptor.Chain>()
        val capturedRequest = slot<Request>()
        val mockResponse = mockk<Response>()

        every { chain.request() } returns request
        every { chain.proceed(capture(capturedRequest)) } returns mockResponse

        interceptor.intercept(chain)
        assertEquals("https://[::1]:6969/api/auth/challenge", capturedRequest.captured.url.toString())
    }

    @Test
    fun `intercept proceeds with original request if already targeting active scheme host and port`() {
        store.updateServer(
            host = "192.168.1.50",
            port = 6969,
            useTls = true
        )

        val matchingRequest = Request.Builder()
            .url("https://192.168.1.50:6969/api/v1/test")
            .get()
            .build()

        val chain = mockk<Interceptor.Chain>()
        val mockResponse = mockk<Response>()

        every { chain.request() } returns matchingRequest
        every { chain.proceed(matchingRequest) } returns mockResponse

        val response = interceptor.intercept(chain)
        assertEquals(mockResponse, response)
    }
}
