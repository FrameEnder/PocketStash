package com.frameender.pocketstash.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.AppSettings
import com.frameender.pocketstash.data.HomeLayouts
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Accents
import com.frameender.pocketstash.ui.theme.Ink

/**
 * The Settings pages. [key] goes in the route (SettingsSectionRoute); Home screen and Updates
 * have their own screens elsewhere in the app.
 */
enum class SettingsSection(val key: String, val title: String, val icon: ImageVector, val tint: Color) {
    SERVER("server", "Server & connection", Icons.Filled.Dns, Color(0xFFF2A93B)),
    APPEARANCE("appearance", "Appearance", Icons.Filled.Palette, Color(0xFFF48FB1)),
    HOME("home", "Home screen", Icons.Filled.Dashboard, Color(0xFF6FA8F0)),
    PLAYER("player", "Player", Icons.Filled.PlayCircle, Color(0xFF8BC37A)),
    LIBRARY("library", "Library stats", Icons.Filled.BarChart, Color(0xFFB394E8)),
    STORAGE("storage", "Storage & offline", Icons.Filled.CloudOff, Color(0xFF7DB8B5)),
    UPDATES("updates", "Updates", Icons.Filled.SystemUpdate, Color(0xFFE6C15A)),
    ABOUT("about", "About", Icons.Filled.Info, Color(0xFF9A958C));

