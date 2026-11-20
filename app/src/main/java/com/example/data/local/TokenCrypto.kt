package com.example.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TokenCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "yuta_ai_bot_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENC_PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrNull()
    }

    fun encrypt(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty()) return ""
        val key = getOrCreateKey() ?: return trimmed
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + encrypted.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrDefault(trimmed)
    }

    fun decrypt(storedValue: String): String {
        if (storedValue.isBlank()) return ""
        if (!storedValue.startsWith(ENC_PREFIX)) {
            // Legacy unencrypted value (e.g., before encryption upgrade or Robolectric fallback)
            return storedValue.trim()
        }
        val key = getOrCreateKey() ?: return ""
        return runCatching {
            val payload = Base64.decode(storedValue.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (payload.isEmpty()) return@runCatching ""
            val ivLen = payload[0].toInt() and 0xFF
            if (payload.size <= 1 + ivLen) return@runCatching ""
            val iv = payload.copyOfRange(1, 1 + ivLen)
            val cipherBytes = payload.copyOfRange(1 + ivLen, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun redactSecrets(message: String?, token: String): String {
        if (message.isNullOrBlank()) return ""
        var sanitized = message
        val trimmedToken = token.trim()
        if (trimmedToken.isNotEmpty()) {
            sanitized = sanitized.replace(trimmedToken, "[REDACTED_TOKEN]")
        }
        return sanitized.replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot[REDACTED_TOKEN]")
    }
}
