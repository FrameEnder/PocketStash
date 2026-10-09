package com.frameender.pocketstash.data

import com.frameender.pocketstash.data.model.Gallery
import com.frameender.pocketstash.data.model.Group
import com.frameender.pocketstash.data.model.Marker
import com.frameender.pocketstash.data.model.Performer
import com.frameender.pocketstash.data.model.Scene
import com.frameender.pocketstash.data.model.ServerInfo
import com.frameender.pocketstash.data.model.StashImage
import com.frameender.pocketstash.data.model.Stats
import com.frameender.pocketstash.data.model.Studio
import com.frameender.pocketstash.data.model.Tag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Every screen's way to Stash. Online it asks the server; in offline mode it answers from the
 * phone ([OfflineLibrary] + [OfflineQuery]) with the same shapes, so screens don't need to care.
 * If the server stops answering, the app switches to offline mode for the rest of the session
 * (when Settings allows it) and the request that noticed is answered from the phone instead.
 */
class StashRepository(
    private val client: StashClient,
    /** Public so offline saving can fetch pictures with the same client and address rewriting. */
    val connection: Connection,
    private val library: OfflineLibrary,
) {
    private fun vars(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    /** Set by the app container once downloads exist (they need the repository too). */
    lateinit var downloads: SceneDownloads

    val isOffline: Boolean get() = connection.offline.value

    /** Emits when offline mode turns on or off; screens reload on it. */
    val modeChanges get() = connection.modeChanges

    private fun downloadedIds(): Set<String> = if (::downloads.isInitialized) downloads.doneIds else emptySet()

    private fun snapshot() = library.snapshot(downloadedIds())

    /** Online read with an offline twin. Switches to offline mode if the server has vanished. */
    private suspend fun <T> read(online: suspend () -> T, offline: () -> T): T {
        if (connection.offline.value) return offline()
        return try {
            online()
        } catch (e: ServerUnreachableException) {
            if (!connection.settings.offlineFallback) throw e
            connection.enterOffline(manual = false)
            offline()
        }
    }

    private fun notOffline(kind: String): Nothing =
        throw StashException("This $kind isn't on your phone. Download it or save its list for offline while you're connected.")

    // ------------------------------------------------------------------ system

    suspend fun testConnection(base: HttpUrl, apiKey: String): ServerInfo {
        val data = client.execute(Q.serverInfo, override = base to apiKey)
        return ServerInfo(
            version = data.optObj("version")?.get("version")?.jsonPrimitive?.contentOrNull,
            status = data.optObj("systemStatus")?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun serverInfo(): ServerInfo {
        if (connection.offline.value) return ServerInfo(version = null, status = "OFFLINE")
        val data = client.execute(Q.serverInfo)
        return ServerInfo(
            version = data.optObj("version")?.get("version")?.jsonPrimitive?.contentOrNull,
            status = data.optObj("systemStatus")?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun stats(): Stats = read(
        online = { client.execute(Q.stats).obj("stats").decode<Stats>() },
        offline = { OfflineQuery.stats(snapshot()).decode<Stats>() },
    )

    // ------------------------------------------------------------------ browse

    suspend fun browse(kind: EntityKind, scope: Scope, q: BrowseQuery, page: Int, perPage: Int = 40): Page<CardItem> = read(
        online = {
            val raw = browseRaw(kind, scope, q, page, perPage)
            Page(raw.items.map { toCard(kind, it) }, raw.total)
        },
        offline = {
            val (items, total) = OfflineQuery.browse(kind, scope, q, page, perPage, snapshot())
            Page(items.map { toCard(kind, it) }, total)
        },
    )

    /** One page straight from the server, as raw JSON (online only). */
    suspend fun browseRaw(kind: EntityKind, scope: Scope, q: BrowseQuery, page: Int, perPage: Int = 40): Page<JsonObject> {
        // "Downloaded" isn't a Stash filter: ask for exactly the downloaded scenes instead.
        val onlyIds: List<String>? = if (kind == EntityKind.SCENES && "downloaded" in q.quick) {
            downloadedIds().toList().ifEmpty { return Page(emptyList(), 0) }
        } else null
        val query = when (kind) {
            EntityKind.SCENES -> Q.findScenes
            EntityKind.PERFORMERS -> Q.findPerformers
            EntityKind.STUDIOS -> Q.findStudios
            EntityKind.TAGS -> Q.findTags
            EntityKind.GALLERIES -> Q.findGalleries
            EntityKind.IMAGES -> Q.findImages
            EntityKind.GROUPS -> Q.findGroups
            EntityKind.MARKERS -> Q.findMarkers
        }
        val variables = vars {
            put("filter", BrowseSpec.findFilter(q, page, perPage))
            put("f", BrowseSpec.entityFilter(kind, scope, q))
            if (onlyIds != null) put("ids", kotlinx.serialization.json.JsonArray(onlyIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        val result = client.execute(query, variables).obj("result")
        val total = result["count"]?.jsonPrimitive?.intOrNull ?: 0
        val items = (result["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Page(items, total)
    }

    /** The card the app would show for this raw entity JSON. */
    fun cardFor(kind: EntityKind, el: JsonElement): CardItem = toCard(kind, el)

    private fun toCard(kind: EntityKind, el: JsonElement): CardItem = when (kind) {
        EntityKind.SCENES -> sceneCard(el.decode())
        EntityKind.PERFORMERS -> el.decode<Performer>().let {
            CardItem(
                id = it.id, kind = kind, title = it.name,
                subtitle = listOfNotNull(it.disambiguation, it.sceneCount?.let { c -> "$c scenes" }).joinToString(" · ").ifBlank { null },
                image = connection.media(it.imagePath), aspect = 2f / 3f,
                rating100 = it.rating100, favorite = it.favorite,
            )
        }
        EntityKind.STUDIOS -> el.decode<Studio>().let {
            CardItem(
                id = it.id, kind = kind, title = it.name,
                subtitle = it.sceneCount?.let { c -> "$c scenes" },
                image = connection.media(it.imagePath), aspect = 16f / 9f, fit = true,
                rating100 = it.rating100, favorite = it.favorite,
            )
        }
        EntityKind.TAGS -> el.decode<Tag>().let {
            CardItem(
                id = it.id, kind = kind, title = it.name,
                subtitle = it.sceneCount?.let { c -> "$c scenes" },
                image = connection.media(it.imagePath), aspect = 4f / 3f, fit = true,
                favorite = it.favorite,
            )
        }
        EntityKind.GALLERIES -> el.decode<Gallery>().let {
            CardItem(
                id = it.id, kind = kind, title = it.displayTitle,
                subtitle = listOfNotNull(it.studio?.name, it.date).joinToString(" · ").ifBlank { null },
                image = connection.media(it.paths.cover), aspect = 4f / 5f,
                badge = it.imageCount?.let { c -> "$c" }, rating100 = it.rating100,
            )
        }
        EntityKind.IMAGES -> el.decode<StashImage>().let {
            val f = it.visualFiles.firstOrNull()
            val w = f?.width ?: 0
            val h = f?.height ?: 0
            val ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f
            CardItem(
                id = it.id, kind = kind, title = it.displayTitle,
                image = connection.media(it.paths.thumbnail), aspect = ratio.coerceIn(0.5f, 2f),
                rating100 = it.rating100, isVideo = it.isVideo,
                fullImage = connection.media(it.paths.image),
                preview = connection.media(it.paths.preview),
            )
        }
        EntityKind.GROUPS -> el.decode<Group>().let {
            CardItem(
                id = it.id, kind = kind, title = it.name,
                subtitle = listOfNotNull(it.studio?.name, it.date).joinToString(" · ").ifBlank { null },
                image = connection.media(it.frontImagePath), aspect = 2f / 3f,
                badge = it.sceneCount?.let { c -> "$c" }, rating100 = it.rating100,
            )
        }
        EntityKind.MARKERS -> markerCard(el.decode())
    }

    fun sceneCard(s: Scene): CardItem {
        val downloaded = isDownloaded(s)
        val dur = s.duration
        val res = s.files.firstOrNull()?.height?.let { resolutionLabel(it) }
        val progress = if (dur != null && dur > 0 && (s.resumeTime ?: 0.0) > 0) (s.resumeTime!! / dur).toFloat().coerceIn(0f, 1f) else null
        return CardItem(
            id = s.id, kind = EntityKind.SCENES, title = s.displayTitle,
            subtitle = listOfNotNull(s.studio?.name, s.date, s.performers.take(2).joinToString { it.name }.ifBlank { null })
                .joinToString(" · ").ifBlank { null },
            image = connection.media(s.paths.screenshot), aspect = 16f / 9f, letterbox = true,
            badge = listOfNotNull(res, dur?.let { formatDuration(it) }).joinToString(" · ").ifBlank { null },
            rating100 = s.rating100, progress = progress,
            preview = connection.media(s.paths.preview),
            downloaded = downloaded,
            infoOnly = connection.offline.value && !downloaded,
        )
    }

    fun markerCard(m: Marker, sceneId: String? = null): CardItem = CardItem(
        id = m.id, kind = EntityKind.MARKERS,
        title = m.title.ifBlank { m.primaryTag?.name ?: "Marker" },
        subtitle = listOfNotNull(m.primaryTag?.name?.takeIf { m.title.isNotBlank() }, m.scene?.displayTitle)
            .joinToString(" · ").ifBlank { null },
        image = connection.media(m.screenshot), aspect = 16f / 9f, letterbox = true,
        badge = formatDuration(m.seconds),
        preview = connection.media(m.preview),
        sceneId = m.scene?.id ?: sceneId, seconds = m.seconds,
        infoOnly = connection.offline.value && (m.scene?.id ?: sceneId) !in downloadedIds(),
    )

    /** Downloaded, and (online) really the same video, not another server's scene with that id. */
    fun isDownloaded(s: Scene): Boolean {
        if (!::downloads.isInitialized) return false
        return downloads.isDownloaded(s.id, if (connection.offline.value || s.files.isEmpty()) null else sceneFileKey(s))
    }

    // ------------------------------------------------------------------ details

    suspend fun scene(id: String): Scene = detail(EntityKind.SCENES, id, "scene")
    suspend fun performer(id: String): Performer = detail(EntityKind.PERFORMERS, id, "performer")
    suspend fun studio(id: String): Studio = detail(EntityKind.STUDIOS, id, "studio")
    suspend fun tag(id: String): Tag = detail(EntityKind.TAGS, id, "tag")
    suspend fun gallery(id: String): Gallery = detail(EntityKind.GALLERIES, id, "gallery")
    suspend fun image(id: String): StashImage = detail(EntityKind.IMAGES, id, "image")
    suspend fun group(id: String): Group = detail(EntityKind.GROUPS, id, "group")

    private suspend inline fun <reified T> detail(kind: EntityKind, id: String, noun: String): T {
        val json = read(
            online = { rawDetail(kind, id).also { library.refreshIfStored(kind, it) } },
            offline = { OfflineQuery.get(kind, id, snapshot()) ?: notOffline(noun) },
        )
        return json.decode<T>()
    }

    /** The full detail JSON for one entity, straight from the server (online only). */
    suspend fun rawDetail(kind: EntityKind, id: String): JsonObject {
        val (query, field) = when (kind) {
            EntityKind.SCENES -> Q.scene to "findScene"
            EntityKind.PERFORMERS -> Q.performer to "findPerformer"
            EntityKind.STUDIOS -> Q.studio to "findStudio"
            EntityKind.TAGS -> Q.tag to "findTag"
            EntityKind.GALLERIES -> Q.gallery to "findGallery"
            EntityKind.IMAGES -> Q.image to "findImage"
            EntityKind.GROUPS -> Q.group to "findGroup"
            EntityKind.MARKERS -> throw StashException("Markers have no detail page")
        }
        val node = client.execute(query, vars { put("id", id) })[field]
        if (node == null || node is JsonNull) throw StashException("Not found (id $id)")
        return node as? JsonObject ?: throw StashException("Unexpected answer for $field")
    }

    // ------------------------------------------------------------------ mutations

    private suspend fun update(query: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        client.execute(query, vars { putJsonObject("input", block) })

    suspend fun rateScene(id: String, rating100: Int?) = update(Q.sceneUpdate) { put("id", id); put("rating100", rating100) }
    suspend fun setSceneOrganized(id: String, value: Boolean) = update(Q.sceneUpdate) { put("id", id); put("organized", value) }
    suspend fun ratePerformer(id: String, rating100: Int?) = update(Q.performerUpdate) { put("id", id); put("rating100", rating100) }
    suspend fun favoritePerformer(id: String, value: Boolean) = update(Q.performerUpdate) { put("id", id); put("favorite", value) }
    suspend fun rateStudio(id: String, rating100: Int?) = update(Q.studioUpdate) { put("id", id); put("rating100", rating100) }
    suspend fun favoriteStudio(id: String, value: Boolean) = update(Q.studioUpdate) { put("id", id); put("favorite", value) }
    suspend fun favoriteTag(id: String, value: Boolean) = update(Q.tagUpdate) { put("id", id); put("favorite", value) }
    suspend fun rateGallery(id: String, rating100: Int?) = update(Q.galleryUpdate) { put("id", id); put("rating100", rating100) }
    suspend fun rateImage(id: String, rating100: Int?) = update(Q.imageUpdate) { put("id", id); put("rating100", rating100) }
    suspend fun rateGroup(id: String, rating100: Int?) = update(Q.groupUpdate) { put("id", id); put("rating100", rating100) }

    suspend fun sceneAddO(id: String): Int =
        client.execute(Q.sceneAddO, vars { put("id", id) }).obj("sceneAddO")["count"]?.jsonPrimitive?.intOrNull ?: 0

    suspend fun sceneDeleteO(id: String): Int =
        client.execute(Q.sceneDeleteO, vars { put("id", id) }).obj("sceneDeleteO")["count"]?.jsonPrimitive?.intOrNull ?: 0

    suspend fun imageIncrementO(id: String): Int =
        client.execute(Q.imageIncrementO, vars { put("id", id) })["imageIncrementO"]?.jsonPrimitive?.intOrNull ?: 0

    suspend fun imageDecrementO(id: String): Int =
        client.execute(Q.imageDecrementO, vars { put("id", id) })["imageDecrementO"]?.jsonPrimitive?.intOrNull ?: 0

    /** Play count +1. Offline (or if the server just vanished) it's kept on the phone and sent later. */
    suspend fun addPlay(id: String) {
        if (connection.offline.value) return library.recordPlay(id, queue = true)
        try {
            client.execute(Q.sceneAddPlay, vars { put("id", id) })
            library.recordPlay(id, queue = false)
        } catch (e: ServerUnreachableException) {
            library.recordPlay(id, queue = true)
        }
    }

    // ------------------------------------------------------------------ editing

    /** Loads the raw entity JSON a form is filled from. */
    suspend fun loadForEdit(spec: EditSpec, id: String): JsonObject {
        val data = client.execute(spec.loadQuery, vars { put("id", id) })
        return spec.extract(data) ?: throw StashException("${spec.kind.label} $id not found")
    }

    /** Saves (id != null) or creates (id == null). Returns the entity id. */
    suspend fun saveEdit(spec: EditSpec, input: JsonObject, creating: Boolean): String {
        val mutation = if (creating) spec.createMutation ?: throw StashException("Can't create a ${spec.kind.label.lowercase()} here")
        else spec.updateMutation
        val data = client.execute(mutation, vars { put("input", input) })
        return data.optObj("result")?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw StashException("Stash didn't confirm the save")
    }

    suspend fun destroy(spec: EditSpec, id: String, deleteFile: Boolean, deleteGenerated: Boolean) {
        val mutation = spec.destroyMutation ?: throw StashException("Can't delete a ${spec.kind.label.lowercase()}")
        val variables = when (spec.kind) {
            EditKind.MARKER -> vars { put("id", id) }
            EditKind.GALLERY -> vars {
                putJsonObject("input") {
                    put("ids", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(id)) })
                    put("delete_file", deleteFile)
                    put("delete_generated", deleteGenerated)
                }
            }
            EditKind.SCENE, EditKind.IMAGE -> vars {
                putJsonObject("input") {
                    put("id", id)
                    put("delete_file", deleteFile)
                    put("delete_generated", deleteGenerated)
                }
            }
            else -> vars { putJsonObject("input") { put("id", id) } }
        }
        client.execute(mutation, variables)
    }

    /** Search used by edit-form pickers. */
    suspend fun searchRefs(kind: EntityKind, text: String, limit: Int = 30): List<Ref> {
        val q = BrowseSpec.defaultSort(kind, Scope.None).let {
            if (text.isBlank() && kind in setOf(EntityKind.SCENES, EntityKind.GALLERIES)) it.copy(sort = "updated_at", descending = true) else it
        }.copy(text = text)
        return browse(kind, Scope.None, q, 1, limit).items.map { Ref(it.id, it.title, it.image) }
    }

    /** Creates a tag / performer / studio / group with just a name (picker "Create …"). */
    suspend fun quickCreate(kind: EntityKind, name: String): Ref {
        val (field, type) = EditSpecs.quickCreate(kind) ?: throw StashException("Can't create ${kind.label.lowercase()} here")
        val query = "mutation QuickCreate(\$input: $type!) { result: $field(input: \$input) { id name } }"
        val data = client.execute(query, vars { putJsonObject("input") { put("name", name.trim()) } })
        val r = data.obj("result")
        return Ref(r["id"]!!.jsonPrimitive.content, r["name"]?.jsonPrimitive?.contentOrNull ?: name)
    }

    /** Resume point + watch time. Offline (or if the server just vanished) it's kept on the phone and sent later. */
    suspend fun saveActivity(id: String, resumeSeconds: Double?, playedSeconds: Double?) {
        if (connection.offline.value) return library.recordActivity(id, resumeSeconds, playedSeconds, queue = true)
        try {
            sendActivity(id, resumeSeconds, playedSeconds)
            library.recordActivity(id, resumeSeconds, playedSeconds, queue = false)
        } catch (e: ServerUnreachableException) {
            library.recordActivity(id, resumeSeconds, playedSeconds, queue = true)
        }
    }

    private suspend fun sendActivity(id: String, resumeSeconds: Double?, playedSeconds: Double?) {
        client.execute(Q.sceneSaveActivity, vars {
            put("id", id)
            put("resume", resumeSeconds)
            put("duration", playedSeconds)
        })
    }

    /** Sends plays and watch time recorded while offline, oldest first. Stops at the first failure. */
    suspend fun flushPending(): Int {
        if (connection.offline.value) return 0
        val todo = library.pending()
        var sent = 0
        for (p in todo) {
            try {
                when (p.type) {
                    "play" -> client.execute(Q.sceneAddPlay, vars { put("id", p.sceneId) })
                    else -> sendActivity(p.sceneId, p.resume, p.duration)
                }
                sent++
            } catch (e: ServerUnreachableException) {
                break
            } catch (e: StashException) {
                sent++ // the scene was deleted on the server, or similar: drop it rather than retry forever
            }
        }
        if (sent > 0) library.dropPending(sent)
        return sent
    }
}

fun resolutionLabel(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    height >= 1440 -> "1440p"
    height >= 1080 -> "1080p"
    height >= 720 -> "720p"
    height >= 480 -> "480p"
    else -> "${height}p"
}

fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Double): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = bytes
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
