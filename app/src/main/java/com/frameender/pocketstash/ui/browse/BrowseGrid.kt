package com.frameender.pocketstash.ui.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DownloadForOffline
import android.widget.Toast
import com.frameender.pocketstash.data.OfflineCollection
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.rememberCoroutineScope
import com.frameender.pocketstash.player.PlayQueue
import com.frameender.pocketstash.player.QueueEntry
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.ui.components.EmptyBox
import com.frameender.pocketstash.ui.components.EntityCard
import com.frameender.pocketstash.ui.components.ErrorBox
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.flow.distinctUntilChanged

/** "Play all" lines up at most this many scenes. */
private const val MAX_QUEUE = 500

/** Card width multiplier relative to the user's base grid width. */
fun widthFactor(kind: EntityKind): Float = when (kind) {
    EntityKind.SCENES, EntityKind.MARKERS -> 1.55f
    EntityKind.STUDIOS -> 1.2f
    EntityKind.IMAGES -> 0.85f
    else -> 1f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseGrid(
    vm: BrowseViewModel,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true,
    /** Name used for "Save for offline" (e.g. "Scenes" or "Jane Doe · Scenes"). */
    offlineLabel: String = vm.kind.label,
    header: LazyGridScope.() -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val settings by LocalContext.current.container.settings.collectAsState()
    val nav = LocalNavigator.current
    val base = settings?.gridCardWidth ?: 170
    val minSize = (base * widthFactor(vm.kind)).dp
    val grid = rememberLazyGridState()

    // Infinite scroll: request the next page when we get close to the end.
    LaunchedEffect(grid, vm) {
        // Emits the item count whenever we're near the end, so a page that
        // doesn't fill the screen still triggers the next one.
        snapshotFlow {
            val info = grid.layoutInfo
            val near = (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8
            if (near) info.totalItemsCount else -1
        }
            .distinctUntilChanged()
            .collect { count -> if (count >= 0) vm.loadMore() }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { vm.reload(refreshing = true) },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            header()
            item(span = { GridItemSpan(maxLineSpan) }, key = "controls") {
                BrowseControls(vm, state, showSearch, offlineLabel)
            }
            itemsIndexed(state.items, key = { _, it -> "${it.kind}:${it.id}" }) { index, item ->
                EntityCard(item, onClick = { nav.open(item, index, vm.scope, state.query) })
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                when {
                    state.error != null -> ErrorBox(state.error!!, onRetry = { vm.loadMore() })
                    state.loading && !state.refreshing -> LoadingBox()
                    state.endReached && state.items.isEmpty() -> EmptyBox("Nothing here")
                    else -> Spacer(Modifier.size(1.dp))
                }
            }
        }
    }
}

@Composable
private fun BrowseControls(vm: BrowseViewModel, state: BrowseState, showSearch: Boolean, offlineLabel: String) {
    val offlineNow by LocalContext.current.container.connection.offline.collectAsState()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var queueing by remember { mutableStateOf(false) }

    /** "Play all" / "Shuffle": line up every scene in this list (as filtered) and start playing. */
    fun playAll(shuffle: Boolean) {
        if (queueing) return
        queueing = true
        scope.launch {
            try {
                val repo = context.container.repository
                val offline = repo.isOffline
                val entries = ArrayList<QueueEntry>()
                var page = 1
                while (entries.size < MAX_QUEUE) {
                    val p = repo.browse(EntityKind.SCENES, vm.scope, state.query, page, 100)
                    // Offline, only downloaded scenes can play.
                    p.items.filter { !offline || !it.infoOnly }.forEach { entries += QueueEntry(it.id, it.title, it.image) }
                    if (p.items.size < 100 || page * 100 >= p.total) break
                    page++
                }
                if (entries.isEmpty()) {
                    Toast.makeText(context, if (offline) "Nothing downloaded to play here" else "Nothing to play", Toast.LENGTH_SHORT).show()
                } else {
                    PlayQueue.start(entries.take(MAX_QUEUE), shuffle = shuffle, from = offlineLabel)
                    nav.playQueue()
                }
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't load the list", Toast.LENGTH_SHORT).show()
            } finally {
                queueing = false
            }
        }
    }
    var saving by remember { mutableStateOf(false) }
    val sorts = remember(vm.kind) { BrowseSpec.sorts(vm.kind) }
    val quick = remember(vm.kind) { BrowseSpec.quickFilters(vm.kind) }
    var sortOpen by remember { mutableStateOf(false) }
    val q = state.query

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (showSearch) {
            OutlinedTextField(
                value = q.text,
                onValueChange = vm::setText,
                placeholder = { Text("Search ${vm.kind.label.lowercase()}") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (q.text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Ink.Line,
                    focusedBorderColor = Ink.Amber,
                    unfocusedContainerColor = Ink.Surface,
                    focusedContainerColor = Ink.Surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                FilterChip(
                    selected = true,
                    onClick = { sortOpen = true },
                    label = { Text(sorts.firstOrNull { it.key == q.sort }?.label ?: q.sort) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Ink.Raised, selectedLabelColor = Ink.Text, selectedLeadingIconColor = Ink.Amber,
                    ),
                )
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    sorts.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.label, color = if (opt.key == q.sort) Ink.Amber else Ink.Text) },
                            onClick = { sortOpen = false; vm.setSort(opt.key) },
                        )
                    }
                }
            }
            if (q.sort == "random") {
                IconButton(onClick = { vm.setSort("random") }) { Icon(Icons.Filled.Shuffle, "Reshuffle", tint = Ink.Amber) }
            } else {
                IconButton(onClick = vm::toggleDirection) {
                    Icon(
                        if (q.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        if (q.descending) "Descending" else "Ascending",
                        tint = Ink.Amber,
                    )
                }
            }
            quick.forEach { f ->
                FilterChip(
                    selected = f.id in q.quick,
                    onClick = { vm.toggleQuick(f.id) },
                    label = { Text(f.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Ink.AmberDim, selectedLabelColor = Ink.Text,
                    ),
                )
            }
            Spacer(Modifier.width(4.dp))
            if (vm.kind == EntityKind.SCENES && state.total > 0) {
                IconButton(onClick = { playAll(false) }, enabled = !queueing) {
                    Icon(Icons.Filled.PlayArrow, "Play all", tint = Ink.Amber)
                }
                IconButton(onClick = { playAll(true) }, enabled = !queueing) {
                    Icon(Icons.Filled.Shuffle, "Shuffle all", tint = Ink.Amber)
                }
            }
            Text(
                if (state.total > 0) "${state.total}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = Ink.Muted,
            )
            // Saving needs the server; offline, everything shown is already on the phone.
            if (!offlineNow) IconButton(onClick = {
                // A random order can't be saved: it's different every time it's asked for.
                if (q.sort == "random") Toast.makeText(context, "Pick a sort other than Random to save this list", Toast.LENGTH_LONG).show()
                else saving = true
            }) {
                Icon(Icons.Filled.DownloadForOffline, "Save for offline", tint = Ink.Muted)
            }
        }
    }

    if (saving) {
        val (scopeType, scopeId) = OfflineCollection.scopeKey(vm.scope)
        val sortLabel = sorts.firstOrNull { it.key == q.sort }?.label ?: q.sort
        val label = listOfNotNull(
            offlineLabel,
            q.text.takeIf { it.isNotBlank() }?.let { "“${it.trim()}”" },
            quick.filter { it.id in q.quick }.joinToString(", ") { it.label }.ifBlank { null },
        ).joinToString(" · ")
        SaveOfflineDialog(
            base = OfflineCollection(
                label = "$label ($sortLabel)",
                kind = vm.kind.name, scopeType = scopeType, scopeId = scopeId,
                sort = q.sort, descending = q.descending, quick = q.quick.toList(), text = q.text.trim(),
                max = 0,
            ),
            total = state.total.takeIf { it > 0 },
            onDismiss = { saving = false },
        )
    }
}
