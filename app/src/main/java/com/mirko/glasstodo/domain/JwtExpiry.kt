package com.mirko.glasstodo.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Reads the `exp` claim out of a JWT without verifying it — we only need to know whether OUR OWN
 * access token is about to expire, not to trust it.
 *
 * Why hand-rolled: `enableLifecycleCallbacks = false` means nothing refreshes the token when the
 * process is resurrected by a widget tap, and Postgrest silently falls back to the anon key rather
 * than refreshing for us. Checking expiry ourselves lets us refresh only when we must, instead of
 * hammering the refresh endpoint on every tap (which would race token rotation).
 */
private val json = Json { ignoreUnknownKeys = true }

fun jwtExpiresAtMillis(token: String): Long? {
    val parts = token.split(".")
    if (parts.size != 3) return null
    return runCatching {
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.content?.toLong()?.times(1000)
    }.getOrNull()
}

/**
 * True when [token] exists and still has more than [skewMillis] of life left. An unparseable token
 * is treated as stale: refreshing an already-good token is cheap, using a dead one loses writes.
 */
fun isTokenFresh(token: String?, nowMillis: Long, skewMillis: Long = 60_000L): Boolean {
    if (token.isNullOrBlank()) return false
    val expiresAt = jwtExpiresAtMillis(token) ?: return false
    return expiresAt - nowMillis > skewMillis
}
