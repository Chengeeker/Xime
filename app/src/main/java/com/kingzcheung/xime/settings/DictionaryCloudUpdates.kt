package com.kingzcheung.xime.settings

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.ProxySelector
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

data class DictionaryCloudState(
    val subscriptions: List<String>, val automatic: Boolean, val frequencyHours: Long,
    val catalog: List<CloudDictionary>, val lastCheck: Long, val error: String,
)

object DictionaryCloudUpdates {
    private const val WORK = "xime-dictionary-updates"
    private val mutex = Mutex()
    private fun prefs(context: Context) = context.getSharedPreferences("xime_dictionary_updates", Context.MODE_PRIVATE)
    private fun subscriptions(context: Context): List<String> = JSONArray(prefs(context).getString("subscriptions", "[\"https://xime-dictionaries.pages.dev/manifest.json\"]")).let { array ->
        (0 until array.length()).map(array::getString)
    }
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }

    internal fun fetch(url: String, limit: Int): ByteArray {
        var current = DictionaryCatalog.httpsUrl(url).toString()
        repeat(4) { hop ->
            val uri = DictionaryCatalog.httpsUrl(current)
            require(Dns.SYSTEM.lookup(uri.host).all(DictionaryCatalog::publicAddress)) { "词库域名指向私有网络，已拒绝下载" }
            val proxyHosts = ProxySelector.getDefault()?.select(uri).orEmpty().mapNotNull { (it.address() as? InetSocketAddress)?.hostString }.toSet()
            val requestClient = client.newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (hostname !in proxyHosts && addresses.any { !DictionaryCatalog.publicAddress(it) }) {
                        throw UnknownHostException("词库域名指向私有网络，已拒绝连接")
                    }
                }
            }).build()
            requestClient.newCall(Request.Builder().url(current).header("User-Agent", "Xime-Dictionaries-Android/1.0").get().build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    require(hop < 3) { "词库下载重定向次数过多" }
                    val location = response.header("Location") ?: error("词库服务器重定向缺少地址")
                    current = DictionaryCatalog.httpsUrl(java.net.URI(current).resolve(location).toString()).toString()
                } else {
                    require(response.isSuccessful) { "词库服务器返回 HTTP ${response.code}；本次更新已停止" }
                    require(response.body.contentLength() <= limit) { "服务器文件超过下载限制" }
                    return response.body.byteStream().use { DictionaryPackFormat.readBounded(it, limit) }
                }
            }
        }
        error("词库下载失败")
    }

    suspend fun state(context: Context): DictionaryCloudState = withContext(Dispatchers.IO) {
        val urls = subscriptions(context)
        val catalog = urls.flatMap { url ->
            val file = cacheFile(context, url)
            if (file.isFile) runCatching { DictionaryCatalog.parse(file.readBytes(), url) }.getOrDefault(emptyList()) else emptyList()
        }
        val prefs = prefs(context)
        DictionaryCloudState(urls, prefs.getBoolean("automatic", false), prefs.getLong("hours", 24), catalog,
            prefs.getLong("lastCheck", 0), prefs.getString("error", "").orEmpty())
    }

    suspend fun subscribe(context: Context, rawUrl: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val url = DictionaryCatalog.httpsUrl(rawUrl.trim()).toString()
            val list = (subscriptions(context) + url).distinct()
            require(list.size <= 8) { "最多订阅 8 个词库目录" }
            val bytes = fetch(url, DictionaryCatalog.MAX_MANIFEST)
            DictionaryCatalog.parse(bytes, url)
            cacheFile(context, url).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
            prefs(context).edit().putString("subscriptions", JSONArray(list).toString()).apply()
            schedule(context)
        }
    }

    suspend fun unsubscribe(context: Context, url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs(context).edit().putString("subscriptions", JSONArray(subscriptions(context).filterNot { it == url }).toString()).apply()
            cacheFile(context, url).delete()
            schedule(context)
        }
    }

    fun configure(context: Context, automatic: Boolean, hours: Long) {
        require(hours in setOf(6L, 24L, 168L))
        prefs(context).edit().putBoolean("automatic", automatic).putLong("hours", hours).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        val prefs = prefs(context)
        val work = WorkManager.getInstance(context)
        if (!prefs.getBoolean("automatic", false) || subscriptions(context).isEmpty()) { work.cancelUniqueWork(WORK); return }
        val hours = prefs.getLong("hours", 24).takeIf { it in setOf(6L, 24L, 168L) } ?: 24L
        val request = PeriodicWorkRequestBuilder<DictionaryUpdateWorker>(hours, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun check(context: Context, installUpdates: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val urls = subscriptions(context)
                require(urls.isNotEmpty()) { "请先添加 HTTPS 词库目录订阅" }
                var changes = 0
                var downloaded = 0
                var downloadBytes = 0L
                for (url in urls) {
                    val bytes = fetch(url, DictionaryCatalog.MAX_MANIFEST)
                    val catalog = DictionaryCatalog.parse(bytes, url)
                    cacheFile(context, url).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
                    val snapshot = DictionaryPackManager.snapshot(context)
                    val installed = snapshot.pending ?: snapshot.installed
                    for (entry in catalog) {
                        val current = installed.find { it.id == entry.id && it.sourceUrl == url } ?: continue
                        if (DictionaryCatalog.needsUpdate(entry, current)) {
                            changes++
                            if (installUpdates && downloaded < 4 && downloadBytes + entry.size <= 24L * 1024 * 1024) {
                                downloadAndStage(context, entry)
                                downloaded++
                                downloadBytes += entry.size
                            }
                        }
                    }
                }
                if (installUpdates && changes > 0) DictionaryPackManager.applyPending(context)
                prefs(context).edit().putLong("lastCheck", System.currentTimeMillis()).remove("error").apply()
                changes
            } catch (failure: Exception) {
                prefs(context).edit().putString("error", failure.message ?: "网络检查失败").apply()
                throw failure
            }
        }
    }

    suspend fun install(context: Context, entry: CloudDictionary): String = withContext(Dispatchers.IO) {
        mutex.withLock { downloadAndStage(context, entry); DictionaryPackManager.applyPending(context) }
    }

    suspend fun previewDirect(url: String, hash: String): DictionaryPackPreview = withContext(Dispatchers.IO) {
        require(hash.matches(Regex("[a-fA-F0-9]{64}"))) { "从 ZIP URL 导入必须提供 SHA-256" }
        val bytes = fetch(url, DictionaryPackFormat.MAX_DOWNLOAD)
        require(DictionaryPackFormat.sha256(bytes).equals(hash, true)) { "ZIP SHA-256 不匹配，已拒绝导入" }
        DictionaryPackFormat.parse(bytes, "url-${hash.take(16)}.zip")
    }

    private suspend fun downloadAndStage(context: Context, entry: CloudDictionary) {
        val current = DictionaryPackManager.snapshot(context).let { (it.pending ?: it.installed).find { pack -> pack.id == entry.id } }
        require(current == null || current.sourceUrl.isBlank() || current.sourceUrl == entry.manifestUrl) { "另一订阅已安装同名词库，请先卸载后再切换来源" }
        val bytes = fetch(entry.url, DictionaryPackFormat.MAX_DOWNLOAD)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val preview = DictionaryCatalog.verify(entry, bytes)
        DictionaryPackManager.stage(context, preview, entry.manifestUrl)
    }

    private fun cacheFile(context: Context, url: String) = File(context.cacheDir, "dictionary-catalog/${DictionaryPackFormat.sha256(url.toByteArray())}.json")
}

class DictionaryUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (DictionaryCloudUpdates.state(applicationContext).automatic) DictionaryCloudUpdates.check(applicationContext, installUpdates = true)
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
