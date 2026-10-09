package com.frameender.pocketstash.data

import android.content.Context
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import com.frameender.pocketstash.MainActivity
import com.frameender.pocketstash.R
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.coroutines.cancelAndJoin
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

    /** The save running right now (so "Stop" and removing the list can cancel just that one). */
    @Volatile private var current: Job? = null

    /** The collection being saved right now (by the screen or the daily refresh). */
    @Volatile private var runningKey: String? = null

    /** Collections removed while a save of them may still be running: that save must not bring them back. */
    private val forgotten = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    val running: Boolean get() = runningKey != null

    // ---------------- the save queue ----------------

    /**
     * Lists waiting to be saved, one after another. Kept in a file so a queue survives the app
     * being closed; worked through by [OfflineSaveWorker] in the foreground (with a
     * notification), so saving carries on while you use other apps.
     */
    private val queueFile = java.io.File(context.filesDir, "save_queue.json")
    private val _queue = MutableStateFlow(loadQueue())
    val queue: StateFlow<List<OfflineCollection>> = _queue

    private fun loadQueue(): List<OfflineCollection> = runCatching {
        if (queueFile.exists()) StashJson.decodeFromString(listSer, queueFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun persistQueue() {
        val text = StashJson.encodeToString(listSer, _queue.value)
        scope.launch(Dispatchers.IO) {
            synchronized(queueFile) {
                val tmp = java.io.File(queueFile.parentFile, queueFile.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(queueFile)) { queueFile.delete(); tmp.renameTo(queueFile) }
            }
        }
    }

    /** Adds lists to the queue (skipping ones already waiting or being saved) and starts working. */
    fun enqueue(lists: List<OfflineCollection>, quiet: Boolean = false) {
        val added: List<OfflineCollection>
        synchronized(this) {
            val taken = _queue.value.map { it.key }.toSet() + listOfNotNull(runningKey)
            added = lists.filter { it.key !in taken }.distinctBy { it.key }
            if (added.isEmpty()) {
                if (!quiet) toast("“${lists.firstOrNull()?.label}” is already in the save queue")
                return
            }
            forgotten.removeAll(added.map { it.key }.toSet())
            _queue.value = _queue.value + added
            persistQueue()
        }
        if (!quiet) {
            val waiting = _queue.value.size + (if (running) 1 else 0) - 1
            toast(
                if (waiting <= 0) "Saving “${added.first().label}” for offline"
                else "Added “${added.first().label}” to the save queue · $waiting ahead of it",
            )
        }
        startWorker()
    }

    /** Takes a waiting list off the queue (doesn't touch one that's being saved). */
    fun dequeue(c: OfflineCollection) {
        synchronized(this) {
            _queue.value = _queue.value.filter { it.key != c.key }
            persistQueue()
        }
    }

    fun clearQueue() {
        synchronized(this) {
            _queue.value = emptyList()
            persistQueue()
        }
    }

    /** Starts (or continues) working through the queue. Safe to call any time. */
    fun startWorker() {
        if (_queue.value.isEmpty()) return
        val req = OneTimeWorkRequestBuilder<OfflineSaveWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SAVE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun takeNext(): OfflineCollection? = synchronized(this) {
        val next = _queue.value.firstOrNull() ?: return null
        _queue.value = _queue.value.drop(1)
        persistQueue()
        next
    }

    private fun putBack(c: OfflineCollection) = synchronized(this) {
        if (_queue.value.none { it.key == c.key } && c.key !in forgotten) {
            _queue.value = listOf(c) + _queue.value
            persistQueue()
        }
    }

    /** Works through the queue, one list at a time. Called by [OfflineSaveWorker]. */
    suspend fun drain(worker: CoroutineWorker) = supervisorScope {
        // (Supervisor: one list failing mustn't stop the rest of the queue.)
        ensureChannel()
        var saved = 0
        var failed = 0
        while (isActive) {
            val c = takeNext() ?: break
            // Keep the notification in step with progress.
            val ticker = launch {
                while (isActive) {
                    runCatching { worker.setForeground(foregroundInfo(c, progress.value, _queue.value.size)) }
                    delay(1_000)
                }
            }
            val job = async(Dispatchers.IO) { run(c) }
            current = job
            try {
                val n = job.await()
                if (c.key !in forgotten) {
                    saved++
                    toast("Saved $n from “${c.label}” for offline")
                }
            } catch (e: CancellationException) {
                if (!isActive) {
                    // The worker itself was stopped (paused, or the network went): try again later.
                    putBack(c)
                    throw e
                }
                // Only this list was stopped ("Stop", or it was removed): carry on with the next.
                if (c.key !in forgotten) toast("Stopped saving “${c.label}”")
            } catch (e: Exception) {
                failed++
                toast("“${c.label}”: " + (e.message ?: "saving for offline failed"))
            } finally {
                current = null
                ticker.cancel()
            }
        }
        notifyFinished(saved, failed)
    }

    // ---------------- notifications ----------------

    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (nm.getNotificationChannel(SAVE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(SAVE_CHANNEL, "Saving for offline", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of lists being saved for offline"
                },
            )
        }
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundInfo(c: OfflineCollection, p: Progress?, waiting: Int): ForegroundInfo {
        val b = NotificationCompat.Builder(context, SAVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Saving “${c.label}”")
            .setContentText(
                listOfNotNull(
                    p?.takeIf { it.total > 0 }?.let { "${it.done} of ${it.total}" } ?: "Getting the list…",
                    if (waiting > 0) "$waiting more waiting" else null,
                ).joinToString(" · "),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent())
            .addAction(0, "Pause", WorkManager.getInstance(context).createCancelPendingIntent(workerId ?: java.util.UUID.randomUUID()))
        if (p != null && p.total > 0) b.setProgress(p.total, p.done, false) else b.setProgress(0, 0, true)
        val n = b.build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(SAVE_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(SAVE_NOTIF, n)
    }

    /** Set by the worker so the notification's Pause button can stop it. */
    @Volatile var workerId: java.util.UUID? = null

    private fun notifyFinished(saved: Int, failed: Int) {
        if (saved + failed == 0) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, SAVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (saved == 1) "1 list saved for offline" else "$saved lists saved for offline")
            .setContentText(if (failed > 0) "$failed couldn't be saved. Try again from Storage & offline." else "Ready to browse without your server")
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SAVE_DONE_NOTIF, n) }
    }

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

    /**
     * Takes a collection off the list and removes the info it saved, except what another saved
     * list or a download still uses. Thumbnails in the image cache age out on their own.
     */
    fun forget(c: OfflineCollection) {
        scope.launch {
            forgotten += c.key
            dequeue(c)
            if (runningKey == c.key) current?.cancelAndJoin()
            update { s -> s.copy(offlineCollections = StashJson.encodeToString(listSer, collections(s).filter { it.key != c.key })) }
            withContext(Dispatchers.IO) {
                library.release(ownerOf(c))
                library.flush()
            }
        }
    }

    /** The owner tag this collection's saved info carries in the offline library. */
    private fun ownerOf(c: OfflineCollection) = "list:" + c.key

    // ---------------- saving ----------------

    /** Queues a list for saving (from a screen). Several can wait their turn. */
    fun save(c: OfflineCollection) = enqueue(listOf(c))

    /** Re-saves a remembered collection with its original options. */
    fun refresh(c: OfflineCollection) = enqueue(listOf(c))

    private fun toast(text: String) {
        scope.launch(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    /** Stops the list being saved right now; the rest of the queue carries on. */
    fun cancel() {
        current?.cancel()
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
    private fun storeScoped(kind: EntityKind, items: List<JsonObject>, scope: Scope, keep: (EntityKind, JsonObject) -> Unit) {
        for (o in items) {
            val linked = if (kind == EntityKind.IMAGES && scope is Scope.Gallery && o["galleries"] == null) {
                JsonObject(o + ("galleries" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(scope.id)))))))
            } else o
            if (kind == EntityKind.MARKERS) continue // markers live inside their scene
            keep(kind, linked)
        }
    }

    /** Does the actual saving. Suspends until done; also used by the background refresh. */
    suspend fun run(c: OfflineCollection): Int = withContext(Dispatchers.IO) {
        if (repo.isOffline) throw StashException("You're in offline mode. Go online to save lists.")
        runningKey = c.key
        try {
            progress.value = Progress(c.label, 0, 0)
            val kind = c.entityKind
            // Everything this save stores is tagged as kept by this list (see OfflineLibrary.retain).
            val owner = ownerOf(c)
            val seen = HashSet<String>()
            fun keep(k: EntityKind, o: JsonObject) {
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return
                library.put(k, o, owner)
                seen += library.entityKey(k, id)
            }
            val todo = walk(c)
            storeScoped(kind, todo, c.scope, ::keep)

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
                    storeScoped(k, page.items, s, ::keep)
                }
            }
            /**
             * Every image in a gallery (not just the first page), as it was when the save started:
             * the list, each image's thumbnail, and the full image too if that was asked for.
             */
            /**
             * An image's thumbnail (and the full image, if asked for) saved as real files: never
             * evicted like the image cache, and removed with the list.
             */
            suspend fun keepPictures(img: JsonObject) {
                val conn = repo.connection
                val paths = img["paths"] as? JsonObject
                fun raw(key: String) = (paths?.get(key) as? JsonPrimitive)?.contentOrNull
                val isVideo = runCatching { repo.cardFor(EntityKind.IMAGES, img).isVideo }.getOrDefault(false)
                runCatching { library.saveImage(raw("thumbnail"), conn.http) { conn.media(it) } }
                if (c.fullImages && !isVideo) runCatching { library.saveImage(raw("image"), conn.http) { conn.media(it) } }
            }

            suspend fun everyImage(galleryId: String, index: Int, galleries: Int) {
                val gScope = Scope.Gallery(galleryId)
                val sort = BrowseSpec.defaultSort(EntityKind.IMAGES, gScope)
                val perPage = 100
                var page = 1
                var count = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val p = runCatching { repo.browseRaw(EntityKind.IMAGES, gScope, sort, page, perPage) }.getOrNull() ?: break
                    storeScoped(EntityKind.IMAGES, p.items, gScope, ::keep)
                    for (img in p.items) {
                        currentCoroutineContext().ensureActive()
                        count++
                        progress.value = Progress("${c.label} · gallery ${index + 1} of $galleries", count, p.total)
                        keepPictures(img)
                    }
                    if (p.items.size < perPage || count >= p.total) break
                    page++
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
                    keep(k, o)
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
                    EntityKind.GALLERIES -> { detail(kind, id); everyImage(id, i, todo.size) }
                    EntityKind.IMAGES -> { detail(kind, id); keepPictures(item) }
                    EntityKind.MARKERS -> (item["scene"] as? JsonObject)?.let { sc ->
                        (sc["id"] as? JsonPrimitive)?.contentOrNull?.let { sid -> detail(EntityKind.SCENES, sid); sceneIds += sid }
                    }
                }
                // The pictures the list and detail screens show.
                if (kind != EntityKind.IMAGES) {
                    val card = runCatching { repo.cardFor(kind, item) }.getOrNull()
                    fetch(card?.image)
                }
            }
            library.markListScenes(sceneIds)

            // The list's owner (the gallery whose images these are, the performer whose scenes…),
            // then what everything links to. Galleries first: theirs links get collected too.
            val scopeOwner: Pair<EntityKind, String>? = when (val sc = c.scope) {
                is Scope.Performer -> EntityKind.PERFORMERS to sc.id
                is Scope.Studio -> EntityKind.STUDIOS to sc.id
                is Scope.Tag -> EntityKind.TAGS to sc.id
                is Scope.Gallery -> EntityKind.GALLERIES to sc.id
                is Scope.Group -> EntityKind.GROUPS to sc.id
                is Scope.Scene -> EntityKind.SCENES to sc.id
                Scope.None -> null
            }
            scopeOwner?.let { (k, id) -> ref(k, id) }
            for (k in listOf(EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.GROUPS, EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS)) {
                val ids = refs[k]?.toList().orEmpty()
                ids.forEachIndexed { i, id ->
                    currentCoroutineContext().ensureActive()
                    val isOwner = scopeOwner == (k to id)
                    // Already on the phone: just note this list keeps it too. The owner is refreshed.
                    if (!isOwner && library.has(k, id)) {
                        library.claim(k, id, owner)
                        seen += library.entityKey(k, id)
                        return@forEachIndexed
                    }
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

            if (c.key in forgotten) {
                // Removed while this was saving: undo it instead of bringing the list back.
                library.release(owner)
                library.flush()
                return@withContext 0
            }
            // Finished: whatever this list kept last time but no longer contains is let go.
            library.retain(owner, seen)
            library.flush()
            remember(c.copy(count = todo.size, savedAt = System.currentTimeMillis()))
            todo.size
        } finally {
            runningKey = null
            progress.value = null
        }
    }
}

private const val SAVE_WORK = "pocketstash-offline-save"
private const val SAVE_CHANNEL = "offline_save"
private const val SAVE_NOTIF = 4840
private const val SAVE_DONE_NOTIF = 4841

/** Daily re-save of every remembered collection, on Wi-Fi while charging. */
class OfflineRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.filterNotNull().first()
        if (!s.offlineAutoRefresh || !s.isConfigured) return Result.success()
        // Through the same queue as saves made by hand, so they never run at the same time.
        c.offlineSaver.enqueue(c.offlineSaver.collections(s), quiet = true)
        return Result.success()
    }
}

/** Works through the save queue in the foreground, so saving continues with the app in the background. */
class OfflineSaveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val saver = applicationContext.container.offlineSaver
        saver.workerId = id
        try {
            saver.drain(this)
        } finally {
            saver.workerId = null
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
