package com.kingzcheung.xime.password

import java.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object BitwardenCrypto {
    private const val KEY_BYTES = 32
    private val argon2 by lazy { Argon2Kt() }

    data class Key(val encryption: ByteArray, val mac: ByteArray) {
        fun clear() {
            encryption.fill(0)
            mac.fill(0)
        }
    }

    fun deriveMasterKey(password: String, email: String, kdf: Int, iterations: Int, memoryMb: Int, parallelism: Int): ByteArray {
        require(iterations in 1..5_000_000) { "Unsupported Bitwarden KDF parameters" }
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val emailBytes = email.trim().lowercase(Locale.ENGLISH).toByteArray(StandardCharsets.UTF_8)
        try {
            return when (kdf) {
                0 -> pbkdf2(passwordBytes, emailBytes, iterations)
                1 -> {
                    require(memoryMb in 8..512 && parallelism in 1..16) { "Unsupported Bitwarden Argon2 parameters" }
                    val salt = MessageDigest.getInstance("SHA-256").digest(emailBytes)
                    val passwordBuffer = ByteBuffer.allocateDirect(passwordBytes.size).put(passwordBytes).apply { flip() }
                    val saltBuffer = ByteBuffer.allocateDirect(salt.size).put(salt).apply { flip() }
                    try {
                        argon2.hash(
                            mode = Argon2Mode.ARGON2_ID,
                            password = passwordBuffer,
                            salt = saltBuffer,
                            tCostInIterations = iterations,
                            mCostInKibibyte = Math.multiplyExact(memoryMb, 1024),
                            parallelism = parallelism,
                            hashLengthInBytes = KEY_BYTES,
                            version = Argon2Version.V13,
                        ).rawHashAsByteArray().also { result ->
                            passwordBuffer.wipe()
                            saltBuffer.wipe()
                            salt.fill(0)
                            require(result.size == KEY_BYTES) { "Invalid Bitwarden KDF output" }
                        }
                    } finally {
                        passwordBuffer.wipe()
                        saltBuffer.wipe()
                        salt.fill(0)
                    }
                }
                else -> error("Unsupported Bitwarden KDF")
            }
        } finally {
            passwordBytes.fill(0)
            emailBytes.fill(0)
        }
    }

    fun derivePasswordHash(masterKey: ByteArray, password: String): String {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        return try {
            Base64.getEncoder().encodeToString(pbkdf2(masterKey, passwordBytes, 1))
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun stretch(masterKey: ByteArray): Key = Key(
        encryption = hkdfExpand(masterKey, "enc".toByteArray(), KEY_BYTES),
        mac = hkdfExpand(masterKey, "mac".toByteArray(), KEY_BYTES),
    )

    fun decryptUserKey(encrypted: String, masterKey: Key): Key {
        val plain = decrypt(encrypted, masterKey)
        try {
            require(plain.size == KEY_BYTES * 2) { "Invalid Bitwarden vault key" }
            return Key(plain.copyOfRange(0, KEY_BYTES), plain.copyOfRange(KEY_BYTES, KEY_BYTES * 2))
        } finally {
            plain.fill(0)
        }
    }

    fun decryptString(value: String, key: Key): String {
        val plain = decrypt(value, key)
        return try {
            String(plain, StandardCharsets.UTF_8)
        } finally {
            plain.fill(0)
        }
    }

    fun encryptString(value: String, key: Key): String {
        val plain = value.toByteArray(StandardCharsets.UTF_8)
        return try { encrypt(plain, key) } finally { plain.fill(0) }
    }

    fun encrypt(value: ByteArray, key: Key): String {
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        val encrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encryption, "AES"), IvParameterSpec(iv))
            doFinal(value)
        }
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.mac, "HmacSHA256"))
            update(iv)
            doFinal(encrypted)
        }
        return try {
            "2.${Base64.getEncoder().encodeToString(iv)}|${Base64.getEncoder().encodeToString(encrypted)}|${Base64.getEncoder().encodeToString(mac)}"
        } finally {
            iv.fill(0)
            encrypted.fill(0)
            mac.fill(0)
        }
    }

    fun looksEncrypted(value: String): Boolean = value.substringBefore('.').toIntOrNull().let { it == 0 || it == 2 }

    private fun decrypt(value: String, key: Key): ByteArray {
        require(value.length <= 1_048_576) { "Bitwarden cipher is too large" }
        val firstDot = value.indexOf('.')
        val type = if (firstDot < 0) 0 else value.substring(0, firstDot).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid Bitwarden cipher")
        require(type == 0 || type == 2) { "Unsupported Bitwarden cipher type" }
        val fields = (if (firstDot < 0) value else value.substring(firstDot + 1)).split('|')
        require(fields.size >= if (type == 2) 3 else 2) { "Invalid Bitwarden cipher" }
        val iv = decode(fields[0])
        val ciphertext = decode(fields[1])
        val mac = if (type == 2) decode(fields[2]) else null
        try {
            require(iv.size == 16 && ciphertext.isNotEmpty() && ciphertext.size % 16 == 0) { "Invalid Bitwarden cipher" }
            if (mac != null) {
                val calculated = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(key.mac, "HmacSHA256"))
                    update(iv)
                    doFinal(ciphertext)
                }
                try {
                    if (!MessageDigest.isEqual(calculated, mac)) throw SecurityException("Bitwarden cipher authentication failed")
                } finally {
                    calculated.fill(0)
                }
            }
            return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encryption, "AES"), IvParameterSpec(iv))
                doFinal(ciphertext)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            mac?.fill(0)
        }
    }

    private fun decode(value: String): ByteArray {
        require(value.length <= 1_048_576) { "Bitwarden cipher segment is too large" }
        return Base64.getDecoder().decode(value.filterNot(Char::isWhitespace).replace('-', '+').replace('_', '/'))
    }

    internal fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val firstBlock = salt + byteArrayOf(0, 0, 0, 1)
        var u = mac.doFinal(firstBlock)
        firstBlock.fill(0)
        val output = u.copyOf()
        repeat(iterations - 1) {
            val previous = u
            u = mac.doFinal(previous)
            previous.fill(0)
            for (index in output.indices) output[index] = (output[index].toInt() xor u[index].toInt()).toByte()
        }
        u.fill(0)
        return output
    }

    private fun hkdfExpand(key: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(previous)
            mac.update(info)
            val next = mac.doFinal(byteArrayOf(counter.toByte()))
            previous.fill(0)
            previous = next
            val count = minOf(next.size, length - written)
            next.copyInto(output, written, 0, count)
            written += count
            counter++
        }
        previous.fill(0)
        return output
    }

    private fun ByteBuffer.wipe() {
        if (isReadOnly) return
        clear()
        while (hasRemaining()) put(0)
    }
}
