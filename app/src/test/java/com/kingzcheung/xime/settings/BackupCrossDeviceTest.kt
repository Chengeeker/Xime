package com.kingzcheung.xime.settings
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import com.kingzcheung.xime.plugin.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.io.File
class BackupCrossDeviceTest {
    @get:Rule val temp=TemporaryFolder()
    private class DeviceCipher:ValueCipher{
        private val key=javax.crypto.KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        override fun encrypt(plain:String):String {
            val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(javax.crypto.Cipher.ENCRYPT_MODE,key)
            return java.util.Base64.getEncoder().encodeToString(c.iv+c.doFinal(plain.toByteArray()))
        }
        override fun decrypt(stored:String):String {
            val b=java.util.Base64.getDecoder().decode(stored);val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,b.copyOfRange(0,12)))
            return String(c.doFinal(b.copyOfRange(12,b.size)))
        }
    }
    private fun device(name:String):Application {
        val dir=temp.newFolder(name);val app=mock(Application::class.java)
        val info=ApplicationInfo().apply{dataDir=dir.path}
        val stores=mutableMapOf<String,SharedPreferences>()
        doReturn(info).`when`(app).applicationInfo
        doReturn(File(dir,"files").apply{mkdirs()}).`when`(app).filesDir
        doReturn(app).`when`(app).applicationContext
        doAnswer { invocation ->
            stores.getOrPut(invocation.getArgument(0)){
                val values=linkedMapOf<String,Any>();val prefs=mock(SharedPreferences::class.java);val editor=mock(SharedPreferences.Editor::class.java)
                doAnswer{values.toMap()}.`when`(prefs).all
                doReturn(editor).`when`(prefs).edit()
                doAnswer{values.clear();editor}.`when`(editor).clear()
                doAnswer{values[it.getArgument(0)]=it.getArgument<String>(1);editor}.`when`(editor).putString(any(),any())
                doAnswer{values[it.getArgument(0)]=it.getArgument<Boolean>(1);editor}.`when`(editor).putBoolean(any(),any())
                doAnswer{values[it.getArgument(0)]=it.getArgument<Int>(1);editor}.`when`(editor).putInt(any(),any())
                doAnswer{values[it.getArgument(0)]=it.getArgument<Long>(1);editor}.`when`(editor).putLong(any(),any())
                doAnswer{values[it.getArgument(0)]=it.getArgument<Float>(1);editor}.`when`(editor).putFloat(any(),any())
                doReturn(true).`when`(editor).commit()
                doAnswer{values[it.getArgument(0)]?:it.getArgument(1)}.`when`(prefs).getString(any(),org.mockito.kotlin.anyOrNull())
                doAnswer{values[it.getArgument(0)]?:it.getArgument(1)}.`when`(prefs).getBoolean(any(),any())
                prefs
            }
        }.`when`(app).getSharedPreferences(any(),any())
        return app
    }
    private fun archive(entries:List<Pair<String,ByteArray>>):File {
        val file=temp.newFile()
        java.util.zip.ZipOutputStream(file.outputStream()).use{out->entries.forEach{(name,data)->out.putNextEntry(java.util.zip.ZipEntry(name));out.write(data);out.closeEntry()}}
        return file
    }
    @Test fun passwordBackupRestoresWithANewDeviceKeyAndSafeExportOmitsSecrets()=runBlocking{
        val a=device("a");val b=device("b");val ka=DeviceCipher();val kb=DeviceCipher()
        val source=PluginConfigStoreImpl(a,"synthetic",ka);source.set("password","synthetic-private-value")
        File(a.applicationInfo.dataDir,"shared_prefs/plugin_cfg_synthetic.xml").apply{parentFile.mkdirs();writeText("synthetic placeholder")}
        assertFalse(BackupManager.collectMetaEntries(a,cipher=ka).any{it.first.contains("plugin-configs")||it.first.contains("plugin_configs")})
        val entries=BackupManager.collectMetaEntries(a,"synthetic-password".toCharArray(),ka)
        assertFalse(entries.any{String(it.second).contains("synthetic-private-value")})
        val zip=archive(entries)
        assertTrue(BackupManager.restoreFromArchive(b,zip,"wrong-password".toCharArray(),kb).isFailure)
        assertNull(PluginConfigStoreImpl(b,"synthetic",kb).get("password"))
        val result=BackupManager.restoreFromArchive(b,zip,"synthetic-password".toCharArray(),kb)
        assertTrue(result.exceptionOrNull()?.message,result.isSuccess)
        assertEquals("synthetic-private-value",PluginConfigStoreImpl(b,"synthetic",kb).get("password"))
        assertNotEquals(a.getSharedPreferences("plugin_cfg_synthetic",0).all,b.getSharedPreferences("plugin_cfg_synthetic",0).all)
    }
}
