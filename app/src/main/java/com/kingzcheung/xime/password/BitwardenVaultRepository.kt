package com.kingzcheung.xime.password

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.kingzcheung.xime.password.BitwardenCrypto.Key
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class BitwardenLoginItem(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val uris: List<String>,
)

data class BitwardenVaultStatus(
    val email: String = "",
    val serverUrl: String = "https://vault.bitwarden.com",
    val itemCount: Int = 0,
    val loginCount: Int = 0,
    val autofillCount: Int = 0,
    val lastSyncAt: Long = 0,
    val configured: Boolean = false,
    val unlocked: Boolean = false,
)

internal object BitwardenHostMatcher {
    fun matches(requested: String, saved: String, packageName: String? = null): Boolean {
        if (saved.isBlank()) return false
        if (saved == packageName) return true
        if (requested.isBlank()) return false
        return saved.count { it == '.' } >= 1 &&
            (requested == saved || requested.endsWith(".$saved"))
    }
}

sealed interface BitwardenOperation {
    data class Success(val itemCount: Int) : BitwardenOperation
    data object TwoFactorRequired : BitwardenOperation
    data class Failure(val message: String) : BitwardenOperation
}

object BitwardenVaultRepository {
    private const val PREFS = "xime_bitwarden_vault"
    private const val SYNC_FILE = "bitwarden-sync.enc"
    private const val CIPHER_ALIAS = "xime_bitwarden_vault_data"
    private const val MAX_SYNC_BYTES = 32 * 1024 * 1024
    private const val KDF_PBKDF2 = 0
    private const val KDF_ARGON2ID = 1

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var sessionKey: Key? = null
    @Volatile private var sessionItems: List<BitwardenLoginItem> = emptyList()
    @Volatile private var sessionSync: String? = null
    @Volatile private var sessionGeneration = 0L
    private val operationMutex = Mutex()

