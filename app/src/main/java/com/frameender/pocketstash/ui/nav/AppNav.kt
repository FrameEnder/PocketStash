package com.frameender.pocketstash.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.ui.browse.BrowseScreen
import com.frameender.pocketstash.ui.detail.GalleryDetailScreen
import com.frameender.pocketstash.ui.detail.GroupDetailScreen
import com.frameender.pocketstash.ui.detail.PerformerDetailScreen
import com.frameender.pocketstash.ui.detail.SceneDetailScreen
import com.frameender.pocketstash.ui.detail.StudioDetailScreen
import com.frameender.pocketstash.ui.detail.TagDetailScreen
import com.frameender.pocketstash.ui.home.HomeScreen
import com.frameender.pocketstash.ui.images.ImageViewerScreen
import com.frameender.pocketstash.ui.library.LibraryScreen
import com.frameender.pocketstash.ui.search.SearchScreen
import com.frameender.pocketstash.ui.settings.SettingsScreen
import com.frameender.pocketstash.ui.settings.SettingsSectionScreen
import com.frameender.pocketstash.ui.settings.UpdatePopup
import com.frameender.pocketstash.ui.downloads.DownloadsScreen
import com.frameender.pocketstash.ui.settings.SavedListsScreen
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.frameender.pocketstash.ui.settings.SetupScreen
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EditKind
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.edit.EditScreen
import com.frameender.pocketstash.ui.edit.EditViewModel
import com.frameender.pocketstash.ui.home.HomeLayoutScreen
import com.frameender.pocketstash.ui.settings.UpdatesScreen
import androidx.compose.runtime.LaunchedEffect

/** Top-level tabs. Scenes/Performers tabs reuse BrowseScreen under their own route objects. */
@kotlinx.serialization.Serializable object ScenesTab
@kotlinx.serialization.Serializable object PerformersTab