    companion object {
        fun of(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}

/** One searchable setting: its name, other words people might type, and its page. */
private data class SettingEntry(val title: String, val section: SettingsSection, val keywords: String = "")

private val SEARCH_INDEX = listOf(
    SettingEntry("Server address", SettingsSection.SERVER, "url host ip port tailscale connect stash"),
    SettingEntry("API key", SettingsSection.SERVER, "key token password auth security login"),
    SettingEntry("Rewrite media URLs", SettingsSection.SERVER, "reverse proxy docker thumbnails host broken images"),
    SettingEntry("Disconnect", SettingsSection.SERVER, "log out sign out forget server"),
    SettingEntry("Highlight color", SettingsSection.APPEARANCE, "accent theme colour amber pink blue"),
    SettingEntry("Card size", SettingsSection.APPEARANCE, "grid columns thumbnails width zoom"),
    SettingEntry("Customize Home sections", SettingsSection.HOME, "layout dashboard widgets carousel start screen"),
    SettingEntry("Resume where you left off", SettingsSection.PLAYER, "resume continue position"),
    SettingEntry("Start muted", SettingsSection.PLAYER, "sound audio volume mute"),
    SettingEntry("Send play activity to Stash", SettingsSection.PLAYER, "play count history o counter watch time tracking"),
    SettingEntry("Count a play after", SettingsSection.PLAYER, "play count threshold seconds"),
    SettingEntry("Player gestures", SettingsSection.PLAYER, "double tap seek skip brightness volume swipe"),
    SettingEntry("Library stats", SettingsSection.LIBRARY, "size count scenes runtime watched"),
    SettingEntry("Offline mode", SettingsSection.STORAGE, "offline airplane flight no server local only"),
    SettingEntry("Switch to offline automatically", SettingsSection.STORAGE, "offline tailscale down unreachable fallback"),
    SettingEntry("Downloads", SettingsSection.STORAGE, "download video save phone offline playback watch"),
    SettingEntry("Download on Wi-Fi only", SettingsSection.STORAGE, "wifi mobile data cellular metered download"),
    SettingEntry("Saved lists", SettingsSection.STORAGE, "offline collections save refresh"),
    SettingEntry("Refresh saved lists daily", SettingsSection.STORAGE, "offline background wifi charging"),
    SettingEntry("Image cache size", SettingsSection.STORAGE, "storage space disk thumbnails"),
    SettingEntry("Clear cache", SettingsSection.STORAGE, "storage space free delete clear saved info images"),
    SettingEntry("Update channel", SettingsSection.UPDATES, "stable nightly release version"),
    SettingEntry("Update notifications", SettingsSection.UPDATES, "notify pop-up background check"),
    SettingEntry("GitHub token", SettingsSection.UPDATES, "private repo"),
    SettingEntry("Version & licenses", SettingsSection.ABOUT, "about build fonts github"),
)

private fun mb(n: Int) = if (n >= 1024) "${n / 1024} GB" else "$n MB"

/** Live summary shown under each category on the main page. */
private fun summary(
    section: SettingsSection, s: AppSettings, saved: Int, downloaded: Int, offline: Boolean, version: String, build: Long,
): String = when (section) {
    SettingsSection.SERVER -> s.baseUrl?.let { it.host + ":" + it.port } ?: "Not set up"
    SettingsSection.APPEARANCE ->
        (Accents.all.firstOrNull { it.key == s.accent }?.label ?: "Amber") + " highlight · ${s.gridCardWidth} dp cards"
    SettingsSection.HOME ->
        if (s.homeLayout.isBlank()) "Default layout"
        else "Custom layout · ${HomeLayouts.decode(s.homeLayout).count { it.enabled }} sections"
    SettingsSection.PLAYER -> listOf(
        if (s.resumePlayback) "Resume on" else "Resume off",
        if (s.startMuted) "start muted" else "sound on",
        if (s.trackActivity) "tracking plays" else "not tracking",
    ).joinToString(" · ")
    SettingsSection.LIBRARY -> "Counts, sizes and watch time"
    SettingsSection.STORAGE ->
        listOfNotNull(
            "Offline mode on".takeIf { offline },
            if (downloaded == 0) "No downloads" else "$downloaded downloaded",
            if (saved == 0) "no saved lists" else "$saved saved ${if (saved == 1) "list" else "lists"}",
        ).joinToString(" · ")
    SettingsSection.UPDATES ->
        s.updateChannel.replaceFirstChar { it.uppercase() } + " channel · " + if (s.autoUpdateCheck) "checks every 6 h" else "manual checks"
    SettingsSection.ABOUT -> "PocketStash $version · build $build"
}

private val GROUPS = listOf(
    "Look & feel" to listOf(SettingsSection.APPEARANCE, SettingsSection.HOME),
    "Watching" to listOf(SettingsSection.PLAYER),
    "Data" to listOf(SettingsSection.LIBRARY, SettingsSection.STORAGE),
    "App" to listOf(SettingsSection.UPDATES, SettingsSection.ABOUT),
)

/**
 * The main Settings page: a search box, the connection card, and every category with a
 * live one-line summary of what's set inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.collectAsState()
    val s = settings ?: AppSettings()
    val update by container.updater.available.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val version = remember { container.updater.installedVersionName() }
    val build = remember { container.updater.installedVersionCode() }
    val saved = container.offlineSaver.collections(s).size
    val downloadItems by container.downloads.items.collectAsState()
    val downloaded = downloadItems.count { it.done }
    val offlineNow by container.connection.offline.collectAsState()

    fun open(section: SettingsSection) {
        when (section) {
            SettingsSection.HOME -> nav.homeLayout()
            SettingsSection.UPDATES -> nav.updates()
            else -> nav.settingsSection(section.key)
        }
    }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.openExternal("https://github.com/${s.updateRepo.trim('/')}#readme") }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, "Help")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
                    unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            val q = query.trim()
            if (q.isNotEmpty()) {
                val words = q.split(' ').filter { it.isNotBlank() }
                val hits = SEARCH_INDEX.filter { e ->
                    words.all { w -> e.title.contains(w, true) || e.keywords.contains(w, true) || e.section.title.contains(w, true) }
                }
                GroupLabel(if (hits.isEmpty()) "No matches" else "${hits.size} matches")
                if (hits.isNotEmpty()) {
                    SettingsCard {
                        hits.forEachIndexed { i, e ->
                            if (i > 0) CardDivider()
                            NavRow(e.section.icon, e.section.tint, e.title, e.section.title) { open(e.section) }
                        }
                    }
                } else {
                    Hint("Try words like “cache”, “mute”, “offline” or “proxy”.")
                }
            } else {
                Spacer(Modifier.height(14.dp))
                ConnectionCard(s) { open(SettingsSection.SERVER) }

                GROUPS.forEach { (label, sections) ->
                    GroupLabel(label)
                    SettingsCard {
                        sections.forEachIndexed { i, section ->
                            if (i > 0) CardDivider()
                            NavRow(
                                icon = section.icon,
                                tint = section.tint,
                                title = section.title,
                                summary = summary(section, s, saved, downloaded, offlineNow, version, build),
                                badge = if (section == SettingsSection.UPDATES && update != null) "NEW" else null,
                            ) { open(section) }
                        }
                    }
                }
                Hint("Long-press any setting to put it back to its default.", Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Which server, whether it answers, its Stash version and response time. */
@Composable
private fun ConnectionCard(s: AppSettings, onClick: () -> Unit) {
    val container = LocalContext.current.container
    val offline by container.connection.offline.collectAsState()
    var ping by remember { mutableStateOf<Long?>(null) }
    var version by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(s.serverUrl, s.apiKey, offline) {
        if (!s.isConfigured || offline) return@LaunchedEffect
        failed = false
        ping = null
        val t0 = SystemClock.elapsedRealtime()
        try {
            version = container.repository.serverInfo().version
            ping = SystemClock.elapsedRealtime() - t0
        } catch (e: Exception) {
            failed = true
        }
    }
    val bad = s.isConfigured && (failed || offline)
    val accent = if (bad) Ink.Red else Ink.Amber
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.04f))))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(Icons.Filled.Dns, accent, size = 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !s.isConfigured -> "Not connected"
                        offline -> "Offline mode"
                        version != null -> "Stash $version"
                        bad -> "Stash"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when {
                                bad -> Ink.Red
                                ping != null -> Ink.Green
                                else -> Ink.Muted
                            },
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (s.baseUrl?.let { it.host + ":" + it.port } ?: "Tap to set up your server") +
                            when {
                                !s.isConfigured -> ""
                                offline -> " · not using the server"
                                bad -> " · unreachable"
                                ping != null -> " · $ping ms"
                                else -> " · checking…"
                            },
                        style = MaterialTheme.typography.labelSmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        offline -> "Showing only what's saved on this phone"
                        s.apiKey.isBlank() -> "No API key · fine if Stash has no login"
                        else -> "Signed in with an API key ending ${s.apiKey.takeLast(4)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.Muted)
        }
    }
}

/** Opens one Settings page by key. */
@Composable
fun SettingsSectionScreen(key: String?, onDisconnected: () -> Unit) {
    val nav = LocalNavigator.current
    when (SettingsSection.of(key)) {
        SettingsSection.SERVER -> ServerPage(onDisconnected)
        SettingsSection.APPEARANCE -> AppearancePage()
        SettingsSection.PLAYER -> PlayerPage()
        SettingsSection.LIBRARY -> LibraryStatsPage()
        SettingsSection.STORAGE -> StoragePage()
        SettingsSection.ABOUT -> AboutPage()
        else -> LaunchedEffect(Unit) { nav.back() }
    }
}
