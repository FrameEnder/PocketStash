package com.frameender.pocketstash.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.ui.browse.BrowseGrid
import com.frameender.pocketstash.ui.browse.BrowseViewModel
import com.frameender.pocketstash.ui.common.Load
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.common.valueOrNull
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.ErrorBox
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.nav.encode
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Loads one entity and supports optimistic edits (rating, favorite, O…). */
class DetailViewModel<T : Any>(private val loader: suspend () -> T) : ViewModel() {
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            if (_state.value !is Load.Ready<*>) _state.value = Load.Loading
            _state.value = try {
                Load.Ready(loader())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.friendly())
            }
        }
    }

    /** Apply [optimistic] immediately, run [action]; roll back on failure. */
    fun mutate(optimistic: (T) -> T, action: suspend () -> Unit) {
        val before = _state.value.valueOrNull() ?: return
        _state.value = Load.Ready(optimistic(before))
        viewModelScope.launch {
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = Load.Ready(before)
                _message.value = e.friendly()
            }
        }
    }

    fun clearMessage() { _message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onEdit: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val nav = LocalNavigator.current
    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    actions()
                    if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), content = content)
    }
}

/** Re-fetch when coming back to this screen (e.g. after saving an edit), but not on first show. */
@Composable
fun ReloadOnReturn(vm: DetailViewModel<*>) {
    var first by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (first) first = false else vm.reload()
    }
}

@Composable
fun <T : Any> LoadSwitch(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(state.message, onRetry)
        is Load.Ready<T> -> content(state.value)
    }
}

data class TabSpec(val kind: EntityKind, val count: Int?)

/**
 * Entity detail page body: a header (info block) on top of a tabbed,
 * infinitely scrolling grid of related items — all in one scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabbedRelated(
    scope: Scope,
    tabs: List<TabSpec>,
    ownerName: String = "",
    header: LazyGridScope.() -> Unit,
) {
    val visible = tabs.filter { it.count == null || it.count > 0 }.ifEmpty { tabs.take(1) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tab = visible[selected.coerceIn(0, visible.lastIndex)]
    val (t, id) = scope.encode()
    val vm = appViewModel("rel:$t:$id:${tab.kind}") {
        BrowseViewModel(it.repository, tab.kind, scope, BrowseSpec.defaultSort(tab.kind, scope))
    }
    BrowseGrid(
        vm, showSearch = false,
        offlineLabel = listOf(ownerName, tab.kind.label).filter { it.isNotBlank() }.joinToString(" · "),
        header = {
        header()
        if (visible.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "tabs") {
                val current = selected.coerceIn(0, visible.lastIndex)
                ScrollableTabRow(
                    selectedTabIndex = current,
                    containerColor = Ink.Bg,
                    contentColor = Ink.Text,
                    edgePadding = 0.dp,
                    divider = {},
                    indicator = { positions ->
                        if (current < positions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(positions[current]),
                                color = Ink.Amber,
                            )
                        }
                    },
                ) {
                    visible.forEachIndexed { i, spec ->
                        Tab(
                            selected = i == selected,
                            onClick = { selected = i },
                            text = {
                                Text(
                                    spec.kind.label + (spec.count?.let { " $it" } ?: ""),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                            selectedContentColor = Ink.Amber,
                            unselectedContentColor = Ink.Muted,
                        )
                    }
                }
            }
        }
    })
}

