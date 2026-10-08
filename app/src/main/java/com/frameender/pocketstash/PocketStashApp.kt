package com.frameender.pocketstash

import com.frameender.pocketstash.security.AppLock
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
import com.frameender.pocketstash.data.OfflineLibrary
import com.frameender.pocketstash.data.SceneDownloads
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

    /** The phone's own copy of the library: the whole database in offline mode. */
    val library = OfflineLibrary(context, appScope)
    val client = StashClient(connection)
    val repository = StashRepository(client, connection, library)

    /** null until DataStore has been read once. */
    val settings: StateFlow<AppSettings?> = settingsStore.settings
        .onEach {
            connection.settings = it
            Accents.select(it.accent)
            PlayerPrefs.startMuted = it.startMuted
        }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val updater = Updater(context, connection.http) { settings.value ?: AppSettings() }

    /** Scenes downloaded for offline playback. */
    val downloads = SceneDownloads(context, connection, repository, library, appScope)

    val offlineSaver = OfflineSaver(
        context, repository, library, { downloads }, appScope,
        settings = { settings.filterNotNull().first() },
        update = { t -> settingsStore.update(t) },
    )

    /** Switches offline mode on by hand: the app uses only what's on the phone until [goOnline]. */
    fun goOffline() = connection.enterOffline(manual = true)

    /** Leaves offline mode; screens reload from the server, and plays made offline are sent. */
    fun goOnline() {
        connection.leaveOffline()
        appScope.launch { runCatching { repository.flushPending() } }
    }

    /** Screen to open from outside the UI (e.g. tapping the update notification). */
    val pendingRoute = MutableStateFlow<String?>(null)

    init {
        repository.downloads = downloads
        connection.localMedia = { raw -> library.localImage(raw) }

        // While in offline mode, check every 20 seconds whether the server is back.
        appScope.launch {
            connection.offline.collectLatest { offline ->
                if (!offline) return@collectLatest
                while (true) {
                    delay(20_000)
                    val reachable = client.ping()
                    connection.serverBack.value = reachable
                    // Nothing on the phone to lose: go straight back online.
                    if (reachable && !connection.manualOffline && library.isEmpty()) {
                        goOnline()
                        break
                    }
                }
            }
        }
        // Send plays made offline last time, and pick up downloads that were interrupted.
        appScope.launch {
            settings.filterNotNull().first()
            runCatching { repository.flushPending() }
            downloads.start()
        }
        // Changing "Wi-Fi only" re-plans waiting downloads.
        appScope.launch {
            settings.filterNotNull().map { it.downloadWifiOnly }.distinctUntilChanged().drop(1).collect {
                downloads.start()
            }
        }

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

    /** App lock and app-drawer hiding (Privacy & security). */
    lateinit var appLock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so the lock is in place before any screen can show.
        appLock = AppLock(this)
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
