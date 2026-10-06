package com.frameender.pocketstash.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

enum class WidgetType(val label: String, val description: String) {
    ROW("Carousel", "A scrolling row of scenes, performers, studios, tags, groups, galleries, images or markers"),
    STATS("Library stats", "Counts, sizes and watch time at a glance"),
    SHORTCUTS("Shortcuts", "Buttons that jump straight to each library section"),
}

/** One section of the Home page. Stored as JSON in settings. */
@Serializable
data class HomeWidget(
    val id: String = UUID.randomUUID().toString(),
    val type: String = WidgetType.ROW.name,
    val title: String = "",
    val enabled: Boolean = true,
    // ROW
    val kind: String = EntityKind.SCENES.name,
    val sort: String = "created_at",
    val descending: Boolean = true,
    val quick: List<String> = emptyList(),
    val text: String = "",
    val count: Int = 20,
    /** Card size: "s", "m" or "l". */
    val size: String = "m",
) {
    val widgetType: WidgetType get() = runCatching { WidgetType.valueOf(type) }.getOrDefault(WidgetType.ROW)
    val entityKind: EntityKind get() = runCatching { EntityKind.valueOf(kind) }.getOrDefault(EntityKind.SCENES)

    fun query(): BrowseQuery = BrowseQuery(text = text, sort = sort, descending = descending, quick = quick.toSet())

    val displayTitle: String
        get() = title.ifBlank {
            when (widgetType) {
                WidgetType.ROW -> entityKind.label
                else -> widgetType.label
            }
        }
}

object HomeLayouts {
    private val serializer = ListSerializer(HomeWidget.serializer())

    /** The original PocketStash Home, used whenever no custom layout is saved. */
    fun default(): List<HomeWidget> = listOf(
        row("default-continue", "Continue watching", EntityKind.SCENES, "last_played_at", quick = listOf("inprogress")),
        row("default-recent", "Recently added", EntityKind.SCENES, "created_at"),
        row("default-newest", "Newest releases", EntityKind.SCENES, "date"),
        row("default-random", "Random picks", EntityKind.SCENES, "random"),
        row("default-favperf", "Favorite performers", EntityKind.PERFORMERS, "latest_scene", quick = listOf("fav")),
        row("default-toprated", "Top rated", EntityKind.SCENES, "rating", quick = listOf("rated")),
        row("default-galleries", "Recent galleries", EntityKind.GALLERIES, "created_at"),
        row("default-markers", "Recent markers", EntityKind.MARKERS, "created_at"),
        row("default-favstudio", "Favorite studios", EntityKind.STUDIOS, "latest_scene", quick = listOf("fav")),
    )

    private fun row(id: String, title: String, kind: EntityKind, sort: String, quick: List<String> = emptyList()) =
        HomeWidget(id = id, title = title, kind = kind.name, sort = sort, quick = quick)

    fun decode(json: String): List<HomeWidget> =
        if (json.isBlank()) default()
        else runCatching { StashJson.decodeFromString(serializer, json) }.getOrElse { default() }

    fun encode(list: List<HomeWidget>): String = StashJson.encodeToString(serializer, list)

    /** Parses a pasted layout; null if it isn't one. */
    fun tryDecode(json: String): List<HomeWidget>? =
        runCatching { StashJson.decodeFromString(serializer, json.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun create(type: WidgetType, kind: EntityKind = EntityKind.SCENES): HomeWidget = when (type) {
        WidgetType.ROW -> HomeWidget(
            type = type.name, kind = kind.name,
            sort = BrowseSpec.defaultSort(kind, Scope.None).sort,
            descending = BrowseSpec.defaultSort(kind, Scope.None).descending,
        )
        else -> HomeWidget(type = type.name)
    }
}
