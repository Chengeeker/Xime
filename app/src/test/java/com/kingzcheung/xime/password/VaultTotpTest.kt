package com.kingzcheung.xime.password

import org.junit.Assert.*
import org.junit.Test

class VaultTotpTest {
    @Test fun `RFC 6238 known answers cover all three hash algorithms`() {
        val keys = mapOf("SHA1" to "12345678901234567890", "SHA256" to "12345678901234567890123456789012", "SHA512" to "1234567890123456789012345678901234567890123456789012345678901234")
        val expected = mapOf("SHA1" to "94287082", "SHA256" to "46119246", "SHA512" to "90693936")
        keys.forEach { (algorithm, key) -> assertEquals(expected[algorithm], VaultTotp.generate(key.toByteArray(), algorithm, 8, 30, 59)) }
        assertEquals("65353130", VaultTotp.generate(keys.getValue("SHA1").toByteArray(), "SHA1", 8, 30, 20_000_000_000L))
    }

    @Test fun `raw Base32 and otpauth URI honor period digits and time boundary`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val raw = VaultTotp.code(secret, 59)!!
        assertEquals("287082", raw.value)
        assertEquals(1, raw.remaining)
        val uri = VaultTotp.code("otpauth://totp/Example?secret=$secret&digits=8&period=30&algorithm=SHA1", 59)!!
        assertEquals("94287082", uri.value)
        assertEquals(30, VaultTotp.code(secret, 60)!!.remaining)
    }

    @Test fun `unsupported and invalid secrets never show invented codes`() {
        assertNull(VaultTotp.code("steam://secret", 59))
        assertNull(VaultTotp.code("not-a-base32-secret", 59))
        assertNull(VaultTotp.code("otpauth://hotp/Example?secret=ABC", 59))
        assertNull(VaultTotp.code("otpauth://totp/Example?secret=ABC&digits=99", 59))
        assertNull(VaultTotp.code("", 59))
    }
}
