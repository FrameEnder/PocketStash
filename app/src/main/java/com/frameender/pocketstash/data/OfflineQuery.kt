package com.frameender.pocketstash.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * The query engine behind offline mode: answers the same questions the server would
 * (search, every sort including a stable random, quick filters, "scenes of this performer"…)
 * from the entities stored on the phone. Pure Kotlin over raw Stash JSON, so it's easy to test.
 *
 * Entities are kept in the exact shape Stash's detail queries return, so the rest of the app
 * decodes and renders them unchanged. Things the phone only knows second-hand are derived:
 *  - markers come from each stored scene's scene_markers
 *  - a performer / studio / tag / group that's only mentioned by a stored scene still shows up,
 *    built from that mention ("stub")
 *  - counts (scene_count…) are recounted from what's on the phone, so tabs and badges match
 */
object OfflineQuery {

    /** Everything stored, by kind, plus which scenes have their video downloaded. */
    class Snapshot(
        val scenes: Map<String, JsonObject> = emptyMap(),
        val performers: Map<String, JsonObject> = emptyMap(),
        val studios: Map<String, JsonObject> = emptyMap(),
        val tags: Map<String, JsonObject> = emptyMap(),
        val groups: Map<String, JsonObject> = emptyMap(),
        val galleries: Map<String, JsonObject> = emptyMap(),
        val images: Map<String, JsonObject> = emptyMap(),
        val downloaded: Set<String> = emptySet(),
    ) {
        internal val index: Index by lazy { Index(this) }
    }

    // ------------------------------------------------------------------ JSON helpers

    internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    internal fun JsonObject.l(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    internal fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    internal fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    internal fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
    internal fun JsonObject.a(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun JsonObject.ids(key: String): List<String> = a(key).mapNotNull { it.s("id") }
    private fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

    private fun firstFile(o: JsonObject): JsonObject? = o.a("files").firstOrNull() ?: o.a("visual_files").firstOrNull()

    private fun baseName(path: String?): String? = path?.substringAfterLast('/')?.substringAfterLast('\\')

    // ------------------------------------------------------------------ relations index

    /** Who links to whom, computed once per snapshot. */
    internal class Index(snap: Snapshot) {
        val scenesByPerformer = HashMap<String, MutableSet<String>>()
        val scenesByStudio = HashMap<String, MutableSet<String>>()
        val scenesByTag = HashMap<String, MutableSet<String>>()
        val scenesByGroup = HashMap<String, MutableSet<String>>()
        val markers = ArrayList<JsonObject>()

        // Stubs for entities only mentioned by something stored.
        val performerStubs = HashMap<String, JsonObject>()
        val studioStubs = HashMap<String, JsonObject>()
        val tagStubs = HashMap<String, JsonObject>()
        val groupStubs = HashMap<String, JsonObject>()

        init {
            fun add(map: HashMap<String, MutableSet<String>>, key: String, id: String) {
                map.getOrPut(key) { LinkedHashSet() }.add(id)
            }
            fun stub(map: HashMap<String, JsonObject>, ref: JsonObject?) {
                val id = ref?.s("id") ?: return
                map[id] = map[id]?.let { JsonObject(it + ref) } ?: ref
            }
            for ((sid, s) in snap.scenes) {
                s.a("performers").forEach { p -> p.s("id")?.let { add(scenesByPerformer, it, sid) }; stub(performerStubs, p) }
                s.o("studio")?.let { st -> st.s("id")?.let { add(scenesByStudio, it, sid) }; stub(studioStubs, st) }
                s.a("tags").forEach { t -> t.s("id")?.let { add(scenesByTag, it, sid) }; stub(tagStubs, t) }
                s.a("groups").forEach { g ->
                    val grp = g.o("group")
                    grp?.s("id")?.let { add(scenesByGroup, it, sid) }
                    stub(groupStubs, grp)
                }
                // Markers: each carries a small copy of its scene, like Stash's marker queries.
                val sceneRef = JsonObject(
                    buildMap {
                        put("id", JsonPrimitive(sid))
                        s["title"]?.let { put("title", it) }
                        s["updated_at"]?.let { put("updated_at", it) }
                        s["files"]?.let { put("files", it) }
                        s["paths"]?.let { put("paths", it) }
                        s["performers"]?.let { put("performers", it) }
                        s["tags"]?.let { put("scene_tags", it) }
                    },
                )
                s.a("scene_markers").forEach { m ->
                    m.a("tags").forEach { stub(tagStubs, it) }
                    stub(tagStubs, m.o("primary_tag"))
                    markers += JsonObject(m + ("scene" to sceneRef))
                }
            }
            for (g in snap.galleries.values) {
                g.a("performers").forEach { stub(performerStubs, it) }
                stub(studioStubs, g.o("studio"))
                g.a("tags").forEach { stub(tagStubs, it) }
            }
            for (i in snap.images.values) {
                i.a("performers").forEach { stub(performerStubs, it) }
                stub(studioStubs, i.o("studio"))
                i.a("tags").forEach { stub(tagStubs, it) }
            }
            for (st in snap.studios.values) st.a("tags").forEach { stub(tagStubs, it) }
            for (p in snap.performers.values) p.a("tags").forEach { stub(tagStubs, it) }
            for (gr in snap.groups.values) {
                stub(studioStubs, gr.o("studio"))
                gr.a("tags").forEach { stub(tagStubs, it) }
            }
        }
    }

    // ------------------------------------------------------------------ entity lists

    private fun merged(stored: Map<String, JsonObject>, stubs: Map<String, JsonObject>): List<JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        stubs.forEach { (id, o) -> out[id] = o }
        stored.forEach { (id, o) -> out[id] = out[id]?.let { JsonObject(it + o) } ?: o }
        return out.values.toList()
    }

    /** Every entity of [kind] the phone knows about, with counts recomputed locally. */
    fun all(kind: EntityKind, snap: Snapshot): List<JsonObject> {
        val ix = snap.index
        return when (kind) {
            EntityKind.SCENES -> snap.scenes.values.toList()
            EntityKind.MARKERS -> ix.markers
            EntityKind.IMAGES -> snap.images.values.toList()
            EntityKind.GALLERIES -> snap.galleries.values.map { g ->
                val id = g.s("id")
                val n = snap.images.values.count { i -> i.ids("galleries").contains(id) }
                if (n > 0) g.with("image_count" to JsonPrimitive(n)) else g
            }
            EntityKind.PERFORMERS -> merged(snap.performers, ix.performerStubs).map { p ->
                val id = p.s("id") ?: ""
                p.with(
                    "scene_count" to JsonPrimitive(ix.scenesByPerformer[id]?.size ?: 0),
                    "image_count" to JsonPrimitive(snap.images.values.count { it.ids("performers").contains(id) }),
                    "gallery_count" to JsonPrimitive(snap.galleries.values.count { it.ids("performers").contains(id) }),
                    "group_count" to JsonPrimitive(groupsWithPerformer(id, snap).size),
                )
            }
            EntityKind.STUDIOS -> merged(snap.studios, ix.studioStubs).map { st ->
                val id = st.s("id") ?: ""
                val scenes = ix.scenesByStudio[id].orEmpty()
                st.with(
                    "scene_count" to JsonPrimitive(scenes.size),
                    "image_count" to JsonPrimitive(snap.images.values.count { it.o("studio")?.s("id") == id }),
                    "gallery_count" to JsonPrimitive(snap.galleries.values.count { it.o("studio")?.s("id") == id }),
                    "performer_count" to JsonPrimitive(scenes.flatMap { snap.scenes[it]?.ids("performers").orEmpty() }.toSet().size),
                    "group_count" to JsonPrimitive(merged(snap.groups, ix.groupStubs).count { it.o("studio")?.s("id") == id }),
                )
            }
            EntityKind.TAGS -> merged(snap.tags, ix.tagStubs).map { t ->
                val id = t.s("id") ?: ""
                t.with(
                    "scene_count" to JsonPrimitive(ix.scenesByTag[id]?.size ?: 0),
                    "scene_marker_count" to JsonPrimitive(ix.markers.count { markerHasTag(it, id) }),
                    "image_count" to JsonPrimitive(snap.images.values.count { it.ids("tags").contains(id) }),
                    "gallery_count" to JsonPrimitive(snap.galleries.values.count { it.ids("tags").contains(id) }),
                    "performer_count" to JsonPrimitive(snap.performers.values.count { it.ids("tags").contains(id) }),
                    "studio_count" to JsonPrimitive(snap.studios.values.count { it.ids("tags").contains(id) }),
                    "group_count" to JsonPrimitive(snap.groups.values.count { it.ids("tags").contains(id) }),
                )
            }
            EntityKind.GROUPS -> merged(snap.groups, ix.groupStubs).map { g ->
                g.with("scene_count" to JsonPrimitive(ix.scenesByGroup[g.s("id") ?: ""]?.size ?: 0))
            }
        }
    }

    /** One entity by id (with local counts), or null if the phone doesn't know it. */
    fun get(kind: EntityKind, id: String, snap: Snapshot): JsonObject? = when (kind) {
        EntityKind.SCENES -> snap.scenes[id]
        EntityKind.IMAGES -> snap.images[id]
        else -> all(kind, snap).firstOrNull { it.s("id") == id }
    }

    private fun groupsWithPerformer(performerId: String, snap: Snapshot): Set<String> =
        snap.index.scenesByPerformer[performerId].orEmpty()
            .flatMap { sid -> snap.scenes[sid]?.a("groups")?.mapNotNull { it.o("group")?.s("id") }.orEmpty() }
            .toSet()

    private fun markerHasTag(m: JsonObject, tagId: String): Boolean =
        m.o("primary_tag")?.s("id") == tagId || m.ids("tags").contains(tagId)

    // ------------------------------------------------------------------ scope

    private fun inScope(kind: EntityKind, scope: Scope, o: JsonObject, snap: Snapshot): Boolean {
        val id = o.s("id")
        return when (scope) {
            Scope.None -> true
            is Scope.Performer -> when (kind) {
                EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.IMAGES -> o.ids("performers").contains(scope.id)
                EntityKind.MARKERS -> o.o("scene")?.ids("performers")?.contains(scope.id) == true
                EntityKind.GROUPS -> id in groupsWithPerformer(scope.id, snap)
                else -> false
            }
            is Scope.Studio -> when (kind) {
                EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.IMAGES, EntityKind.GROUPS -> o.o("studio")?.s("id") == scope.id
                EntityKind.PERFORMERS -> snap.index.scenesByStudio[scope.id].orEmpty()
                    .any { sid -> snap.scenes[sid]?.ids("performers")?.contains(id) == true }
                else -> false
            }
            is Scope.Tag -> when (kind) {
                EntityKind.MARKERS -> markerHasTag(o, scope.id)
                else -> o.ids("tags").contains(scope.id)
            }
            is Scope.Gallery -> kind == EntityKind.IMAGES && o.ids("galleries").contains(scope.id)
            is Scope.Group -> kind == EntityKind.SCENES && o.a("groups").any { it.o("group")?.s("id") == scope.id }
            is Scope.Scene -> kind == EntityKind.MARKERS && o.o("scene")?.s("id") == scope.id
        }
    }

    // ------------------------------------------------------------------ search

    /** The text a search box looks through, per kind. */
    private fun haystack(kind: EntityKind, o: JsonObject): String {
        val parts = ArrayList<String?>()
        when (kind) {
            EntityKind.SCENES -> {
                parts += listOf(o.s("title"), o.s("code"), o.s("details"), o.s("director"))
                o.a("files").forEach { parts += it.s("path") ?: it.s("basename") }
                parts += o.o("studio")?.s("name")
                o.a("performers").forEach { parts += it.s("name") }
                o.a("tags").forEach { parts += it.s("name") }
            }
            EntityKind.PERFORMERS -> {
                parts += listOf(o.s("name"), o.s("disambiguation"))
                (o["alias_list"] as? JsonArray)?.forEach { parts += (it as? JsonPrimitive)?.contentOrNull }
            }
            EntityKind.STUDIOS, EntityKind.TAGS -> {
                parts += o.s("name")
                (o["aliases"] as? JsonArray)?.forEach { parts += (it as? JsonPrimitive)?.contentOrNull }
            }
            EntityKind.GROUPS -> parts += listOf(o.s("name"), o.s("aliases"), o.s("director"))
            EntityKind.GALLERIES -> {
                parts += listOf(o.s("title"), o.s("details"), o.o("folder")?.s("path"))
                o.a("files").forEach { parts += it.s("path") }
            }
            EntityKind.IMAGES -> {
                parts += o.s("title")
                o.a("visual_files").forEach { parts += it.s("path") }
            }
            EntityKind.MARKERS -> parts += listOf(o.s("title"), o.o("primary_tag")?.s("name"), o.o("scene")?.s("title"))
        }
        return parts.filterNotNull().joinToString("\n").lowercase()
    }

    private fun matchesText(kind: EntityKind, o: JsonObject, text: String): Boolean {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = haystack(kind, o)
        return words.all { hay.contains(it) }
    }

    // ------------------------------------------------------------------ quick filters

    private fun matchesQuick(id: String, o: JsonObject, snap: Snapshot): Boolean = when (id) {
        "unwatched" -> (o.l("play_count") ?: 0L) == 0L
        "inprogress" -> (o.d("resume_time") ?: 0.0) > 0.0
        "rated" -> (o.l("rating100") ?: 0L) > 0L
        "organized" -> o.b("organized")
        "unorganized" -> !o.b("organized")
        "fav" -> o.b("favorite")
        "downloaded" -> o.s("id") in snap.downloaded
        else -> true
    }

    // ------------------------------------------------------------------ sorting

    /** Title as the app shows it (falls back to the file name, like the cards do). */
    private fun title(kind: EntityKind, o: JsonObject): String = when (kind) {
        EntityKind.MARKERS -> o.s("title")?.takeIf { it.isNotBlank() } ?: o.o("primary_tag")?.s("name") ?: ""
        EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS, EntityKind.GROUPS -> o.s("name") ?: ""
        else -> o.s("title")?.takeIf { it.isNotBlank() }
            ?: baseName(firstFile(o)?.s("path")) ?: firstFile(o)?.s("basename")
            ?: baseName(o.o("folder")?.s("path")) ?: ""
    }

    /** Most recent date of a stored scene linked to this performer / studio. */
    private fun latestScene(scenes: Set<String>?, snap: Snapshot): String? =
        scenes.orEmpty().mapNotNull { snap.scenes[it]?.s("date") }.maxOrNull()

    private fun sortValue(kind: EntityKind, key: String, o: JsonObject, snap: Snapshot): Comparable<*>? {
        val id = o.s("id") ?: ""
        val ix = snap.index
        return when (key) {
            "title" -> title(kind, o).lowercase()
            "name" -> (o.s("sort_name")?.takeIf { it.isNotBlank() } ?: title(kind, o)).lowercase()
            "date", "birthdate", "created_at", "updated_at", "last_played_at", "career_start", "career_end" -> o.s(key)
            "rating" -> o.l("rating100")
            "play_count", "o_counter", "height", "weight" -> o.l(if (key == "height") "height_cm" else key)
            "duration" -> when (kind) {
                EntityKind.MARKERS -> o.d("end_seconds")?.let { it - (o.d("seconds") ?: 0.0) }
                EntityKind.GROUPS -> o.d("duration")
                else -> firstFile(o)?.d("duration")
            }
            "resolution" -> firstFile(o)?.l("height")
            "filesize" -> firstFile(o)?.l("size")
            "path" -> (firstFile(o)?.s("path") ?: o.o("folder")?.s("path"))?.lowercase()
            "seconds" -> o.d("seconds")
            "scenes_updated_at" -> o.o("scene")?.s("updated_at")
            "scenes_count" -> o.l("scene_count")
            "images_count" -> o.l("image_count")
            "performers_count" -> o.l("performer_count")
            "scene_markers_count" -> o.l("scene_marker_count")
            "latest_scene" -> when (kind) {
                EntityKind.PERFORMERS -> latestScene(ix.scenesByPerformer[id], snap)
                EntityKind.STUDIOS -> latestScene(ix.scenesByStudio[id], snap)
                else -> null
            }
            else -> title(kind, o).lowercase()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareValues(a: Comparable<*>?, b: Comparable<*>?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> (a as Comparable<Any>).compareTo(b)
    }

    /** Numeric-aware id order, used to break ties so pages never shuffle. */
    private val idOrder = Comparator<JsonObject> { x, y ->
        val a = x.s("id") ?: ""
        val b = y.s("id") ?: ""
        val na = a.toLongOrNull()
        val nb = b.toLongOrNull()
        if (na != null && nb != null) na.compareTo(nb) else a.compareTo(b)
    }

    private fun sorted(kind: EntityKind, list: List<JsonObject>, q: BrowseQuery, snap: Snapshot): List<JsonObject> {
        if (q.sort == "random") {
            // Same seed, same order: paging through a random list never repeats or skips.
            return list.sortedWith(idOrder).shuffled(Random(q.seed))
        }
        val values = list.associateWith { sortValue(kind, q.sort, it, snap) }
        // Missing values always go last, whichever way the list is sorted.
        val byValue = Comparator<JsonObject> { x, y ->
            val a = values[x]
            val b = values[y]
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> if (q.descending) compareValues(b, a) else compareValues(a, b)
            }
        }
        return list.sortedWith(byValue.then(if (q.descending) idOrder.reversed() else idOrder))
    }

    // ------------------------------------------------------------------ browse

    /** One page of [kind] in [scope], filtered, searched and sorted like the server would. */
    fun browse(
        kind: EntityKind,
        scope: Scope,
        q: BrowseQuery,
        page: Int,
        perPage: Int,
        snap: Snapshot,
    ): Pair<List<JsonObject>, Int> {
        val filtered = all(kind, snap).filter { o ->
            inScope(kind, scope, o, snap) &&
                q.quick.all { matchesQuick(it, o, snap) } &&
                matchesText(kind, o, q.text)
        }
        val ordered = sorted(kind, filtered, q, snap)
        val from = ((page - 1) * perPage).coerceAtLeast(0)
        if (from >= ordered.size) return emptyList<JsonObject>() to ordered.size
        return ordered.subList(from, minOf(ordered.size, from + perPage)) to ordered.size
    }

    // ------------------------------------------------------------------ stats

    /** The Stats query's answer, computed from the phone (same field names as Stash). */
    fun stats(snap: Snapshot): JsonObject {
        val scenes = snap.scenes.values
        val files = scenes.mapNotNull { firstFile(it) }
        fun n(v: Number) = JsonPrimitive(v)
        return JsonObject(
            mapOf(
                "scene_count" to n(scenes.size),
                "scenes_size" to n(files.sumOf { (it.l("size") ?: 0L).toDouble() }),
                "scenes_duration" to n(files.sumOf { it.d("duration") ?: 0.0 }),
                "image_count" to n(snap.images.size),
                "images_size" to n(0),
                "gallery_count" to n(snap.galleries.size),
                "performer_count" to n(all(EntityKind.PERFORMERS, snap).size),
                "studio_count" to n(all(EntityKind.STUDIOS, snap).size),
                "group_count" to n(all(EntityKind.GROUPS, snap).size),
                "tag_count" to n(all(EntityKind.TAGS, snap).size),
                "total_o_count" to n(scenes.sumOf { it.l("o_counter") ?: 0L }),
                "total_play_duration" to n(scenes.sumOf { it.d("play_duration") ?: 0.0 }),
                "total_play_count" to n(scenes.sumOf { it.l("play_count") ?: 0L }),
                "scenes_played" to n(scenes.count { (it.l("play_count") ?: 0L) > 0L }),
            ),
        )
    }
}
