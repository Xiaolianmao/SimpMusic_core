package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Native libmpv/FFmpeg in the Windows bundle does not reliably proxy HTTPS.
 * Keep metadata, image and stream transport on the application's configured
 * HTTP proxy while mpv reads plain HTTP only from this randomized loopback URL.
 *
 * No streaming URL, signed token, cookie or proxy credentials are logged.
 * No generic forward proxy is exposed: only allowlisted music CDN URLs can be
 * registered by the application, and requests must carry a random local token.
 */
internal class MpvHttpsRelay {
    private data class Entry(
        val url: URI,
        val proxy: ProxyConfiguration,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()
    @Volatile private var server: HttpServer? = null

    @Synchronized
    fun route(url: String, proxy: ProxyConfiguration?): String {
        if (proxy?.type != DataStoreManager.ProxyType.PROXY_TYPE_HTTP) return url
        val target = runCatching { URI(url) }.getOrNull() ?: return url
        if (!isAllowedRemoteUrl(target)) return url
        val proxyHost = proxy.host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (proxyHost.isBlank() || '/' in proxyHost || '@' in proxyHost || proxy.port !in 1..65535) return url

        val localServer = server ?: run {
            val created = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
            } catch (e: Exception) {
                Logger.w("MpvHttpsRelay", "Cannot open loopback streaming listener: " + e.javaClass.simpleName)
                return url
            }
            created.createContext("/audio/") { handle(it) }
            created.executor = Executors.newFixedThreadPool(6) { task ->
                Thread(task, "SimpMusic-Audio-Relay").apply { isDaemon = true }
            }
            created.start()
            server = created
            created
        }

        if (entries.size > 150) {
            val now = System.currentTimeMillis()
            entries.entries.removeIf { it.value.expiresAt < now }
            if (entries.size > 150) entries.clear()
        }
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        entries[token] = Entry(target, proxy, System.currentTimeMillis() + 3 * 60 * 60 * 1000L)
        return "http://127.0.0.1:" + localServer.address.port + "/audio/" + token
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!exchange.remoteAddress.address.isLoopbackAddress ||
                exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD"
            ) {
                respondEmpty(exchange, 403)
                return
            }
            val path = exchange.requestURI.path
            val token = if (path.startsWith("/audio/")) path.removePrefix("/audio/") else ""
            val entry = entries[token]
            if (token.isBlank() || entry == null || System.currentTimeMillis() > entry.expiresAt) {
                respondEmpty(exchange, 404)
                return
            }
            val configuredHost = entry.proxy.host.trim()
                .removePrefix("http://").removePrefix("https://").trimEnd('/')
            val client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(configuredHost, entry.proxy.port)))
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
            val method = exchange.requestMethod
            val requestedRange = exchange.requestHeaders.getFirst("Range")
            val requestedIfRange = exchange.requestHeaders.getFirst("If-Range")
            var location = entry.url

            repeat(6) {
                if (!isAllowedRemoteUrl(location)) {
                    respondEmpty(exchange, 502)
                    return
                }
                val request = HttpRequest.newBuilder(location)
                    .header("Accept-Encoding", "identity")
                    .apply {
                        if (!requestedRange.isNullOrBlank()) header("Range", requestedRange)
                        if (!requestedIfRange.isNullOrBlank()) header("If-Range", requestedIfRange)
                    }
                    .method(
                        method,
                        if (method == "HEAD") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.noBody(),
                    )
                    .build()
                val upstream = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val code = upstream.statusCode()

                if (code in listOf(301, 302, 303, 307, 308)) {
                    upstream.body().close()
                    val redirect = upstream.headers().firstValue("Location").orElse(null)
                    if (redirect.isNullOrBlank()) {
                        respondEmpty(exchange, 502)
                        return
                    }
                    location = location.resolve(redirect)
                    return@repeat
                }

                upstream.body().use { stream ->
                    if (code !in listOf(200, 206, 416)) {
                        Logger.w("MpvHttpsRelay", "Music CDN returned HTTP " + code)
                        respondEmpty(exchange, 502)
                        return
                    }
                    val headers = exchange.responseHeaders
                    for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
                        upstream.headers().firstValue(name).ifPresent { headers.set(name, it) }
                    }
                    headers.set("Cache-Control", "no-store")
                    val length = upstream.headers().firstValueAsLong("Content-Length")
                    if (method == "HEAD" || code == 416) {
                        if (length.isPresent) headers.set("Content-Length", length.asLong.toString())
                        exchange.sendResponseHeaders(code, -1)
                    } else {
                        // 0 requests chunked encoding; never buffer a whole song in RAM.
                        exchange.sendResponseHeaders(code, if (length.isPresent && length.asLong > 0) length.asLong else 0)
                        exchange.responseBody.use { output ->
                            stream.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                }
                return
            }
            respondEmpty(exchange, 502)
        } catch (e: Exception) {
            Logger.w("MpvHttpsRelay", "Streaming request failed (" + e.javaClass.simpleName + ")")
            runCatching { respondEmpty(exchange, 502) }
        } finally {
            runCatching { exchange.close() }
        }
    }

    @Synchronized
    fun close() {
        server?.stop(0)
        server = null
        entries.clear()
    }

    private fun respondEmpty(exchange: HttpExchange, code: Int) {
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, -1)
    }

    companion object {
        /**
         * Reject arbitrary localhost/private-network fetches even if an upstream
         * service maliciously returns a redirect to a local address.
         */
        internal fun isAllowedRemoteUrl(uri: URI): Boolean {
            if (uri.scheme != "https" || uri.userInfo != null) return false
            val host = uri.host?.lowercase() ?: return false
            return listOf(
                "googlevideo.com", "youtube.com", "youtube-nocookie.com",
                "googleusercontent.com", "sndcdn.com", "soundcloud.com",
                "scdn.co", "spotify.com", "akamaized.net",
                "tidal.com", "qobuz.com",
            ).any { host == it || host.endsWith("." + it) }
        }
    }
}
