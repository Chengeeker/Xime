package com.kingzcheung.xime.settings

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable plugin credentials remain encrypted independently of either device's Keystore. */
internal object PortablePluginConfigs {
    private val header="XIMEPC1".toByteArray(Charsets.US_ASCII)
    private const val MAX_BYTES=16*1024*1024
    private fun key(password:CharArray,salt:ByteArray):SecretKeySpec {
        require(password.size in 8..1024){"备份密码至少需要 8 个字符"}
        val spec=PBEKeySpec(password,salt,600_000,256)
        return try{SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,"AES")}
        finally{spec.clearPassword()}
    }
    fun encrypt(data:ByteArray,password:CharArray):ByteArray {
        require(data.size<=MAX_BYTES){"插件配置超过备份大小上限"}
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(password,salt));cipher.updateAAD(header+salt)
        return header+salt+cipher.iv+cipher.doFinal(data)
    }
    fun decrypt(data:ByteArray,password:CharArray):ByteArray {
        require(data.size in (header.size+16+12+16)..(MAX_BYTES+header.size+44) && data.copyOfRange(0,header.size).contentEquals(header)){"加密插件配置格式或版本无效"}
        val salt=data.copyOfRange(header.size,header.size+16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(password,salt),GCMParameterSpec(128,data.copyOfRange(header.size+16,header.size+28)))
        cipher.updateAAD(header+salt)
        return try{cipher.doFinal(data.copyOfRange(header.size+28,data.size))}
        catch(_:javax.crypto.AEADBadTagException){error("备份密码不正确，或加密配置已损坏；未恢复任何资料")}
    }
}
