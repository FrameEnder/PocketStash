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
import com.frameender.pocketstash.ui.settings.SetupScreen
import com.frameender.pocketstash.ui.theme.Ink

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
    val showBar = tabs.any { t -> destination?.hierarchy?.any(t.matches) == true }

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
            NavHost(
                navController = navController,
                startDestination = if (configured) HomeRoute else SetupRoute,
                modifier = Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize(),
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
                composable<SettingsRoute> {
                    SettingsScreen(onDisconnected = {
                        navController.navigate(SetupRoute) { popUpTo(0) { inclusive = true } }
                    })
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
            }
        }
    }
}
