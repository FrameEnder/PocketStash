package com.frameender.pocketstash

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.frameender.pocketstash.data.AppSettings
import com.frameender.pocketstash.data.Connection
import com.frameender.pocketstash.data.OfflineRefreshScheduler
import com.frameender.pocketstash.data.OfflineSaver
import com.frameender.pocketstash.data.ResponseCache
import com.frameender.pocketstash.player.PlayerPrefs
import com.frameender.pocketstash.data.SettingsStore
import com.frameender.pocketstash.data.StashClient
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.data.UpdateScheduler
import com.frameender.pocketstash.data.Updater
import com.frameender.pocketstash.ui.theme.Accents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(context)
    val connection = Connection()

    /** Saved GraphQL answers (see ResponseCache); serves screens when the server is unreachable. */
    val responseCache = ResponseCache(context.cacheDir.resolve("graphql"))
    val client = StashClient(connection, responseCache)
    val repository = StashRepository(client, connection)

    /** null until DataStore has been read once. */
    val settings: StateFlow<AppSettings?> = settingsStore.settings
        .onEach {
            connection.settings = it
            Accents.select(it.accent)
            PlayerPrefs.startMuted = it.startMuted
        }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val updater = Updater(context, connection.http) { settings.value ?: AppSettings() }

    val offlineSaver = OfflineSaver(
        context, repository, appScope,
        settings = { settings.filterNotNull().first() },
        update = { t -> settingsStore.update(t) },
    )

    /** Screen to open from outside the UI (e.g. tapping the update notification). */
    val pendingRoute = MutableStateFlow<String?>(null)

    init {
        // Keep the background update check in step with the setting, and check once per launch.
        appScope.launch {
            settings.filterNotNull().map { it.autoUpdateCheck }.distinctUntilChanged().collect { enabled ->
                UpdateScheduler.apply(context, enabled)
            }
        }
        appScope.launch {
            settings.filterNotNull().map { it.offlineAutoRefresh }.distinctUntilChanged().collect { enabled ->
                OfflineRefreshScheduler.apply(context, enabled)
            }
        }
        appScope.launch {
            val s = settings.filterNotNull().first()
            if (s.autoUpdateCheck) updater.check(s)
        }
    }
}

class PocketStashApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.connection.http }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    // Size from Settings → Storage & offline (applies on the next start).
                    .maxSizeBytes((container.settings.value?.imageCacheMb ?: 512).coerceIn(128, 8192).toLong() * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}

val Context.container: AppContainer get() = (applicationContext as PocketStashApp).container
