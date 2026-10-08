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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /** Scenes: also download each video, in this quality ("original", "STANDARD_HD"…); null = details only. */
    val downloadQuality: String? = null,
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
 * "Save for offline": walks a list the same way the app browses it and stores every entry's
 * full details in the phone's offline library, along with the first page of each one's related
 * grid (a performer's scenes, a gallery's images…). In offline mode those become part of the
 * database the whole app runs on, so any sort, search or filter works on them.
 *
 * Pictures go into Coil's image cache (size set in Settings). For scene lists, the videos can be
 * downloaded too (handed to [SceneDownloads]); otherwise scenes are browsable but not playable
 * offline. Saved collections are listed in Settings → Storage & offline, where they can be
 * refreshed or forgotten.
 */
class OfflineSaver(
    private val context: Context,
    private val repo: StashRepository,
    private val library: OfflineLibrary,
    private val downloads: () -> SceneDownloads,
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

    /** Every entry of the list (up to [OfflineCollection.max]) as raw JSON, straight from the server. */
    private suspend fun walk(c: OfflineCollection): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page = 1
        val perPage = 40
        while (items.size < c.max) {
            currentCoroutineContext().ensureActive()
            val p = repo.browseRaw(c.entityKind, c.scope, c.query(), page, perPage)
            items += p.items
            if (p.items.size < perPage || items.size >= p.total) break
            page++
        }
        return items.take(c.max)
    }

    /**
     * Stores items that were listed under [scope] so the phone can list them there again.
     * Image cards don't say which gallery they're in, so that link is added from the scope.
     */
    private fun storeScoped(kind: EntityKind, items: List<JsonObject>, scope: Scope) {
        for (o in items) {
            val linked = if (kind == EntityKind.IMAGES && scope is Scope.Gallery && o["galleries"] == null) {
                JsonObject(o + ("galleries" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(scope.id)))))))
            } else o
            if (kind == EntityKind.MARKERS) continue // markers live inside their scene
            library.put(kind, linked)
        }
    }

    /** Does the actual saving. Suspends until done; also used by the background refresh. */
    suspend fun run(c: OfflineCollection): Int = withContext(Dispatchers.IO) {
        if (repo.isOffline) throw StashException("You're in offline mode. Go online to save lists.")
        try {
            progress.value = Progress(c.label, 0, 0)
            val kind = c.entityKind
            val todo = walk(c)
            storeScoped(kind, todo, c.scope)

            val loader = SingletonImageLoader.get(context)
            suspend fun fetch(url: String?) {
                if (url.isNullOrBlank()) return
                loader.execute(
                    ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).build(),
                )
            }
            /** The first page of a detail screen's related grid, stored with its link. */
            suspend fun related(k: EntityKind, s: Scope) {
                runCatching {
                    val page = repo.browseRaw(k, s, BrowseSpec.defaultSort(k, s), 1, 40)
                    storeScoped(k, page.items, s)
                }
            }
            // Everything the saved entries link to, so their links still open offline.
            val refs = LinkedHashMap<EntityKind, LinkedHashSet<String>>()
            fun ref(k: EntityKind, id: String?) { if (!id.isNullOrBlank()) refs.getOrPut(k) { LinkedHashSet() }.add(id) }
            fun collectRefs(o: JsonObject) = with(OfflineQuery) {
                o.a("performers").forEach { ref(EntityKind.PERFORMERS, it.s("id")) }
                ref(EntityKind.STUDIOS, o.o("studio")?.s("id"))
                o.a("tags").forEach { ref(EntityKind.TAGS, it.s("id")) }
                o.a("galleries").forEach { ref(EntityKind.GALLERIES, it.s("id")) }
                o.a("groups").forEach { ref(EntityKind.GROUPS, it.o("group")?.s("id")) }
            }
            suspend fun detail(k: EntityKind, id: String): JsonObject? =
                runCatching { repo.rawDetail(k, id) }.getOrNull()?.also { o ->
                    library.put(k, o)
                    collectRefs(o)
                }

            val sceneIds = LinkedHashSet<String>()
            todo.forEachIndexed { i, item ->
                currentCoroutineContext().ensureActive()
                progress.value = Progress(c.label, i, todo.size)
                val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                when (kind) {
                    EntityKind.SCENES -> { detail(kind, id); sceneIds += id }
                    EntityKind.PERFORMERS -> { detail(kind, id); related(EntityKind.SCENES, Scope.Performer(id)) }
                    EntityKind.STUDIOS -> { detail(kind, id); related(EntityKind.SCENES, Scope.Studio(id)) }
                    EntityKind.TAGS -> { detail(kind, id); related(EntityKind.SCENES, Scope.Tag(id)) }
                    EntityKind.GROUPS -> { detail(kind, id); related(EntityKind.SCENES, Scope.Group(id)) }
                    EntityKind.GALLERIES -> { detail(kind, id); related(EntityKind.IMAGES, Scope.Gallery(id)) }
                    EntityKind.IMAGES -> detail(kind, id)
                    EntityKind.MARKERS -> (item["scene"] as? JsonObject)?.let { sc ->
                        (sc["id"] as? JsonPrimitive)?.contentOrNull?.let { sid -> detail(EntityKind.SCENES, sid); sceneIds += sid }
                    }
                }
                // The pictures the list and detail screens show.
                val card = runCatching { repo.cardFor(kind, item) }.getOrNull()
                fetch(card?.image)
                if (c.fullImages && kind == EntityKind.IMAGES && card?.isVideo == false) fetch(card?.fullImage)
            }
            library.markListScenes(sceneIds)

            // The list's owner (the gallery whose images these are, the performer whose scenes…),
            // then what everything links to. Galleries first: theirs links get collected too.
            val owner: Pair<EntityKind, String>? = when (val sc = c.scope) {
                is Scope.Performer -> EntityKind.PERFORMERS to sc.id
                is Scope.Studio -> EntityKind.STUDIOS to sc.id
                is Scope.Tag -> EntityKind.TAGS to sc.id
                is Scope.Gallery -> EntityKind.GALLERIES to sc.id
                is Scope.Group -> EntityKind.GROUPS to sc.id
                is Scope.Scene -> EntityKind.SCENES to sc.id
                Scope.None -> null
            }
            owner?.let { (k, id) -> ref(k, id) }
            for (k in listOf(EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.GROUPS, EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS)) {
                val ids = refs[k]?.toList().orEmpty()
                ids.forEachIndexed { i, id ->
                    currentCoroutineContext().ensureActive()
                    val isOwner = owner == (k to id)
                    // Already on the phone: leave it, except the owner, which is refreshed.
                    if (!isOwner && library.has(k, id)) return@forEachIndexed
                    progress.value = Progress("${c.label} · linked ${k.label.lowercase()}", i, ids.size)
                    val o = detail(k, id) ?: return@forEachIndexed
                    runCatching { repo.cardFor(k, o) }.getOrNull()?.let { fetch(it.image) }
                    if (k == EntityKind.SCENES) sceneIds += id
                }
            }
            library.markListScenes(sceneIds)

            // Scene lists can download the videos too.
            val quality = c.downloadQuality
            if (quality != null && kind == EntityKind.SCENES) {
                val dl = downloads()
                for (id in sceneIds) {
                    currentCoroutineContext().ensureActive()
                    // Already downloaded, or on its way: leave it be (failed ones get another go).
                    if (dl.get(id)?.let { it.status != SceneDownload.FAILED } == true) continue
                    runCatching {
                        val scene = repo.scene(id)
                        dl.optionFor(scene, quality)?.let { dl.enqueue(scene, it) }
                    }
                }
            }

            library.flush()
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
