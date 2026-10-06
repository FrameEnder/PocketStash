package com.frameender.pocketstash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketstash")

data class AppSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    /** Rewrite media URLs Stash returns so they always point at [serverUrl]. */
    val rewriteHost: Boolean = true,
    /** Resume scenes from Stash's saved resume point. */
    val resumePlayback: Boolean = true,
    /** Send play counts / resume time / play duration back to Stash. */
    val trackActivity: Boolean = true,
    /** Seconds of playback before a play is counted. */
    val playCountAfterSeconds: Int = 10,
    /** Use animated webp/mp4 previews on scene cards. */
    val animatedPreviews: Boolean = false,
    /** Minimum card width in dp for grids. */
    val gridCardWidth: Int = 170,
) {
    val baseUrl: HttpUrl? get() = normalizeServerUrl(serverUrl)
    val isConfigured: Boolean get() = baseUrl != null
}

/** Accepts "192.168.1.5:9999", "http://host:9999/", "https://stash.example.com/sub" etc. */
fun normalizeServerUrl(raw: String): HttpUrl? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return withScheme.toHttpUrlOrNull()
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val url = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val rewrite = booleanPreferencesKey("rewrite_host")
        val resume = booleanPreferencesKey("resume_playback")
        val track = booleanPreferencesKey("track_activity")
        val playAfter = intPreferencesKey("play_count_after")
        val animated = booleanPreferencesKey("animated_previews")
        val gridWidth = intPreferencesKey("grid_card_width")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.url] ?: "",
            apiKey = p[Keys.apiKey] ?: "",
            rewriteHost = p[Keys.rewrite] ?: true,
            resumePlayback = p[Keys.resume] ?: true,
            trackActivity = p[Keys.track] ?: true,
            playCountAfterSeconds = p[Keys.playAfter] ?: 10,
            animatedPreviews = p[Keys.animated] ?: false,
            gridCardWidth = p[Keys.gridWidth] ?: 170,
        )
    }

    suspend fun saveServer(url: String, apiKey: String) {
        context.dataStore.edit {
            it[Keys.url] = url.trim().trimEnd('/')
            it[Keys.apiKey] = apiKey.trim()
        }
    }

    suspend fun clearServer() {
        context.dataStore.edit {
            it.remove(Keys.url)
            it.remove(Keys.apiKey)
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings, current: AppSettings) {
        val next = transform(current)
        context.dataStore.edit {
            it[Keys.rewrite] = next.rewriteHost
            it[Keys.resume] = next.resumePlayback
            it[Keys.track] = next.trackActivity
            it[Keys.playAfter] = next.playCountAfterSeconds
            it[Keys.animated] = next.animatedPreviews
            it[Keys.gridWidth] = next.gridCardWidth
        }
    }
}
