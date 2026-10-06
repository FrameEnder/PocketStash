package com.frameender.pocketstash.ui.detail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.data.formatDuration
import com.frameender.pocketstash.data.model.Scene
import com.frameender.pocketstash.data.resolutionLabel
import com.frameender.pocketstash.ui.common.valueOrNull
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.components.EntityCard
import com.frameender.pocketstash.ui.components.InfoRow
import com.frameender.pocketstash.ui.components.LinkChip
import com.frameender.pocketstash.ui.components.Pill
import com.frameender.pocketstash.ui.components.ScrimBrush
import com.frameender.pocketstash.ui.components.SectionHeader
import com.frameender.pocketstash.ui.components.StarRating
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind

@Composable
fun SceneDetailScreen(id: String) {
    val container = LocalContext.current.container
    val repo = container.repository
    val vm = appViewModel("scene:$id") { c -> DetailViewModel { c.repository.scene(id) } }
    val state by vm.state.collectAsState()
    MutationToasts(vm)
    // Pick up the new resume point / play count after returning from the player.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.reload() }

    val sc = state.valueOrNull()
    DetailScaffold(title = sc?.displayTitle ?: "Scene") {
        LoadSwitch(state, vm::reload) { scene ->
            SceneBody(
                scene,
                onRate = { r -> vm.mutate({ it.copy(rating100 = r) }) { repo.rateScene(id, r) } },
                onO = { delta ->
                    vm.mutate({ it.copy(oCounter = ((it.oCounter ?: 0) + delta).coerceAtLeast(0)) }) {
                        if (delta > 0) repo.sceneAddO(id) else repo.sceneDeleteO(id)
                    }
                },
                onOrganized = { v -> vm.mutate({ it.copy(organized = v) }) { repo.setSceneOrganized(id, v) } },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceneBody(
    s: Scene,
    onRate: (Int?) -> Unit,
    onO: (Int) -> Unit,
    onOrganized: (Boolean) -> Unit,
) {
    val nav = LocalNavigator.current
    val conn = LocalContext.current.container.connection
    val file = s.files.firstOrNull()
    val resume = s.resumeTime?.takeIf { it > 5 && (s.duration == null || it < s.duration!! - 10) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        // ---------------------------------------------------------------- hero
        item(key = "hero") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
                    .clickable { nav.play(s.id, resume) },
            ) {
                AsyncImage(
                    model = conn.media(s.paths.screenshot), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(ScrimBrush))
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Ink.Amber),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, "Play", tint = Ink.Bg, modifier = Modifier.size(44.dp))
                }
                Row(
                    Modifier.align(Alignment.BottomStart).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    file?.height?.let { Pill(resolutionLabel(it)) }
                    s.duration?.let { Pill(formatDuration(it)) }
                    resume?.let { Pill("Resume ${formatDuration(it)}", accent = true) }
                }
                if (resume != null) {
                    IconButton(
                        onClick = { nav.play(s.id, 0.0) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    ) { Icon(Icons.Filled.Replay, "Play from start", tint = Color.White) }
                }
                if (resume != null && s.duration != null && s.duration!! > 0) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(Modifier.fillMaxWidth((resume / s.duration!!).toFloat()).height(3.dp).background(Ink.Amber))
                    }
                }
            }
        }

        // ---------------------------------------------------------------- title block
        item(key = "title") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(s.displayTitle, style = MaterialTheme.typography.headlineSmall)
                val meta = listOfNotNull(s.date, s.code, s.director?.let { "dir. $it" }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                s.studio?.let { st ->
                    Spacer(Modifier.height(8.dp))
                    LinkChip(st.name, onClick = { nav.studio(st.id) }, image = conn.media(st.imagePath))
                }
                Spacer(Modifier.height(10.dp))
                StarRating(s.rating100, onRate, size = 30.dp)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // O counter: tap adds, minus removes the last one.
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink.Raised)
                            .border(1.dp, Ink.Line, RoundedCornerShape(50)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.clickable { onO(1) }.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.WaterDrop, null, tint = Ink.Amber, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("${s.oCounter ?: 0}", style = MaterialTheme.typography.labelLarge)
                        }
                        if ((s.oCounter ?: 0) > 0) {
                            Icon(
                                Icons.Filled.Remove, "Remove O", tint = Ink.Muted,
                                modifier = Modifier.clip(CircleShape).clickable { onO(-1) }.padding(6.dp).size(18.dp),
                            )
                        }
                    }
                    AssistChip(
                        onClick = { onOrganized(!s.organized) },
                        label = { Text(if (s.organized) "Organized" else "Not organized") },
                        leadingIcon = {
                            Icon(
                                if (s.organized) Icons.Outlined.CheckCircle else Icons.Outlined.Circle, null,
                                tint = if (s.organized) Ink.Green else Ink.Muted, modifier = Modifier.size(18.dp),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Ink.Raised, labelColor = Ink.Text),
                        border = null,
                    )
                    Text(
                        "▶ ${s.playCount ?: 0} plays" + (s.playDuration?.takeIf { it > 0 }?.let { " · ${formatDuration(it)} watched" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }

        // ---------------------------------------------------------------- performers
        if (s.performers.isNotEmpty()) {
            item(key = "performers") {
                SectionHeader("Performers", s.performers.size)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.performers, key = { it.id }) { p ->
                        EntityCard(
                            CardItem(
                                id = p.id, kind = EntityKind.PERFORMERS, title = p.name,
                                subtitle = p.disambiguation, image = conn.media(p.imagePath),
                                aspect = 2f / 3f, favorite = p.favorite,
                            ),
                            onClick = { nav.performer(p.id) },
                            modifier = Modifier.width(112.dp),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- tags
        if (s.tags.isNotEmpty()) {
            item(key = "tags") {
                SectionHeader("Tags", s.tags.size)
                Column(Modifier.padding(horizontal = 16.dp)) { TagChips(s.tags) }
            }
        }

        // ---------------------------------------------------------------- markers
        if (s.markers.isNotEmpty()) {
            item(key = "markers-h") { SectionHeader("Markers", s.markers.size) }
            items(s.markers.sortedBy { it.seconds }, key = { "m" + it.id }) { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.play(s.id, m.seconds) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = conn.media(m.screenshot), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Ink.Surface),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.title.ifBlank { m.primaryTag?.name ?: "Marker" },
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(m.primaryTag?.name?.takeIf { m.title.isNotBlank() }, m.tags.joinToString { it.name }.ifBlank { null })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        formatDuration(m.seconds) + (m.endSeconds?.let { "–" + formatDuration(it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = Ink.Amber,
                    )
                }
            }
        }

        // ---------------------------------------------------------------- galleries / groups
        if (s.galleries.isNotEmpty() || s.groups.isNotEmpty()) {
            item(key = "links") {
                SectionHeader("Linked")
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.groups.forEach { g ->
                        LinkChip(
                            g.group.name + (g.sceneIndex?.let { " #$it" } ?: ""),
                            onClick = { nav.group(g.group.id) },
                            image = conn.media(g.group.frontImagePath),
                            icon = Icons.Outlined.Movie,
                        )
                    }
                    s.galleries.forEach { g ->
                        LinkChip(
                            g.title?.ifBlank { null } ?: "Gallery ${g.id}",
                            onClick = { nav.gallery(g.id) },
                            image = conn.media(g.paths?.cover),
                            icon = Icons.Outlined.Collections,
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- details + file
        item(key = "details") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (!s.details.isNullOrBlank()) {
                    SectionHeaderInline("Details")
                    ExpandableText(s.details, collapsedLines = 6)
                }
                UrlChips(s.urls)
                if (file != null) {
                    SectionHeaderInline("File")
                    InfoRow("Path", file.path)
                    InfoRow("Size", file.size?.let { formatBytes(it.toDouble()) })
                    InfoRow("Resolution", if (file.width != null && file.height != null) "${file.width}×${file.height}" else null)
                    InfoRow("Video", listOfNotNull(file.videoCodec, file.frameRate?.let { "%.2f fps".format(it) }).joinToString(" · "))
                    InfoRow("Audio", file.audioCodec)
                    InfoRow("Bitrate", file.bitRate?.let { "%.1f Mb/s".format(it / 1_000_000.0) })
                    InfoRow("Container", file.format)
                    if (s.files.size > 1) InfoRow("Files", "${s.files.size} files attached")
                }
                InfoRow("Added", s.createdAt?.take(10))
                InfoRow("Last played", s.lastPlayedAt?.take(16)?.replace('T', ' '))
            }
        }
    }
}

@Composable
private fun SectionHeaderInline(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Ink.Amber,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
