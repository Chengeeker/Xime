package com.kingzcheung.xime.plugin.http

import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.io.OutputStream
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class BackupDownloadTest {
    private var removedProviders:List<Pair<Int,java.security.Provider>> = emptyList()
    @org.junit.Before fun hostTlsProviders(){
        removedProviders=java.security.Security.getProviders().mapIndexedNotNull{index,provider->if(provider.javaClass.name.contains("conscrypt",true))index+1 to provider else null}
        removedProviders.forEach{java.security.Security.removeProvider(it.second.name)}
    }
    @org.junit.After fun restoreProviders(){removedProviders.forEach{java.security.Security.insertProviderAt(it.second,it.first)}}
    private val auth = Credentials.basic("synthetic-user", "synthetic-password")
    private class Headers {
        private val values=linkedMapOf<String,String>()
        fun add(name:String,value:String){values[name.lowercase()]=value}
        fun getFirst(name:String)=values[name.lowercase()]
        fun lines()=values.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }
    private class Exchange(private val socket:Socket) {
        val requestHeaders=Headers()
        val responseHeaders=Headers()
        val requestURI:URI
        val responseBody:OutputStream=socket.getOutputStream()
        init {
            socket.soTimeout=5000
            val input=socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            requestURI=URI(input.readLine().split(' ')[1])
            while(true){val line=input.readLine()?:break;if(line.isEmpty())break
                val colon=line.indexOf(':');requestHeaders.add(line.substring(0,colon),line.substring(colon+1).trim())}
            // PUT fixtures have tiny bodies. Consume them before the reply.
            val length=requestHeaders.getFirst("Content-Length")?.toIntOrNull()?:0
            repeat(length){input.read()}
        }
        fun sendResponseHeaders(code:Int,length:Long){
            if(length!=0L)responseHeaders.add("Content-Length",length.coerceAtLeast(0).toString())
            responseHeaders.add("Connection","close")
            responseBody.write(("HTTP/1.1 $code Fixture\r\n"+responseHeaders.lines()+"\r\n").toByteArray(Charsets.ISO_8859_1))
            responseBody.flush()
        }
    }
    private class Server(tls: SSLContext? = null) : AutoCloseable {
        @Volatile var handle: (Exchange) -> Unit = { reply(it, 200, "{}") }
        private val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        private val executor = Executors.newCachedThreadPool { work -> Thread(work).apply { isDaemon = true } }
        private val server = tls?.serverSocketFactory?.createServerSocket(0,50,java.net.InetAddress.getByName("127.0.0.1"))
            ?: ServerSocket(0,50,java.net.InetAddress.getByName("127.0.0.1"))
        val port get()=server.localPort
        private val origin="${if(tls==null)"http"else"https"}://localhost:$port"
        init { executor.execute {
            while(!server.isClosed) {
                val socket=try{server.accept()}catch(_:java.io.IOException){break}
                executor.execute {socket.use{try{handle(Exchange(it));it.getOutputStream().flush()}
                    catch(e:AssertionError){failure.compareAndSet(null,e)}
                    catch(_:java.io.IOException){}catch(_:InterruptedException){}}}
            }
        } }
        fun url(path:String="/backup.json")="$origin$path".toHttpUrl()
        override fun close(){server.close();executor.shutdownNow();failure.get()?.let{throw it}}
    }

    @get:org.junit.Rule val temp=org.junit.rules.TemporaryFolder()
    private val files=linkedMapOf<String,java.io.File>()
    private val blobs=object:com.kingzcheung.xime.plugin.core.js.http.BlobStore{
        override fun createTempFile(ownerPluginId:String)=temp.newFile()
        override fun register(ownerPluginId:String,file:java.io.File,owned:Boolean):String=java.util.UUID.randomUUID().toString().also{files[it]=file}
        override fun resolve(pluginId:String,id:String)=files[id]
        override fun release(id:String){files.remove(id)?.delete()}
    }
    private fun client(tls:SSLContext,trust:X509TrustManager)=OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust).build()
    private fun download(http:OkHttpClient)=BackupDownload(http,blobs,"synthetic",{true},1024)
    private suspend fun get(http:OkHttpClient,url:HttpUrl)=download(http).get(url.toString(),mapOf("Authorization" to auth,"Cookie" to "do-not-forward"))
    @Test fun allRedirectsKeepSignedQueryAndSameOriginAuth()=runBlocking{
        val(tls,trust)=tls();val http=client(tls,trust)
        Server(tls).use{server->
            for(code in listOf(301,302,303,307,308)){
                server.handle={
                    assertEquals(auth,it.requestHeaders.getFirst("Authorization"));assertNull(it.requestHeaders.getFirst("Cookie"))
                    if(it.requestURI.path=="/backup.json")redirect(it,code,"file?sign=a%2Bb%3D")
                    else{assertEquals("sign=a%2Bb%3D",it.requestURI.rawQuery);reply(it,200,"synthetic-zip")}
                }
                val result=get(http,server.url());assertEquals(200,result.status);assertEquals("synthetic-zip",files[result.blobId]!!.readText())
                blobs.release(result.blobId!!)
            }
        }
    }
    @Test fun crossOriginAndReturnNeverReattachCredentials()=runBlocking{
        val(tls,trust)=tls()
        val http=client(tls,trust).newBuilder().addInterceptor{chain->chain.proceed(chain.request().newBuilder().header("Cookie","leak").build())}
            .authenticator{_,_->error("must not run")}.build()
        Server(tls).use{origin->Server(tls).use{cdn->
            origin.handle={assertNull(it.requestHeaders.getFirst("Cookie"))
                if(it.requestURI.path=="/backup.json"){assertEquals(auth,it.requestHeaders.getFirst("Authorization"));redirect(it,302,cdn.url().toString())}
                else{assertNull(it.requestHeaders.getFirst("Authorization"));reply(it,200,"zip")}}
            cdn.handle={assertNull(it.requestHeaders.getFirst("Authorization"));assertNull(it.requestHeaders.getFirst("Cookie"));redirect(it,308,origin.url("/returned").toString())}
            val result=get(http,origin.url());blobs.release(result.blobId!!)
        }}
    }
    @Test fun unsafeLocationLoopsAndHopLimitRejected()=runBlocking{
        val(tls,trust)=tls();val http=client(tls,trust)
        Server(tls).use{server->
            for(target in listOf("","javascript:bad","https://user:secret@example.test/x","http://localhost/plain","/backup.json")){
                server.handle={redirect(it,302,target)};fails{get(http,server.url())}
            }
            server.handle={reply(it,302)};fails{get(http,server.url())}
            server.handle={redirect(it,302,it.requestURI.path+"x")};fails{get(http,server.url())}
        }
    }
    @Test fun statusAndSizeFailuresLeaveNoBlob()=runBlocking{
        val(tls,trust)=tls();val http=client(tls,trust)
        Server(tls).use{server->
            for(code in listOf(401,403,404,429,500,503)){server.handle={reply(it,code,"private")};val result=get(http,server.url());assertEquals(code,result.status);assertNull(result.blobId);assertEquals(0,result.body.size)}
            server.handle={reply(it,200,"x".repeat(1025))};fails{get(http,server.url())}
            server.handle={it.sendResponseHeaders(200,0);it.responseBody.write(ByteArray(1025))};fails{get(http,server.url())}
            server.handle={reply(it,200,"")};fails{get(http,server.url())}
            assertTrue(files.isEmpty());assertTrue(temp.root.listFiles()!!.isEmpty())
        }
    }
    @Test fun cancellationStopsDownloadAndLeavesNoBlob()=runBlocking{
        val(tls,trust)=tls();val http=client(tls,trust);val received=CountDownLatch(1)
        Server(tls).use{server->
            server.handle={received.countDown();Thread.sleep(5000);reply(it,200,"zip")}
            val job=launch(Dispatchers.IO){get(http,server.url())}
            assertTrue(withContext(Dispatchers.IO){received.await(2,TimeUnit.SECONDS)})
            withTimeout(1000){job.cancelAndJoin()};assertTrue(job.isCancelled);assertTrue(files.isEmpty())
        }
    }
    private suspend fun fails(block:suspend()->Unit){try{block();fail("Expected refusal")}catch(_:IllegalStateException){}}
    private fun tls(): Pair<SSLContext, X509TrustManager> {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/webdav-synthetic.p12")!!.use { store.load(it, "synthetic-only".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm(),"SunJSSE").apply { init(store, "synthetic-only".toCharArray()) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(),"SunJSSE").apply { init(store) }
        return SSLContext.getInstance("TLS","SunJSSE").apply { init(keys.keyManagers, trusts.trustManagers, null) } to trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    companion object {
        private fun reply(e: Exchange, code: Int, body: String = "") {
            val bytes = body.toByteArray()
            e.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) e.responseBody.write(bytes)
        }
        private fun redirect(e: Exchange, code: Int, target: String) { e.responseHeaders.add("Location", target); reply(e, code) }
    }
}
