package com.kingzcheung.xime.password

import java.util.HexFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitwardenCryptoTest {
    @Test
    fun pbkdf2MatchesHmacSha256KnownAnswer() {
        val actual = BitwardenCrypto.pbkdf2("password".toByteArray(), "salt".toByteArray(), 1)
        try {
            assertEquals(
                "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
                HexFormat.of().formatHex(actual),
            )
        } finally {
            actual.fill(0)
        }
    }

    @Test
    fun hostMatcherRequiresExactHostOrSubdomainBoundary() {
        assertTrue(BitwardenHostMatcher.matches("login.example.com", "example.com"))
        assertTrue(BitwardenHostMatcher.matches("example.com", "example.com"))
        assertTrue(BitwardenHostMatcher.matches("", "com", "com"))
        assertFalse(BitwardenHostMatcher.matches("evil-example.com", "example.com"))
        assertFalse(BitwardenHostMatcher.matches("example.com", "com"))
    }
}
