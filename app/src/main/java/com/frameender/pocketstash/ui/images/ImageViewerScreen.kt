package com.frameender.pocketstash.ui.images

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.WaterDrop
import com.frameender.pocketstash.data.EditKind
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.ui.browse.BrowseViewModel
import com.frameender.pocketstash.ui.common.appViewModel
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.components.StarRating
import com.frameender.pocketstash.ui.nav.ImageViewerRoute
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.nav.decodeScope
import com.frameender.pocketstash.ui.nav.query
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(route: ImageViewerRoute) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val repo = context.container.repository
    val scope = rememberCoroutineScope()
    val vm = appViewModel("viewer:${route.scopeType}:${route.scopeId}:${route.sort}:${route.seed}:${route.text}:${route.quick}") {
        BrowseViewModel(it.repository, EntityKind.IMAGES, decodeScope(route.scopeType, route.scopeId), route.query(), perPage = 60)
    }
    val state by vm.state.collectAsState()
    var chrome by remember { mutableStateOf(true) }

    val pageCount = maxOf(state.total, state.items.size)
    val pager = rememberPagerState(initialPage = route.index) { pageCount.coerceAtLeast(route.index + 1) }

    // Keep loading pages until the requested start index (and the area around
    // the current page) is in memory.
    LaunchedEffect(pager.currentPage, state.items.size, state.loading) {
        if (!state.loading && !state.endReached && pager.currentPage >= state.items.size - 10) vm.loadMore()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = state.items.getOrNull(page)
            if (item == null) {
                LoadingBox(Modifier.fillMaxSize())
            } else if (item.isVideo) {
                Box(Modifier.fillMaxSize().clickable { item.fullImage?.let { nav.playUrl(it, item.title) } }) {
                    AsyncImage(
                        model = item.preview ?: item.image, contentDescription = item.title,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                    Icon(
                        Icons.Filled.PlayArrow, "Play clip", tint = Ink.OnAmber,
                        modifier = Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Ink.Amber).padding(12.dp),
                    )
                }
            } else {
                ZoomableImage(item, onTap = { chrome = !chrome })
            }
        }

        val current = state.items.getOrNull(pager.currentPage)
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.title ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${pager.currentPage + 1} / ${if (pageCount > 0) pageCount else "…"}",
                        color = Ink.Muted, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        AnimatedVisibility(
            chrome && current != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (current != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StarRating(current.rating100, onRate = { r ->
                        vm.patch(current.id) { it.copy(rating100 = r) }
                        scope.launch {
                            runCatching { repo.rateImage(current.id, r) }.onFailure {
                                vm.patch(current.id) { c -> c.copy(rating100 = current.rating100) }
                                Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show()
                            }
                        }
                    })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { repo.imageIncrementO(current.id) }
                                .onSuccess { Toast.makeText(context, "O count: $it", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                        }
                    }) { Icon(Icons.Outlined.WaterDrop, "Add O", tint = Ink.Amber) }
                    IconButton(onClick = { nav.edit(EditKind.IMAGE, current.id) }) {
                        Icon(Icons.Outlined.Edit, "Edit image", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Pinch / double-tap zoom that leaves single-finger swipes to the pager at 1×. */
@Composable
private fun ZoomableImage(item: CardItem, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - tap) * (scale - 1f), scale)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) clamp(offset + pan, newScale) else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = item.fullImage ?: item.preview ?: item.image,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}
