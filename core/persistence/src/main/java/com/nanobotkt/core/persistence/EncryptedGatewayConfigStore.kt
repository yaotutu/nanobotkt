package com.nanobotkt.core.persistence

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 持久化层向认证模块返回的完整 Gateway 配置；Secret 在离开本类前已经完成解密。 */
data class StoredGatewayConnectionConfig(
    val serverUrl: String,
    val bootstrapSecret: String,
)

/**
 * 完整 Gateway 配置的加密持久化实现。
 *
 * 地址和 Secret 属于不可拆分的活动配置：先在内存中完成 Secret 加密，再通过一次 DataStore
 * edit 同时替换地址和密文。任何一步失败都不会留下“新地址 + 旧 Secret”或反向组合。
 */
@Singleton
class EncryptedGatewayConfigStore @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    suspend fun save(config: StoredGatewayConnectionConfig) = withContext(Dispatchers.IO) {
        val encryptedSecret = encrypt(config.bootstrapSecret)
        preferences.writeEncryptedGatewayConfig(
            serverUrl = config.serverUrl,
            encryptedSecret = encryptedSecret,
        )
    }

    suspend fun load(): StoredGatewayConnectionConfig? = withContext(Dispatchers.IO) {
        val record = preferences.readEncryptedGatewayConfig() ?: return@withContext null
        try {
            StoredGatewayConnectionConfig(
                serverUrl = record.serverUrl,
                bootstrapSecret = decrypt(record.encryptedSecret),
            )
        } catch (_: Exception) {
            // KeyStore 被重置、密文损坏或格式非法时，整组配置都已经不可恢复。必须同时删除地址
            // 和密文，禁止只丢 Secret 后把旧地址伪装成仍然可用的活动配置。
            preferences.clearEncryptedGatewayConfig()
            null
        }
    }

    suspend fun clear() = preferences.clearEncryptedGatewayConfig()

    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split('.', limit = 2)
        require(parts.size == 2) { "invalid encrypted gateway configuration" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
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
        const val KEY_ALIAS = "nanobot.gateway-config.v2"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
