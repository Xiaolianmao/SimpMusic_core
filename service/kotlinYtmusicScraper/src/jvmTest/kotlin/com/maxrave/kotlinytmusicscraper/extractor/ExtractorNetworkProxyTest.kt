package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtractorNetworkProxyTest {
    @Test
    fun correctHttpAndSocksProxyConfiguration() {
        assertNull(extractorNetworkProxy(true, null, 0))
        val http = assertNotNull(extractorNetworkProxy(true, "127.0.0.1", 7897))
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(7897, (http.address() as InetSocketAddress).port)

        val socks = assertNotNull(extractorNetworkProxy(false, "127.0.0.1", 7897))
        assertEquals(Proxy.Type.SOCKS, socks.type())
    }

    @Test
    fun audioOnlyTracksAreAcceptedWithoutRequiringVideo() {
        assertTrue(listOf(ITAG.AUDIO.first() to "https://example.com/audio").hasRequiredItags())
        assertFalse(listOf(ITAG.VIDEO.first() to "https://example.com/video").hasRequiredItags())
    }

    @Test
    fun healthCheckActuallyUsesConfiguredHttpProxyInsteadOfDirectNetwork() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { proxySocket ->
            proxySocket.soTimeout = 5000
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxySocket.localPort))
            var requestLine = ""
            val worker = Thread {
                proxySocket.accept().use { connection ->
                    connection.soTimeout = 5000
                    val reader = connection.getInputStream().bufferedReader()
                    requestLine = reader.readLine()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val headers = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                    connection.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }

            try {
                configureStreamHealthCheckProxy(proxy)
                val formats = listOf(
                    ITAG.VIDEO.first() to "http://example.invalid/video",
                    ITAG.AUDIO.first() to "http://example.invalid/stream",
                )
                assertTrue(formats.headCheckRandomStream(), "The request must use the test proxy")
                worker.join(3000)
                assertTrue(requestLine.startsWith("HEAD http://example.invalid/stream"))
            } finally {
                configureStreamHealthCheckProxy(null)
            }
        }
    }
}
