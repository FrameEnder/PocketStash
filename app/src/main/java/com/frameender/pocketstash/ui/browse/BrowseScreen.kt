package com.frameender.pocketstash.ui.browse

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.filled.Add
import com.frameender.pocketstash.data.EditKind
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    kind: EntityKind,
    initial: BrowseQuery? = null,
    showBack: Boolean = false,
    vmKey: String = "browse:${kind.name}",
) {
    val nav = LocalNavigator.current
    val vm = appViewModel(vmKey) {
        BrowseViewModel(it.repository, kind, Scope.None, initial ?: BrowseSpec.defaultSort(kind, Scope.None))
    }
    val creatable = when (kind) {
        EntityKind.PERFORMERS -> EditKind.PERFORMER
        EntityKind.STUDIOS -> EditKind.STUDIO
        EntityKind.TAGS -> EditKind.TAG
        EntityKind.GROUPS -> EditKind.GROUP
        EntityKind.GALLERIES -> EditKind.GALLERY
        else -> null
    }
    Scaffold(
        containerColor = Ink.Bg,
        floatingActionButton = {
            if (creatable != null) {
                ExtendedFloatingActionButton(
                    onClick = { nav.create(creatable) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New ${creatable.label.lowercase()}") },
                    containerColor = Ink.Amber,
                    contentColor = Ink.OnAmber,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(kind.label) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        BrowseGrid(vm, Modifier.padding(pad))
    }
}
