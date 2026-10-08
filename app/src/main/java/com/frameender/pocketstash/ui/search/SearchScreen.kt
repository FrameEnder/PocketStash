package com.frameender.pocketstash.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.ui.browse.widthFactor
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.components.EmptyBox
import com.frameender.pocketstash.ui.components.EntityCard
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.components.SectionHeader
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchHit(val kind: EntityKind, val query: BrowseQuery, val items: List<CardItem>, val total: Int)

data class SearchState(
    val text: String = "",
    val loading: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val searched: Boolean = false,
)

/** Searches every entity type at once — the "find anything" box. */
class SearchViewModel(private val repo: StashRepository) : ViewModel() {
    private val order = listOf(
        EntityKind.SCENES, EntityKind.PERFORMERS, EntityKind.STUDIOS, EntityKind.TAGS,
        EntityKind.GROUPS, EntityKind.GALLERIES, EntityKind.MARKERS, EntityKind.IMAGES,
    )
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var job: Job? = null

    init {
        // Same search again against the new source when going offline or back online.
        viewModelScope.launch { repo.modeChanges.collect { setText(_state.value.text) } }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        job?.cancel()
        if (text.isBlank()) {
            _state.update { it.copy(hits = emptyList(), loading = false, searched = false) }
            return
        }
        job = viewModelScope.launch {
            delay(400)
            _state.update { it.copy(loading = true) }
            val hits = order.map { kind ->
                async {
                    val q = BrowseSpec.defaultSort(kind, Scope.None).copy(text = text)
                    runCatching { repo.browse(kind, Scope.None, q, 1, 12) }.getOrNull()
                        ?.let { SearchHit(kind, q, it.items, it.total) }
                }
            }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
            _state.update { it.copy(hits = hits, loading = false, searched = true) }
        }
    }
}

@Composable
fun SearchScreen() {
    val nav = LocalNavigator.current
    val vm = appViewModel("search") { SearchViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (state.text.isEmpty()) runCatching { focus.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.text,
                onValueChange = vm::setText,
                placeholder = { Text("Search everything") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.text.isNotEmpty()) IconButton(onClick = { vm.setText("") }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
                    unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            )
        }
        when {
            state.loading -> item { LoadingBox() }
            state.searched && state.hits.isEmpty() -> item { EmptyBox("No matches for “${state.text}”") }
            !state.searched -> item { EmptyBox("Scenes, performers, studios, tags, groups, galleries, markers and images") }
        }
        items(state.hits, key = { it.kind.name }) { hit ->
            Column(Modifier.fillMaxWidth()) {
                SectionHeader(hit.kind.label, hit.total, "See all") {
                    nav.browse(hit.kind, hit.query.sort, hit.query.descending, text = hit.query.text)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(hit.items, key = { _, it -> it.id }) { index, item ->
                        EntityCard(
                            item,
                            onClick = { nav.open(item, index, Scope.None, hit.query) },
                            modifier = Modifier.width((140 * widthFactor(hit.kind)).dp),
                        )
                    }
                }
            }
        }
    }
}
