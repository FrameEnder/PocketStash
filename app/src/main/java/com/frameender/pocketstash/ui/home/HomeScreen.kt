package com.frameender.pocketstash.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.ui.browse.widthFactor
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.EntityCard
import com.frameender.pocketstash.ui.components.ErrorBox
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.components.SectionHeader
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeSection(
    val title: String,
    val kind: EntityKind,
    val query: BrowseQuery,
    val items: List<CardItem> = emptyList(),
    val total: Int = 0,
)

data class HomeState(
    val sections: List<HomeSection> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val repo: StashRepository) : ViewModel() {

    private fun specs() = listOf(
        HomeSection("Continue watching", EntityKind.SCENES, BrowseQuery(sort = "last_played_at", quick = setOf("inprogress"))),
        HomeSection("Recently added", EntityKind.SCENES, BrowseQuery(sort = "created_at")),
        HomeSection("Newest releases", EntityKind.SCENES, BrowseQuery(sort = "date")),
        HomeSection("Random picks", EntityKind.SCENES, BrowseQuery(sort = "random")),
        HomeSection("Favorite performers", EntityKind.PERFORMERS, BrowseQuery(sort = "latest_scene", quick = setOf("fav"))),
        HomeSection("Top rated", EntityKind.SCENES, BrowseQuery(sort = "rating", quick = setOf("rated"))),
        HomeSection("Recent galleries", EntityKind.GALLERIES, BrowseQuery(sort = "created_at")),
        HomeSection("Recent markers", EntityKind.MARKERS, BrowseQuery(sort = "created_at")),
        HomeSection("Favorite studios", EntityKind.STUDIOS, BrowseQuery(sort = "latest_scene", quick = setOf("fav"))),
    )

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init { load() }

    fun load(refreshing: Boolean = false) {
        _state.update { it.copy(loading = !refreshing && it.sections.isEmpty(), refreshing = refreshing, error = null) }
        viewModelScope.launch {
            try {
                val loaded = specs().map { spec ->
                    async {
                        runCatching { repo.browse(spec.kind, Scope.None, spec.query, 1, 20) }
                            .map { spec.copy(items = it.items, total = it.total) }
                    }
                }.awaitAll()
                val ok = loaded.mapNotNull { it.getOrNull() }.filter { it.items.isNotEmpty() }
                val firstError = loaded.firstNotNullOfOrNull { it.exceptionOrNull() }
                _state.update {
                    it.copy(
                        sections = ok, loading = false, refreshing = false,
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
    val vm = appViewModel("home") { HomeViewModel(it.repository) }
    val state by vm.state.collectAsState()

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
                actions = { IconButton(onClick = { nav.settings() }) { Icon(Icons.Outlined.Settings, "Settings") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.load(refreshing = true) },
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            when {
                state.loading -> LoadingBox()
                state.error != null -> ErrorBox(state.error!!, onRetry = { vm.load() })
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.sections, key = { it.title }) { section ->
                        SectionHeader(section.title, section.total, "See all") {
                            nav.browse(section.kind, section.query.sort, section.query.descending, section.query.quick)
                        }
                        val w = (150 * widthFactor(section.kind)).dp
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(section.items, key = { _, it -> it.id }) { index, item ->
                                EntityCard(
                                    item,
                                    onClick = { nav.open(item, index, Scope.None, section.query) },
                                    modifier = Modifier.width(w),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

