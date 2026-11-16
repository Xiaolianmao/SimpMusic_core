package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginTarget
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic test cookies only. No real browser profiles or credentials are accessed. */
class BrowserExtensionImportTest {
    @Test
    fun acceptsChromiumExtensionRequestsWithoutOriginButStillRequiresCode() = runBlocking {
        val imported = BrowserExtensionImport.receive(BrowserLoginTarget.YOUTUBE, onReady = { port, code ->
            val payload = payload("YOUTUBE", ".youtube.com", "SAPISID", "synthetic-value")
            assertEquals(403, postImport(port, payload, "incorrect-code", null))
            assertEquals(403, postImport(port, payload, code, "https://untrusted.example"))
            assertEquals(200, postImport(port, payload, code, null))
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
        val conn = URL("http://127.0.0.1:$port/import").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-SimpMusic-Code", code)
            if (origin != null) conn.setRequestProperty("Origin", origin)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
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
