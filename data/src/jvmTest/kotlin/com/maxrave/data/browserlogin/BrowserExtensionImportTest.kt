package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic test cookies only. No real browser profiles or credentials are accessed. */
class BrowserExtensionImportTest {
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
