package com.nanobotkt.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

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
    val serverUrl: String? = null,
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
    suspend fun setServerUrl(value: String?) { context.nanobotDataStore.edit { if (value.isNullOrBlank()) it.remove(Keys.serverUrl) else it[Keys.serverUrl] = value.trim().trimEnd('/') } }

    /** 读取旧版本只保存一个 Secret 的字段，供按端点迁移时使用。 */
    internal suspend fun readLegacyEncryptedSecret(): String? =
        context.nanobotDataStore.data.map { it[Keys.bootstrapSecret] }.firstValue()

    /** 迁移完成或旧密文损坏时清理旧字段，避免后续错误关联到另一台服务器。 */
    internal suspend fun writeLegacyEncryptedSecret(value: String?) {
        context.nanobotDataStore.edit {
            if (value == null) it.remove(Keys.bootstrapSecret) else it[Keys.bootstrapSecret] = value
        }
    }

    /**
     * 按规范化服务器地址的不可逆摘要保存密文。
     *
     * DataStore 的 key 不直接包含服务器地址，既避免特殊字符影响 key，也避免在偏好文件
     * 元数据中暴露完整内部域名；真正的 Secret 仍由 Android Keystore 加密。
     */
    internal suspend fun readEncryptedSecret(scope: String): String? =
        context.nanobotDataStore.data.map { it[scopedBootstrapSecretKey(scope)] }.firstValue()

    internal suspend fun writeEncryptedSecret(scope: String, value: String?) {
        val key = scopedBootstrapSecretKey(scope)
        context.nanobotDataStore.edit {
            if (value == null) it.remove(key) else it[key] = value
        }
    }

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) { context.nanobotDataStore.edit { it[key] = value } }

    private fun decode(values: Preferences): UserPreferences = UserPreferences(
        theme = values[Keys.theme]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() } ?: ThemePreference.SYSTEM,
        languageTag = values[Keys.language],
        density = values[Keys.density]?.let { runCatching { DensityPreference.valueOf(it) }.getOrNull() } ?: DensityPreference.COMFORTABLE,
        showActivityDetails = values[Keys.activityDetails] ?: false,
        wrapCode = values[Keys.wrapCode] ?: false,
        showBrandLogos = values[Keys.brandLogos] ?: true,
        fileEditDisplay = values[Keys.fileEditDisplay]?.let { runCatching { FileEditDisplay.valueOf(it) }.getOrNull() } ?: FileEditDisplay.SUMMARY,
        serverUrl = values[Keys.serverUrl],
    )

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val density = stringPreferencesKey("density")
        val activityDetails = booleanPreferencesKey("activity_details")
        val wrapCode = booleanPreferencesKey("wrap_code")
        val brandLogos = booleanPreferencesKey("brand_logos")
        val fileEditDisplay = stringPreferencesKey("file_edit_display")
        val serverUrl = stringPreferencesKey("server_url")
        val bootstrapSecret = stringPreferencesKey("bootstrap_secret_ciphertext")
    }
}

private fun scopedBootstrapSecretKey(scope: String): Preferences.Key<String> =
    stringPreferencesKey("bootstrap_secret_ciphertext_$scope")

private suspend fun <T> Flow<T>.firstValue(): T = first()


