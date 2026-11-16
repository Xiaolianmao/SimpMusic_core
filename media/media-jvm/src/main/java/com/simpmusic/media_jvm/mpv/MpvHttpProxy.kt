package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.domain.manager.DataStoreManager

/**
 * mpv's http-proxy option must receive an absolute http URL.
 * Native SOCKS is not supported by this option; avoid setting an invalid value.
 * This config is consulted each time a new player handle is created.
 */
internal fun mpvHttpProxy(config: ProxyConfiguration?): String? {
    if (config?.type != DataStoreManager.ProxyType.PROXY_TYPE_HTTP) return null
    val host = config.host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (host.isBlank() || '/' in host || '@' in host || config.port !in 1..65535) return null
    return "http://$host:${config.port}"
}
