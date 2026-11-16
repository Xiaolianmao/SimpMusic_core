package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserCookies
import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

/**
 * A short-lived loopback-only bridge for an explicitly user-authorized browser extension.
 *
 * No browser encryption is bypassed. The extension uses the browser's documented cookies
 * permission and sends only the selected service's cookies after a user gesture.
 * Neither cookies nor the pairing code are written to logs, URLs or persistent storage here.
 */
internal object BrowserExtensionImport {
    private const val PORT = 48518
    private const val MAX_BODY_BYTES = 128 * 1024
    private const val TIMEOUT_MS = 120_000L
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val originPattern = Regex("^chrome-extension://[a-p]{32}$")

    suspend fun receive(
        target: BrowserLoginTarget,
        onReady: suspend (port: Int, code: String) -> Unit,
        listenPort: Int = PORT,
    ): BrowserCookies = mutex.withLock {
        withContext(Dispatchers.IO) {
            val codeBytes = ByteArray(18).also(random::nextBytes)
            val code = Base64.getUrlEncoder().withoutPadding().encodeToString(codeBytes)
            codeBytes.fill(0)
            val deferred = CompletableDeferred<BrowserCookies>()
            val server = try {
                HttpServer.create(InetSocketAddress("127.0.0.1", listenPort), 2)
            } catch (_: Exception) {
                throw BrowserLoginException(BrowserLoginFailure.EXTENSION_UNAVAILABLE)
            }
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "SimpMusic-browser-import").apply { isDaemon = true }
            }
            try {
                server.executor = executor
                server.createContext("/import") { exchange ->
                    respondToImport(exchange, target, code, deferred)
                }
                server.start()
                onReady(server.address.port, code)
                try {
                    withTimeout(TIMEOUT_MS) { deferred.await() }
                } catch (_: TimeoutCancellationException) {
                    throw BrowserLoginException(BrowserLoginFailure.EXTENSION_TIMEOUT)
                }
            } finally {
                server.stop(0)
                executor.shutdownNow()
            }
        }
    }

    private fun respondToImport(
        exchange: HttpExchange,
        target: BrowserLoginTarget,
        code: String,
        deferred: CompletableDeferred<BrowserCookies>,
    ) {
        try {
            // Chrome extensions with host_permissions may omit Origin on fetch().
            // Other Chromium-based browsers may send chrome-extension://<id>.
            // Reject non-extension Origins (including "null") to prevent websites
            // from importing credentials. A missing Origin is accepted only on
            // loopback, with the 144-bit one-time code still mandatory for POST.
            val origin = exchange.requestHeaders.getFirst("Origin")
            if (!exchange.remoteAddress.address.isLoopbackAddress ||
                (origin != null && !originPattern.matches(origin))
            ) {
                respond(exchange, 403, "Forbidden")
                return
            }
            if (origin != null) {
                exchange.responseHeaders.set("Access-Control-Allow-Origin", origin)
                exchange.responseHeaders.set("Vary", "Origin")
            }
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (exchange.requestMethod == "OPTIONS") {
                if (origin == null) {
                    respond(exchange, 403, "Forbidden")
                    return
                }
                exchange.responseHeaders.set("Access-Control-Allow-Methods", "POST, OPTIONS")
                exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, X-SimpMusic-Code")
                respond(exchange, 204, "")
                return
            }
            if (exchange.requestMethod != "POST" || deferred.isCompleted) {
                respond(exchange, 405, "Not available")
                return
            }
            val supplied = exchange.requestHeaders.getFirst("X-SimpMusic-Code").orEmpty()
            if (!MessageDigest.isEqual(code.toByteArray(Charsets.US_ASCII), supplied.toByteArray(Charsets.US_ASCII))) {
                respond(exchange, 403, "Invalid code")
                return
            }
            if (!exchange.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("application/json")) {
                respond(exchange, 415, "JSON required")
                return
            }
            val body = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1)
            if (body.size > MAX_BODY_BYTES) {
                body.fill(0)
                respond(exchange, 413, "Too large")
                return
            }
            val cookies = try {
                val raw = String(body, Charsets.UTF_8)
                parseCookies(raw, target)
            } catch (_: Exception) {
                respond(exchange, 400, "Invalid cookies")
                return
            } finally {
                body.fill(0)
            }
            // Finish writing the HTTP response before waking receive(), whose
            // finally block stops the local server. Completing first could
            // close the socket before Chromium receives HTTP 200.
            if (deferred.isCompleted) {
                respond(exchange, 409, "Already imported")
            } else {
                respond(exchange, 200, "Imported")
                deferred.complete(cookies)
            }
        } catch (_: Exception) {
            try {
                respond(exchange, 400, "Import failed")
            } catch (_: Exception) {
                // An aborted local connection does not affect other app functionality.
            }
        } finally {
            exchange.close()
        }
    }

    internal fun parseCookies(payload: String, target: BrowserLoginTarget): BrowserCookies {
        val root = Json.parseToJsonElement(payload) as? JsonObject
            ?: throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        if ((root["target"] as? JsonPrimitive)?.contentOrNull != target.name) {
            throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        }
        val items = root["cookies"] as? JsonArray
            ?: throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        if (items.isEmpty() || items.size > 256) {
            throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        }
        val rows = items.map { item ->
            val obj = item as? JsonObject
                ?: throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
            fun string(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull
                ?: throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
            fun bool(key: String): Boolean = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
            fun long(key: String): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: 0
            val host = string("domain")
            val name = string("name")
            val value = string("value")
            val path = string("path")
            if (!BrowserCookieRecord.isTargetHost(host, target) || name.length !in 1..256 ||
                value.length > 8192 || host.length > 253 || path.length > 1024
            ) {
                throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
            }
            BrowserCookieRecord(
                host = host,
                name = name,
                value = value,
                path = path,
                expiresSeconds = long("expires"),
                secure = bool("secure"),
                httpOnly = bool("httpOnly"),
            )
        }
        return BrowserCookieRecord.toCookies(rows, target)
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        if (status == 204) exchange.sendResponseHeaders(status, -1)
        else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
