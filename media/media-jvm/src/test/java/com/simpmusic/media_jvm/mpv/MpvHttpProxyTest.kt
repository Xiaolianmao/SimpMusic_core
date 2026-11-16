package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.domain.manager.DataStoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvHttpProxyTest {
    @Test fun httpProxyGetsAbsoluteUrl() {
        val config = ProxyConfiguration("127.0.0.1", 7897, DataStoreManager.ProxyType.PROXY_TYPE_HTTP)
        assertEquals("http://127.0.0.1:7897", mpvHttpProxy(config))
    }

    @Test fun alreadyPrefixedHostDoesNotGetDoubled() {
        val config = ProxyConfiguration("http://127.0.0.1", 7897, DataStoreManager.ProxyType.PROXY_TYPE_HTTP)
        assertEquals("http://127.0.0.1:7897", mpvHttpProxy(config))
    }

    @Test fun proxyOffOrSocksLeavesNativePlayerDefault() {
        assertNull(mpvHttpProxy(null))
        val config = ProxyConfiguration("127.0.0.1", 7897, DataStoreManager.ProxyType.PROXY_TYPE_SOCKS)
        assertNull(mpvHttpProxy(config))
    }

    @Test fun invalidPortsAndCredentialsInHostAreRejected() {
        assertNull(mpvHttpProxy(ProxyConfiguration("127.0.0.1", 0, DataStoreManager.ProxyType.PROXY_TYPE_HTTP)))
        assertNull(mpvHttpProxy(ProxyConfiguration("name:secret@proxy", 7897, DataStoreManager.ProxyType.PROXY_TYPE_HTTP)))
    }
}
