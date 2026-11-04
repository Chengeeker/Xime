package com.kingzcheung.xime.password

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitwardenAndroidJsonTest {
    @Test fun nullableCipherFieldsUseAndroidRuntimeSemantics() {
        val key = BitwardenCrypto.Key(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
        try {
            val cipher = BitwardenEntryCodec.encode(
                BitwardenVaultEntry(title = "Example", values = mapOf("password" to "test-only-password")), null, key,
            ).put("id", "test-only-id").put("key", JSONObject.NULL).put("notes", JSONObject.NULL)
            cipher.getJSONObject("login").put("username", JSONObject.NULL).put("totp", JSONObject.NULL)
            assertEquals("null", cipher.optString("key", ""))
            assertEquals("", cipher.stringOrEmpty("key"))
            val entry = BitwardenEntryCodec.decode(cipher, key)!!
            assertEquals("Example", entry.title)
            assertEquals("", entry.notes)
            assertEquals("", entry.values["totp"])
            assertEquals("test-only-password", entry.values["password"])
        } finally { key.clear() }
    }
}
