package com.kingzcheung.xime.plugin

import android.app.Application
import android.content.Context
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

class PluginConfigStoreImpl(
    private val app: Application,
    private val pluginId: String,
    private val cipher: ValueCipher = SecureValueCipher
) : PluginConfigStore {

    private val prefs: android.content.SharedPreferences =
        app.getSharedPreferences("plugin_cfg_$pluginId", Context.MODE_PRIVATE)

    override fun get(key: String): String? =
        prefs.getString(key, null)?.let { cipher.decrypt(it) }

    override fun set(key: String, value: String):Unit = synchronized(prefs) {
        get(key)
        val old=prefs.getString(key,null)
        if(!prefs.edit().putString(key,cipher.encrypt(value)).commit()){
            check(prefs.edit().putString(key,old).commit()){"插件配置保存失败且回滚未完成"}
            error("插件配置保存失败")
        }
    }

    fun preserveUnreadableAndReset():Boolean = synchronized(prefs) {
        try { keys().forEach(::get);return@synchronized false }
        catch(e:SecretReadFailure){if(!e.recoverable)throw e}
        val original=prefs.all.toMap()
        require(original.values.all{it is String}){"插件配置文件格式无效"}
        archiveEncryptedFile(app,java.io.File(app.applicationInfo.dataDir,"shared_prefs/plugin_cfg_$pluginId.xml"))
        check(prefs.all==original){"插件配置已改变，请重新读取"}
        if(!prefs.edit().clear().commit()){
            val rollback=prefs.edit().clear();original.forEach{(key,value)->rollback.putString(key,value as String)}
            check(rollback.commit()){"配置保留失败且回退未完成"}
            error("旧配置归档成功，但本机配置未能重建，请重试")
        }
        true
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}
