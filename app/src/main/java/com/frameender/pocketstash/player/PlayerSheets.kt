package com.frameender.pocketstash.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.ui.theme.Ink

/** A choice in one of the player's lists (subtitles, audio, quality). */
data class PickOption(val label: String, val detail: String? = null, val selected: Boolean)

/** Sleep timer presets in minutes; -1 = at the end of this scene. */
val SLEEP_PRESETS = listOf(15, 30, 45, 60, 90, -1)

val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun speedLabel(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

/** "Up next": the play queue, with shuffle and autoplay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(onDismiss: () -> Unit, onJump: (Int) -> Unit, onEndQueue: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { list.scrollToItem((PlayQueue.index - 1).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Raised) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Up next", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                    Text(
                        "${PlayQueue.index + 1} of ${PlayQueue.entries.size}" +
                            PlayQueue.source.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayQueue.setShuffle(!PlayQueue.shuffled) }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (PlayQueue.shuffled) Ink.Amber else Ink.Muted)
                }
                TextButton(onClick = onEndQueue) { Text("End queue", color = Ink.Red) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Play the next scene automatically", style = MaterialTheme.typography.bodyMedium, color = Ink.Text, modifier = Modifier.weight(1f))
                Switch(
                    checked = PlayQueue.autoNext, onCheckedChange = { PlayQueue.autoNext = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
                )
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(420.dp)) {
                itemsIndexed(PlayQueue.entries, key = { _, e -> e.id }) { i, e ->
                    val current = i == PlayQueue.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (current) Ink.AmberDim.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { onJump(i) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Color.Black),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (e.image != null) {
                                AsyncImage(model = e.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                            }
                            if (current) Icon(Icons.Filled.PlayArrow, null, tint = Ink.Amber)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            e.title, color = if (current) Ink.Amber else Ink.Text,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${i + 1}", color = Ink.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Playback settings: speed, subtitles, audio track, quality, sleep timer, rotate and
 * picture-in-picture, in one sheet so the top bar stays uncluttered.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlaybackSheet(
    speed: Float,
    onSpeed: (Float) -> Unit,
    subtitles: List<PickOption>,
    onSubtitle: (Int?) -> Unit,
    audio: List<PickOption>,
    onAudio: (Int) -> Unit,
    qualities: List<PickOption>,
    onQuality: (Int) -> Unit,
    sleepLabel: String?,
    onSleep: (Int?) -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Raised) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SheetLabel("Speed")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { s -> Chip(speedLabel(s), s == speed) { onSpeed(s) } }
            }
            Text("Tip: hold a finger on the video to play at 2× until you let go.", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)

            if (subtitles.isNotEmpty()) {
                SheetLabel("Subtitles")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Off", subtitles.none { it.selected }) { onSubtitle(null) }
                    subtitles.forEachIndexed { i, o -> Chip(o.label, o.selected) { onSubtitle(i) } }
                }
            }
            if (audio.size > 1) {
                SheetLabel("Audio")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    audio.forEachIndexed { i, o -> Chip(o.label, o.selected) { onAudio(i) } }
                }
            }
            if (qualities.size > 1) {
                SheetLabel("Quality")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    qualities.forEachIndexed { i, o -> Chip(o.label, o.selected) { onQuality(i) } }
                }
            }
            SheetLabel("Sleep timer" + (sleepLabel?.let { " · $it" } ?: ""))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Off", sleepLabel == null) { onSleep(null) }
                SLEEP_PRESETS.forEach { m -> Chip(if (m < 0) "End of scene" else "$m min", false) { onSleep(m) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRotate) { Text("Rotate screen", color = Ink.Amber) }
                TextButton(onClick = onPip) { Text("Picture-in-picture", color = Ink.Amber) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Ink.Muted) }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.Amber, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Ink.AmberDim, selectedLabelColor = Ink.Text, labelColor = Ink.Text,
        ),
    )
}
