package com.frameender.pocketstash.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import com.frameender.pocketstash.ui.components.Pill
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.model.Stats
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink

private data class Tile(val kind: EntityKind, val icon: ImageVector, val count: (Stats) -> Int?)

private val tiles = listOf(
    Tile(EntityKind.SCENES, Icons.Outlined.VideoLibrary) { it.sceneCount },
    Tile(EntityKind.PERFORMERS, Icons.Outlined.People) { it.performerCount },
    Tile(EntityKind.STUDIOS, Icons.Outlined.Business) { it.studioCount },
    Tile(EntityKind.TAGS, Icons.Outlined.LocalOffer) { it.tagCount },
    Tile(EntityKind.GROUPS, Icons.Outlined.Movie) { it.groupCount },
    Tile(EntityKind.GALLERIES, Icons.Outlined.Collections) { it.galleryCount },
    Tile(EntityKind.IMAGES, Icons.Outlined.Image) { it.imageCount },
    Tile(EntityKind.MARKERS, Icons.Outlined.Bookmarks) { null },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val nav = LocalNavigator.current
    val container = LocalContext.current.container
    val repo = container.repository
    val offline by container.connection.offline.collectAsState()
    val downloads by container.downloads.items.collectAsState()
    var stats by remember { mutableStateOf<Stats?>(null) }
    LaunchedEffect(offline) { stats = runCatching { repo.stats() }.getOrNull() }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = { IconButton(onClick = { nav.settings() }) { Icon(Icons.Outlined.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "downloads") {
                val done = downloads.count { it.done }
                val active = downloads.count { !it.done && it.status != "failed" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.Surface)
                        .border(1.dp, if (offline) Ink.Amber.copy(alpha = 0.5f) else Ink.Line, RoundedCornerShape(14.dp))
                        .clickable { nav.downloads() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DownloadForOffline, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Downloads", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                downloads.isEmpty() -> "Download scenes to watch without your server"
                                active > 0 -> "$done ready · $active downloading"
                                else -> "$done ready to watch offline"
                            },
                            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                        )
                    }
                    if (offline) Pill("OFFLINE", accent = true)
                }
            }
            items(tiles, key = { it.kind.name }) { tile ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.Surface)
                        .border(1.dp, Ink.Line, RoundedCornerShape(14.dp))
                        .clickable { nav.browse(tile.kind) }
                        .padding(16.dp),
                ) {
                    Icon(tile.icon, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(tile.kind.label, style = MaterialTheme.typography.titleMedium)
                    val count = stats?.let(tile.count)
                    Text(
                        count?.let { "%,d".format(it) } ?: " ",
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink.Muted,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }
    }
}
