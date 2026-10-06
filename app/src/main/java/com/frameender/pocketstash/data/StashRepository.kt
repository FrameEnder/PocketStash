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

class StashRepository(
    private val client: StashClient,
    private val connection: Connection,
) {
    private fun vars(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    // ------------------------------------------------------------------ system

    suspend fun testConnection(base: HttpUrl, apiKey: String): ServerInfo {
        val data = client.execute(Q.serverInfo, override = base to apiKey)
        return ServerInfo(
            version = data.optObj("version")?.get("version")?.jsonPrimitive?.contentOrNull,
            status = data.optObj("systemStatus")?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun serverInfo(): ServerInfo {
        val data = client.execute(Q.serverInfo)
        return ServerInfo(
            version = data.optObj("version")?.get("version")?.jsonPrimitive?.contentOrNull,
            status = data.optObj("systemStatus")?.get("status")?.jsonPrimitive?.contentOrNull,
        )
    }

    suspend fun stats(): Stats = client.execute(Q.stats).obj("stats").decode()

    // ------------------------------------------------------------------ browse

    suspend fun browse(kind: EntityKind, scope: Scope, q: BrowseQuery, page: Int, perPage: Int = 40): Page<CardItem> {
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
        }
        val result = client.execute(query, variables).obj("result")
        val total = result["count"]?.jsonPrimitive?.intOrNull ?: 0
        val items = (result["items"] as? JsonArray).orEmpty()
        return Page(items.map { toCard(kind, it) }, total)
    }

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
        val dur = s.duration
        val res = s.files.firstOrNull()?.height?.let { resolutionLabel(it) }
        val progress = if (dur != null && dur > 0 && (s.resumeTime ?: 0.0) > 0) (s.resumeTime!! / dur).toFloat().coerceIn(0f, 1f) else null
        return CardItem(
            id = s.id, kind = EntityKind.SCENES, title = s.displayTitle,
            subtitle = listOfNotNull(s.studio?.name, s.date, s.performers.take(2).joinToString { it.name }.ifBlank { null })
                .joinToString(" · ").ifBlank { null },
            image = connection.media(s.paths.screenshot), aspect = 16f / 9f,
            badge = listOfNotNull(res, dur?.let { formatDuration(it) }).joinToString(" · ").ifBlank { null },
            rating100 = s.rating100, progress = progress,
            preview = connection.media(s.paths.preview),
        )
    }

    fun markerCard(m: Marker, sceneId: String? = null): CardItem = CardItem(
        id = m.id, kind = EntityKind.MARKERS,
        title = m.title.ifBlank { m.primaryTag?.name ?: "Marker" },
        subtitle = listOfNotNull(m.primaryTag?.name?.takeIf { m.title.isNotBlank() }, m.scene?.displayTitle)
            .joinToString(" · ").ifBlank { null },
        image = connection.media(m.screenshot), aspect = 16f / 9f,
        badge = formatDuration(m.seconds),
        preview = connection.media(m.preview),
        sceneId = m.scene?.id ?: sceneId, seconds = m.seconds,
    )

    // ------------------------------------------------------------------ details

    suspend fun scene(id: String): Scene = detail(Q.scene, "findScene", id)
    suspend fun performer(id: String): Performer = detail(Q.performer, "findPerformer", id)
    suspend fun studio(id: String): Studio = detail(Q.studio, "findStudio", id)
    suspend fun tag(id: String): Tag = detail(Q.tag, "findTag", id)
    suspend fun gallery(id: String): Gallery = detail(Q.gallery, "findGallery", id)
    suspend fun image(id: String): StashImage = detail(Q.image, "findImage", id)
    suspend fun group(id: String): Group = detail(Q.group, "findGroup", id)

    private suspend inline fun <reified T> detail(query: String, field: String, id: String): T {
        val data = client.execute(query, vars { put("id", id) })
        val node = data[field]
        if (node == null || node is JsonNull) throw StashException("Not found (id $id)")
        return node.decode()
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

    suspend fun addPlay(id: String) {
        client.execute(Q.sceneAddPlay, vars { put("id", id) })
    }

    suspend fun saveActivity(id: String, resumeSeconds: Double?, playedSeconds: Double?) {
        client.execute(Q.sceneSaveActivity, vars {
            put("id", id)
            put("resume", resumeSeconds)
            put("duration", playedSeconds)
        })
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
