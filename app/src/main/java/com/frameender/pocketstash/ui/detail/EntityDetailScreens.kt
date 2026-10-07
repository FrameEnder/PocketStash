package com.frameender.pocketstash.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EditKind
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.data.formatDuration
import com.frameender.pocketstash.data.model.Gallery
import com.frameender.pocketstash.data.model.Group
import com.frameender.pocketstash.data.model.Performer
import com.frameender.pocketstash.data.model.Studio
import com.frameender.pocketstash.data.model.Tag
import com.frameender.pocketstash.ui.common.valueOrNull
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.components.InfoRow
import com.frameender.pocketstash.ui.components.LinkChip
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import java.time.LocalDate
import java.time.Period

private val headerSpan: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) =
    { GridItemSpan(maxLineSpan) }

// ============================================================ Performer

@Composable
fun PerformerDetailScreen(id: String) {
    val repo = LocalContext.current.container.repository
    val nav = LocalNavigator.current
    val vm = appViewModel("performer:$id") { c -> DetailViewModel { c.repository.performer(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    ReloadOnReturn(vm)
    val p = state.valueOrNull()

    DetailScaffold(
        title = p?.name ?: "Performer",
        onEdit = { nav.edit(EditKind.PERFORMER, id) },
        actions = {
            if (p != null) FavoriteButton(p.favorite) { v ->
                vm.mutate({ it.copy(favorite = v) }) { repo.favoritePerformer(id, v) }
            }
        },
    ) {
        LoadSwitch(state, vm::reload) { perf ->
            TabbedRelated(
                Scope.Performer(id),
                ownerName = perf.name,
                listOf(
                    TabSpec(EntityKind.SCENES, perf.sceneCount),
                    TabSpec(EntityKind.GALLERIES, perf.galleryCount),
                    TabSpec(EntityKind.IMAGES, perf.imageCount),
                    TabSpec(EntityKind.GROUPS, perf.groupCount),
                    TabSpec(EntityKind.MARKERS, null),
                ),
            ) {
                item(span = headerSpan, key = "header") {
                    PerformerInfo(perf) { r -> vm.mutate({ it.copy(rating100 = r) }) { repo.ratePerformer(id, r) } }
                }
            }
        }
    }
}

@Composable
private fun PerformerInfo(p: Performer, onRate: (Int?) -> Unit) {
    val age = p.birthdate?.let { b ->
        runCatching {
            val end = p.deathDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
            Period.between(LocalDate.parse(b), end).years
        }.getOrNull()
    }
    ProfileHeader(
        image = p.imagePath?.let { LocalContext.current.container.connection.media(it) },
        aspect = 2f / 3f, fit = false,
        title = p.name,
        subtitle = listOfNotNull(p.disambiguation, p.gender?.lowercase()?.replace('_', ' '), age?.let { "$it" }, p.country)
            .joinToString(" · ").ifBlank { null },
        rating100 = p.rating100, onRate = onRate,
        side = {
            CountLine("scenes" to p.sceneCount, "galleries" to p.galleryCount, "images" to p.imageCount, "O" to p.oCounter)
        },
        below = {
            if (p.aliasList.isNotEmpty()) InfoRow("Aliases", p.aliasList.joinToString(", "))
            InfoRow("Born", p.birthdate)
            InfoRow("Died", p.deathDate)
            InfoRow("Ethnicity", p.ethnicity)
            InfoRow("Eyes", p.eyeColor)
            InfoRow("Hair", p.hairColor)
            InfoRow("Height", p.heightCm?.let { "$it cm" })
            InfoRow("Weight", p.weight?.let { "$it kg" })
            InfoRow("Measurements", p.measurements)
            InfoRow("Fake tits", p.fakeTits)
            InfoRow("Career", p.careerLength)
            InfoRow("Tattoos", p.tattoos)
            InfoRow("Piercings", p.piercings)
            ExpandableText(p.details)
            TagChips(p.tags)
            UrlChips(p.urls.orEmpty())
        },
    )
}

// ============================================================ Studio

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioDetailScreen(id: String) {
    val container = LocalContext.current.container
    val repo = container.repository
    val nav = LocalNavigator.current
    val vm = appViewModel("studio:$id") { c -> DetailViewModel { c.repository.studio(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    ReloadOnReturn(vm)
    val st = state.valueOrNull()

    DetailScaffold(
        title = st?.name ?: "Studio",
        onEdit = { nav.edit(EditKind.STUDIO, id) },
        actions = {
            if (st != null) FavoriteButton(st.favorite) { v ->
                vm.mutate({ it.copy(favorite = v) }) { repo.favoriteStudio(id, v) }
            }
        },
    ) {
        LoadSwitch(state, vm::reload) { s: Studio ->
            TabbedRelated(
                Scope.Studio(id),
                ownerName = s.name,
                listOf(
                    TabSpec(EntityKind.SCENES, s.sceneCount),
                    TabSpec(EntityKind.GALLERIES, s.galleryCount),
                    TabSpec(EntityKind.IMAGES, s.imageCount),
                    TabSpec(EntityKind.PERFORMERS, s.performerCount),
                    TabSpec(EntityKind.GROUPS, s.groupCount),
                ),
            ) {
                item(span = headerSpan, key = "header") {
                    ProfileHeader(
                        image = container.connection.media(s.imagePath),
                        aspect = 16f / 9f, fit = true, imageWidth = 170,
                        title = s.name,
                        subtitle = s.aliases.joinToString(", ").ifBlank { null },
                        rating100 = s.rating100,
                        onRate = { r -> vm.mutate({ it.copy(rating100 = r) }) { repo.rateStudio(id, r) } },
                        side = { CountLine("scenes" to s.sceneCount, "performers" to s.performerCount) },
                        below = {
                            s.parentStudio?.let { parent ->
                                Text("PARENT", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                                LinkChip(parent.name, onClick = { nav.studio(parent.id) }, image = container.connection.media(parent.imagePath))
                            }
                            if (s.childStudios.isNotEmpty()) {
                                Text(
                                    "SUBSIDIARIES", style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    s.childStudios.forEach { c ->
                                        LinkChip(c.name, onClick = { nav.studio(c.id) }, image = container.connection.media(c.imagePath))
                                    }
                                }
                            }
                            ExpandableText(s.details)
                            TagChips(s.tags)
                            UrlChips(s.urls)
                        },
                    )
                }
            }
        }
    }
}

// ============================================================ Tag

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDetailScreen(id: String) {
    val container = LocalContext.current.container
    val repo = container.repository
    val nav = LocalNavigator.current
    val vm = appViewModel("tag:$id") { c -> DetailViewModel { c.repository.tag(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    ReloadOnReturn(vm)
    val tg = state.valueOrNull()

    DetailScaffold(
        title = tg?.name ?: "Tag",
        onEdit = { nav.edit(EditKind.TAG, id) },
        actions = {
            if (tg != null) FavoriteButton(tg.favorite) { v ->
                vm.mutate({ it.copy(favorite = v) }) { repo.favoriteTag(id, v) }
            }
        },
    ) {
        LoadSwitch(state, vm::reload) { t: Tag ->
            TabbedRelated(
                Scope.Tag(id),
                ownerName = t.name,
                listOf(
                    TabSpec(EntityKind.SCENES, t.sceneCount),
                    TabSpec(EntityKind.MARKERS, t.markerCount),
                    TabSpec(EntityKind.PERFORMERS, t.performerCount),
                    TabSpec(EntityKind.GALLERIES, t.galleryCount),
                    TabSpec(EntityKind.IMAGES, t.imageCount),
                    TabSpec(EntityKind.STUDIOS, t.studioCount),
                    TabSpec(EntityKind.GROUPS, t.groupCount),
                ),
            ) {
                item(span = headerSpan, key = "header") {
                    ProfileHeader(
                        image = container.connection.media(t.imagePath),
                        aspect = 4f / 3f, fit = true, imageWidth = 120,
                        title = t.name,
                        subtitle = t.aliases.joinToString(", ").ifBlank { null },
                        rating100 = null, onRate = null,
                        side = { CountLine("scenes" to t.sceneCount, "markers" to t.markerCount, "performers" to t.performerCount) },
                        below = {
                            ExpandableText(t.description)
                            if (t.parents.isNotEmpty()) {
                                Text("PARENT TAGS", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                                TagChips(t.parents)
                            }
                            if (t.children.isNotEmpty()) {
                                Text("SUB-TAGS", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    t.children.forEach { c -> LinkChip(c.name, onClick = { nav.tag(c.id) }) }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ============================================================ Group

@Composable
fun GroupDetailScreen(id: String) {
    val container = LocalContext.current.container
    val repo = container.repository
    val nav = LocalNavigator.current
    val vm = appViewModel("group:$id") { c -> DetailViewModel { c.repository.group(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    ReloadOnReturn(vm)
    val gr = state.valueOrNull()

    DetailScaffold(title = gr?.name ?: "Group", onEdit = { nav.edit(EditKind.GROUP, id) }) {
        LoadSwitch(state, vm::reload) { g: Group ->
            TabbedRelated(Scope.Group(id), listOf(TabSpec(EntityKind.SCENES, g.sceneCount)), ownerName = g.name) {
                item(span = headerSpan, key = "header") {
                    ProfileHeader(
                        image = container.connection.media(g.frontImagePath),
                        aspect = 2f / 3f, fit = false,
                        title = g.name,
                        subtitle = listOfNotNull(g.studio?.name, g.date).joinToString(" · ").ifBlank { null },
                        rating100 = g.rating100,
                        onRate = { r -> vm.mutate({ it.copy(rating100 = r) }) { repo.rateGroup(id, r) } },
                        side = { CountLine("scenes" to g.sceneCount) },
                        below = {
                            InfoRow("Aliases", g.aliases)
                            InfoRow("Director", g.director)
                            InfoRow("Duration", g.duration?.let { formatDuration(it.toDouble()) })
                            g.studio?.let { s ->
                                LinkChip(s.name, onClick = { nav.studio(s.id) }, image = container.connection.media(s.imagePath))
                            }
                            ExpandableText(g.synopsis)
                            if (g.containingGroups.isNotEmpty()) {
                                Text("PART OF", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                                g.containingGroups.forEach { d ->
                                    LinkChip(d.group.name, onClick = { nav.group(d.group.id) })
                                }
                            }
                            if (g.subGroups.isNotEmpty()) {
                                Text("SUB-GROUPS", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                                g.subGroups.forEach { d ->
                                    LinkChip(d.group.name, onClick = { nav.group(d.group.id) })
                                }
                            }
                            TagChips(g.tags)
                            UrlChips(g.urls)
                        },
                    )
                }
            }
        }
    }
}

// ============================================================ Gallery

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryDetailScreen(id: String) {
    val container = LocalContext.current.container
    val repo = container.repository
    val nav = LocalNavigator.current
    val vm = appViewModel("gallery:$id") { c -> DetailViewModel { c.repository.gallery(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    ReloadOnReturn(vm)
    val ga = state.valueOrNull()

    DetailScaffold(title = ga?.displayTitle ?: "Gallery", onEdit = { nav.edit(EditKind.GALLERY, id) }) {
        LoadSwitch(state, vm::reload) { g: Gallery ->
            TabbedRelated(Scope.Gallery(id), listOf(TabSpec(EntityKind.IMAGES, g.imageCount)), ownerName = g.displayTitle) {
                item(span = headerSpan, key = "header") {
                    ProfileHeader(
                        image = container.connection.media(g.paths.cover),
                        aspect = 4f / 5f, fit = false, imageWidth = 120,
                        title = g.displayTitle,
                        subtitle = listOfNotNull(g.date, g.photographer, g.imageCount?.let { "$it images" })
                            .joinToString(" · ").ifBlank { null },
                        rating100 = g.rating100,
                        onRate = { r -> vm.mutate({ it.copy(rating100 = r) }) { repo.rateGallery(id, r) } },
                        below = {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                g.studio?.let { s ->
                                    LinkChip(s.name, onClick = { nav.studio(s.id) }, image = container.connection.media(s.imagePath))
                                }
                                g.performers.forEach { p ->
                                    LinkChip(p.name, onClick = { nav.performer(p.id) }, image = container.connection.media(p.imagePath))
                                }
                                g.scenes.forEach { sc ->
                                    LinkChip(sc.displayTitle, onClick = { nav.scene(sc.id) }, image = container.connection.media(sc.paths.screenshot))
                                }
                            }
                            InfoRow("Code", g.code)
                            InfoRow("Path", g.files.firstOrNull()?.path ?: g.folder?.path)
                            ExpandableText(g.details)
                            TagChips(g.tags)
                            UrlChips(g.urls)
                        },
                    )
                }
            }
        }
    }
}
