package com.kingzcheung.xime.settings
import org.junit.Assert.*
import org.junit.Test
class PortablePluginConfigsTest {
    @Test fun portableCiphertextNeedsTheOriginalPasswordAndDetectsTampering(){
        val data="synthetic-plugin-credentials".toByteArray();val pass="synthetic-passphrase".toCharArray()
        val encrypted=PortablePluginConfigs.encrypt(data,pass)
        assertFalse(String(encrypted).contains("synthetic-plugin"))
        assertArrayEquals(data,PortablePluginConfigs.decrypt(encrypted,pass))
        assertThrows(IllegalStateException::class.java){PortablePluginConfigs.decrypt(encrypted,"another-password".toCharArray())}
        encrypted[encrypted.lastIndex]=(encrypted.last().toInt() xor 1).toByte()
        assertThrows(IllegalStateException::class.java){PortablePluginConfigs.decrypt(encrypted,pass)}
    }
    @Test fun malformedOrUnboundedInputIsRejectedBeforeDecrypt(){
        assertThrows(IllegalArgumentException::class.java){PortablePluginConfigs.decrypt(byteArrayOf(1),"synthetic-password".toCharArray())}
        assertThrows(IllegalArgumentException::class.java){PortablePluginConfigs.encrypt(byteArrayOf(1),"short".toCharArray())}
    }
}
