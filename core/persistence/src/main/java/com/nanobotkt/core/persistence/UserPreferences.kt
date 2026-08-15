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

    /**
     * 读取最近一次 App 更新检查时间。
     *
     * 该值只用于“每天最多自动检查一次”的节流，不参与版本判断；版本信息始终来自
     * BuildConfig 与 GitHub Release，避免本地持久化形成第二套版本来源。
     */
    suspend fun readLastAppUpdateCheckAtMillis(): Long? =
        context.nanobotDataStore.data.map { it[Keys.lastAppUpdateCheckAtMillis] }.firstValue()

    /** 在检查开始时立即记录时间，使失败的自动请求也不会在同一天反复打扰用户。 */
    suspend fun writeLastAppUpdateCheckAtMillis(value: Long) =
        update(Keys.lastAppUpdateCheckAtMillis, value)

    internal suspend fun readEncryptedSecret(): String? = context.nanobotDataStore.data.map { it[Keys.bootstrapSecret] }.firstValue()
    internal suspend fun writeEncryptedSecret(value: String?) { context.nanobotDataStore.edit { if (value == null) it.remove(Keys.bootstrapSecret) else it[Keys.bootstrapSecret] = value } }

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
        val lastAppUpdateCheckAtMillis = longPreferencesKey("last_app_update_check_at_millis")
    }
}

private suspend fun <T> Flow<T>.firstValue(): T = first()


