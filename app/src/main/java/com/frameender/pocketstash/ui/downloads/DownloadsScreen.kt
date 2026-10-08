package com.frameender.pocketstash.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.SceneDownload
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.settings.updateSettings
import com.frameender.pocketstash.ui.theme.Ink
import java.io.File

/** Every downloaded (or downloading) scene, with storage use and controls for the queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = context.container
    val downloads = container.downloads
    val items by downloads.items.collectAsState()
    val work by downloads.workState.collectAsState()
    val settings by container.settings.collectAsState()
    val offline by container.connection.offline.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SceneDownload?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    val sorted = remember(items) {
        // Active first, then failed, then finished (newest first).
        items.sortedWith(
            compareBy<SceneDownload> {
                when (it.status) {
                    SceneDownload.RUNNING -> 0
                    SceneDownload.QUEUED -> 1
                    SceneDownload.FAILED -> 2
                    else -> 3
                }
            }.thenByDescending { if (it.done) it.finishedAt else it.addedAt },
        )
    }
    val used = remember(items) { downloads.totalBytes() }
    val free = remember(items) { downloads.freeBytes() }
    val pending = items.count { !it.done && it.status != SceneDownload.FAILED }
    val wifiOnly = settings?.downloadWifiOnly != false

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (pending > 0) {
                                if (work == "idle") {
                                    DropdownMenuItem(text = { Text("Resume all") }, onClick = { menu = false; downloads.resumeAll() })
                                } else {
                                    DropdownMenuItem(text = { Text("Pause all") }, onClick = { menu = false; downloads.pauseAll() })
                                }
                            }
                            if (items.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Delete all downloads") }, onClick = { menu = false; confirmAll = true })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Surface(shape = RoundedCornerShape(20.dp), color = Ink.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${items.count { it.done }} downloaded · ${formatBytes(used.toDouble())}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${formatBytes(free.toDouble())} free on this phone", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                        if (pending > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when (work) {
                                    "running" -> "Downloading · $pending left"
                                    "waiting" -> if (wifiOnly) "Waiting for Wi-Fi · $pending queued" else "Waiting for a connection · $pending queued"
                                    else -> "Paused · $pending queued"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.Amber,
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                                Text("Don't use mobile data for downloads", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                            }
                            Switch(
                                checked = wifiOnly,
                                onCheckedChange = { v -> context.updateSettings { it.copy(downloadWifiOnly = v) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
                            )
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.DownloadForOffline, null, tint = Ink.Muted, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Open a scene and tap Download to watch it without your server. " +
                                "Downloaded scenes, and everything about them, stay browsable in offline mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.Muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            items(sorted, key = { it.sceneId }) { d ->
                DownloadRow(
                    d = d,
                    image = d.screenshot?.let { raw -> container.library.localImage(raw) ?: container.connection.media(raw) },
                    fileSize = if (d.done) File(downloads.dir, d.fileName).length() else 0L,
                    onOpen = { nav.scene(d.sceneId) },
                    onPlay = { nav.play(d.sceneId) },
                    onRetry = { downloads.retry(d.sceneId) },
                    onDelete = { if (d.done) confirmDelete = d else downloads.delete(d.sceneId) },
                )
            }
            if (offline && items.any { it.done }) {
                item(key = "hint") {
                    Text(
                        "You're in offline mode: the library shows only what's saved on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.Muted,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete download?") },
            text = { Text("“${d.title}” will be removed from this phone. It stays on your Stash server.") },
            confirmButton = {
                TextButton(onClick = { downloads.delete(d.sceneId); confirmDelete = null }) { Text("Delete", color = Ink.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = Ink.Raised,
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all downloads?") },
            text = { Text("${items.size} videos (${formatBytes(used.toDouble())}) will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    downloads.deleteAll()
                    confirmAll = false
                }) { Text("Delete all", color = Ink.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
            containerColor = Ink.Raised,
        )
    }
}

@Composable
private fun DownloadRow(
    d: SceneDownload,
    image: String?,
    fileSize: Long,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Ink.Surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (d.done) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val status = when (d.status) {
                    SceneDownload.DONE -> "${d.qualityLabel} · ${formatBytes(fileSize.toDouble())}"
                    SceneDownload.RUNNING -> buildString {
                        append(d.qualityLabel).append(" · ").append(formatBytes(d.bytes.toDouble()))
                        if (d.total > 0) append(" of ").append(formatBytes(d.total.toDouble()))
                    }
                    SceneDownload.FAILED -> d.error ?: "Failed"
                    else -> "${d.qualityLabel} · Queued"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.status == SceneDownload.FAILED) Ink.Red else Ink.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (d.status == SceneDownload.RUNNING || d.status == SceneDownload.QUEUED) {
                    Spacer(Modifier.height(6.dp))
                    val p = d.progress
                    if (d.status == SceneDownload.RUNNING && p != null) {
                        LinearProgressIndicator(progress = { p }, color = Ink.Amber, trackColor = Ink.Line, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(color = if (d.status == SceneDownload.RUNNING) Ink.Amber else Ink.Muted, trackColor = Ink.Line, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (d.status == SceneDownload.FAILED) {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Retry", tint = Ink.Amber) }
            }
            IconButton(onClick = onDelete) {
                if (d.done) Icon(Icons.Filled.DeleteOutline, "Delete", tint = Ink.Muted)
                else if (d.status == SceneDownload.FAILED) Icon(Icons.Filled.ErrorOutline, "Remove", tint = Ink.Muted)
                else Icon(Icons.Filled.Close, "Cancel", tint = Ink.Muted)
            }
        }
    }
}
