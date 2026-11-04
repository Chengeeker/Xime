package com.kingzcheung.xime.password

import org.json.JSONArray
import org.json.JSONObject

internal data class BitwardenVaultEntry(
    val id: String = "",
    val type: Int = 1,
    val title: String = "",
    val values: Map<String, String> = emptyMap(),
    val notes: String = "",
    val uris: List<String> = emptyList(),
    val favorite: Boolean = false,
    val revision: String = "",
    val unavailableReason: String = "",
    val folderId: String = "",
    val archived: Boolean = false,
    val deleted: Boolean = false,
)

internal object BitwardenEntryCodec {
    val typeNames = mapOf(1 to "登录", 2 to "安全笔记", 3 to "支付卡", 4 to "身份", 5 to "SSH 密钥", 6 to "银行账户", 7 to "驾照", 8 to "护照")
    val fields = mapOf(
        1 to linkedMapOf("username" to "用户名", "password" to "密码", "totp" to "验证器密钥（TOTP）"),
        2 to emptyMap(),
        3 to linkedMapOf("cardholderName" to "持卡人", "brand" to "品牌（Visa / Mastercard 等）", "number" to "卡号", "expMonth" to "到期月份", "expYear" to "到期年份", "code" to "安全码"),
        4 to linkedMapOf("title" to "称谓", "firstName" to "名", "middleName" to "中间名", "lastName" to "姓", "username" to "用户名", "company" to "公司", "email" to "邮箱", "phone" to "电话", "address1" to "地址", "address2" to "地址第二行", "address3" to "地址第三行", "city" to "城市", "state" to "省/州", "postalCode" to "邮编", "country" to "国家", "ssn" to "身份证件号码", "passportNumber" to "护照号码", "licenseNumber" to "驾照号码"),
    )
    private val sections = mapOf(1 to "login", 2 to "secureNote", 3 to "card", 4 to "identity")

    fun decode(cipher: JSONObject, userKey: BitwardenCrypto.Key): BitwardenVaultEntry? {
        val type = cipher.optInt("type")
        if (!cipher.isNull("organizationId") || !cipher.isNull("deletedDate") ||
            cipher.optBoolean("deleted")) return null
        val key = itemKey(cipher, userKey)
        return try {
            fun plain(value: String) = if (value.isBlank()) "" else BitwardenCrypto.decryptString(value, key)
            val title = plain(cipher.stringOrEmpty("name"))
            val unavailableReason = when {
                type !in sections -> "${typeNames[type] ?: "此类型"}的内容读取与编辑尚未接入"
                cipher.optInt("reprompt", 0) != 0 -> "此条目要求重新验证主密码，请在支持该验证的客户端打开"
                !cipher.isNull("archivedDate") -> "已归档条目，请先在支持归档管理的客户端恢复"
                !cipher.isNull("data") -> "此条目使用尚未支持的数据格式"
                else -> ""
            }
            if (unavailableReason.isNotEmpty()) return BitwardenVaultEntry(
                id = cipher.getString("id"), type = type, title = title, unavailableReason = unavailableReason,
            )
            val section = cipher.optJSONObject(sections.getValue(type)) ?: JSONObject()
            BitwardenVaultEntry(
                id = cipher.getString("id"), type = type,
                title = title, notes = plain(cipher.stringOrEmpty("notes")),
                values = fields.getValue(type).keys.associateWith { plain(section.stringOrEmpty(it)) },
                uris = section.optJSONArray("uris")?.let { array ->
                    (0 until array.length()).map { plain(array.getJSONObject(it).stringOrEmpty("uri")) }
                }.orEmpty(),
                favorite = cipher.optBoolean("favorite"), revision = cipher.stringOrEmpty("revisionDate"),
            )
        } finally { if (key !== userKey) key.clear() }
    }

    fun inventory(syncJson: String, userKey: BitwardenCrypto.Key, includeHidden: Boolean = false): List<BitwardenVaultEntry> {
        val ciphers = JSONObject(syncJson).getJSONArray("ciphers")
        return (0 until ciphers.length()).mapNotNull { index ->
            val cipher = ciphers.optJSONObject(index) ?: return@mapNotNull null
            val deleted = cipher.optBoolean("deleted") || !cipher.isNull("deletedDate")
            if (deleted && !includeHidden) return@mapNotNull null
            val type = cipher.optInt("type")
            val readable = JSONObject(cipher.toString()).also { it.remove("deletedDate"); it.remove("deleted") }
            val decoded = runCatching { decode(readable, userKey) }.getOrNull()
            val entry = decoded ?: BitwardenVaultEntry(
                id = cipher.stringOrEmpty("id", "unavailable-$index"), type = type,
                title = "${typeNames[type] ?: "其他类型"}条目（暂不可读取）",
                unavailableReason = when {
                    !cipher.isNull("organizationId") -> "组织条目的密钥解密尚未支持"
                    type !in sections -> "条目类型暂不支持读取（类型 $type）"
                    else -> "条目解密失败；已保留同步数据，请重新同步或检查账户密钥"
                },
            )
            entry.copy(folderId = cipher.stringOrEmpty("folderId"), archived = !cipher.isNull("archivedDate"),
                deleted = deleted, unavailableReason = if (deleted) "回收站条目；恢复操作尚未接入" else entry.unavailableReason)
        }.sortedWith(compareByDescending<BitwardenVaultEntry> { it.favorite }.thenBy { it.title.lowercase(java.util.Locale.ROOT) })
    }

