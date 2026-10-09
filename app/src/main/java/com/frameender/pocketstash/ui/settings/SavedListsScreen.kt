package com.frameender.pocketstash.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Format
import com.frameender.pocketstash.data.OfflineCollection
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink

private fun kindIcon(k: EntityKind): ImageVector = when (k) {
    EntityKind.SCENES, EntityKind.MARKERS -> Icons.Filled.VideoLibrary
    EntityKind.PERFORMERS -> Icons.Filled.People
    EntityKind.IMAGES, EntityKind.GALLERIES -> Icons.Filled.Image
    else -> Icons.Filled.Movie
}

/**
 * Every list saved for offline, the save queue and the save in progress. Long-press a list
 * (or use Select) to pick several, then refresh or remove them together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedListsScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = context.container
    val saver = container.offlineSaver
    val s = settingsState()
    val collections = saver.collections(s)
    val saving by saver.progress.collectAsState()
    val waiting by saver.queue.collectAsState()
    val offline by container.connection.offline.collectAsState()

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf<List<OfflineCollection>?>(null) }
    // Drop selections of lists that went away.
    val picked = collections.filter { it.key in selected }

    fun toggle(c: OfflineCollection) {
        selected = if (c.key in selected) selected - c.key else selected + c.key
        if (selected.isEmpty()) selecting = false
    }

    fun endSelection() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = selecting) { endSelection() }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${picked.size} selected" else "Saved lists") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { endSelection() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    } else {
                        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            selected = if (picked.size == collections.size) emptySet() else collections.map { it.key }.toSet()
                            if (selected.isEmpty()) selecting = false
                        }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(
                            enabled = picked.isNotEmpty() && !offline,
                            onClick = {
                                saver.enqueue(picked)
                                endSelection()
                            },
                        ) { Icon(Icons.Filled.Refresh, "Refresh selected") }
                        IconButton(enabled = picked.isNotEmpty(), onClick = { confirmRemove = picked }) {
                            Icon(Icons.Filled.Delete, "Remove selected", tint = if (picked.isNotEmpty()) Ink.Red else Ink.Muted)
                        }
                    } else if (collections.isNotEmpty()) {
                        TextButton(onClick = { selecting = true }) { Text("Select") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---------- saving now ----------
            saving?.let { p ->
                item(key = "saving") {
                    SettingsCard {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Ink.Teal, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Saving “${p.label}”" + if (p.total > 0) " · ${p.done}/${p.total}" else "",
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { saver.cancel() }) { Text("Stop", maxLines = 1) }
                            }
                            if (p.total > 0) {
                                LinearProgressIndicator(
                                    progress = { p.done.toFloat() / p.total }, color = Ink.Teal, trackColor = Ink.Raised,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ---------- the queue ----------
            if (waiting.isNotEmpty()) {
                item(key = "queue") {
                    SettingsCard {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (saving == null) "Waiting to save · ${waiting.size}" else "Up next · ${waiting.size}",
                                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                            )
                            if (saving == null && !offline) TextButton(onClick = { saver.startWorker() }) { Text("Resume", maxLines = 1) }
                            TextButton(onClick = { saver.clearQueue() }) { Text("Clear", color = Ink.Red, maxLines = 1) }
                        }
                        waiting.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.label, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { saver.dequeue(c) }) { Icon(Icons.Filled.Close, "Remove from queue", tint = Ink.Muted) }
                            }
                        }
                        if (saving == null) {
                            Hint(
                                if (offline) "Waiting for your server: saving needs it." else "Paused, or waiting for a connection. Tap Resume to carry on.",
                                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }

            // ---------- saved ----------
            if (collections.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Bookmarks, null, tint = Ink.Muted, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Nothing saved yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap the save-for-offline button next to the sort and filter chips on any list " +
                                "(Scenes, a performer's galleries, a gallery's images…). Scene lists can download their videos too.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                item(key = "hint") {
                    Hint(
                        if (selecting) "Tap lists to select them." else "Long-press a list to select several. Removing a list also removes the info only it kept; videos stay in Downloads.",
                        Modifier.padding(bottom = 2.dp),
                    )
                }
                items(collections, key = { it.key }) { c ->
                    val queued = waiting.any { it.key == c.key } || saving?.label?.startsWith(c.label) == true
                    CollectionRow(
                        c = c,
                        selecting = selecting,
                        checked = c.key in selected,
                        queued = queued,
                        canRefresh = !offline && !queued,
                        onToggle = { toggle(c) },
                        onLongPress = {
                            selecting = true
                            selected = selected + c.key
                        },
                        onRefresh = { saver.refresh(c) },
                        onRemove = { confirmRemove = listOf(c) },
                    )
                }
            }
        }
    }

    confirmRemove?.let { lists ->
        val one = lists.singleOrNull()
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(if (one != null) "Remove saved list?" else "Remove ${lists.size} saved lists?") },
            text = {
                Text(
                    (if (one != null) "“${one.label}” and the info it saved are" else "These lists and the info they saved are") +
                        " removed from this phone. Anything another saved list or a download still uses is kept." +
                        if (lists.any { it.downloadQuality != null }) " Downloaded videos stay; delete them in Downloads." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    lists.forEach { saver.forget(it) }
                    context.toast(if (one != null) "Removed “${one.label}”" else "Removed ${lists.size} lists")
                    confirmRemove = null
                    endSelection()
                }) { Text("Remove", color = Ink.Red, maxLines = 1) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel", maxLines = 1) } },
            containerColor = Ink.Raised,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionRow(
    c: OfflineCollection,
    selecting: Boolean,
    checked: Boolean,
    queued: Boolean,
    canRefresh: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    val kind = c.entityKind
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (checked) Ink.AmberDim.copy(alpha = 0.35f) else Ink.Surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = { if (!selecting) onLongPress() else onToggle() }),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(
                    checked = checked, onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = Ink.Amber, checkmarkColor = Ink.OnAmber),
                )
            } else {
                IconTile(kindIcon(kind), if (c.scopeType == "none") Ink.Amber else Ink.Violet, size = 38)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        "${c.count} ${kind.label.lowercase()}",
                        when {
                            kind == EntityKind.IMAGES || kind == EntityKind.GALLERIES -> if (c.fullImages) "full images" else "thumbnails"
                            else -> null
                        },
                        "with videos".takeIf { c.downloadQuality != null },
                        if (queued) "in the save queue" else c.savedAt.takeIf { it > 0 }?.let { Format.agoMillis(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (queued) Ink.Teal else Ink.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                IconButton(onClick = onRefresh, enabled = canRefresh) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = if (canRefresh) Ink.Muted else Ink.Line)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove", tint = Ink.Red) }
            }
        }
    }
}
