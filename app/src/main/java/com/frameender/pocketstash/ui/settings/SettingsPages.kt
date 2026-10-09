package com.frameender.pocketstash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material.icons.filled.DownloadForOffline
import com.frameender.pocketstash.data.SceneDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.frameender.pocketstash.R
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.AppSettings
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Format
import com.frameender.pocketstash.data.OfflineCollection
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.data.model.Stats
import com.frameender.pocketstash.data.normalizeServerUrl
import com.frameender.pocketstash.ui.browse.widthFactor
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.StatTile
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Accents
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val D = SettingsDefaults

@Composable
private fun settingsState(): AppSettings {
    val s by LocalContext.current.container.settings.collectAsState()
    return s ?: AppSettings()
}

// =====================================================================
// Server & connection
// =====================================================================

@Composable
fun ServerPage(onDisconnected: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val s = settingsState()
    val scope = rememberCoroutineScope()

    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var key by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var alsoDownloads by remember { mutableStateOf(true) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
        unfocusedContainerColor = Ink.Bg, focusedContainerColor = Ink.Bg,
    )

    /** Drops the offline library and saved lists (and, if asked, the downloaded videos). */
    suspend fun forgetSavedData(downloads: Boolean) {
        if (downloads) container.downloads.deleteAll()
        withContext(Dispatchers.IO) { container.library.clearAll() }
        container.settingsStore.update { it.copy(offlineCollections = "") }
        container.goOnline()
    }

    SettingsPage("Server & connection") {
        GroupLabel("Connection")
        SettingsCard {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim(); status = null },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://100.x.y.z:9999") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); status = null },
                    label = { Text("API key (blank if login is off)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide the key")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        if (ok) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Ink.Green, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(it, color = if (ok) Ink.Green else Ink.Red, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = !busy && url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.OnAmber),
                    onClick = {
                        val base = normalizeServerUrl(url)
                        if (base == null) {
                            ok = false
                            status = "That doesn't look like a URL."
                            return@Button
                        }
                        busy = true
                        status = null
                        scope.launch {
                            try {
                                val info = container.repository.testConnection(base, key)
                                ok = true
                                status = "Connected · Stash ${info.version ?: "(unknown version)"}"
                                // Saved data is kept: the same server is often reached by more than one
                                // address (LAN, Tailscale, a proxy). Downloads check their file matches.
                                container.settingsStore.saveServer(base.toString(), key)
                                if (container.connection.offline.value) container.goOnline()
                            } catch (e: Exception) {
                                ok = false
                                status = e.friendly()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Ink.OnAmber, strokeWidth = 2.dp)
                    else Text("Test & save")
                }
            }
        }
        Hint("Find the key in Stash → Settings → Security → API Key. Changing the address keeps your downloads; clear them in Storage & offline if you move to a different server.")

        GroupLabel("Network")
        SettingsCard {
            SwitchSetting(
                "Rewrite media URLs to this server",
                "Fixes thumbnails and streams when Stash reports a different host (reverse proxy, Docker, Tailscale)",
                s.rewriteHost,
                onReset = { it.copy(rewriteHost = D.rewriteHost) },
            ) { v -> context.updateSettings { it.copy(rewriteHost = v) } }
        }

        GroupLabel("Leave")
        SettingsCard {
            ActionRow("Disconnect", "Forget this server, its key and what's saved from it", color = Ink.Red) {
                confirmDisconnect = true
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect?") },
            text = {
                Column {
                    Text("PocketStash forgets this server and its API key, and clears the info saved for offline.")
                    if (container.downloads.items.value.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp).clickable { alsoDownloads = !alsoDownloads },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = alsoDownloads, onCheckedChange = { alsoDownloads = it },
                                colors = CheckboxDefaults.colors(checkedColor = Ink.Amber, checkmarkColor = Ink.OnAmber),
                            )
                            Text(
                                "Also delete ${container.downloads.items.value.size} downloaded videos " +
                                    "(${formatBytes(container.downloads.totalBytes().toDouble())})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch {
                        forgetSavedData(alsoDownloads)
                        container.settingsStore.clearServer()
                        onDisconnected()
                    }
                }) { Text("Disconnect", color = Ink.Red, maxLines = 1) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel", maxLines = 1) } },
            containerColor = Ink.Raised,
        )
    }
}

// =====================================================================
// Appearance
// =====================================================================

@Composable
fun AppearancePage() {
    val context = LocalContext.current
    val s = settingsState()
    SettingsPage("Appearance") {
        GroupLabel("Highlight color")
        SettingsCard {
            Column(Modifier.padding(14.dp)) {
                AccentPicker(s.accent) { k -> context.updateSettings { it.copy(accent = k) } }
            }
        }
        Hint("Used for buttons, ratings, the selected tab, progress bars and the update pop-up.")

        GroupLabel("Card size")
        SettingsCard {
            Column(Modifier.padding(12.dp)) {
                CardPreview(s.gridCardWidth)
            }
            CardDivider()
            SliderSetting(
                "Card size", "${s.gridCardWidth} dp", s.gridCardWidth.toFloat(), 110f..280f, 16,
                summary = "Smaller fits more cards on each row",
                onReset = { it.copy(gridCardWidth = D.gridCardWidth) },
            ) { v -> context.updateSettings { it.copy(gridCardWidth = (v / 10).roundToInt() * 10) } }
        }
        Hint("Long-press any setting to put it back to its default.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(selected: String, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Accents.all.forEach { a ->
            val on = a.key == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(60.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(a.key) }.padding(vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(a.main)
                        .border(if (on) 3.dp else 0.dp, if (on) Ink.Text else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Filled.Check, null, tint = a.on, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(a.label, style = MaterialTheme.typography.labelSmall, color = if (on) a.main else Ink.Muted, maxLines = 1)
            }
        }
    }
}

/** How many scene and performer cards fit on a row of this phone at the chosen size. */
@Composable
private fun CardPreview(widthDp: Int) {
    val screen = LocalConfiguration.current.screenWidthDp - 24 // grid side padding
    fun columns(kind: EntityKind) = ((screen + 12) / (widthDp * widthFactor(kind) + 12)).toInt().coerceAtLeast(1)
    val hues = listOf(Ink.Amber, Ink.Violet, Ink.Teal, Ink.Red, Ink.Green, Ink.Blue, Ink.Gold)
    Column {
        listOf(
            Triple(EntityKind.SCENES, 16f / 9f, Icons.Filled.VideoLibrary),
            Triple(EntityKind.PERFORMERS, 2f / 3f, Icons.Filled.People),
        ).forEachIndexed { row, (kind, ratio, icon) ->
            val n = columns(kind)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Icon(icon, null, tint = Ink.Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("${kind.label}: $n per row", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(n) { i ->
                    val hue = hues[(i + row * 3) % hues.size]
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.linearGradient(listOf(hue.copy(alpha = 0.75f), hue.copy(alpha = 0.22f)))),
                    )
                }
            }
            if (row == 0) Spacer(Modifier.height(12.dp))
        }
        Text(
            "Live preview",
            style = MaterialTheme.typography.labelSmall, color = Ink.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

// =====================================================================
// Player
// =====================================================================

@Composable
fun PlayerPage() {
    val context = LocalContext.current
    val s = settingsState()
    SettingsPage("Player") {
        GroupLabel("Playback")
        SettingsCard {
            SwitchSetting(
                "Resume where you left off", "Scenes start from Stash's saved resume point", s.resumePlayback,
                onReset = { it.copy(resumePlayback = D.resumePlayback) },
            ) { v -> context.updateSettings { it.copy(resumePlayback = v) } }
            CardDivider()
            SwitchSetting(
                "Start muted", "The mute button remembers your choice until the app closes", s.startMuted,
                onReset = { it.copy(startMuted = D.startMuted) },
            ) { v -> context.updateSettings { it.copy(startMuted = v) } }
        }

        GroupLabel("Play tracking")
        SettingsCard {
            SwitchSetting(
                "Send play activity to Stash", "Play count, resume point and watch time", s.trackActivity,
                onReset = { it.copy(trackActivity = D.trackActivity) },
            ) { v -> context.updateSettings { it.copy(trackActivity = v) } }
            CardDivider()
            SliderSetting(
                "Count a play after", "${s.playCountAfterSeconds} s", s.playCountAfterSeconds.toFloat(), 1f..120f, 0,
                summary = if (s.trackActivity) "Seconds of watching before Stash's play count goes up" else "Turn on play activity to use this",
                onReset = { it.copy(playCountAfterSeconds = D.playCountAfterSeconds) },
            ) { v -> context.updateSettings { it.copy(playCountAfterSeconds = v.roundToInt()) } }
        }

        GroupLabel("Gestures")
        SettingsCard {
            Gesture(Icons.Filled.FastRewind, "Double-tap left", "Back 5 seconds")
            CardDivider()
            Gesture(Icons.Filled.FastForward, "Double-tap right", "Forward 15 seconds")
            CardDivider()
            Gesture(Icons.Filled.TouchApp, "Keep tapping", "Each extra tap skips again")
            CardDivider()
            Gesture(Icons.Filled.BrightnessMedium, "Swipe up or down on the left", "Brightness (only inside the player)")
            CardDivider()
            Gesture(Icons.AutoMirrored.Filled.VolumeUp, "Swipe up or down on the right", "Volume; turning it up while muted unmutes")
            CardDivider()
            Gesture(Icons.Filled.Bookmarks, "Seek bar", "White ticks are your markers")
        }
    }
}

@Composable
private fun Gesture(icon: ImageVector, title: String, what: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, Ink.Green, size = 34)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(what, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
    }
}

// =====================================================================
// Library stats
// =====================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryStatsPage() {
    val container = LocalContext.current.container
    var stats by remember { mutableStateOf<Stats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val offline by container.connection.offline.collectAsState()
    LaunchedEffect(offline) {
        try {
            stats = container.repository.stats()
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    SettingsPage("Library stats") {
        Spacer(Modifier.height(8.dp))
        val st = stats
        when {
            error != null -> SettingsCard { Text(error!!, color = Ink.Red, modifier = Modifier.padding(16.dp)) }
            st == null -> SettingsCard {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Ink.Amber, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            }
            else -> {
                GroupLabel("Library")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Scenes", Format.count(st.sceneCount))
                    StatTile("Scene size", formatBytes(st.scenesSize))
                    StatTile("Runtime", "%,.0f h".format(st.scenesDuration / 3600))
                    StatTile("Images", Format.count(st.imageCount))
                    StatTile("Image size", formatBytes(st.imagesSize))
                    StatTile("Galleries", Format.count(st.galleryCount))
                    StatTile("Performers", Format.count(st.performerCount))
                    StatTile("Studios", Format.count(st.studioCount))
                    StatTile("Groups", Format.count(st.groupCount))
                    StatTile("Tags", Format.count(st.tagCount))
                }
                GroupLabel("Watching")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Plays", Format.count(st.totalPlayCount))
                    StatTile("Scenes played", Format.count(st.scenesPlayed))
                    StatTile("Watched", "%,.1f h".format(st.totalPlayDuration / 3600))
                    StatTile("O count", Format.count(st.totalOCount))
                }
                Hint(
                    if (offline) "Offline mode: counted from what's on this phone (downloads and saved lists)."
                    else "Straight from your Stash server; the same numbers as its Stats page.",
                )
            }
        }
    }
}

// =====================================================================
// Storage & offline
// =====================================================================

private data class Usage(val images: Long, val library: Long, val downloads: Long, val updates: Long, val free: Long) {
    val total get() = images + library + downloads + updates
}

private fun dirSize(f: File): Long = if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoragePage() {
    val context = LocalContext.current
    val container = context.container
    val nav = LocalNavigator.current
    val saver = container.offlineSaver
    val downloads = container.downloads
    val scope = rememberCoroutineScope()
    val s = settingsState()
    val saving by saver.progress.collectAsState()
    val waiting by saver.queue.collectAsState()
    val offline by container.connection.offline.collectAsState()
    val downloadItems by downloads.items.collectAsState()
    val libraryVersion by container.library.version.collectAsState()
    val collections = saver.collections(s)
    var usage by remember { mutableStateOf<Usage?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, saving == null, downloadItems.count { it.done }, libraryVersion) {
        usage = withContext(Dispatchers.IO) {
            Usage(
                images = SingletonImageLoader.get(context).diskCache?.size ?: 0L,
                library = container.library.sizeBytes(),
                downloads = downloads.totalBytes(),
                updates = dirSize(File(context.cacheDir, "updates")),
                free = downloads.freeBytes(),
            )
        }
    }

    SettingsPage("Storage & offline") {
        Spacer(Modifier.height(8.dp))
        // ---------- usage ----------
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                val u = usage
                val scale = u?.let { it.total + it.free } ?: 0L
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (u == null) "…" else Format.bytes(u.total), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (u == null) "" else "used · ${Format.bytes(u.free)} free",
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Ink.Surface3),
                ) {
                    if (u != null && scale > 0) {
                        var rest = 1f
                        listOf(u.downloads to Ink.Green, u.images to Ink.Amber, u.library to Ink.Teal, u.updates to Ink.Violet)
                            .forEach { (bytes, color) ->
                                // A sliver stays visible even when one part is tiny next to the free space.
                                val f = (bytes.toFloat() / scale).let { if (bytes > 0) it.coerceAtLeast(0.006f) else 0f }.coerceIn(0f, rest)
                                if (f > 0.001f) {
                                    Box(Modifier.fillMaxHeight().weight(f).background(color))
                                    rest -= f
                                }
                            }
                        if (rest > 0.001f) Spacer(Modifier.weight(rest))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Ink.Green, "Downloads ${u?.let { Format.bytes(it.downloads) } ?: "…"}")
                    Legend(Ink.Amber, "Images ${u?.let { Format.bytes(it.images) } ?: "…"}")
                    Legend(Ink.Teal, "Offline info ${u?.let { Format.bytes(it.library) } ?: "…"}")
                    Legend(Ink.Violet, "Updates ${u?.let { Format.bytes(it.updates) } ?: "…"}")
                }
            }
        }

        // ---------- offline mode ----------
        GroupLabel("Offline mode")
        SettingsCard {
            SwitchSetting(
                "Offline mode",
                if (offline) "On · only what's saved on this phone is shown, and nothing is sent to the server"
                else "Use only what's on this phone, as if it were your whole library",
                offline,
            ) { v -> if (v) container.goOffline() else container.goOnline() }
            CardDivider()
            SwitchSetting(
                "Switch automatically", "Go offline when your Stash server can't be reached", s.offlineFallback,
                onReset = { it.copy(offlineFallback = D.offlineFallback) },
            ) { v -> context.updateSettings { it.copy(offlineFallback = v) } }
        }
        Hint(
            "In offline mode, downloads and saved lists stand in for your server: browsing, search, sorting " +
                "(random too), filters and stats all work on them. Plays and watch time are sent once you're back online.",
        )

        // ---------- downloads ----------
        GroupLabel("Downloads")
        SettingsCard {
            val done = downloadItems.count { it.done }
            val active = downloadItems.count { !it.done && it.status != SceneDownload.FAILED }
            NavRow(
                Icons.Filled.DownloadForOffline, Ink.Green, "Downloaded scenes",
                buildString {
                    append(if (done == 0) "None yet" else "$done scenes · ${Format.bytes(usage?.downloads ?: 0L)}")
                    if (active > 0) append(" · $active in progress")
                },
            ) { nav.downloads() }
            CardDivider()
            SwitchSetting(
                "Wi-Fi only", "Wait for Wi-Fi before downloading videos", s.downloadWifiOnly,
                onReset = { it.copy(downloadWifiOnly = D.downloadWifiOnly) },
            ) { v -> context.updateSettings { it.copy(downloadWifiOnly = v) } }
        }

        // ---------- saved collections ----------
        GroupLabel("Saved lists")
        saving?.let { p ->
            SettingsCard(Modifier.padding(bottom = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Ink.Teal, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Saving “${p.label}”" + if (p.total > 0) " · ${p.done}/${p.total}" else "",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { saver.cancel() }) { Text("Stop", maxLines = 1) }
                    }
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { p.done.toFloat() / p.total }, color = Ink.Teal, trackColor = Ink.Raised,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        // Lists waiting their turn (saved one after another, in the background).
        if (waiting.isNotEmpty()) {
            SettingsCard(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (saving == null) "Waiting to save · ${waiting.size}" else "Up next · ${waiting.size}",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    if (saving == null && !offline) TextButton(onClick = { saver.startWorker() }) { Text("Resume", maxLines = 1) }
                    TextButton(onClick = { saver.clearQueue() }) { Text("Clear", color = Ink.Red, maxLines = 1) }
                }
                waiting.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            c.label, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { saver.dequeue(c) }) { Icon(Icons.Filled.Close, "Remove from queue", tint = Ink.Muted) }
                    }
                }
                if (saving == null) {
                    Hint(
                        if (offline) "Waiting for your server: saving needs it." else "Paused, or waiting for a connection. Tap Resume to carry on.",
                        Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                    )
                }
            }
        }
        if (collections.isEmpty()) {
            SettingsCard {
                Text(
                    "Nothing saved yet. Tap the save-for-offline button next to the sort and filter chips on any list " +
                        "(Scenes, a performer's scenes, a gallery's images…). Scene lists can download their videos too.",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            SettingsCard {
                collections.forEachIndexed { i, c ->
                    if (i > 0) CardDivider()
                    CollectionRow(c, busy = offline)
                }
            }
            Hint("Removing a list also removes the info it saved, except anything another list or a download still uses. Videos stay in Downloads.")
        }

        // ---------- behaviour ----------
        GroupLabel("Behaviour")
        SettingsCard {
            SwitchSetting(
                "Refresh saved lists daily", "On Wi-Fi while charging", s.offlineAutoRefresh,
                onReset = { it.copy(offlineAutoRefresh = D.offlineAutoRefresh) },
            ) { v -> context.updateSettings { it.copy(offlineAutoRefresh = v) } }
            CardDivider()
            ChipsSetting(
                "Image cache size",
                listOf(256 to "256 MB", 512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB"),
                s.imageCacheMb,
                summary = "Thumbnails for lists and pages you've browsed. Pictures for downloads and saved info are kept separately and never evicted.",
                onReset = { it.copy(imageCacheMb = D.imageCacheMb) },
            ) { v -> context.updateSettings { it.copy(imageCacheMb = v) } }
        }

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    withContext(Dispatchers.IO) { loader.diskCache?.clear() }
                    context.toast("Images cleared")
                    refresh++
                }
            }) { Text("Clear images", maxLines = 1) }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear saved info", maxLines = 1) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear saved info?") },
            text = {
                Text(
                    "Every saved list and its offline info is removed, including info saved before lists " +
                        "tracked what they keep. Downloaded scenes stay, with the info needed to browse them offline.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            container.library.pruneTo(downloadItems.filter { it.done || it.status != SceneDownload.FAILED }.map { it.sceneId }.toSet())
                        }
                        container.settingsStore.update { it.copy(offlineCollections = "") }
                        context.toast("Saved info cleared")
                        refresh++
                    }
                }) { Text("Clear", color = Ink.Red, maxLines = 1) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", maxLines = 1) } },
            containerColor = Ink.Raised,
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
    }
}

