package com.kingzcheung.xime.password

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VaultBrowseTest {
    @Test fun `normal categories exclude archived and deleted items`() {
        val login = BitwardenVaultEntry(id = "login", favorite = true, folderId = "folder", values = mapOf("totp" to "secret"))
        val entries = listOf(login, login.copy(id = "archived", archived = true), login.copy(id = "trash", deleted = true), BitwardenVaultEntry(id = "card", type = 3))
        assertEquals(2, entries.count(VaultBrowseFilter("All")::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("Login", VaultBrowseFilter.Kind.Type, type = 1)::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("Folder", VaultBrowseFilter.Kind.Folder, folderId = "folder")::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("Favorites", VaultBrowseFilter.Kind.Favorites)::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("TOTP", VaultBrowseFilter.Kind.Totp)::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("Archive", VaultBrowseFilter.Kind.Archived)::accepts))
        assertEquals(1, entries.count(VaultBrowseFilter("Trash", VaultBrowseFilter.Kind.Trash)::accepts))
    }

    @Test fun `hidden inventory preserves folder association and keeps trash read only`() {
        val key = BitwardenCrypto.Key(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
        try {
            val original = BitwardenEntryCodec.encode(BitwardenVaultEntry(title = "Name"), null, key).put("id", "active").put("folderId", "folder")
            val deleted = JSONObject(original.toString()).put("id", "deleted").put("deletedDate", "2026-10-13")
            val sync = JSONObject().put("ciphers", JSONArray(listOf(original, deleted))).toString()
            val entries = BitwardenEntryCodec.inventory(sync, key, includeHidden = true)
            assertEquals(2, entries.size)
            assertTrue(entries.all { it.folderId == "folder" })
            val trash = entries.single { it.deleted }
            assertEquals("Name", trash.title)
            assertTrue(trash.unavailableReason.isNotEmpty())
            assertEquals(1, BitwardenEntryCodec.inventory(sync, key).size)
        } finally { key.clear() }
    }

    @Test fun `password generator stays within requested range`() {
        assertEquals(20, generateVaultPassword(20).length)
        assertThrows(IllegalArgumentException::class.java) { generateVaultPassword(1) }
        assertNotEquals(generateVaultPassword(64), generateVaultPassword(64))
    }
}
