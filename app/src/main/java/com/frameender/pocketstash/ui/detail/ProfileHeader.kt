package com.frameender.pocketstash.ui.detail

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.data.model.TagRef
import com.frameender.pocketstash.ui.components.LinkChip
import com.frameender.pocketstash.ui.components.StarRating
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink

/** Image + title + rating block shared by performer/studio/tag/group pages. */
@Composable
fun ProfileHeader(
    image: String?,
    aspect: Float,
    fit: Boolean,
    title: String,
    subtitle: String?,
    rating100: Int?,
    onRate: ((Int?) -> Unit)?,
    imageWidth: Int = 140,
    side: @Composable ColumnScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row {
            Box(
                Modifier
                    .width(imageWidth.dp)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.Surface)
                    .border(1.dp, Ink.Line, RoundedCornerShape(12.dp)),
            ) {
                if (image != null) {
                    AsyncImage(
                        model = image, contentDescription = title,
                        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(if (fit) Modifier.padding(8.dp) else Modifier),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted) }
                if (onRate != null) {
                    Spacer(Modifier.height(8.dp))
                    StarRating(rating100, onRate, size = 26.dp)
                }
                Spacer(Modifier.height(8.dp))
                side()
            }
        }
        Spacer(Modifier.height(12.dp))
        below()
    }
}

@Composable
fun FavoriteButton(value: Boolean, onToggle: (Boolean) -> Unit) {
    IconButton(onClick = { onToggle(!value) }) {
        Icon(
            if (value) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (value) "Unfavorite" else "Favorite",
            tint = if (value) Ink.Red else Ink.Muted,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<TagRef>) {
    if (tags.isEmpty()) return
    val nav = LocalNavigator.current
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { LinkChip(it.name, onClick = { nav.tag(it.id) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UrlChips(urls: List<String>) {
    if (urls.isEmpty()) return
    val nav = LocalNavigator.current
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.forEach { url ->
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
            LinkChip(host, onClick = { nav.openExternal(url) }, icon = Icons.AutoMirrored.Filled.OpenInNew)
        }
    }
}

/** Long text collapsed to a few lines; tap to expand. */
@Composable
fun ExpandableText(text: String?, collapsedLines: Int = 4) {
    if (text.isNullOrBlank()) return
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Ink.Text.copy(alpha = 0.85f),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
    )
}

/** Surfaces mutation errors from a DetailViewModel as a toast. */
@Composable
fun MutationToasts(vm: DetailViewModel<*>) {
    val context = LocalContext.current
    val message by vm.message.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }
}

@Composable
fun CountLine(vararg parts: Pair<String, Int?>) {
    val text = parts.mapNotNull { (label, n) -> n?.takeIf { it > 0 }?.let { "$it $label" } }.joinToString(" · ")
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
    }
}

