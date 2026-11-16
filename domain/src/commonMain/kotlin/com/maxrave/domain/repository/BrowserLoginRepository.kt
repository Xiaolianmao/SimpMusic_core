package com.maxrave.domain.repository

/** Desktop-only source of sign-ins. Discovery never requests an OS storage key. */
interface BrowserLoginRepository {
    suspend fun profiles(target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): List<BrowserProfile>

    /** Reads only the selected service; Spotify imports contain sp_dc alone. */
    suspend fun cookies(profile: BrowserProfile, target: BrowserLoginTarget): BrowserCookies

    /** Explicit user-authorized import from a browser extension on the local computer.
     * The bridge only accepts the selected service's cookies and never sends credentials
     * outside the computer. Other platforms can keep using native browser discovery.
     */
    suspend fun receiveFromExtension(
        target: BrowserLoginTarget,
        onReady: suspend (port: Int, code: String) -> Unit,
    ): BrowserCookies = throw BrowserLoginException(BrowserLoginFailure.EXTENSION_UNAVAILABLE)

    /** Reads only YouTube cookies from the explicitly selected profile. */
    suspend fun youTubeCookies(profile: BrowserProfile): BrowserCookies = cookies(profile, BrowserLoginTarget.YOUTUBE)
}

enum class BrowserLoginTarget { YOUTUBE, SPOTIFY }

data class BrowserProfile(
    val id: String,
    val browser: String,
    val name: String,
    val status: BrowserProfileStatus,
)

/** Cookie presence is a hint, not proof that the service will accept the session. */
enum class BrowserProfileStatus {
    COOKIES_FOUND,
    NO_COOKIES,
    PERMISSION_REQUIRED,
    UNREADABLE,
    UNSUPPORTED,
}

/** Not a data class: generated toString/copy must not expose credential values. */
class BrowserCookies(
    val cookie: String,
    val netscapeCookie: String,
) {
    override fun toString(): String = "BrowserCookies(<redacted>)"
}

enum class BrowserLoginFailure {
    FILE_ACCESS_DENIED,
    ACCESS_DENIED,
    KEY_UNAVAILABLE,
    UNSUPPORTED_ENCRYPTION,
    DATABASE_BUSY,
    INVALID_DATA,
    NO_COOKIES,
    EXTENSION_UNAVAILABLE,
    EXTENSION_TIMEOUT,
}

/** Public errors contain a category only, never native errors, paths, cookie values or keys. */
class BrowserLoginException(
    val reason: BrowserLoginFailure,
) : Exception(reason.name)