private data class TabItem(
    val route: Any,
    val matches: (NavDestination) -> Boolean,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val tabs = listOf(
    TabItem(HomeRoute, { it.hasRoute<HomeRoute>() }, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    TabItem(ScenesTab, { it.hasRoute<ScenesTab>() }, "Scenes", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
    TabItem(PerformersTab, { it.hasRoute<PerformersTab>() }, "Performers", Icons.Outlined.People, Icons.Filled.People),
    TabItem(SearchRoute, { it.hasRoute<SearchRoute>() }, "Search", Icons.Outlined.Search, Icons.Filled.Search),
    TabItem(LibraryRoute, { it.hasRoute<LibraryRoute>() }, "Library", Icons.Outlined.ViewModule, Icons.Filled.ViewModule),
)

@Composable
fun AppNav(configured: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val navigator = remember(navController) { Navigator(navController, context) }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // Deep links from outside the UI (the update notification).
    val container = context.container
    LaunchedEffect(Unit) {
        container.pendingRoute.collect { route ->
            when (route) {
                "updates" -> if (configured) navigator.updates()
                "downloads" -> if (configured) navigator.downloads()
            }
            if (route != null) container.pendingRoute.value = null
        }
    }

    val showBar = tabs.any { t -> destination?.hierarchy?.any(t.matches) == true }
    val onUpdates = destination?.hasRoute<UpdatesRoute>() == true
    val onSetup = destination?.hasRoute<SetupRoute>() == true
    val offlineSave by container.offlineSaver.progress.collectAsState()
    val offline by container.connection.offline.collectAsState()
    val serverBack by container.connection.serverBack.collectAsState()
    val downloadItems by container.downloads.items.collectAsState()

    // Coming back to the app re-checks for updates if the last check is over 30 minutes old.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && container.settings.value?.autoUpdateCheck == true) {
                scope.launch { container.updater.checkIfStale(30 * 60 * 1000L) }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Scaffold(
            containerColor = Ink.Bg,
            // Screens handle their own top insets; this padding is only the bottom bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar) {
                    NavigationBar(containerColor = Ink.Surface) {
                        tabs.forEach { t ->
                            val selected = destination?.hierarchy?.any(t.matches) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(t.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ink.Bg,
                                    indicatorColor = Ink.Amber,
                                    selectedTextColor = Ink.Amber,
                                    unselectedIconColor = Ink.Muted,
                                    unselectedTextColor = Ink.Muted,
                                ),
                            )
                        }
                    }
                }
            },
        ) { pad ->
          Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = if (configured) HomeRoute else SetupRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<SetupRoute> {
                    SetupScreen(onDone = {
                        navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                    })
                }
                composable<HomeRoute> { HomeScreen() }
                composable<ScenesTab> { BrowseScreen(EntityKind.SCENES, vmKey = "tab:scenes") }
                composable<PerformersTab> { BrowseScreen(EntityKind.PERFORMERS, vmKey = "tab:performers") }
                composable<SearchRoute> { SearchScreen() }
                composable<LibraryRoute> { LibraryScreen() }
                composable<SettingsRoute> { SettingsScreen() }
                composable<SettingsSectionRoute> {
                    SettingsSectionScreen(
                        it.toRoute<SettingsSectionRoute>().key,
                        onDisconnected = { navController.navigate(SetupRoute) { popUpTo(0) { inclusive = true } } },
                    )
                }
                composable<BrowseRoute> { entry ->
                    val r = entry.toRoute<BrowseRoute>()
                    val kind = EntityKind.valueOf(r.kind)
                    BrowseScreen(
                        kind,
                        initial = r.toQuery(kind, BrowseSpec.defaultSort(kind, Scope.None)),
                        showBack = true,
                        vmKey = "browse:${entry.id}",
                    )
                }
                composable<SceneRoute> { SceneDetailScreen(it.toRoute<SceneRoute>().id) }
                composable<PerformerRoute> { PerformerDetailScreen(it.toRoute<PerformerRoute>().id) }
                composable<StudioRoute> { StudioDetailScreen(it.toRoute<StudioRoute>().id) }
                composable<TagRoute> { TagDetailScreen(it.toRoute<TagRoute>().id) }
                composable<GalleryRoute> { GalleryDetailScreen(it.toRoute<GalleryRoute>().id) }
                composable<GroupRoute> { GroupDetailScreen(it.toRoute<GroupRoute>().id) }
                composable<ImageViewerRoute> { ImageViewerScreen(it.toRoute<ImageViewerRoute>()) }
                composable<UpdatesRoute> { UpdatesScreen() }
                composable<DownloadsRoute> { DownloadsScreen() }
                composable<SavedListsRoute> { SavedListsScreen() }
                composable<HomeLayoutRoute> { HomeLayoutScreen() }
                composable<EditRoute> { entry ->
                    val r = entry.toRoute<EditRoute>()
                    val kind = EditKind.valueOf(r.kind)
                    val vm = appViewModel("edit:${entry.id}") { c ->
                        EditViewModel(
                            c.repository, c.connection, kind, r.id,
                            sceneId = r.sceneId, sceneTitle = r.sceneTitle, startSeconds = r.seconds?.toDoubleOrNull(),
                        )
                    }
                    EditScreen(
                        vm,
                        onSaved = { id ->
                            if (r.id == null && kind != EditKind.MARKER) navigator.openCreated(kind, id)
                            else navController.popBackStack()
                        },
                        onDeleted = { navigator.afterDelete(kind) },
                        onClose = { navController.popBackStack() },
                    )
                }
            }

            // Saving a list for offline: progress along the bottom.
            offlineSave?.let { p ->
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 4.dp)) {
                    Text(
                        "Saving “${p.label}” for offline" + if (p.total > 0) " ${p.done}/${p.total}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Teal,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp),
                    )
                    if (p.total == 0) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Ink.Teal, trackColor = Ink.Raised)
                    } else {
                        LinearProgressIndicator(
                            progress = { p.done.toFloat() / p.total },
                            modifier = Modifier.fillMaxWidth(),
                            color = Ink.Teal, trackColor = Ink.Raised,
                        )
                    }
                }
            }

            // Offline mode: the app is running on what's on the phone.
            AnimatedVisibility(
                visible = offline && offlineSave == null && !onSetup,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Ink.Surface3,
                    border = BorderStroke(1.dp, if (serverBack) Ink.Amber else Ink.Line),
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, null, tint = Ink.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (serverBack) "Server's back" else "Offline mode · ${downloadItems.count { it.done }} downloaded",
                            style = MaterialTheme.typography.labelMedium, color = Ink.Text, maxLines = 1,
                        )
                        TextButton(onClick = { container.goOnline() }) {
                            Text("Go online", color = Ink.Amber, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
          }
        }

        // New-build pop-up (only with automatic checks and update notifications both on).
        UpdatePopup(suppressed = onUpdates || onSetup || !configured, onDetails = { navigator.updates() })
    }
}
