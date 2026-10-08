package com.frameender.pocketstash.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A play or watch-time update made while offline, sent to Stash once it's reachable again. */
@Serializable
data class PendingActivity(
    val sceneId: String,
    /** "play" (play count +1) or "activity" (resume point and watch time). */
    val type: String,
    val resume: Double? = null,
    val duration: Double? = null,
    val at: Long = System.currentTimeMillis(),
)

/**
 * The phone's own copy of part of your Stash library: everything downloaded scenes need,
 * plus whatever was saved for offline. In offline mode it is the whole database (see
 * [OfflineQuery] for searching and sorting it).
 *
 * Entities are stored exactly as Stash's detail queries return them, one JSON file per kind
 * under files/offline/, kept in memory while the app runs. Pictures that downloads need are
 * saved as real files under files/offline/media/ so they never get evicted like cached images.
 */
class OfflineLibrary(context: Context, private val scope: CoroutineScope) {

    private val dir = File(context.filesDir, "offline").apply { mkdirs() }
    private val mediaDir = File(dir, "media").apply { mkdirs() }

    /** The kinds that are stored (markers are derived from scenes). */
    private val storedKinds = listOf(
        EntityKind.SCENES, EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS,
        EntityKind.GROUPS, EntityKind.GALLERIES, EntityKind.IMAGES,
    )
    private val maps: Map<EntityKind, ConcurrentHashMap<String, JsonObject>> =
        storedKinds.associateWith { ConcurrentHashMap<String, JsonObject>() }

    /** Original image path (no host, no query) → saved file name. */
    private val media = ConcurrentHashMap<String, String>()

    /** Scenes stored by a saved list (so deleting their download keeps their info). */
    private val listScenes = ConcurrentHashMap.newKeySet<String>()

    private val pending = ArrayList<PendingActivity>()

    private val writeLock = Mutex()
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    private var saveJob: Job? = null

    /** Bumped on every change; screens showing offline data can watch it. */
    val version = MutableStateFlow(0L)

