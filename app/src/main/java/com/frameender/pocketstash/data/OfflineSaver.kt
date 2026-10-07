package com.frameender.pocketstash.data

import android.content.Context
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.frameender.pocketstash.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.TimeUnit

/** A list the user saved for offline viewing. Remembered so it can be refreshed. */
@Serializable
data class OfflineCollection(
    val label: String,
    val kind: String,
    /** Scope of the list ("none", "performer", "studio", "tag", "gallery", "group", "scene") and its id. */
    val scopeType: String = "none",
    val scopeId: String = "",
    val sort: String,
    val descending: Boolean,
    val quick: List<String> = emptyList(),
    val text: String = "",
    val max: Int,
    /** Images: also keep the full-size pictures, not just thumbnails. */
    val fullImages: Boolean = false,
    /** How many entries the last save stored. */
    val count: Int = 0,
    /** When it was last saved (epoch ms). */
    val savedAt: Long = 0,
) {
    val key: String get() = listOf(kind, scopeType, scopeId, sort, descending, quick.sorted(), text).joinToString("|")
    val entityKind: EntityKind get() = runCatching { EntityKind.valueOf(kind) }.getOrDefault(EntityKind.SCENES)
    val scope: Scope get() = scopeOf(scopeType, scopeId)
    fun query(): BrowseQuery = BrowseQuery(text = text, sort = sort, descending = descending, quick = quick.toSet())

    companion object {
        fun scopeOf(type: String, id: String): Scope = when (type) {
            "performer" -> Scope.Performer(id)
            "studio" -> Scope.Studio(id)
            "tag" -> Scope.Tag(id)
            "gallery" -> Scope.Gallery(id)
            "group" -> Scope.Group(id)
            "scene" -> Scope.Scene(id)
            else -> Scope.None
        }

        fun scopeKey(scope: Scope): Pair<String, String> = when (scope) {
            Scope.None -> "none" to ""
            is Scope.Performer -> "performer" to scope.id
            is Scope.Studio -> "studio" to scope.id
            is Scope.Tag -> "tag" to scope.id
            is Scope.Gallery -> "gallery" to scope.id
            is Scope.Group -> "group" to scope.id
            is Scope.Scene -> "scene" to scope.id
        }
    }
}

/**
 * "Save for offline": walks a list the same way the app browses it, so every page of results,
 * every entry's detail page (with the first page of its related grid), and their pictures land
 * in the caches. Later, when the server can't be reached, those screens open from the saved copies.
 *
 * Answers go into the [ResponseCache]; pictures into Coil's image cache (size set in Settings).
 * Videos themselves are not saved, only their screenshots. Saved collections are listed in
 * Settings → Storage & offline, where they can be refreshed or forgotten.
 */