private fun kindIcon(k: EntityKind): ImageVector = when (k) {
    EntityKind.SCENES, EntityKind.MARKERS -> Icons.Filled.VideoLibrary
    EntityKind.PERFORMERS -> Icons.Filled.People
    EntityKind.IMAGES, EntityKind.GALLERIES -> Icons.Filled.Image
    else -> Icons.Filled.Movie
}

@Composable
private fun CollectionRow(c: OfflineCollection, busy: Boolean) {
    val context = LocalContext.current
    val saver = context.container.offlineSaver
    val kind = c.entityKind
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove saved list?") },
            text = {
                Text(
                    "“${c.label}” and the info it saved are removed from this phone. Anything another saved " +
                        "list or a download still uses is kept." +
                        if (c.downloadQuality != null) " Downloaded videos stay; delete them in Downloads." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    saver.forget(c)
                    context.toast("Removed “${c.label}”")
                }) { Text("Remove", color = Ink.Red, maxLines = 1) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel", maxLines = 1) } },
            containerColor = Ink.Raised,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(kindIcon(kind), if (c.scopeType == "none") Ink.Amber else Ink.Violet, size = 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${c.count} ${kind.label.lowercase()}" +
                    (if (kind == EntityKind.IMAGES) if (c.fullImages) " · full images" else " · thumbnails only" else "") +
                    (if (c.downloadQuality != null) " · with videos" else "") +
                    (if (c.savedAt > 0) " · " + Format.agoMillis(c.savedAt) else ""),
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { saver.refresh(c) }, enabled = !busy) {
            Icon(Icons.Filled.Refresh, "Refresh", tint = if (busy) Ink.Line else Ink.Muted)
        }
        IconButton(onClick = { confirmRemove = true }) {
            Icon(Icons.Filled.Delete, "Remove", tint = Ink.Red)
        }
    }
}

