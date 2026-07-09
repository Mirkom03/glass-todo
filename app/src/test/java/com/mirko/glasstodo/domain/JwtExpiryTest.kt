package com.mirko.glasstodo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtExpiryTest {

    private val enc: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun token(payloadJson: String): String {
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature-we-never-verify"
    }

    private fun tokenExpiringAt(epochSeconds: Long) = token("""{"sub":"u1","exp":$epochSeconds}""")

    @Test fun readsTheExpClaim() {
        assertEquals(1_752_037_080_000L, jwtExpiresAtMillis(tokenExpiringAt(1_752_037_080L)))
    }

    @Test fun garbageTokensYieldNull() {
        assertNull(jwtExpiresAtMillis("not-a-jwt"))
        assertNull(jwtExpiresAtMillis("only.two"))
        assertNull(jwtExpiresAtMillis("aaa.###.ccc"))              // middle segment is not base64
        assertNull(jwtExpiresAtMillis(token("""{"sub":"u1"}""")))  // no exp claim at all
        assertNull(jwtExpiresAtMillis(token("""{"exp":"manana"}""")))  // exp is not a number
    }

    @Test fun freshWhenComfortablyInTheFuture() {
        val now = 1_000_000_000_000L
        assertTrue(isTokenFresh(tokenExpiringAt(now / 1000 + 3600), now))
    }

    @Test fun staleInsideTheSkewWindow() {
        val now = 1_000_000_000_000L
        // expires in 30 s, skew is 60 s -> treat as stale so we refresh before writing
        assertFalse(isTokenFresh(tokenExpiringAt(now / 1000 + 30), now))
    }

    @Test fun staleWhenExpiredNullOrUnparseable() {
        val now = 1_000_000_000_000L
        assertFalse(isTokenFresh(tokenExpiringAt(now / 1000 - 10), now))
        assertFalse(isTokenFresh(null, now))
        assertFalse(isTokenFresh("", now))
        assertFalse(isTokenFresh("garbage", now))     // unparseable -> refresh, never assume good
    }
}
