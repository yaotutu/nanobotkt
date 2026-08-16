package com.nanobotkt.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.nanobotDataStore by preferencesDataStore(name = "nanobot_preferences")

enum class ThemePreference { SYSTEM, LIGHT, DARK }
enum class DensityPreference { COMFORTABLE, COMPACT }
enum class FileEditDisplay { SUMMARY, DIFF, HIDDEN }

data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val languageTag: String? = null,
    val density: DensityPreference = DensityPreference.COMFORTABLE,
    val showActivityDetails: Boolean = false,
    val wrapCode: Boolean = false,
    val showBrandLogos: Boolean = true,
    val fileEditDisplay: FileEditDisplay = FileEditDisplay.SUMMARY,
)

/** DataStore 内的一次性 Gateway 配置记录；地址和加密 Secret 只能成对出现。 */
internal data class EncryptedGatewayConfigRecord(
    val serverUrl: String,
    val encryptedSecret: String,
)

@Singleton
class UserPreferencesRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    val preferences: Flow<UserPreferences> = context.nanobotDataStore.data.map(::decode)

    suspend fun setTheme(value: ThemePreference) = update(Keys.theme, value.name)
    suspend fun setLanguage(value: String?) { context.nanobotDataStore.edit { if (value == null) it.remove(Keys.language) else it[Keys.language] = value } }
    suspend fun setDensity(value: DensityPreference) = update(Keys.density, value.name)
    suspend fun setShowActivityDetails(value: Boolean) = update(Keys.activityDetails, value)
    suspend fun setWrapCode(value: Boolean) = update(Keys.wrapCode, value)
    suspend fun setShowBrandLogos(value: Boolean) = update(Keys.brandLogos, value)
    suspend fun setFileEditDisplay(value: FileEditDisplay) = update(Keys.fileEditDisplay, value.name)

    /**
     * 读取新的完整 Gateway 配置记录。
     *
     * 旧版本分离保存的 server_url/bootstrap_secret_ciphertext 不再参与恢复。项目明确不做
     * 兼容迁移，因此只有同一次 DataStore 事务写入的 v2 地址和密文才构成有效活动配置。
     */
    internal suspend fun readEncryptedGatewayConfig(): EncryptedGatewayConfigRecord? =
        context.nanobotDataStore.data.map { values ->
            val serverUrl = values[Keys.gatewayServerUrl]
            val encryptedSecret = values[Keys.gatewaySecretCiphertext]
            if (serverUrl.isNullOrBlank() || encryptedSecret.isNullOrBlank()) {
                null
            } else {
                EncryptedGatewayConfigRecord(serverUrl = serverUrl, encryptedSecret = encryptedSecret)
            }
        }.firstValue()

    /**
     * 原子替换完整 Gateway 配置。
     *
     * DataStore 的 edit 要么整体提交地址和密文，要么整体失败；同时删除旧格式字段，避免
     * logout 或后续排障时设备上继续残留一份已经失去业务意义的旧 Secret。
     */
    internal suspend fun writeEncryptedGatewayConfig(serverUrl: String, encryptedSecret: String) {
        context.nanobotDataStore.edit { values ->
            values[Keys.gatewayServerUrl] = serverUrl
            values[Keys.gatewaySecretCiphertext] = encryptedSecret
            values.remove(Keys.legacyServerUrl)
            values.remove(Keys.legacyBootstrapSecret)
        }
    }

    /** 地址、Secret 以及不再支持的旧格式字段必须在同一个事务中一起清除。 */
    internal suspend fun clearEncryptedGatewayConfig() {
        context.nanobotDataStore.edit { values ->
            values.remove(Keys.gatewayServerUrl)
            values.remove(Keys.gatewaySecretCiphertext)
            values.remove(Keys.legacyServerUrl)
            values.remove(Keys.legacyBootstrapSecret)
        }
    }

    /**
     * 读取最近一次 App 更新检查时间。
     *
     * 该值只用于“每天最多自动检查一次”的节流，不参与版本判断；版本信息始终来自
     * BuildConfig 与 GitHub Release，避免本地持久化形成第二套版本来源。
     */
    suspend fun readLastAppUpdateCheckAtMillis(): Long? =
        context.nanobotDataStore.data.map { it[Keys.lastAppUpdateCheckAtMillis] }.firstValue()

    /** 在检查开始时立即记录时间，使失败的自动请求也不会在同一天反复打扰用户。 */
    suspend fun writeLastAppUpdateCheckAtMillis(value: Long) = update(Keys.lastAppUpdateCheckAtMillis, value)

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) { context.nanobotDataStore.edit { it[key] = value } }

    private fun decode(values: Preferences): UserPreferences = UserPreferences(
        theme = values[Keys.theme]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() } ?: ThemePreference.SYSTEM,
        languageTag = values[Keys.language],
        density = values[Keys.density]?.let { runCatching { DensityPreference.valueOf(it) }.getOrNull() } ?: DensityPreference.COMFORTABLE,
        showActivityDetails = values[Keys.activityDetails] ?: false,
        wrapCode = values[Keys.wrapCode] ?: false,
        showBrandLogos = values[Keys.brandLogos] ?: true,
        fileEditDisplay = values[Keys.fileEditDisplay]?.let { runCatching { FileEditDisplay.valueOf(it) }.getOrNull() } ?: FileEditDisplay.SUMMARY,
    )

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val density = stringPreferencesKey("density")
        val activityDetails = booleanPreferencesKey("activity_details")
        val wrapCode = booleanPreferencesKey("wrap_code")
        val brandLogos = booleanPreferencesKey("brand_logos")
        val fileEditDisplay = stringPreferencesKey("file_edit_display")
        val gatewayServerUrl = stringPreferencesKey("gateway_config_v2_server_url")
        val gatewaySecretCiphertext = stringPreferencesKey("gateway_config_v2_secret_ciphertext")
        // 旧字段只用于彻底清理，不参与任何恢复或迁移逻辑。
        val legacyServerUrl = stringPreferencesKey("server_url")
        val legacyBootstrapSecret = stringPreferencesKey("bootstrap_secret_ciphertext")
        val lastAppUpdateCheckAtMillis = longPreferencesKey("last_app_update_check_at_millis")
    }
}

private suspend fun <T> Flow<T>.firstValue(): T = first()
