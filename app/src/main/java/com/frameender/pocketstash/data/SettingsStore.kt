package com.frameender.pocketstash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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

    // Appearance
    val accent: String = "amber",
    /** JSON list of Home widgets; blank = built-in default layout. */
    val homeLayout: String = "",

    // In-app updates (GitHub Releases)
    val updateChannel: String = "stable",          // "stable" or "nightly"
    val autoUpdateCheck: Boolean = true,
    val updateNotify: Boolean = false,
    val updateRepo: String = "FrameEnder/PocketStash",
    val githubToken: String = "",                  // only needed while the repo is private
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
    private object K {
        val url = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val rewrite = booleanPreferencesKey("rewrite_host")
        val resume = booleanPreferencesKey("resume_playback")
        val track = booleanPreferencesKey("track_activity")
        val playAfter = intPreferencesKey("play_count_after")
        val animated = booleanPreferencesKey("animated_previews")
        val gridWidth = intPreferencesKey("grid_card_width")
        val accent = stringPreferencesKey("accent")
        val homeLayout = stringPreferencesKey("home_layout")
        val updChannel = stringPreferencesKey("upd_channel")
        val updAuto = booleanPreferencesKey("upd_auto")
        val updNotify = booleanPreferencesKey("upd_notify")
        val updRepo = stringPreferencesKey("upd_repo")
        val ghToken = stringPreferencesKey("gh_token")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            serverUrl = this[K.url] ?: d.serverUrl,
            apiKey = this[K.apiKey] ?: d.apiKey,
            rewriteHost = this[K.rewrite] ?: d.rewriteHost,
            resumePlayback = this[K.resume] ?: d.resumePlayback,
            trackActivity = this[K.track] ?: d.trackActivity,
            playCountAfterSeconds = this[K.playAfter] ?: d.playCountAfterSeconds,
            animatedPreviews = this[K.animated] ?: d.animatedPreviews,
            gridCardWidth = this[K.gridWidth] ?: d.gridCardWidth,
            accent = this[K.accent] ?: d.accent,
            homeLayout = this[K.homeLayout] ?: d.homeLayout,
            updateChannel = this[K.updChannel] ?: d.updateChannel,
            autoUpdateCheck = this[K.updAuto] ?: d.autoUpdateCheck,
            updateNotify = this[K.updNotify] ?: d.updateNotify,
            updateRepo = this[K.updRepo] ?: d.updateRepo,
            githubToken = this[K.ghToken] ?: d.githubToken,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.url] = s.serverUrl
        this[K.apiKey] = s.apiKey
        this[K.rewrite] = s.rewriteHost
        this[K.resume] = s.resumePlayback
        this[K.track] = s.trackActivity
        this[K.playAfter] = s.playCountAfterSeconds
        this[K.animated] = s.animatedPreviews
        this[K.gridWidth] = s.gridCardWidth
        this[K.accent] = s.accent
        this[K.homeLayout] = s.homeLayout
        this[K.updChannel] = s.updateChannel
        this[K.updAuto] = s.autoUpdateCheck
        this[K.updNotify] = s.updateNotify
        this[K.updRepo] = s.updateRepo
        this[K.ghToken] = s.githubToken
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun saveServer(url: String, apiKey: String) {
        update { it.copy(serverUrl = url.trim().trimEnd('/'), apiKey = apiKey.trim()) }
    }

    suspend fun clearServer() {
        update { it.copy(serverUrl = "", apiKey = "") }
    }

    /** Atomic read-modify-write of all settings. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p -> p.write(transform(p.toSettings())) }
    }
}