    private suspend fun <T> vaultOperation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        operationMutex.withLock { block() }
    }

    fun status(context: Context): BitwardenVaultStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return BitwardenVaultStatus(
            email = prefs.getString("email", "").orEmpty(),
            serverUrl = prefs.getString("server", "https://vault.bitwarden.com").orEmpty(),
            itemCount = prefs.getInt("item_count", 0),
            loginCount = prefs.getInt("login_count", 0),
            autofillCount = if (sessionKey != null) sessionItems.size else prefs.getInt("autofill_count", 0),
            lastSyncAt = prefs.getLong("last_sync", 0),
            configured = prefs.contains("email") && prefs.contains("key_cipher"),
            unlocked = sessionKey != null,
        )
    }

    suspend fun connect(
        context: Context,
        email: String,
        password: String,
        serverUrl: String,
        twoFactorCode: String = "",
    ): BitwardenOperation = vaultOperation {
        val generation = sessionGeneration
        if (email.isBlank() || password.isEmpty()) return@vaultOperation BitwardenOperation.Failure("请输入 Bitwarden 邮箱和主密码")
        var masterBytes: ByteArray? = null
        var masterKey: Key? = null
        var vaultKey: Key? = null
        try {
            val normalizedEmail = email.trim().lowercase(Locale.ENGLISH)
            val endpoints = endpoints(serverUrl)
            val prelogin = prelogin(endpoints.identity, normalizedEmail)
            val kdf = prelogin.optInt("kdf", KDF_PBKDF2)
            val iterations = prelogin.optInt("kdfIterations", 600_000)
            val memory = prelogin.optInt("kdfMemory", 64)
            val parallelism = prelogin.optInt("kdfParallelism", 4)
            if (kdf != KDF_PBKDF2 && kdf != KDF_ARGON2ID) return@vaultOperation BitwardenOperation.Failure("当前账号使用了不支持的 Bitwarden 密钥派生方式")

            masterBytes = BitwardenCrypto.deriveMasterKey(password, normalizedEmail, kdf, iterations, memory, parallelism)
            masterKey = BitwardenCrypto.stretch(masterBytes)
            val authResponse = authenticate(context, endpoints, normalizedEmail, BitwardenCrypto.derivePasswordHash(masterBytes, password), twoFactorCode)
            if (authResponse.second && twoFactorCode.isBlank()) return@vaultOperation BitwardenOperation.TwoFactorRequired
            val token = authResponse.first ?: return@vaultOperation BitwardenOperation.Failure("Bitwarden 登录失败，请检查账号、主密码或验证码")
            val encryptedKey = token.stringOrEmpty("Key", token.stringOrEmpty("key", ""))
            if (encryptedKey.isBlank()) return@vaultOperation BitwardenOperation.Failure("Bitwarden 登录响应缺少保险库密钥")
            vaultKey = BitwardenCrypto.decryptUserKey(encryptedKey, masterKey)
            val syncJson = requestSync(endpoints, token.stringOrEmpty("access_token"))
            val items = decodeItems(syncJson, vaultKey)
            synchronized(this) {
                check(generation == sessionGeneration) { "Vault was locked" }
                persist(context, normalizedEmail, endpoints.server, kdf, iterations, memory, parallelism, encryptedKey, token, syncJson, items.size)
                rememberSession(context, vaultKey)
                replaceSession(vaultKey, items, syncJson)
            }
            vaultKey = null
            BitwardenOperation.Success(countVaultItems(syncJson))
        } catch (error: BitwardenHttpException) {
            BitwardenOperation.Failure("Bitwarden ${error.stage}请求失败（HTTP ${error.statusCode}），请检查服务器地址和服务版本。")
        } catch (_: UnknownHostException) {
            BitwardenOperation.Failure("无法解析 Bitwarden 服务器地址，请检查网络和服务器 URL。")
        } catch (_: SocketTimeoutException) {
            BitwardenOperation.Failure("连接 Bitwarden 服务器超时，请检查网络或服务器状态。")
        } catch (_: SSLException) {
            BitwardenOperation.Failure("Bitwarden 服务器 HTTPS 证书校验失败。")
        } catch (_: IOException) {
            BitwardenOperation.Failure("Bitwarden 网络请求失败，请检查网络和服务器地址。")
        } catch (_: Exception) {
            BitwardenOperation.Failure("无法连接或解锁 Bitwarden。请检查网络、服务器地址、主密码和二步验证码。")
        } finally {
            masterBytes?.fill(0)
            masterKey?.clear()
            vaultKey?.clear()
        }
    }

    suspend fun unlock(context: Context, password: String): BitwardenOperation = vaultOperation {
        val generation = sessionGeneration
        if (password.isEmpty()) return@vaultOperation BitwardenOperation.Failure("请输入 Bitwarden 主密码")
        var masterBytes: ByteArray? = null
        var masterKey: Key? = null
        var vaultKey: Key? = null
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val email = prefs.getString("email", null) ?: return@vaultOperation BitwardenOperation.Failure("尚未连接 Bitwarden 账户")
            masterBytes = BitwardenCrypto.deriveMasterKey(
                password,
                email,
                prefs.getInt("kdf", KDF_PBKDF2),
                prefs.getInt("iterations", 600_000),
                prefs.getInt("memory", 64),
                prefs.getInt("parallelism", 4),
            )
            masterKey = BitwardenCrypto.stretch(masterBytes)
            vaultKey = BitwardenCrypto.decryptUserKey(prefs.getString("key_cipher", "") ?: "", masterKey)
            val syncJson = readSync(context)
            val items = decodeItems(syncJson, vaultKey)
            synchronized(this) {
                check(generation == sessionGeneration) { "Vault was locked" }
                rememberSession(context, vaultKey)
                saveCounts(context, syncJson, items.size)
                replaceSession(vaultKey, items, syncJson)
            }
            vaultKey = null
            BitwardenOperation.Success(countVaultItems(syncJson))
        } catch (_: Exception) {
            BitwardenOperation.Failure("主密码不正确，或本地保险库无法解密")
        } finally {
            masterBytes?.fill(0)
            masterKey?.clear()
            vaultKey?.clear()
        }
    }

    suspend fun sync(context: Context): BitwardenOperation = vaultOperation {
        val originalKey = sessionKey ?: return@vaultOperation BitwardenOperation.Failure("请先解锁保险库")
        val key = synchronized(this) { Key(originalKey.encryption.copyOf(), originalKey.mac.copyOf()) }
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val server = prefs.getString("server", null) ?: return@vaultOperation BitwardenOperation.Failure("Bitwarden 账户未配置")
            val endpoints = endpoints(server)
            var accessToken = decryptPref(prefs.getString("access_token", null))
            var refreshToken = decryptPref(prefs.getString("refresh_token", null))
            if (accessToken.isNullOrBlank() || prefs.getLong("expires_at", 0) <= System.currentTimeMillis() + 60_000) {
                if (refreshToken.isNullOrBlank()) return@vaultOperation BitwardenOperation.Failure("登录已过期，请重新登录 Bitwarden")
                val refreshed = refresh(endpoints, refreshToken)
                    ?: return@vaultOperation BitwardenOperation.Failure("Bitwarden 登录已过期，请重新连接账户")
                accessToken = refreshed.stringOrEmpty("access_token")
                refreshToken = refreshed.stringOrEmpty("refresh_token", refreshToken)
                saveTokens(context, refreshed, accessToken, refreshToken)
            }
            val syncJson = requestSync(endpoints, accessToken)
            val items = decodeItems(syncJson, key)
            synchronized(this) {
                check(sessionKey === originalKey) { "Vault was locked" }
                writeSync(context, syncJson)
                prefs.edit()
                    .putLong("last_sync", System.currentTimeMillis())
                    .putInt("item_count", countVaultItems(syncJson))
                    .apply()
                saveCounts(context, syncJson, items.size)
                replaceSession(originalKey, items, syncJson)
            }
            BitwardenOperation.Success(countVaultItems(syncJson))
        } catch (_: Exception) {
            BitwardenOperation.Failure("Bitwarden 同步失败。请检查网络或重新登录账户。")
        } finally { key.clear() }
    }

    @Synchronized
    fun lock(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("remembered_key").commit()
        sessionGeneration++
        sessionKey?.clear()
        sessionKey = null
        sessionItems = emptyList()
        sessionSync = null
    }

    @Synchronized
    fun disconnect(context: Context) {
        lock(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AtomicFile(File(context.filesDir, SYNC_FILE)).delete()
    }

    internal fun matchingItems(context: Context, host: String, packageName: String? = null): List<BitwardenLoginItem> {
        if (sessionKey == null) return emptyList()
        val normalizedHost = normalizeHost(host)
        val normalizedPackage = packageName?.lowercase(Locale.ROOT)
        return sessionItems.filter { item ->
            item.uris.any { saved ->
                val savedHost = normalizeHost(saved)
                BitwardenHostMatcher.matches(normalizedHost, savedHost, normalizedPackage)
            }
        }
    }

    internal fun item(context: Context, id: String): BitwardenLoginItem? =
        if (sessionKey == null) null else sessionItems.firstOrNull { it.id == id }

    internal fun hasSession(): Boolean = sessionKey != null

    internal fun unlockedItems(): List<BitwardenLoginItem> =
        if (sessionKey == null) emptyList() else sessionItems.toList()

    suspend fun resumeSession(context: Context): Boolean = vaultOperation {
        if (sessionKey != null) return@vaultOperation true
        val generation = sessionGeneration
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = decryptPref(prefs.getString("remembered_key", null)) ?: return@vaultOperation false
        var key: Key? = null
        var raw: ByteArray? = null
        try {
            raw = java.util.Base64.getDecoder().decode(encoded)
            require(raw.size == 64)
            key = Key(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
            val syncJson = readSync(context)
            val items = decodeItems(syncJson, key)
            synchronized(this) {
                if (generation != sessionGeneration) return@vaultOperation false
                replaceSession(key, items, syncJson)
                saveCounts(context, syncJson, items.size)
                key = null
            }
            true
        } catch (_: Exception) {
            prefs.edit().remove("remembered_key").apply()
            false
        } finally { raw?.fill(0); key?.clear() }
    }

    private fun rememberSession(context: Context, key: Key) {
        val raw = key.encryption + key.mac
        try {
            val encrypted = encryptPref(java.util.Base64.getEncoder().encodeToString(raw))
            check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("remembered_key", encrypted).commit())
        } finally { raw.fill(0) }
    }

    internal fun countVaultItems(syncJson: String, type: Int? = null): Int {
        val ciphers = JSONObject(syncJson).getJSONArray("ciphers")
        return (0 until ciphers.length()).count { index ->
            val cipher = ciphers.optJSONObject(index)
            cipher != null && !cipher.optBoolean("deleted") && cipher.isNull("deletedDate") &&
                (type == null || cipher.optInt("type") == type)
        }
    }

    private fun saveCounts(context: Context, syncJson: String, autofillCount: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("item_count", countVaultItems(syncJson)).putInt("login_count", countVaultItems(syncJson, 1))
            .putInt("autofill_count", autofillCount).apply()
    }

    internal suspend fun vaultEntries(): List<BitwardenVaultEntry> = vaultOperation {
        val snapshot = synchronized(this) {
            val original = sessionKey ?: return@vaultOperation emptyList()
            Triple(original, Key(original.encryption.copyOf(), original.mac.copyOf()), sessionSync ?: return@vaultOperation emptyList())
        }
        try {
            val entries = BitwardenEntryCodec.inventory(snapshot.third, snapshot.second, includeHidden = true)
            if (sessionKey === snapshot.first) entries else emptyList()
        } finally { snapshot.second.clear() }
    }

    internal suspend fun vaultFolders(): Map<String, String> = vaultOperation {
        val snapshot = synchronized(this) {
            val key = sessionKey ?: return@vaultOperation emptyMap()
            Pair(Key(key.encryption.copyOf(), key.mac.copyOf()), sessionSync ?: return@vaultOperation emptyMap())
        }
        try {
            val folders = JSONObject(snapshot.second).optJSONArray("folders") ?: return@vaultOperation emptyMap()
            (0 until folders.length()).mapNotNull { index ->
                val folder = folders.optJSONObject(index) ?: return@mapNotNull null
                val id = folder.stringOrEmpty("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                id to runCatching { BitwardenCrypto.decryptString(folder.stringOrEmpty("name"), snapshot.first) }.getOrDefault("无法解密的文件夹")
            }.toMap()
        } finally { snapshot.first.clear() }
    }

    internal suspend fun saveEntry(context: Context, entry: BitwardenVaultEntry): BitwardenOperation =
        mutateEntry(context, entry, trash = false)

    internal suspend fun trashEntry(context: Context, entry: BitwardenVaultEntry): BitwardenOperation =
        mutateEntry(context, entry, trash = true)

    private suspend fun mutateEntry(context: Context, entry: BitwardenVaultEntry, trash: Boolean): BitwardenOperation = vaultOperation {
        val originalKey = sessionKey ?: return@vaultOperation BitwardenOperation.Failure("请先解锁保险库")
        val key = synchronized(this) { Key(originalKey.encryption.copyOf(), originalKey.mac.copyOf()) }
        var applied = false
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val endpoints = endpoints(prefs.getString("server", "").orEmpty())
            val token = accessToken(context, endpoints)
            val profile = authorizedJson(endpoints.api + "/accounts/profile", token)
            if (profile.stringOrEmpty("key").isBlank() || profile.stringOrEmpty("key") != prefs.getString("key_cipher", "")) {
                return@vaultOperation BitwardenOperation.Failure("账户加密密钥已变化，请重新连接账户后再保存。编辑内容尚未上传。")
            }
            val original = if (entry.id.isEmpty()) null else {
                UUID.fromString(entry.id)
                val remote = authorizedJson(endpoints.api + "/ciphers/${entry.id}", token)
                if (entry.revision.isBlank() || remote.stringOrEmpty("revisionDate") != entry.revision) {
                    return@vaultOperation BitwardenOperation.Failure("条目已被其他客户端修改，请返回列表同步后重新编辑。当前编辑内容尚未保存。")
                }
                require(remote.isNull("organizationId") && remote.optInt("reprompt", 0) == 0)
                remote
            }
            val body = if (trash) {
                require(original != null)
                "{}"
            } else BitwardenEntryCodec.encode(entry, original, key).put("encryptedFor", profile.getString("id")).toString()
            check(sessionKey === originalKey) { "Vault was locked" }
            val path = if (entry.id.isEmpty()) "/ciphers" else "/ciphers/${entry.id}${if (trash) "/delete" else ""}"
            authorizedJson(endpoints.api + path, token, if (entry.id.isEmpty()) "POST" else "PUT", body)
            applied = true
            val syncJson = requestSync(endpoints, token)
            val items = decodeItems(syncJson, key)
            synchronized(this) {
                if (sessionKey !== originalKey) return@synchronized
                writeSync(context, syncJson)
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                saveCounts(context, syncJson, items.size)
                replaceSession(originalKey, items, syncJson)
            }
            BitwardenOperation.Success(countVaultItems(syncJson))
        } catch (error: BitwardenHttpException) {
            BitwardenOperation.Failure(if (applied) "云端已保存，但本机刷新失败，请同步后检查，不要重复新增。" else when (error.statusCode) {
                401 -> "Bitwarden 登录已过期，请重新连接账户。"
                409 -> "云端条目已发生变化，请同步后重新编辑。"
                403 -> "当前账户无权修改此条目。"
                else -> "Bitwarden 保存失败（HTTP ${error.statusCode}），编辑内容已保留。"
            })
        } catch (_: IOException) {
            BitwardenOperation.Failure(if (applied) "云端已保存，但本机刷新失败，请同步后检查。" else "连接中断，保存结果可能尚未确认。请先同步检查，避免重复新增。")
        } catch (_: Exception) {
            BitwardenOperation.Failure(if (applied) "云端已保存，请重新同步检查本机状态。" else "无法保存此条目：保险库已锁定、数据格式不受支持或字段过长。编辑内容已保留。")
        } finally { key.clear() }
    }

    private fun accessToken(context: Context, endpoints: Endpoints): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = decryptPref(prefs.getString("access_token", null))
        if (!token.isNullOrBlank() && prefs.getLong("expires_at", 0) > System.currentTimeMillis() + 60_000) return token
        val refreshToken = decryptPref(prefs.getString("refresh_token", null)) ?: throw BitwardenHttpException("登录", 401)
        val refreshed = refresh(endpoints, refreshToken) ?: throw BitwardenHttpException("登录", 401)
        val access = refreshed.getString("access_token")
        saveTokens(context, refreshed, access, refreshed.stringOrEmpty("refresh_token", refreshToken))
        return access
    }

    private fun authorizedJson(url: String, token: String, method: String = "GET", json: String? = null): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store")
            .header("Bitwarden-Client-Name", "web").header("Bitwarden-Client-Version", "2026.10.13")
            .method(method, json?.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BitwardenHttpException("条目管理", response.code)
            val body = response.body?.string().orEmpty()
            require(body.length <= MAX_SYNC_BYTES)
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    private fun saveTokens(context: Context, token: JSONObject, accessToken: String, refreshToken: String?) {
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sessionKey == null || !prefs.contains("email")) return
            prefs.edit()
                .putString("access_token", encryptPref(accessToken))
                .putString("refresh_token", encryptPref(refreshToken.orEmpty()))
                .putLong("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
                .apply()
        }
    }

    private fun authenticate(context: Context, endpoints: Endpoints, email: String, hash: String, otp: String): Pair<JSONObject?, Boolean> {
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", email)
            .add("password", hash)
            .add("scope", "api offline_access")
            .add("client_id", "desktop")
            .add("deviceIdentifier", deviceId(context))
            .add("deviceType", "8")
            .add("deviceName", "Xime Android")
            .apply {
                if (otp.isNotBlank()) {
                    add("twoFactorToken", otp.trim())
                    add("twoFactorProvider", "0")
                    add("twoFactorRemember", "0")
                }
            }
            .build()
        val response = post(endpoints.identity + "/connect/token", form, email)
        if (response.first == 200) return JSONObject(response.second) to false
        val challenge = response.second.contains("TwoFactorProviders", ignoreCase = true) ||
            response.second.contains("twoFactorProviders", ignoreCase = true)
        return null to challenge
    }

    private fun refresh(endpoints: Endpoints, refreshToken: String): JSONObject? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", "desktop")
            .build()
        val response = post(endpoints.identity + "/connect/token", body)
        return if (response.first in 200..299) runCatching { JSONObject(response.second) }.getOrNull() else null
    }

    private fun requestSync(endpoints: Endpoints, accessToken: String): String {
        require(accessToken.isNotBlank())
        val request = Request.Builder()
            .url(endpoints.api + "/sync?excludeDomains=true")
            .header("Authorization", "Bearer $accessToken")
            .header("Bitwarden-Client-Name", "web")
            .header("Bitwarden-Client-Version", "2026.10.13")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BitwardenHttpException("保险库同步", response.code)
            val text = response.body?.string() ?: throw IllegalStateException("Empty sync response")
            require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_SYNC_BYTES) { "Sync response is too large" }
            val json = JSONObject(text)
            require(json.optJSONArray("ciphers") != null) { "Invalid sync response" }
            return text
        }
    }

    private fun prelogin(identityEndpoint: String, email: String): JSONObject {
        val request = preloginRequest(identityEndpoint, email)
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BitwardenHttpException("身份预登录", response.code)
            return JSONObject(response.body?.string() ?: throw IllegalStateException("Empty prelogin response"))
        }
    }

    internal fun preloginRequest(identityEndpoint: String, email: String): Request =
        Request.Builder()
            .url("${identityEndpoint.trimEnd('/')}/accounts/prelogin")
            .header("Cache-Control", "no-store")
            .post(JSONObject().put("email", email).toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun post(url: String, body: FormBody, email: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(url)
            .header("Cache-Control", "no-store")
            .header("Bitwarden-Client-Name", "web")
            .header("Bitwarden-Client-Version", "2026.10.13")
            .apply { if (email != null) header("Auth-Email", Base64.encodeToString(email.toByteArray(), Base64.NO_WRAP)) }
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return response.code to text
        }
    }

    internal fun decodeItems(syncJson: String, userKey: Key): List<BitwardenLoginItem> {
        val ciphers = JSONObject(syncJson).optJSONArray("ciphers") ?: throw IllegalArgumentException("Missing ciphers")
        require(ciphers.length() <= 100_000) { "Too many vault items" }
        val items = ArrayList<BitwardenLoginItem>(ciphers.length())
        for (index in 0 until ciphers.length()) {
            val cipher = ciphers.optJSONObject(index) ?: continue
            if (cipher.optInt("type", -1) != 1 || cipher.optBoolean("deleted", false) ||
                !cipher.isNull("deletedDate") || !cipher.isNull("archivedDate") ||
                cipher.optInt("reprompt", 0) != 0 || !cipher.isNull("organizationId")) continue
            val login = cipher.optJSONObject("login") ?: continue
            var itemKey: Key? = null
            try {
                val encryptedItemKey = cipher.stringOrEmpty("key", "")
                itemKey = if (encryptedItemKey.isNotBlank()) BitwardenCrypto.decryptUserKey(encryptedItemKey, userKey) else userKey
                val password = decryptField(login.stringOrEmpty("password", ""), itemKey)
                if (password.isEmpty()) continue
                val username = decryptField(login.stringOrEmpty("username", ""), itemKey)
                val title = decryptField(cipher.stringOrEmpty("name", ""), itemKey).ifBlank { username.ifBlank { "登录" } }
                val uris = mutableListOf<String>()
                login.optJSONArray("uris")?.let { values ->
                    for (uriIndex in 0 until values.length()) {
                        val value = values.optJSONObject(uriIndex)?.stringOrEmpty("uri", "").orEmpty()
                        if (value.isNotBlank()) uris += decryptField(value, itemKey)
                    }
                }
                if (uris.isEmpty()) login.stringOrEmpty("uri", "").takeIf(String::isNotBlank)?.let { uris += decryptField(it, itemKey) }
                items += BitwardenLoginItem(cipher.stringOrEmpty("id", index.toString()), title, username, password, uris.distinct())
            } catch (_: Exception) {
                // Invalid, organization-only, or unsupported cipher entries are omitted from Autofill.
            } finally {
                if (itemKey !== userKey) itemKey?.clear()
            }
        }
        return items
    }

    private fun decryptField(value: String, key: Key): String =
        if (value.isEmpty() || !BitwardenCrypto.looksEncrypted(value)) value else BitwardenCrypto.decryptString(value, key)

    private fun writeSync(context: Context, syncJson: String) {
        val plain = syncJson.toByteArray(StandardCharsets.UTF_8)
        require(plain.size <= MAX_SYNC_BYTES) { "Sync response is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        val encrypted = cipher.doFinal(plain)
        plain.fill(0)
        val blob = cipher.iv + encrypted
        encrypted.fill(0)
        val file = AtomicFile(File(context.filesDir, SYNC_FILE))
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(blob)
            file.finishWrite(output)
        } catch (error: Exception) {
            if (output != null) file.failWrite(output)
            throw error
        } finally {
            blob.fill(0)
        }
    }

    private fun readSync(context: Context): String {
        val file = AtomicFile(File(context.filesDir, SYNC_FILE))
        val blob = file.openRead().use { it.readBytes() }
        try {
            require(blob.size in 29..(MAX_SYNC_BYTES + 64)) { "Invalid local vault" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            val plain = cipher.doFinal(blob, 12, blob.size - 12)
            return try {
                String(plain, StandardCharsets.UTF_8)
            } finally {
                plain.fill(0)
            }
        } finally {
            blob.fill(0)
        }
    }

    private fun localKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(CIPHER_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(CIPHER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    private fun encryptPref(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decryptPref(value: String?): String? = runCatching {
        val blob = Base64.decode(value ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        String(cipher.doFinal(blob, 12, blob.size - 12), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun persist(
        context: Context,
        email: String,
        server: String,
        kdf: Int,
        iterations: Int,
        memory: Int,
        parallelism: Int,
        keyCipher: String,
        token: JSONObject,
        syncJson: String,
        itemCount: Int,
    ) {
        writeSync(context, syncJson)
        val accessToken = token.stringOrEmpty("access_token")
        val refreshToken = token.stringOrEmpty("refresh_token")
        val expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("email", email)
            .putString("server", server)
            .putInt("kdf", kdf)
            .putInt("iterations", iterations)
            .putInt("memory", memory)
            .putInt("parallelism", parallelism)
            .putString("key_cipher", keyCipher)
            .putString("access_token", encryptPref(accessToken))
            .putString("refresh_token", encryptPref(refreshToken))
            .putLong("expires_at", expiresAt)
            .putLong("last_sync", System.currentTimeMillis())
            .putInt("item_count", countVaultItems(syncJson))
            .putInt("login_count", countVaultItems(syncJson, 1))
            .putInt("autofill_count", itemCount)
            .commit()
            .also { check(it) { "Could not save Bitwarden account state" } }
    }

    private fun endpoints(raw: String): Endpoints {
        val uri = URI(raw.trim().trimEnd('/'))
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "Bitwarden server must use HTTPS" }
        val host = uri.host.lowercase(Locale.ROOT)
        if (host.endsWith(".bitwarden.com") || host == "bitwarden.com") {
            return Endpoints("https://identity.bitwarden.com", "https://api.bitwarden.com", "https://vault.bitwarden.com")
        }
        if (host.endsWith(".bitwarden.eu") || host == "bitwarden.eu") {
            return Endpoints("https://identity.bitwarden.eu", "https://api.bitwarden.eu", "https://vault.bitwarden.eu")
        }
        val origin = "https://${uri.rawAuthority}"
        val path = uri.path.trimEnd('/')
        val identity = when {
            path.endsWith("/identity") -> origin + path
            path.endsWith("/api") -> origin + path.removeSuffix("/api") + "/identity"
            else -> origin + path + "/identity"
        }
        val api = when {
            path.endsWith("/api") -> origin + path
            path.endsWith("/identity") -> origin + path.removeSuffix("/identity") + "/api"
            else -> origin + path + "/api"
        }
        return Endpoints(identity.trimEnd('/'), api.trimEnd('/'), origin + path)
    }

    private fun normalizeHost(value: String): String = runCatching {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        val uri = URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
        uri.host?.removePrefix("www.").orEmpty()
    }.getOrDefault("")

    private fun replaceSession(key: Key, items: List<BitwardenLoginItem>, syncJson: String) {
        synchronized(this) {
            if (sessionKey !== key) sessionKey?.clear()
            sessionKey = key
            sessionItems = items
            sessionSync = syncJson
        }
    }

    private data class Endpoints(val identity: String, val api: String, val server: String)

    private class BitwardenHttpException(val stage: String, val statusCode: Int) : IOException()
}
