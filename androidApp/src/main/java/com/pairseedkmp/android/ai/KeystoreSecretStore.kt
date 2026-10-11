package com.pairseedkmp.android.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.pairseedkmp.domain.ai.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的 Android 保存（01）：AES/GCM 加密后**密文**落 prefs——Key 明文不落盘、不进日志、不进 git。
 * 加密密钥由 Android Keystore 生成并保管（不导出）；每次读写现场加解密（iv 随密文存，重加即换）。
 * 解密失败（密钥被清 / 数据损坏）当未配置处理——重填即可。
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun get(): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_CIPHERTEXT, null) ?: return@withContext null
        try {
            decrypt(stored)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun set(value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_CIPHERTEXT, encrypt(value)).apply()
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_CIPHERTEXT).apply()
        }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // "iv:密文"，双 Base64——GCM 的 iv 每次加密重新生成，必须随密文一起存
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** 取 Keystore 里的 AES 密钥；首次调用生成。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "pairseedkmp_ai_secrets"
        const val KEY_CIPHERTEXT = "api_key_encrypted"
        const val KEY_ALIAS = "pairseedkmp_api_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
