package com.kingzcheung.xime.password

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BitwardenEntryCodecTest {
    private fun key() = BitwardenCrypto.Key(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })

    @Test fun `encrypted writes round trip and use fresh IVs`() {
        val key = key()
        val one = BitwardenCrypto.encryptString("密码 example", key)
        val two = BitwardenCrypto.encryptString("密码 example", key)
        assertNotEquals(one, two)
        assertTrue(one.startsWith("2."))
        assertEquals("密码 example", BitwardenCrypto.decryptString(one, key))
        val parts = one.substringAfter('.').split('|').toMutableList()
        val bytes = Base64.getDecoder().decode(parts[1]).also { it[0] = (it[0].toInt() xor 1).toByte() }
        parts[1] = Base64.getEncoder().encodeToString(bytes)
        assertThrows(SecurityException::class.java) { BitwardenCrypto.decryptString("2.${parts.joinToString("|")}", key) }
        key.clear()
    }

    @Test fun `login edit preserves other encrypted fields attachment keys uri matching and history`() {
        val key = key()
        val originalEntry = BitwardenVaultEntry(title = "Example", values = mapOf("username" to "name", "password" to "old"), uris = listOf("https://example.test"))
        val original = BitwardenEntryCodec.encode(originalEntry, null, key).put("id", "item").put("revisionDate", "2026-10-13T00:00:00Z")
        original.put("fields", JSONArray().put(JSONObject().put("name", "encrypted-custom-field")))
        original.put("attachments", JSONArray().put(JSONObject().put("id", "attachment").put("fileName", "encrypted-filename").put("key", "encrypted-key")))
        original.getJSONObject("login").getJSONArray("uris").getJSONObject(0).put("match", 3)
        original.getJSONObject("login").put("fido2Credentials", JSONArray().put("existing-passkey"))
        val decoded = BitwardenEntryCodec.decode(original, key)!!
        val encoded = BitwardenEntryCodec.encode(decoded.copy(values = decoded.values + ("password" to "new")), original, key)
        assertEquals("new", BitwardenCrypto.decryptString(encoded.getJSONObject("login").getString("password"), key))
        assertEquals("encrypted-custom-field", encoded.getJSONArray("fields").getJSONObject(0).getString("name"))
        assertEquals("encrypted-key", encoded.getJSONObject("attachments2").getJSONObject("attachment").getString("key"))
        assertEquals(3, encoded.getJSONObject("login").getJSONArray("uris").getJSONObject(0).getInt("match"))
        assertEquals("existing-passkey", encoded.getJSONObject("login").getJSONArray("fido2Credentials").getString(0))
        assertEquals("old", BitwardenCrypto.decryptString(encoded.getJSONArray("passwordHistory").getJSONObject(0).getString("password"), key))
        assertEquals(decoded.revision, encoded.getString("lastKnownRevisionDate"))
        assertEquals("old", BitwardenCrypto.decryptString(original.getJSONObject("login").getString("password"), key))
        key.clear()
    }

    @Test fun `all four personal item types round trip including empty passwords`() {
        val key = key()
        for (type in 1..4) {
            val entry = BitwardenVaultEntry(type = type, title = "Item $type", notes = "note", favorite = true,
                values = BitwardenEntryCodec.fields.getValue(type).keys.associateWith { if (it == "password") "" else "value" })
            val encrypted = BitwardenEntryCodec.encode(entry, null, key).put("id", "item")
            val decoded = BitwardenEntryCodec.decode(encrypted, key)!!
            assertEquals(entry.values, decoded.values)
            assertEquals(entry.notes, decoded.notes)
            assertEquals(entry.type, decoded.type)
            assertTrue(decoded.favorite)
        }
        key.clear()
    }

    @Test fun `per item key is retained and used for editing`() {
        val userKey = key()
        val raw = ByteArray(64) { (it + 2).toByte() }
        val itemKey = BitwardenCrypto.Key(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
        val original = BitwardenEntryCodec.encode(BitwardenVaultEntry(title = "Secret", values = mapOf("password" to "password")), null, itemKey)
            .put("key", BitwardenCrypto.encrypt(raw, userKey)).put("id", "item")
        val decoded = BitwardenEntryCodec.decode(original, userKey)!!
        val edited = BitwardenEntryCodec.encode(decoded.copy(title = "Updated"), original, userKey)
        assertEquals(original.getString("key"), edited.getString("key"))
        assertEquals("Updated", BitwardenCrypto.decryptString(edited.getString("name"), itemKey))
        userKey.clear(); itemKey.clear(); raw.fill(0)
    }

    @Test fun `organization deleted and reprompt items cannot be accidentally overwritten`() {
        val key = key()
        val entry = BitwardenVaultEntry(title = "Example")
        val original = BitwardenEntryCodec.encode(entry, null, key).put("id", "item")
        original.put("organizationId", "org")
        assertNull(BitwardenEntryCodec.decode(original, key))
        assertThrows(IllegalArgumentException::class.java) { BitwardenEntryCodec.encode(entry, original, key) }
        original.put("organizationId", JSONObject.NULL).put("deletedDate", "2026-10-13")
        assertNull(BitwardenEntryCodec.decode(original, key))
        original.remove("deletedDate"); original.put("reprompt", 1)
        assertThrows(IllegalArgumentException::class.java) { BitwardenEntryCodec.encode(entry, original, key) }
        key.clear()
    }
}
