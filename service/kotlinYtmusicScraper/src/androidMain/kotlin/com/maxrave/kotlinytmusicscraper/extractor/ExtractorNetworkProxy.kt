package com.maxrave.kotlinytmusicscraper.extractor

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * A separate OkHttp transport is used by both YouTube extraction libraries.
 * The Ktor proxy alone cannot route their watch pages or CDN HEAD requests.
 */
internal fun extractorNetworkProxy(isHttp: Boolean, host: String?, port: Int): Proxy? {
    if (host.isNullOrBlank()) return null
    val normalized = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    require(normalized.isNotBlank() && '/' !in normalized && '@' !in normalized)
    require(port in 1..65535)
    val type = if (isHttp) Proxy.Type.HTTP else Proxy.Type.SOCKS
    return Proxy(type, InetSocketAddress.createUnresolved(normalized, port))
}
