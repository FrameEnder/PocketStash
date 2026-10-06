package com.frameender.pocketstash.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class EntityKind(val label: String, val singular: String) {
    SCENES("Scenes", "Scene"),
    PERFORMERS("Performers", "Performer"),
    STUDIOS("Studios", "Studio"),
    TAGS("Tags", "Tag"),
    GALLERIES("Galleries", "Gallery"),
    IMAGES("Images", "Image"),
    GROUPS("Groups", "Group"),
    MARKERS("Markers", "Marker"),
}

/** Restricts a browse list to things related to one entity. */
sealed interface Scope {
    data object None : Scope
    data class Performer(val id: String) : Scope
    data class Studio(val id: String) : Scope
    data class Tag(val id: String) : Scope
    data class Gallery(val id: String) : Scope
    data class Group(val id: String) : Scope
    data class Scene(val id: String) : Scope
}

data class SortOption(val label: String, val key: String)

/** A toggleable chip that adds fields to the entity filter. */
data class QuickFilter(val id: String, val label: String, val block: JsonObjectBuilder.() -> Unit)

data class BrowseQuery(
    val text: String = "",
    val sort: String,
    val descending: Boolean = true,
    val quick: Set<String> = emptySet(),
    /** Seed for stable random paging. */
    val seed: Int = (1..99_999_999).random(),
)

data class Page<T>(val items: List<T>, val total: Int)

/** Uniform card model so every grid in the app shares one renderer. */
data class CardItem(
    val id: String,
    val kind: EntityKind,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    /** Width / height of the artwork slot. */
    val aspect: Float,
    /** Logos/studio art look better letterboxed than cropped. */
    val fit: Boolean = false,
    val badge: String? = null,
    val rating100: Int? = null,
    val favorite: Boolean = false,
    /** 0..1 watch progress bar (scenes). */
    val progress: Float? = null,
    val preview: String? = null,
    /** Markers: the scene to open and where to seek. */
    val sceneId: String? = null,
    val seconds: Double? = null,
    /** Images: the item is actually a video clip. */
    val isVideo: Boolean = false,
    val fullImage: String? = null,
)

object BrowseSpec {

    fun sorts(kind: EntityKind): List<SortOption> = when (kind) {
        EntityKind.SCENES -> listOf(
            SortOption("Date added", "created_at"),
            SortOption("Release date", "date"),
            SortOption("Title", "title"),
            SortOption("Rating", "rating"),
            SortOption("Duration", "duration"),
            SortOption("Last played", "last_played_at"),
            SortOption("Play count", "play_count"),
            SortOption("O count", "o_counter"),
            SortOption("Resolution", "resolution"),
            SortOption("File size", "filesize"),
            SortOption("Updated", "updated_at"),
            SortOption("Random", "random"),
        )
        EntityKind.PERFORMERS -> listOf(
            SortOption("Name", "name"),
            SortOption("Scene count", "scenes_count"),
            SortOption("Rating", "rating"),
            SortOption("Latest scene", "latest_scene"),
            SortOption("Date added", "created_at"),
            SortOption("Birthdate", "birthdate"),
            SortOption("O count", "o_counter"),
            SortOption("Play count", "play_count"),
            SortOption("Random", "random"),
        )
        EntityKind.STUDIOS -> listOf(
            SortOption("Name", "name"),
            SortOption("Scene count", "scenes_count"),
            SortOption("Rating", "rating"),
            SortOption("Latest scene", "latest_scene"),
            SortOption("Date added", "created_at"),
            SortOption("Random", "random"),
        )
        EntityKind.TAGS -> listOf(
            SortOption("Name", "name"),
            SortOption("Scene count", "scenes_count"),
            SortOption("Marker count", "scene_markers_count"),
            SortOption("Performer count", "performers_count"),
            SortOption("Date added", "created_at"),
            SortOption("Random", "random"),
        )
        EntityKind.GALLERIES -> listOf(
            SortOption("Date added", "created_at"),
            SortOption("Date", "date"),
            SortOption("Title", "title"),
            SortOption("Rating", "rating"),
            SortOption("Image count", "images_count"),
            SortOption("Path", "path"),
            SortOption("Random", "random"),
        )
        EntityKind.IMAGES -> listOf(
            SortOption("Date added", "created_at"),
            SortOption("Date", "date"),
            SortOption("Title", "title"),
            SortOption("Rating", "rating"),
            SortOption("O count", "o_counter"),
            SortOption("Path", "path"),
            SortOption("File size", "filesize"),
            SortOption("Random", "random"),
        )
        EntityKind.GROUPS -> listOf(
            SortOption("Name", "name"),
            SortOption("Date", "date"),
            SortOption("Rating", "rating"),
            SortOption("Scene count", "scenes_count"),
            SortOption("Duration", "duration"),
            SortOption("Date added", "created_at"),
            SortOption("Random", "random"),
        )
        EntityKind.MARKERS -> listOf(
            SortOption("Date added", "created_at"),
            SortOption("Title", "title"),
            SortOption("Scene updated", "scenes_updated_at"),
            SortOption("Position", "seconds"),
            SortOption("Duration", "duration"),
            SortOption("Random", "random"),
        )
    }

