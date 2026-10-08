package com.frameender.pocketstash.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.HomeLayouts
import com.frameender.pocketstash.data.HomeWidget
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.data.WidgetType
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.data.model.Stats
import com.frameender.pocketstash.ui.browse.widthFactor
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.EntityCard
import com.frameender.pocketstash.ui.components.ErrorBox
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.components.SectionHeader
import com.frameender.pocketstash.ui.components.StatTile
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A Home widget plus whatever it loaded. */
data class HomeBlock(
    val widget: HomeWidget,
    val items: List<CardItem> = emptyList(),
    val total: Int = 0,
)

data class HomeState(
    val blocks: List<HomeBlock> = emptyList(),
    val stats: Stats? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val repo: StashRepository) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    private var layout: List<HomeWidget> = emptyList()
    private var layoutJson: String? = null
    private var job: Job? = null

    init {
        viewModelScope.launch { repo.modeChanges.collect { load() } }
    }

    /** Called whenever the saved layout changes; reloads only if it really did. */
    fun setLayout(json: String) {
        if (json == layoutJson) return
        layoutJson = json
        layout = HomeLayouts.decode(json).filter { it.enabled }
        load()
    }

    fun load(refreshing: Boolean = false) {
        job?.cancel()
        _state.update { it.copy(loading = !refreshing && it.blocks.isEmpty(), refreshing = refreshing, error = null) }
        job = viewModelScope.launch {
            try {
                val widgets = layout
                val loaded = widgets.map { w ->
                    async {
                        when (w.widgetType) {
                            WidgetType.ROW -> runCatching {
                                repo.browse(w.entityKind, Scope.None, w.query(), 1, w.count.coerceIn(1, 60))
                            }.map { HomeBlock(w, it.items, it.total) }
                            else -> Result.success(HomeBlock(w))
                        }
                    }
                }.awaitAll()
                val stats = if (widgets.any { it.widgetType == WidgetType.STATS }) runCatching { repo.stats() }.getOrNull() else null
                // Rows that came back empty are hidden, like before.
                val ok = loaded.mapNotNull { it.getOrNull() }
                    .filter { it.widget.widgetType != WidgetType.ROW || it.items.isNotEmpty() }
                val firstError = loaded.firstNotNullOfOrNull { it.exceptionOrNull() }
                _state.update {
                    it.copy(
                        blocks = ok, stats = stats, loading = false, refreshing = false,
                        error = if (ok.isEmpty() && firstError != null) firstError.friendly() else null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.friendly()) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val nav = LocalNavigator.current
    val container = LocalContext.current.container
    val vm = appViewModel("home") { HomeViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val settings by container.settings.collectAsState()
    val update by container.updater.available.collectAsState()

    LaunchedEffect(settings?.homeLayout) { settings?.let { vm.setLayout(it.homeLayout) } }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(buildAnnotatedString {
                        append("Pocket")
                        withStyle(SpanStyle(color = Ink.Amber)) { append("Stash") }
                    }, style = MaterialTheme.typography.headlineSmall)
                },
                actions = {
                    IconButton(onClick = { nav.homeLayout() }) { Icon(Icons.Outlined.Dashboard, "Customize Home") }
                    IconButton(onClick = { nav.settings() }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.load(refreshing = true) },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                update?.let { info ->
                    item(key = "update") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Ink.AmberDim)
                                .clickable { nav.updates() }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.SystemUpdate, null, tint = Ink.Text)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Update available", style = MaterialTheme.typography.titleSmall)
                                Text("${info.title} · build ${info.versionCode}", style = MaterialTheme.typography.labelMedium, color = Ink.Text.copy(alpha = 0.8f))
                            }
                            Text("View", color = Ink.Text, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                when {
                    state.loading -> item(key = "loading") { LoadingBox() }
                    state.error != null -> item(key = "error") { ErrorBox(state.error!!, onRetry = { vm.load() }) }
                    state.blocks.isEmpty() -> item(key = "empty") {
                        ErrorBox("Home is empty. Add sections with the layout button up top.", onRetry = null)
                    }
                }
                items(state.blocks, key = { it.widget.id }) { block -> HomeBlockView(block, state.stats) }
            }
        }
    }
}

@Composable
private fun HomeBlockView(block: HomeBlock, stats: Stats?) {
    // One Column per lazy item: siblings emitted directly into an item would overlap.
    Column(Modifier.fillMaxWidth()) { HomeBlockContent(block, stats) }
}

@Composable
private fun HomeBlockContent(block: HomeBlock, stats: Stats?) {
    val nav = LocalNavigator.current
    val w = block.widget
    when (w.widgetType) {
        WidgetType.ROW -> {
            val q = remember(w) { w.query() }
            SectionHeader(w.displayTitle, block.total, "See all") {
                nav.browse(w.entityKind, w.sort, w.descending, w.quick.toSet(), w.text)
            }
            val base = when (w.size) { "s" -> 115; "l" -> 200; else -> 150 }
            val width = (base * widthFactor(w.entityKind)).dp
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(block.items, key = { _, it -> it.id }) { index, item ->
                    EntityCard(
                        item,
                        onClick = { nav.open(item, index, Scope.None, q) },
                        modifier = Modifier.width(width),
                        showText = w.size != "s" || w.entityKind != EntityKind.IMAGES,
                    )
                }
            }
        }
        WidgetType.STATS -> {
            SectionHeader(w.displayTitle)
            if (stats == null) {
                Text("Stats unavailable", color = Ink.Muted, modifier = Modifier.padding(horizontal = 16.dp))
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile("Scenes", "%,d".format(stats.sceneCount))
                    StatTile("Library", formatBytes(stats.scenesSize + stats.imagesSize))
                    StatTile("Runtime", "%,.0f h".format(stats.scenesDuration / 3600))
                    StatTile("Performers", "%,d".format(stats.performerCount))
                    StatTile("Images", "%,d".format(stats.imageCount))
                    StatTile("Watched", "%,.1f h".format(stats.totalPlayDuration / 3600))
                    StatTile("Plays", "%,d".format(stats.totalPlayCount))
                    StatTile("O count", "%,d".format(stats.totalOCount))
                }
            }
        }
        WidgetType.SHORTCUTS -> {
            SectionHeader(w.displayTitle)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EntityKind.entries.forEach { k ->
                    Text(
                        k.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Ink.Surface)
                            .border(1.dp, Ink.Line, RoundedCornerShape(50))
                            .clickable { nav.browse(k) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
