package com.frameender.pocketstash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.data.CardItem
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntityCard(
    item: CardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showText: Boolean = true,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(item.aspect)
                .clip(RoundedCornerShape(10.dp))
                .background(if (item.letterbox) LetterboxBg else Ink.Surface)
                .border(1.dp, Ink.Line, RoundedCornerShape(10.dp))
        ) {
            if (item.image != null) {
                AsyncImage(
                    model = item.image,
                    contentDescription = item.title,
                    contentScale = if (item.fit || item.letterbox) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (item.fit) Modifier.padding(10.dp) else Modifier),
                )
            } else {
                Text(
                    item.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Ink.Line,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (item.favorite) {
                Icon(
                    Icons.Filled.Favorite, null, tint = Ink.Red,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
                )
            }
            if (item.isVideo) {
                Icon(
                    Icons.Filled.PlayArrow, null, tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                )
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.rating100?.takeIf { it > 0 }?.let { Pill("★ ${ratingLabel(it)}", accent = true) }
                item.badge?.let { Pill(it) }
            }
            item.progress?.let { p ->
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(Modifier.fillMaxWidth(p).height(3.dp).background(Ink.Amber))
                }
            }
        }
        if (showText) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
            )
            item.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Backdrop behind letterboxed / pillarboxed video frames. */
val LetterboxBg = Color(0xFF050506)

fun ratingLabel(rating100: Int): String {
    val stars = rating100 / 20.0
    return if (stars % 1.0 == 0.0) stars.toInt().toString() else "%.1f".format(stars)
}

@Composable
fun Pill(text: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (accent) Ink.Bg else Color.White,
        modifier = modifier
            .background(if (accent) Ink.Amber else Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** Five tappable stars. Tapping the current value clears the rating. */
@Composable
fun StarRating(rating100: Int?, onRate: (Int?) -> Unit, size: Dp = 28.dp) {
    val current = (rating100 ?: 0)
    Row {
        for (i in 1..5) {
            val value = i * 20
            val filled = current >= value
            val half = !filled && current > value - 20
            Icon(
                if (filled || half) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = "$i stars",
                tint = if (filled) Ink.Amber else if (half) Ink.AmberDim else Ink.Muted,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable { onRate(if (current == value) null else value) },
            )
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxWidth().padding(32.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Ink.Amber, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.Red, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text(message, color = Ink.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionHeader(title: String, count: Int? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = Ink.Amber,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(8.dp),
            )
        }
    }
}

/** Rounded chip for tags / performers / studios. */
@Composable
fun LinkChip(text: String, onClick: () -> Unit, image: String? = null, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.Raised)
            .border(1.dp, Ink.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = if (image != null) 3.dp else 12.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image != null) {
            AsyncImage(
                model = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(26.dp).clip(CircleShape).background(Ink.Surface),
            )
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = Ink.Amber, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Label / value line used in detail info blocks. */
@Composable
fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Muted,
            modifier = Modifier.width(120.dp).padding(top = 2.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (looksTechnical(value)) Mono else null)
    }
}

private fun looksTechnical(v: String) = v.startsWith("/") || v.contains(":\\") || v.matches(Regex("^[0-9.:x ]+.*"))

/** Dark gradient so text stays readable on top of artwork. */
val ScrimBrush = Brush.verticalGradient(listOf(Color.Transparent, Ink.Bg.copy(alpha = 0.6f), Ink.Bg))

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Line, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink.Amber)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
    }
}
