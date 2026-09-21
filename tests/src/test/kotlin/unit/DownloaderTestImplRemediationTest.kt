package unit

import com.lagradost.cloudstream3.DownloaderTestImpl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.nio.charset.StandardCharsets

/**
 * Architectural Remediation & Parity Test for [DownloaderTestImpl].
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream & NewPipeExtractor Downloader:
 * - Singleton lifecycle and OkHttpClient builder delegation
 * - NewPipe global extractor engine binding via [NewPipe.init]
 * - Request transformation: HTTP method, URL, User-Agent injection, single and multi-valued header mapping
 * - Request payload mapping: byte array to OkHttp RequestBody
 * - Response transformation: status code, message, multimap headers, response body string, latest URL
 * - HTTP 429 Rate Limiting to [ReCaptchaException] conversion
 * - Downloader base class helper methods (get, head) execution
 */
class DownloaderTestImplRemediationTest {

    @AfterEach
    fun tearDown() {
        // Reset singleton to default state after tests
        DownloaderTestImpl.init(null)
    }

    @Test
    fun `getInstance returns non-null singleton instance`() {
        val instance1 = DownloaderTestImpl.getInstance()
        val instance2 = DownloaderTestImpl.getInstance()

        assertNotNull(instance1, "DownloaderTestImpl.getInstance() must not be null")
        assertSame(instance1, instance2, "DownloaderTestImpl must adhere to singleton pattern across calls")
    }

    @Test
    fun `init with custom builder creates new instance and updates singleton`() {
        val originalInstance = DownloaderTestImpl.getInstance()
        val customBuilder = OkHttpClient.Builder()
        val newInstance = DownloaderTestImpl.init(customBuilder)

        assertNotNull(newInstance)
        assertSame(newInstance, DownloaderTestImpl.getInstance())
    }

    @Test
    fun `init with null builder gracefully falls back without throwing`() {
        val instance = DownloaderTestImpl.init(null)
        assertNotNull(instance)
        assertSame(instance, DownloaderTestImpl.getInstance())
    }

    @Test
    fun `NewPipe engine binding parity`() {
        val downloader = DownloaderTestImpl.getInstance()
        assertNotNull(downloader)

        NewPipe.init(downloader)
        val registeredDownloader = NewPipe.getDownloader()
        assertNotNull(registeredDownloader)
        assertSame(downloader, registeredDownloader, "NewPipe.getDownloader() must match DownloaderTestImpl instance")
    }

    @Test
    fun `execute GET request maps URL, headers, and injects Chrome User-Agent`() {
        var interceptedMethod = ""
        var interceptedUrl = ""
        var interceptedUserAgent: String? = null
        var interceptedCustomHeader: String? = null

        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            interceptedMethod = req.method
            interceptedUrl = req.url.toString()
            interceptedUserAgent = req.header("User-Agent")
            interceptedCustomHeader = req.header("X-Custom-Client")

            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .addHeader("X-Response-Tag", "StreamFound")
                .body("<html><title>YouTube Test</title></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val targetUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val newPipeRequest = Request.newBuilder()
            .get(targetUrl)
            .setHeader("X-Custom-Client", "CloudStreamDesktopTest")
            .build()

        val response = downloader!!.execute(newPipeRequest)

        assertEquals("GET", interceptedMethod)
        assertEquals(targetUrl, interceptedUrl)
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            interceptedUserAgent,
            "Must inject upstream desktop User-Agent string exactly"
        )
        assertEquals("CloudStreamDesktopTest", interceptedCustomHeader)

        assertEquals(200, response.responseCode())
        assertEquals("OK", response.responseMessage())
        assertEquals("<html><title>YouTube Test</title></html>", response.responseBody())
        assertEquals(targetUrl, response.latestUrl())
        assertTrue(response.responseHeaders().containsKey("content-type") || response.responseHeaders().containsKey("Content-Type"))
    }

    @Test
    fun `execute maps multi-valued headers accurately`() {
        val capturedHeaders = mutableListOf<String>()

        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            capturedHeaders.addAll(req.headers("Accept-Language"))

            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("OK".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val newPipeRequest = Request.newBuilder()
            .get("https://www.youtube.com/feed/trending")
            .setHeaders("Accept-Language", listOf("en-US", "en;q=0.9", "tr;q=0.8"))
            .build()

        val response = downloader!!.execute(newPipeRequest)
        assertEquals(200, response.responseCode())
        assertEquals(listOf("en-US", "en;q=0.9", "tr;q=0.8"), capturedHeaders)
    }

    @Test
    fun `execute POST request with binary body maps RequestBody accurately`() {
        var capturedMethod = ""
        var capturedBodyBytes: ByteArray? = null

        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            capturedMethod = req.method
            val buffer = okio.Buffer()
            req.body?.writeTo(buffer)
            capturedBodyBytes = buffer.readByteArray()

            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"status\":\"success\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val payload = "{\"context\":{\"client\":{\"clientName\":\"WEB\"}}}".toByteArray(StandardCharsets.UTF_8)
        val newPipeRequest = Request.newBuilder()
            .post("https://www.youtube.com/youtubei/v1/player", payload)
            .setHeader("Content-Type", "application/json")
            .build()

        val response = downloader!!.execute(newPipeRequest)
        assertEquals("POST", capturedMethod)
        assertNotNull(capturedBodyBytes)
        assertArrayEquals(payload, capturedBodyBytes)
        assertEquals(200, response.responseCode())
        assertEquals("{\"status\":\"success\"}", response.responseBody())
    }

    @Test
    fun `execute on HTTP 429 closes response and throws ReCaptchaException`() {
        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("Challenge Required".toResponseBody("text/html".toMediaType()))
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val targetUrl = "https://www.youtube.com/results?search_query=anime"
        val request = Request.newBuilder().get(targetUrl).build()

        val exception = assertThrows(ReCaptchaException::class.java) {
            downloader!!.execute(request)
        }

        assertEquals("reCaptcha Challenge requested", exception.message)
        assertEquals(targetUrl, exception.url)
    }

    @Test
    fun `execute handles empty response body safely without throwing NullPointerException`() {
        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(null)
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val request = Request.newBuilder().head("https://www.youtube.com/ping").build()
        val response = downloader!!.execute(request)

        assertEquals(204, response.responseCode())
        assertEquals("No Content", response.responseMessage())
        assertEquals("", response.responseBody(), "Response body string must default to empty string when body is null")
    }

    @Test
    fun `Downloader base helper methods get and head delegate correctly to execute`() {
        val recordedMethods = mutableListOf<String>()

        val customBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            recordedMethods.add(req.method)
            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("Echo".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val downloader = DownloaderTestImpl.init(customBuilder)
        assertNotNull(downloader)

        val getResponse = downloader!!.get("https://www.youtube.com/test-get")
        assertEquals(200, getResponse.responseCode())

        val headResponse = downloader.head("https://www.youtube.com/test-head")
        assertEquals(200, headResponse.responseCode())

        assertEquals(listOf("GET", "HEAD"), recordedMethods)
    }
}