class OfflineSaver(
    private val context: Context,
    private val repo: StashRepository,
    private val scope: CoroutineScope,
    private val settings: suspend () -> AppSettings,
    private val update: suspend ((AppSettings) -> AppSettings) -> Unit,
) {
    data class Progress(val label: String, val done: Int, val total: Int)

    val progress = MutableStateFlow<Progress?>(null)
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    // ---------------- remembered collections ----------------

    private val listSer = ListSerializer(OfflineCollection.serializer())

    fun collections(s: AppSettings): List<OfflineCollection> =
        if (s.offlineCollections.isBlank()) emptyList()
        else runCatching { StashJson.decodeFromString(listSer, s.offlineCollections) }.getOrDefault(emptyList())

    private suspend fun remember(c: OfflineCollection) {
        update { s ->
            val list = listOf(c) + collections(s).filter { it.key != c.key }
            s.copy(offlineCollections = StashJson.encodeToString(listSer, list))
        }
    }

    /** Takes a collection off the list. Its files stay cached until space is needed or you clear them. */
    fun forget(c: OfflineCollection) {
        scope.launch {
            update { s -> s.copy(offlineCollections = StashJson.encodeToString(listSer, collections(s).filter { it.key != c.key })) }
        }
    }

    // ---------------- saving ----------------

    /** Starts saving in the background (from a screen). */
    fun save(c: OfflineCollection) = start(c)

    /** Re-saves a remembered collection with its original options. */
    fun refresh(c: OfflineCollection) = start(c)

    private fun toast(text: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    private fun start(c: OfflineCollection) {
        if (running) {
            toast("Already saving “${progress.value?.label}”")
            return
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val n = run(c)
                toast("Saved $n from “${c.label}” for offline")
            } catch (e: CancellationException) {
                toast("Stopped saving “${c.label}”")
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "Saving for offline failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Pages the grid asks for (40 per page), plus the image viewer's 60-per-page pages. */
    private suspend fun walk(c: OfflineCollection, perPage: Int): List<CardItem> {
        val items = mutableListOf<CardItem>()
        var page = 1
        while (items.size < c.max) {
            currentCoroutineContext().ensureActive()
            val p = repo.browse(c.entityKind, c.scope, c.query(), page, perPage)
            items += p.items
            if (p.items.size < perPage || items.size >= p.total) break
            page++
        }
        return items
    }

    /** Does the actual saving. Suspends until done; also used by the background refresh. */
    suspend fun run(c: OfflineCollection): Int = withContext(Dispatchers.IO) {
        try {
            progress.value = Progress(c.label, 0, 0)
            val kind = c.entityKind
            val todo = walk(c, 40).take(c.max)
            if (kind == EntityKind.IMAGES) runCatching { walk(c, 60) }

            val loader = SingletonImageLoader.get(context)
            suspend fun fetch(url: String?) {
                if (url.isNullOrBlank()) return
                loader.execute(
                    ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build(),
                )
            }
            /** The first page of a detail screen's related grid, as that screen asks for it. */
            suspend fun related(k: EntityKind, s: Scope) {
                runCatching { repo.browse(k, s, BrowseSpec.defaultSort(k, s), 1, 40) }
            }

            todo.forEachIndexed { i, item ->
                currentCoroutineContext().ensureActive()
                progress.value = Progress(c.label, i, todo.size)
                runCatching {
                    when (kind) {
                        EntityKind.SCENES -> repo.scene(item.id)
                        EntityKind.PERFORMERS -> { repo.performer(item.id); related(EntityKind.SCENES, Scope.Performer(item.id)) }
                        EntityKind.STUDIOS -> { repo.studio(item.id); related(EntityKind.SCENES, Scope.Studio(item.id)) }
                        EntityKind.TAGS -> { repo.tag(item.id); related(EntityKind.SCENES, Scope.Tag(item.id)) }
                        EntityKind.GROUPS -> { repo.group(item.id); related(EntityKind.SCENES, Scope.Group(item.id)) }
                        EntityKind.GALLERIES -> { repo.gallery(item.id); related(EntityKind.IMAGES, Scope.Gallery(item.id)) }
                        EntityKind.MARKERS -> item.sceneId?.let { repo.scene(it) }
                        EntityKind.IMAGES -> Unit
                    }
                }
                fetch(item.image)
                if (c.fullImages && kind == EntityKind.IMAGES && !item.isVideo) fetch(item.fullImage)
            }
            remember(c.copy(count = todo.size, savedAt = System.currentTimeMillis()))
            todo.size
        } finally {
            progress.value = null
        }
    }
}

/** Daily re-save of every remembered collection, on Wi-Fi while charging. */
class OfflineRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.filterNotNull().first()
        if (!s.offlineAutoRefresh || !s.isConfigured) return Result.success()
        for (col in c.offlineSaver.collections(s)) {
            runCatching { c.offlineSaver.run(col) }
        }
        return Result.success()
    }
}

object OfflineRefreshScheduler {
    private const val WORK = "pocketstash-offline-refresh"

    /** Turns the daily refresh on or off to match settings. Safe to call repeatedly. */
    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<OfflineRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