    init {
        storedKinds.forEach { kind -> load(file(kind))?.forEach { (id, el) -> (el as? JsonObject)?.let { maps[kind]!![id] = it } } }
        load(File(dir, "media.json"))?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { media[k] = it } }
        load(File(dir, "lists.json"))?.keys?.let { listScenes.addAll(it) }
        runCatching {
            val f = File(dir, "pending.json")
            if (f.exists()) pending += StashJson.decodeFromString(ListSerializer(PendingActivity.serializer()), f.readText())
        }
    }

    private fun file(kind: EntityKind) = File(dir, kind.name.lowercase() + ".json")

    private fun load(f: File): JsonObject? =
        if (!f.exists()) null else runCatching { StashJson.parseToJsonElement(f.readText()).jsonObject }.getOrNull()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** Writes changed files a moment after the last change (batches bursts of puts). */
    private fun changed(vararg what: String) {
        dirty.addAll(what)
        version.value = version.value + 1
        snapshotCache = null
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(400)
            flush()
        }
    }

    /** Writes everything that changed. Safe to call any time. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val todo = dirty.toList()
            dirty.removeAll(todo.toSet())
            for (name in todo) {
                when (name) {
                    "media" -> writeAtomically(File(dir, "media.json"), JsonObject(media.mapValues { JsonPrimitive(it.value) }).toString())
                    "lists" -> writeAtomically(File(dir, "lists.json"), JsonObject(listScenes.associateWith { JsonPrimitive(true) }).toString())
                    "pending" -> writeAtomically(
                        File(dir, "pending.json"),
                        StashJson.encodeToString(ListSerializer(PendingActivity.serializer()), synchronized(pending) { pending.toList() }),
                    )
                    else -> storedKinds.firstOrNull { it.name == name }?.let { kind ->
                        writeAtomically(file(kind), JsonObject(maps[kind]!!.toMap()).toString())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ entities

    private fun JsonObject.id(): String? = (this["id"] as? JsonPrimitive)?.contentOrNull

    /** Stores an entity, merging into what's already there (newer fields win). */
    fun put(kind: EntityKind, obj: JsonObject) {
        val map = maps[kind] ?: return
        val id = obj.id() ?: return
        map[id] = map[id]?.let { merge(it, obj) } ?: obj
        changed(kind.name)
    }

    /** Replaces an entity only if it's already stored (keeps the phone's copy fresh while online). */
    fun refreshIfStored(kind: EntityKind, obj: JsonObject) {
        val map = maps[kind] ?: return
        val id = obj.id() ?: return
        if (map.containsKey(id)) put(kind, obj)
    }

    fun has(kind: EntityKind, id: String): Boolean = maps[kind]?.containsKey(id) == true

    fun markListScenes(ids: Collection<String>) {
        if (listScenes.addAll(ids)) changed("lists")
    }

    /** Forgets a scene that was only kept for its download. */
    fun removeSceneUnlessListed(id: String) {
        if (id in listScenes) return
        if (maps[EntityKind.SCENES]!!.remove(id) != null) changed(EntityKind.SCENES.name)
    }

    fun isEmpty(): Boolean = maps.values.all { it.isEmpty() }

    fun count(kind: EntityKind): Int = maps[kind]?.size ?: 0

    // ------------------------------------------------------------------ queries

    @Volatile private var snapshotCache: Pair<Set<String>, OfflineQuery.Snapshot>? = null

    /** The current contents as a query snapshot (rebuilt only when something changed). */
    fun snapshot(downloaded: Set<String>): OfflineQuery.Snapshot {
        snapshotCache?.let { (d, s) -> if (d == downloaded) return s }
        val s = OfflineQuery.Snapshot(
            scenes = maps[EntityKind.SCENES]!!.toMap(),
            performers = maps[EntityKind.PERFORMERS]!!.toMap(),
            studios = maps[EntityKind.STUDIOS]!!.toMap(),
            tags = maps[EntityKind.TAGS]!!.toMap(),
            groups = maps[EntityKind.GROUPS]!!.toMap(),
            galleries = maps[EntityKind.GALLERIES]!!.toMap(),
            images = maps[EntityKind.IMAGES]!!.toMap(),
            downloaded = downloaded,
        )
        snapshotCache = downloaded to s
        return s
    }

    // ------------------------------------------------------------------ pictures

    /** Stable key for an image URL: its path without host or "?t=" cache-buster. */
    fun mediaKey(raw: String): String {
        val url = raw.toHttpUrlOrNull()
        return url?.encodedPath ?: raw.substringBefore('?')
    }

    /** A saved picture for this URL, as a file:// URI, or null. */
    fun localImage(raw: String): String? {
        val name = media[mediaKey(raw)] ?: return null
        val f = File(mediaDir, name)
        return if (f.exists()) "file://" + f.absolutePath else null
    }

    /**
     * Saves the picture at [raw] (a URL Stash returned) as a file, if it isn't saved yet.
     * [resolve] turns it into a URL this phone can reach.
     */
    suspend fun saveImage(raw: String?, http: OkHttpClient, resolve: (String) -> String?) = withContext(Dispatchers.IO) {
        if (raw.isNullOrBlank()) return@withContext
        val key = mediaKey(raw)
        media[key]?.let { if (File(mediaDir, it).exists()) return@withContext }
        val url = resolve(raw) ?: return@withContext
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use
                val body = r.body ?: return@use
                val tmp = File(mediaDir, "$name.part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                if (tmp.renameTo(File(mediaDir, name))) {
                    media[key] = name
                    changed("media")
                }
            }
        }
    }

    // ------------------------------------------------------------------ play activity while offline

    private fun patchScene(id: String, transform: (JsonObject) -> JsonObject) {
        val map = maps[EntityKind.SCENES]!!
        val cur = map[id] ?: return
        map[id] = transform(cur)
        changed(EntityKind.SCENES.name)
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** Play count +1 on the phone's copy; queued for Stash when [queue] is true. */
    fun recordPlay(sceneId: String, queue: Boolean) {
        patchScene(sceneId) { s ->
            val n = ((s["play_count"] as? JsonPrimitive)?.longOrNull ?: 0L) + 1
            JsonObject(s + mapOf("play_count" to JsonPrimitive(n), "last_played_at" to JsonPrimitive(java.time.Instant.now().toString())))
        }
        if (queue) {
            synchronized(pending) { pending += PendingActivity(sceneId, "play") }
            changed("pending")
        }
    }

    /** Resume point and watch time on the phone's copy; queued for Stash when [queue] is true. */
    fun recordActivity(sceneId: String, resume: Double?, played: Double?, queue: Boolean) {
        patchScene(sceneId) { s ->
            val extra = HashMap<String, JsonElement>()
            if (resume != null) extra["resume_time"] = JsonPrimitive(resume)
            if (played != null) extra["play_duration"] = JsonPrimitive((s.num("play_duration") ?: 0.0) + played)
            JsonObject(s + extra)
        }
        if (queue) {
            synchronized(pending) { pending += PendingActivity(sceneId, "activity", resume, played) }
            changed("pending")
        }
    }

    fun pending(): List<PendingActivity> = synchronized(pending) { pending.toList() }

    fun pendingCount(): Int = synchronized(pending) { pending.size }

    /** Drops the first [n] queued updates (they were sent). */
    fun dropPending(n: Int) {
        synchronized(pending) { repeat(minOf(n, pending.size)) { pending.removeAt(0) } }
        changed("pending")
    }

    // ------------------------------------------------------------------ housekeeping

    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Keeps only what [keepScenes] need: those scenes, the performers, studios, tags and
     * groups they mention, and their pictures. Everything from saved lists goes.
     */
    fun pruneTo(keepScenes: Set<String>) {
        val scenes = maps[EntityKind.SCENES]!!
        scenes.keys.retainAll(keepScenes)
        val keep = HashMap<EntityKind, MutableSet<String>>()
        fun keepRef(kind: EntityKind, ref: JsonObject?) {
            ref?.id()?.let { keep.getOrPut(kind) { HashSet() }.add(it) }
        }
        with(OfflineQuery) {
            for (s in scenes.values) {
                s.a("performers").forEach { keepRef(EntityKind.PERFORMERS, it) }
                keepRef(EntityKind.STUDIOS, s.o("studio"))
                s.a("tags").forEach { keepRef(EntityKind.TAGS, it) }
                s.a("groups").forEach { keepRef(EntityKind.GROUPS, it.o("group")) }
            }
        }
        for (kind in listOf(EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS, EntityKind.GROUPS)) {
            maps[kind]!!.keys.retainAll(keep[kind].orEmpty())
        }
        maps[EntityKind.GALLERIES]!!.clear()
        maps[EntityKind.IMAGES]!!.clear()
        listScenes.clear()

        // Pictures still referenced by what's left.
        val wanted = HashSet<String>()
        fun collect(el: JsonElement?) {
            when (el) {
                is JsonObject -> el.forEach { (k, v) ->
                    if (v is JsonPrimitive && k in IMAGE_FIELDS) v.contentOrNull?.let { wanted += mediaKey(it) } else collect(v)
                }
                is kotlinx.serialization.json.JsonArray -> el.forEach { collect(it) }
                else -> Unit
            }
        }
        maps.values.forEach { m -> m.values.forEach { collect(it) } }
        val drop = media.keys.filter { it !in wanted }
        drop.forEach { k -> media.remove(k)?.let { File(mediaDir, it).delete() } }

        storedKinds.forEach { changed(it.name) }
        changed("media", "lists")
    }

    /** Forgets everything (used when disconnecting from the server). */
    fun clearAll() {
        maps.values.forEach { it.clear() }
        media.clear()
        listScenes.clear()
        synchronized(pending) { pending.clear() }
        mediaDir.listFiles()?.forEach { it.delete() }
        storedKinds.forEach { changed(it.name) }
        changed("media", "lists", "pending")
    }

    companion object {
        /** JSON fields that hold picture URLs. */
        val IMAGE_FIELDS = setOf("screenshot", "image_path", "front_image_path", "back_image_path", "cover", "thumbnail")
    }
}

/**
 * Combines a stored record with a newer copy. New values win, but a list card never thins out a
 * detailed record: nested objects merge field by field, and lists of linked entities (performers,
 * tags, galleries…) keep the extra fields of entries that are still linked.
 */
internal fun merge(old: JsonObject, new: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(old)
    for ((k, v) in new) {
        val prev = old[k]
        out[k] = when {
            prev is JsonObject && v is JsonObject && prev["id"] == v["id"] -> merge(prev, v)
            prev is JsonArray && v is JsonArray -> {
                val byId = prev.mapNotNull { e -> (e as? JsonObject)?.let { o -> o["id"]?.let { it to o } } }.toMap()
                if (byId.isEmpty()) v
                else JsonArray(v.map { e ->
                    val o = e as? JsonObject
                    val match = o?.get("id")?.let { byId[it] }
                    if (o != null && match != null) merge(match, o) else e
                })
            }
            else -> v
        }
    }
    return JsonObject(out)
}
