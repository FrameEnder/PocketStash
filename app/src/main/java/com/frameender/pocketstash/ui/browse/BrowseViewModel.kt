package com.frameender.pocketstash.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.data.StashRepository
import com.frameender.pocketstash.ui.common.friendly
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseState(
    val query: BrowseQuery,
    val items: List<CardItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
)

class BrowseViewModel(
    private val repo: StashRepository,
    val kind: EntityKind,
    val scope: Scope,
    initial: BrowseQuery,
    private val perPage: Int = 40,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseState(query = initial))
    val state: StateFlow<BrowseState> = _state

    private var job: Job? = null
    private var searchJob: Job? = null

    init {
        reload()
        // Going offline or back online changes the whole database: start the list over.
        viewModelScope.launch { repo.modeChanges.collect { reload() } }
    }

    fun reload(refreshing: Boolean = false) {
        job?.cancel()
        _state.update {
            it.copy(items = emptyList(), page = 0, total = 0, endReached = false, error = null, loading = false, refreshing = refreshing)
        }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        val next = s.page + 1
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val page = repo.browse(kind, scope, s.query, next, perPage)
                _state.update {
                    val items = (it.items + page.items).distinctBy { c -> c.id }
                    it.copy(
                        items = items, total = page.total, page = next, loading = false, refreshing = false,
                        endReached = page.items.size < perPage || items.size >= page.total,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.friendly()) }
            }
        }
    }

    private fun setQuery(q: BrowseQuery) {
        _state.update { it.copy(query = q) }
        reload()
    }

    fun setText(text: String) {
        _state.update { it.copy(query = it.query.copy(text = text)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            reload()
        }
    }

    fun setSort(key: String) {
        val q = _state.value.query
        if (key == q.sort && key == "random") setQuery(q.copy(seed = (1..99_999_999).random()))
        else setQuery(q.copy(sort = key))
    }

    fun toggleDirection() = setQuery(_state.value.query.let { it.copy(descending = !it.descending) })

    fun toggleQuick(id: String) {
        val q = _state.value.query
        setQuery(q.copy(quick = if (id in q.quick) q.quick - id else q.quick + id))
    }

    /** Lets other screens (rating changes etc.) patch a card in place. */
    fun patch(id: String, transform: (CardItem) -> CardItem) {
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) transform(it) else it }) }
    }
}
