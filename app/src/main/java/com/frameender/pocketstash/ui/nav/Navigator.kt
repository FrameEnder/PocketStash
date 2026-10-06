package com.frameender.pocketstash.ui.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import com.frameender.pocketstash.data.BrowseQuery
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Scope
import com.frameender.pocketstash.player.PlayerActivity

/** One place that knows where every card/link goes. */
class Navigator(private val nav: NavController, private val context: Context) {

    fun open(item: CardItem, index: Int, scope: Scope, query: BrowseQuery) {
        when (item.kind) {
            EntityKind.SCENES -> scene(item.id)
            EntityKind.PERFORMERS -> performer(item.id)
            EntityKind.STUDIOS -> studio(item.id)
            EntityKind.TAGS -> tag(item.id)
            EntityKind.GALLERIES -> gallery(item.id)
            EntityKind.GROUPS -> group(item.id)
            EntityKind.IMAGES -> nav.navigate(imageViewerRoute(scope, query, index))
            EntityKind.MARKERS -> item.sceneId?.let { play(it, item.seconds) }
        }
    }

    fun scene(id: String) = nav.navigate(SceneRoute(id))
    fun performer(id: String) = nav.navigate(PerformerRoute(id))
    fun studio(id: String) = nav.navigate(StudioRoute(id))
    fun tag(id: String) = nav.navigate(TagRoute(id))
    fun gallery(id: String) = nav.navigate(GalleryRoute(id))
    fun group(id: String) = nav.navigate(GroupRoute(id))
    fun settings() = nav.navigate(SettingsRoute)
    fun setup() = nav.navigate(SetupRoute)
    fun back() = nav.popBackStack()

    fun browse(kind: EntityKind, sort: String? = null, descending: Boolean = true, quick: Set<String> = emptySet(), text: String = "") =
        nav.navigate(BrowseRoute(kind.name, sort, descending, quick.joinToString(","), text))

    fun play(sceneId: String, startSeconds: Double? = null) {
        context.startActivity(PlayerActivity.sceneIntent(context, sceneId, startSeconds))
    }

    fun playUrl(url: String, title: String) {
        context.startActivity(PlayerActivity.urlIntent(context, url, title))
    }

    fun openExternal(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
