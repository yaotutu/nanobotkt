package com.nanobotkt.core.persistence

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val COMPOSER_RECENTS_LIMIT = 5

interface ComposerRecentsStore {
    suspend fun load(): List<String>
    suspend fun save(commands: List<String>)
}

@Singleton
class DataStoreComposerRecentsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ComposerRecentsStore {
    override suspend fun load(): List<String> = runCatching {
        decodeComposerRecents(context.nanobotDataStore.data.first()[Keys.commands])
    }.getOrDefault(emptyList())

    override suspend fun save(commands: List<String>) {
        runCatching {
            val normalized = normalizeComposerRecents(commands)
            context.nanobotDataStore.edit { preferences ->
                preferences[Keys.commands] = JsonArray(normalized.map(::JsonPrimitive)).toString()
            }
        }
    }

    private object Keys {
        val commands = stringPreferencesKey("composer_recents")
    }
}

internal fun decodeComposerRecents(raw: String?): List<String> {
    if (raw == null) return emptyList()
    return runCatching {
        val array = Json.parseToJsonElement(raw) as? JsonArray ?: return@runCatching emptyList()
        normalizeComposerRecents(
            array.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            },
        )
    }.getOrDefault(emptyList())
}

internal fun normalizeComposerRecents(commands: List<String>): List<String> =
    commands.take(COMPOSER_RECENTS_LIMIT)

@Module
@InstallIn(SingletonComponent::class)
abstract class ComposerRecentsModule {
    @Binds
    abstract fun bindComposerRecentsStore(
        implementation: DataStoreComposerRecentsStore,
    ): ComposerRecentsStore
}