package com.kingzcheung.xime.plugin.http

import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class BackupDownload(client:OkHttpClient,private val blobs:BlobStore,private val owner:String,private val authorize:(String)->Boolean,private val maxBytes:Long=PluginBlobStore.MAX_BLOB_BYTES) {
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
        .cache(null).apply{interceptors().clear();networkInterceptors().clear()}.build()
    suspend fun get(url:String,headers:Map<String,String>):HttpResponse {
        var target=url.toHttpUrl();var authorization=headers.entries.firstOrNull{it.key.equals("Authorization",true)}?.value
        val visited=mutableSetOf<HttpUrl>()
        for(hop in 0..5){
            check(target.isHttps && target.username.isEmpty() && target.password.isEmpty()){"备份下载必须使用无内嵌账号的 HTTPS 地址"}
            check(visited.add(target)){"备份下载地址循环跳转"}
            check(authorize(target.toString())){"备份下载目标域名未授权，请在插件网络设置中授权后重试"}
            val request=Request.Builder().url(target).apply{authorization?.let{header("Authorization",it)}}.build()
            val response=fetch(request)
            if(response.status !in setOf(301,302,303,307,308))return response
            check(hop<5){"备份下载跳转超过五次"}
            val location=response.headers.entries.firstOrNull{it.key.equals("Location",true)}?.value
            check(!location.isNullOrBlank() && location.none{it.code<32 || it.code==127}){"备份下载跳转地址无效"}
            val next=target.resolve(location)?.newBuilder()?.fragment(null)?.build()?:error("备份下载跳转地址无效")
            check(next.isHttps && next.username.isEmpty() && next.password.isEmpty()){"备份下载跳转不安全"}
            if(next.scheme!=target.scheme || next.host!=target.host || next.port!=target.port)authorization=null
            target=next
        }
        error("备份下载跳转超过五次")
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fetch(request:Request):HttpResponse=suspendCancellableCoroutine{continuation->
        val call=client.newCall(request)
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(IOException("备份下载网络中断或超时，请重试"))}
            override fun onResponse(call:Call,response:Response){
                var temporary:java.io.File?=null;var blob:String?=null
                try {
                    val result=response.use{
                        if(it.code!=200)return@use HttpResponse(it.code,it.headers.toMap(),byteArrayOf())
                        val body=it.body?:error("备份下载内容为空")
                        check(body.contentLength()<=maxBytes){"备份超过 512 MB 上限"}
                        val file=blobs.createTempFile(owner);temporary=file;var count=0L
                        body.byteStream().use{input->file.outputStream().use{output->
                            val buffer=ByteArray(64*1024)
                            while(true){continuation.context.ensureActive();val size=input.read(buffer);if(size<0)break
                                count+=size;check(count<=maxBytes){"备份超过 512 MB 上限"};output.write(buffer,0,size)}
                        }}
                        check(count>0){"备份下载内容为空"}
                        blob=blobs.register(owner,file,owned=true)
                        HttpResponse(it.code,it.headers.toMap(),byteArrayOf(),blob,count)
                    }
                    if(continuation.isActive)continuation.resume(result,onCancellation={blob?.let(blobs::release)})
                    else blob?.let(blobs::release)
                }catch(e:Exception){
                    if(blob!=null)blobs.release(blob!!)else temporary?.delete()
                    if(continuation.isActive)continuation.resumeWithException(if(e is IOException)IOException("备份下载中断或超时，请重试")else e)
                }
            }
        })
    }
}
