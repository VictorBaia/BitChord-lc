package com.music.bitchord.data.navidrome

import java.security.MessageDigest
import java.security.SecureRandom

object NavidromeAuth {
    @Volatile private var current: NavidromeConfig = NavidromeConfig()
    private val random = SecureRandom()

    fun update(config: NavidromeConfig) {
        current = config
    }

    fun queryParameters(): Map<String, String> = queryParameters(current)

    fun queryParameters(config: NavidromeConfig): Map<String, String> {
        if (config.username.isBlank() || config.password.isEmpty()) return emptyMap()
        val saltBytes = ByteArray(16).also(random::nextBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val token = md5(config.password + salt)
        return mapOf(
            "u" to config.username,
            "t" to token,
            "s" to salt,
            "v" to "1.16.1",
            "c" to "BitChord",
            "f" to "json",
        )
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
