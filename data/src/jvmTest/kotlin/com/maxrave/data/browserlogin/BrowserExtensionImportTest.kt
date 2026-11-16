package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginTarget
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic test cookies only. No real browser profiles or credentials are accessed. */
class BrowserExtensionImportTest {
    @Test
    fun acceptsChromiumExtensionRequestsWithoutOrigin() = runBlocking {
        val imported = BrowserExtensionImport.receive(BrowserLoginTarget.YOUTUBE, onReady = { port, code ->
            assertEquals(200, postImport(port, payload("YOUTUBE", ".youtube.com", "SAPISID", "synthetic-value"), code, null))
        }, listenPort = 0)
        assertEquals("SAPISID=synthetic-value", imported.cookie)
    }

    @Test
    fun rejectsWrongCodeWithoutOrigin() = runBlocking {
        val imported = BrowserExtensionImport.receive(BrowserLoginTarget.YOUTUBE, onReady = { port, code ->
            val synthetic = payload("YOUTUBE", ".youtube.com", "SAPISID", "synthetic-value")
            assertEquals(403, postImport(port, synthetic, "incorrect-code", null))
            assertEquals(200, postImport(port, synthetic, code, null))
        }, listenPort = 0)
        assertEquals("SAPISID=synthetic-value", imported.cookie)
    }

    @Test
    fun rejectsUntrustedWebsiteOrigin() = runBlocking {
        val imported = BrowserExtensionImport.receive(BrowserLoginTarget.YOUTUBE, onReady = { port, code ->
            val synthetic = payload("YOUTUBE", ".youtube.com", "SAPISID", "synthetic-value")
            assertEquals(403, postImport(port, synthetic, code, "https://untrusted.example"))
            assertEquals(200, postImport(port, synthetic, code, null))
        }, listenPort = 0)
        assertEquals("SAPISID=synthetic-value", imported.cookie)
    }

    @Test
    fun acceptsValidExtensionOrigin() = runBlocking {
        val imported = BrowserExtensionImport.receive(BrowserLoginTarget.SPOTIFY, onReady = { port, code ->
            val validOrigin = "chrome-extension://" + "a".repeat(32)
            assertEquals(200, postImport(port, payload("SPOTIFY", ".spotify.com", "sp_dc", "synthetic-value"), code, validOrigin))
        }, listenPort = 0)
        assertEquals("sp_dc=synthetic-value", imported.cookie)
    }

    private fun postImport(port: Int, body: String, code: String, origin: String?): Int {
        // HttpURLConnection silently strips a caller-provided Origin header,
        // which would make malicious-Origin checks a false positive.
        // Java HttpClient forwards the exact Origin header we set here.
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/import"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-SimpMusic-Code", code)
            .apply { if (origin != null) header("Origin", origin) }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.discarding())
            .statusCode()
    }
    @Test
    fun youtubeAcceptsOnlySelectedService() {
        val cookies = BrowserExtensionImport.parseCookies(
            payload("YOUTUBE", ".youtube.com", "SAPISID", "synthetic-value"),
            BrowserLoginTarget.YOUTUBE,
        )
        assertEquals("SAPISID=synthetic-value", cookies.cookie)
        assertTrue(cookies.netscapeCookie.contains(".youtube.com"))
        assertFalse(cookies.toString().contains("synthetic-value"))
    }

    @Test
    fun spotifyOnlyKeepsSpdc() {
        val cookies = BrowserExtensionImport.parseCookies(
            payload("SPOTIFY", ".spotify.com", "sp_dc", "synthetic-value"),
            BrowserLoginTarget.SPOTIFY,
        )
        assertEquals("sp_dc=synthetic-value", cookies.cookie)
    }

    @Test
    fun rejectsMismatchedTarget() {
        assertFailsWith<BrowserLoginException> {
            BrowserExtensionImport.parseCookies(
                payload("SPOTIFY", ".spotify.com", "sp_dc", "synthetic-value"),
                BrowserLoginTarget.YOUTUBE,
            )
        }
    }

    @Test
    fun rejectsCookiesFromDifferentDomains() {
        assertFailsWith<BrowserLoginException> {
            BrowserExtensionImport.parseCookies(
                payload("YOUTUBE", ".not-youtube.com", "SAPISID", "synthetic-value"),
                BrowserLoginTarget.YOUTUBE,
            )
        }
    }

    @Test
    fun rejectsMissingAuthenticationCookie() {
        assertFailsWith<BrowserLoginException> {
            BrowserExtensionImport.parseCookies(
                payload("SPOTIFY", ".spotify.com", "unrelated", "synthetic-value"),
                BrowserLoginTarget.SPOTIFY,
            )
        }
    }

    @Test
    fun rejectsCookieHeaderInjection() {
        assertFailsWith<BrowserLoginException> {
            BrowserExtensionImport.parseCookies(
                payload("YOUTUBE", ".youtube.com", "SAPISID", "first;second"),
                BrowserLoginTarget.YOUTUBE,
            )
        }
    }

    private fun payload(target: String, domain: String, name: String, value: String): String = """
        {
          "target": "$target",
          "cookies": [{
            "domain": "$domain", "name": "$name", "value": "$value",
            "path": "/", "expires": 0, "secure": true, "httpOnly": true
          }]
        }
    """.trimIndent()
}
