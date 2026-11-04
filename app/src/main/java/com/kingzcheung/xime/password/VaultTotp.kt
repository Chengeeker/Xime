package com.kingzcheung.xime.password

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object VaultTotp {
    data class Code(val value: String, val remaining: Int, val period: Int)

    fun code(source: String, seconds: Long): Code? = runCatching {
        require(source.length <= 4096 && seconds >= 0)
        val parameters = if (source.startsWith("otpauth://", true)) {
            val uri = URI(source)
            require(uri.host.equals("totp", true))
            uri.rawQuery.orEmpty().split('&').associate { parameter ->
                val parts = parameter.split('=', limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
        } else emptyMap()
        val algorithm = parameters["algorithm"]?.uppercase() ?: "SHA1"
        require(algorithm in setOf("SHA1", "SHA256", "SHA512"))
        val digits = parameters["digits"]?.toInt() ?: 6
        val period = parameters["period"]?.toInt() ?: 30
        require(digits in 6..8 && period in 1..300)
        val key = base32(parameters["secret"] ?: source)
        try { Code(generate(key, algorithm, digits, period, seconds), period - (seconds % period).toInt(), period) }
        finally { key.fill(0) }
    }.getOrNull()

    internal fun generate(key: ByteArray, algorithm: String, digits: Int, period: Int, seconds: Long): String {
        require(digits in 6..8 && period in 1..300 && seconds >= 0 && algorithm in setOf("SHA1", "SHA256", "SHA512"))
        val hash = Mac.getInstance("Hmac$algorithm").run {
            init(SecretKeySpec(key, "Hmac$algorithm"))
            doFinal(ByteBuffer.allocate(8).putLong(seconds / period).array())
        }
        return try {
            val offset = hash.last().toInt() and 15
            val binary = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
            val modulus = when (digits) { 6 -> 1_000_000; 7 -> 10_000_000; else -> 100_000_000 }
            (binary % modulus).toString().padStart(digits, '0')
        } finally { hash.fill(0) }
    }

    private fun base32(source: String): ByteArray {
        val value = source.filterNot(Char::isWhitespace).uppercase().trimEnd('=')
        require(value.isNotEmpty())
        val output = ByteArray(value.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in value) {
            val digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(char)
            require(digit >= 0)
            buffer = (buffer shl 5) or digit
            bits += 5
            if (bits >= 8) { bits -= 8; output[index++] = (buffer shr bits).toByte() }
        }
        require(output.isNotEmpty() && (buffer and ((1 shl bits) - 1)) == 0)
        return output
    }
}