    /** Sort a scoped list (e.g. a gallery's images) uses by default. */
    fun defaultSort(kind: EntityKind, scope: Scope): BrowseQuery = when {
        kind == EntityKind.IMAGES && scope is Scope.Gallery -> BrowseQuery(sort = "path", descending = false)
        kind == EntityKind.MARKERS && scope is Scope.Scene -> BrowseQuery(sort = "seconds", descending = false)
        kind in setOf(EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS, EntityKind.GROUPS) ->
            BrowseQuery(sort = "name", descending = false)
        else -> BrowseQuery(sort = "created_at", descending = true)
    }

    fun quickFilters(kind: EntityKind): List<QuickFilter> = when (kind) {
        EntityKind.SCENES -> listOf(
            QuickFilter("unwatched", "Unwatched") { putInt("play_count", 0, "EQUALS") },
            QuickFilter("inprogress", "In progress") { putInt("resume_time", 0, "GREATER_THAN") },
            QuickFilter("rated", "Rated") { putInt("rating100", 0, "GREATER_THAN") },
            QuickFilter("organized", "Organized") { put("organized", true) },
            QuickFilter("unorganized", "Unorganized") { put("organized", false) },
        )
        EntityKind.PERFORMERS -> listOf(
            QuickFilter("fav", "Favorites") { put("filter_favorites", true) },
            QuickFilter("rated", "Rated") { putInt("rating100", 0, "GREATER_THAN") },
        )
        EntityKind.STUDIOS -> listOf(
            QuickFilter("fav", "Favorites") { put("favorite", true) },
        )
        EntityKind.TAGS -> listOf(
            QuickFilter("fav", "Favorites") { put("favorite", true) },
        )
        EntityKind.GALLERIES -> listOf(
            QuickFilter("rated", "Rated") { putInt("rating100", 0, "GREATER_THAN") },
            QuickFilter("organized", "Organized") { put("organized", true) },
        )
        EntityKind.IMAGES -> listOf(
            QuickFilter("rated", "Rated") { putInt("rating100", 0, "GREATER_THAN") },
            QuickFilter("organized", "Organized") { put("organized", true) },
        )
        EntityKind.GROUPS -> listOf(
            QuickFilter("rated", "Rated") { putInt("rating100", 0, "GREATER_THAN") },
        )
        EntityKind.MARKERS -> emptyList()
    }

    /** Which entity kinds can be listed under a given scope (detail-screen tabs). */
    fun kindsFor(scope: Scope): List<EntityKind> = when (scope) {
        Scope.None -> EntityKind.entries
        is Scope.Performer -> listOf(EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.IMAGES, EntityKind.GROUPS, EntityKind.MARKERS)
        is Scope.Studio -> listOf(EntityKind.SCENES, EntityKind.GALLERIES, EntityKind.IMAGES, EntityKind.PERFORMERS, EntityKind.GROUPS)
        is Scope.Tag -> listOf(EntityKind.SCENES, EntityKind.MARKERS, EntityKind.PERFORMERS, EntityKind.GALLERIES, EntityKind.IMAGES, EntityKind.STUDIOS, EntityKind.GROUPS)
        is Scope.Gallery -> listOf(EntityKind.IMAGES)
        is Scope.Group -> listOf(EntityKind.SCENES)
        is Scope.Scene -> listOf(EntityKind.MARKERS)
    }

    fun findFilter(q: BrowseQuery, page: Int, perPage: Int): JsonObject = buildJsonObject {
        if (q.text.isNotBlank()) put("q", q.text.trim())
        put("page", page)
        put("per_page", perPage)
        put("sort", if (q.sort == "random") "random_${q.seed}" else q.sort)
        put("direction", if (q.descending) "DESC" else "ASC")
    }

    fun entityFilter(kind: EntityKind, scope: Scope, q: BrowseQuery): JsonObject = buildJsonObject {
        when (scope) {
            Scope.None -> Unit
            is Scope.Performer -> putMulti("performers", scope.id)
            is Scope.Studio -> putHierarchical("studios", scope.id)
            is Scope.Tag -> putHierarchical("tags", scope.id)
            is Scope.Gallery -> putMulti("galleries", scope.id)
            is Scope.Group -> putHierarchical("groups", scope.id)
            is Scope.Scene -> putMulti("scenes", scope.id)
        }
        quickFilters(kind).filter { it.id in q.quick }.forEach { f -> f.block(this) }
    }

    private fun JsonObjectBuilder.putMulti(field: String, id: String) {
        putJsonObject(field) {
            putJsonArray("value") { add(kotlinx.serialization.json.JsonPrimitive(id)) }
            put("modifier", "INCLUDES")
        }
    }

    private fun JsonObjectBuilder.putHierarchical(field: String, id: String) {
        putJsonObject(field) {
            putJsonArray("value") { add(kotlinx.serialization.json.JsonPrimitive(id)) }
            put("modifier", "INCLUDES")
            put("depth", 0)
        }
    }

    private fun JsonObjectBuilder.putInt(field: String, value: Int, modifier: String) {
        putJsonObject(field) {
            put("value", value)
            put("modifier", modifier)
        }
    }
}
