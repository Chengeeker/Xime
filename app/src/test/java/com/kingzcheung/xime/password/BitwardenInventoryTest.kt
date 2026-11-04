package com.kingzcheung.xime.password

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BitwardenInventoryTest {
    private fun key() = BitwardenCrypto.Key(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })

    @Test fun `null encrypted fields never reach Android string coercion`() {
        val cipher = object : JSONObject() {
            override fun optString(name: String, fallback: String): String {
                check(!isNull(name)) { "Android would return the string null here" }
                return super.optString(name, fallback)
            }
        }
        cipher.put("key", JSONObject.NULL).put("name", "null")
        assertEquals("", cipher.stringOrEmpty("key"))
        assertEquals("fallback", cipher.stringOrEmpty("missing", "fallback"))
        assertEquals("null", cipher.stringOrEmpty("name"))
    }

    @Test fun `109 logins with 53 item keys and 56 null keys are all readable`() {
        val userKey = key()
        val raw = ByteArray(64) { (it + 7).toByte() }
        val itemKey = BitwardenCrypto.Key(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
        try {
            val ciphers = JSONArray()
            repeat(109) { index ->
                val entry = BitwardenVaultEntry(title = "Login $index", values = mapOf("password" to "secret-$index"), uris = listOf("https://example.test"))
                val cipher = BitwardenEntryCodec.encode(entry, null, if (index < 53) itemKey else userKey).put("id", "item-$index")
                    .put("key", if (index < 53) BitwardenCrypto.encrypt(raw, userKey) else JSONObject.NULL)
                cipher.getJSONObject("login").put("username", JSONObject.NULL).put("totp", JSONObject.NULL)
                ciphers.put(cipher)
            }
            val sync = JSONObject().put("ciphers", ciphers).toString()
            val inventory = BitwardenEntryCodec.inventory(sync, userKey)
            assertEquals(109, inventory.size)
            assertEquals(0, inventory.count { it.unavailableReason.isNotEmpty() })
            assertEquals(109, BitwardenVaultRepository.countVaultItems(sync, 1))
            assertEquals(109, BitwardenVaultRepository.decodeItems(sync, userKey).size)
        } finally { userKey.clear(); itemKey.clear(); raw.fill(0) }
    }

    @Test fun `vault count includes empty-password logins and reports unreadable items`() {
        val key = key()
        try {
            val emptyPassword = BitwardenEntryCodec.encode(BitwardenVaultEntry(title = "Passkey only", values = mapOf("username" to "user")), null, key).put("id", "empty")
            val corrupt = JSONObject(emptyPassword.toString()).put("id", "corrupt").put("name", "2.invalid")
            val organization = JSONObject(emptyPassword.toString()).put("id", "org").put("organizationId", "org-id")
            val archived = JSONObject(emptyPassword.toString()).put("id", "archived").put("archivedDate", "2026-10-13")
            val deleted = JSONObject(emptyPassword.toString()).put("id", "deleted").put("deletedDate", "2026-10-13")
            val note = BitwardenEntryCodec.encode(BitwardenVaultEntry(type = 2, title = "Note"), null, key).put("id", "note")
            val sync = JSONObject().put("ciphers", JSONArray(listOf(emptyPassword, corrupt, organization, archived, deleted, note))).toString()
            val inventory = BitwardenEntryCodec.inventory(sync, key)
            assertEquals(5, inventory.size)
            assertEquals(5, BitwardenVaultRepository.countVaultItems(sync))
            assertEquals(4, BitwardenVaultRepository.countVaultItems(sync, 1))
            assertEquals(3, inventory.count { it.unavailableReason.isNotEmpty() })
            assertTrue(inventory.any { it.id == "empty" && it.unavailableReason.isEmpty() })
            assertEquals(0, BitwardenVaultRepository.decodeItems(sync, key).size)
        } finally { key.clear() }
    }
}