    fun encode(entry: BitwardenVaultEntry, original: JSONObject?, userKey: BitwardenCrypto.Key): JSONObject {
        require(entry.type in sections && entry.title.isNotBlank() && entry.title.length <= 500)
        require(entry.notes.length <= 10_000 && entry.values.values.all { it.length <= 10_000 })
        require(entry.uris.size <= 100 && entry.uris.all { it.length <= 2_000 })
        if (original != null) {
            require(original.isNull("organizationId") && original.optInt("type") == entry.type)
            require(original.optInt("reprompt", 0) == 0) { "请在官方客户端编辑需要主密码重新验证的条目" }
            require(original.isNull("deletedDate") && original.isNull("archivedDate"))
            require(original.isNull("data")) { "当前条目使用新版数据格式，请使用官方客户端编辑" }
        }
        val cipher = original?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("type", entry.type).put("organizationId", JSONObject.NULL)
            .put("folderId", JSONObject.NULL).put("reprompt", 0)
        val key = itemKey(cipher, userKey)
        try {
            fun encrypted(value: String) = if (value.isEmpty()) JSONObject.NULL else BitwardenCrypto.encryptString(value, key)
            cipher.put("name", encrypted(entry.title)).put("notes", encrypted(entry.notes)).put("favorite", entry.favorite)
            val sectionName = sections.getValue(entry.type)
            val section = cipher.optJSONObject(sectionName)?.let { JSONObject(it.toString()) } ?: JSONObject()
            entry.values.forEach { (field, value) ->
                require(field in fields.getValue(entry.type))
                section.put(field, encrypted(value))
            }
            if (entry.type == 1) {
                val oldLogin = original?.optJSONObject("login")
                val oldPassword = oldLogin?.stringOrEmpty("password").orEmpty()
                val previous = if (oldPassword.isNotEmpty()) BitwardenCrypto.decryptString(oldPassword, key) else ""
                if (original != null && previous != entry.values["password"].orEmpty()) {
                    val history = cipher.optJSONArray("passwordHistory") ?: JSONArray()
                    if (oldPassword.isNotEmpty()) history.put(JSONObject().put("password", oldPassword).put("lastUsedDate", java.time.Instant.now().toString()))
                    cipher.put("passwordHistory", history)
                    section.put("passwordRevisionDate", java.time.Instant.now().toString())
                }
                val oldUris = oldLogin?.optJSONArray("uris") ?: JSONArray()
                val uris = JSONArray()
                entry.uris.filter(String::isNotBlank).distinct().forEach { uri ->
                    val preserved = (0 until oldUris.length()).map { oldUris.getJSONObject(it) }.firstOrNull {
                        BitwardenCrypto.decryptString(it.getString("uri"), key) == uri
                    }
                    uris.put(preserved ?: JSONObject().put("uri", encrypted(uri)).put("match", JSONObject.NULL))
                }
                section.put("uris", uris)
            }
            if (entry.type == 2) section.put("type", 0)
            cipher.put(sectionName, section)
            original?.stringOrEmpty("revisionDate")?.takeIf(String::isNotBlank)?.let { cipher.put("lastKnownRevisionDate", it) }
            // Preserve attachment keys and names when the server accepts the request model.
            original?.optJSONArray("attachments")?.let { attachments ->
                val mapped = JSONObject()
                for (i in 0 until attachments.length()) {
                    val attachment = attachments.getJSONObject(i)
                    mapped.put(attachment.getString("id"), JSONObject().put("fileName", attachment.get("fileName")).put("key", attachment.opt("key") ?: JSONObject.NULL))
                }
                cipher.remove("attachments")
                cipher.put("attachments2", mapped)
            }
            return cipher
        } finally { if (key !== userKey) key.clear() }
    }

    private fun itemKey(cipher: JSONObject, userKey: BitwardenCrypto.Key): BitwardenCrypto.Key =
        cipher.stringOrEmpty("key").takeIf(String::isNotBlank)?.let { BitwardenCrypto.decryptUserKey(it, userKey) } ?: userKey
}