// =====================================================================
// About
// =====================================================================

@Composable
fun AboutPage() {
    val nav = LocalNavigator.current
    val container = LocalContext.current.container
    val s = settingsState()
    val repo = "https://github.com/${s.updateRepo.trim('/')}"
    SettingsPage("About") {
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(Ink.Bg), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(12.dp))
                Text("PocketStash", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${container.updater.installedVersionName()} · build ${container.updater.installedVersionCode()} · ${s.updateChannel}",
                    style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                )
                Text(
                    "An unofficial Stash client for Android",
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        GroupLabel("Links")
        SettingsCard {
            LinkRow("Source code", repo.removePrefix("https://")) { nav.openExternal(repo) }
            CardDivider()
            LinkRow("Report a problem", "GitHub issues") { nav.openExternal("$repo/issues") }
            CardDivider()
            LinkRow("Stash", "github.com/stashapp/stash") { nav.openExternal("https://github.com/stashapp/stash") }
        }
        GroupLabel("Licenses")
        SettingsCard {
            Text(
                "Space Grotesk and JetBrains Mono fonts: SIL Open Font License 1.1.\n" +
                    "Jetpack Compose, Media3, WorkManager, DataStore: Apache 2.0.\n" +
                    "Coil, OkHttp, kotlinx.serialization: Apache 2.0.",
                style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Ink.Muted, modifier = Modifier.size(18.dp))
    }
}
