package com.nanobotkt.core.persistence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 Android Keystore 加密并按 Gateway 地址隔离 Bootstrap Secret。
 *
 * 同一个 Secret 绝不能因为用户修改服务器地址而被自动发送到另一台主机。地址摘要只用于
 * 选择密文槽位，不是加密密钥；密文本身仍由不可导出的 Android Keystore AES key 保护。
 */
@Singleton
class EncryptedSecretStore @Inject constructor(private val preferences: UserPreferencesRepository) {
    suspend fun save(serverUrl: String, secret: String) = withContext(Dispatchers.IO) {
        preferences.writeEncryptedSecret(serverScope(serverUrl), encrypt(secret))
    }

    suspend fun load(serverUrl: String): String? = withContext(Dispatchers.IO) {
        val scope = serverScope(serverUrl)
        preferences.readEncryptedSecret(scope)?.let { payload ->
            return@withContext decryptOrClear(payload) {
                preferences.writeEncryptedSecret(scope, null)
            }
        }

        // 旧版本只有一个全局 Secret。升级后第一次读取时，将它只关联到当前已选择的
        // Gateway；迁移完成立即清理旧字段，之后切换地址不会再错误复用这份凭据。
        val legacyPayload = preferences.readLegacyEncryptedSecret() ?: return@withContext null
        val legacySecret = decryptOrClear(legacyPayload) {
            preferences.writeLegacyEncryptedSecret(null)
        } ?: return@withContext null
        preferences.writeEncryptedSecret(scope, encrypt(legacySecret))
        preferences.writeLegacyEncryptedSecret(null)
        legacySecret
    }

    suspend fun clear(serverUrl: String) =
        preferences.writeEncryptedSecret(serverScope(serverUrl), null)

    private suspend fun decryptOrClear(payload: String, clearBrokenPayload: suspend () -> Unit): String? =
        runCatching { decrypt(payload) }
            .getOrElse {
                // 密文损坏或 Keystore key 失效时不能继续重试同一无效值；清理后让认证页
                // 要求用户重新输入，避免无限启动失败循环。
                clearBrokenPayload()
                null
            }

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun serverScope(serverUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(serverUrl.trim().trimEnd('/').toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nanobot.bootstrap-secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
