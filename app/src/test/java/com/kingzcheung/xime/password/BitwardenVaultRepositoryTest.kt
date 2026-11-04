package com.kingzcheung.xime.password

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BitwardenVaultRepositoryTest {
    @Test
    fun `prelogin uses Bitwarden POST JSON endpoint`() {
        val request = BitwardenVaultRepository.preloginRequest("https://identity.example", "person@example.test")

        assertEquals("POST", request.method)
        assertEquals("https://identity.example/accounts/prelogin", request.url.toString())
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        assertEquals("person@example.test", JSONObject(request.bodyText()).getString("email"))
    }

    private fun Request.bodyText(): String = Buffer().also { body ->
        assertNotNull(this.body)
        this.body!!.writeTo(body)
    }.readUtf8()
}
