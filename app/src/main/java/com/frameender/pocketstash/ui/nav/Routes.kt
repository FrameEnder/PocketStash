package com.frameender.pocketstash.ui.nav

import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import kotlinx.serialization.Serializable

@Serializable object SetupRoute
@Serializable object HomeRoute
@Serializable object SearchRoute
@Serializable object LibraryRoute
@Serializable object SettingsRoute
@Serializable object UpdatesRoute
@Serializable object HomeLayoutRoute
@Serializable object DownloadsRoute

/** One Settings topic page (settings/<key>), see SettingsSection. */
@Serializable data class SettingsSectionRoute(val key: String)

/** Edit form for any entity; [id] null = create. [sceneId]/[seconds] pre-fill a new marker. */
@Serializable
data class EditRoute(
    val kind: String,
    val id: String? = null,
    val sceneId: String? = null,
    val sceneTitle: String? = null,
    val seconds: String? = null,
)

/** Top-level list for one entity kind, optionally pre-filtered (from "See all"). */
@Serializable
data class BrowseRoute(
    val kind: String,
    val sort: String? = null,
    val descending: Boolean = true,
    val quick: String = "",
    val text: String = "",
)

@Serializable data class SceneRoute(val id: String)
@Serializable data class PerformerRoute(val id: String)
@Serializable data class StudioRoute(val id: String)
@Serializable data class TagRoute(val id: String)
@Serializable data class GalleryRoute(val id: String)
@Serializable data class GroupRoute(val id: String)

/** Full-screen image pager over the same list the user tapped in. */
@Serializable
data class ImageViewerRoute(
    val scopeType: String,
    val scopeId: String = "",
    val sort: String,
    val descending: Boolean,
    val quick: String,
    val text: String,
    val seed: Int,
    val index: Int,
)

fun BrowseRoute.toQuery(kind: EntityKind, default: BrowseQuery): BrowseQuery = default.copy(
    sort = sort ?: default.sort,
    descending = if (sort != null) descending else default.descending,
    quick = quick.split(',').filter { it.isNotBlank() }.toSet(),
    text = text,
)

fun Scope.encode(): Pair<String, String> = when (this) {
    Scope.None -> "none" to ""
    is Scope.Performer -> "performer" to id
    is Scope.Studio -> "studio" to id
    is Scope.Tag -> "tag" to id
    is Scope.Gallery -> "gallery" to id
    is Scope.Group -> "group" to id
    is Scope.Scene -> "scene" to id
}

fun decodeScope(type: String, id: String): Scope = when (type) {
    "performer" -> Scope.Performer(id)
    "studio" -> Scope.Studio(id)
    "tag" -> Scope.Tag(id)
    "gallery" -> Scope.Gallery(id)
    "group" -> Scope.Group(id)
    "scene" -> Scope.Scene(id)
    else -> Scope.None
}

fun imageViewerRoute(scope: Scope, q: BrowseQuery, index: Int): ImageViewerRoute {
    val (t, id) = scope.encode()
    return ImageViewerRoute(
        scopeType = t, scopeId = id, sort = q.sort, descending = q.descending,
        quick = q.quick.joinToString(","), text = q.text, seed = q.seed, index = index,
    )
}

fun ImageViewerRoute.query(): BrowseQuery = BrowseQuery(
    text = text, sort = sort, descending = descending,
    quick = quick.split(',').filter { it.isNotBlank() }.toSet(), seed = seed,
)
