package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.domain.manager.DataStoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class MpvHttpsRelayTest {
    private val settings = ProxyConfiguration(
        "127.0.0.1", 7897, DataStoreManager.ProxyType.PROXY_TYPE_HTTP
    )

    @Test
    fun routesOnlyRemoteMusicHttpsThroughRandomizedLoopback() {
        val relay = MpvHttpsRelay()
        try {
            val real = "https://rr1---sn-123.googlevideo.com/videoplayback?expire=10000&signature=dummy"
            val route = relay.route(real, settings)
            assertTrue(route.startsWith("http://127.0.0.1:"))
            assertTrue(route.contains("/audio/"))
            assertFalse(route.contains("signature"))
            assertFalse(route.contains("dummy"))
            assertEquals(real, relay.route(real, null))
            assertEquals(real, relay.route(real, settings.copy(type = DataStoreManager.ProxyType.PROXY_TYPE_SOCKS)))
            assertEquals("C:/Music/song.mp3", relay.route("C:/Music/song.mp3", settings))
            assertEquals("https://127.0.0.1/private", relay.route("https://127.0.0.1/private", settings))
            assertEquals("https://example.com/file", relay.route("https://example.com/file", settings))
        } finally {
            relay.close()
        }
    }

    @Test
    fun unregisteredTokensCannotUseLocalRelay() {
        val relay = MpvHttpsRelay()
        try {
            val route = relay.route("https://r1.googlevideo.com/videoplayback", settings)
            val guessed = URI(route.substringBeforeLast('/') + "/not-a-valid-token")
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                HttpRequest.newBuilder(guessed).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(404, response.statusCode())
        } finally {
            relay.close()
        }
    }

    @Test
    fun maliciousRedirectDestinationsAreRejected() {
        assertFalse(MpvHttpsRelay.isAllowedRemoteUrl(URI("https://localhost/admin")))
        assertFalse(MpvHttpsRelay.isAllowedRemoteUrl(URI("https://127.0.0.1/admin")))
        assertFalse(MpvHttpsRelay.isAllowedRemoteUrl(URI("https://googlevideo.com.evil.test/audio")))
        assertFalse(MpvHttpsRelay.isAllowedRemoteUrl(URI("http://rr1.googlevideo.com/audio")))
        assertFalse(MpvHttpsRelay.isAllowedRemoteUrl(URI("https://user:pass@rr1.googlevideo.com/audio")))
        assertTrue(MpvHttpsRelay.isAllowedRemoteUrl(URI("https://r1.googlevideo.com/videoplayback")))
    }
}
