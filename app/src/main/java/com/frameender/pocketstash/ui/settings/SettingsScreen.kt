package com.frameender.pocketstash.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.AppSettings
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.data.model.ServerInfo
import com.frameender.pocketstash.data.model.Stats
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.InfoRow
import com.frameender.pocketstash.ui.components.Pill
import com.frameender.pocketstash.ui.components.StatTile
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Accents
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onDisconnected: () -> Unit) {
    val nav = LocalNavigator.current
    val container = LocalContext.current.container
    val settings by container.settings.collectAsState()
    val update by container.updater.available.collectAsState()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            info = container.repository.serverInfo()
            stats = container.repository.stats()
        } catch (e: Exception) {
            infoError = e.friendly()
        }
    }

    val s = settings ?: AppSettings()
    fun change(t: (AppSettings) -> AppSettings) = scope.launch { container.settingsStore.update(t) }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard("Server") {
                InfoRow("URL", s.baseUrl?.toString())
                InfoRow("API key", if (s.apiKey.isBlank()) "none" else "•••• ${s.apiKey.takeLast(4)}")
                InfoRow("Version", info?.version)
                InfoRow("Status", info?.status)
                infoError?.let { Text(it, color = Ink.Red, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.setup() }) { Text("Change server") }
                    TextButton(onClick = {
                        scope.launch {
                            container.settingsStore.clearServer()
                            onDisconnected()
                        }
                    }) { Text("Disconnect", color = Ink.Red) }
                }
            }

            SettingsCard("App") {
                NavRow(
                    Icons.Outlined.SystemUpdate, "Updates",
                    if (update != null) "Build ${update!!.versionCode} available" else "Channel: ${s.updateChannel}",
                    badge = if (update != null) "NEW" else null,
                ) { nav.updates() }
                NavRow(Icons.Outlined.Dashboard, "Customize Home", "Pick, order and tune the Home sections") { nav.homeLayout() }
            }

            SettingsCard("Highlight color") {
                AccentPicker(s.accent) { key -> change { it.copy(accent = key) } }
            }

            stats?.let { st ->
                SettingsCard("Library stats") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Scenes", "%,d".format(st.sceneCount))
                        StatTile("Scene size", formatBytes(st.scenesSize))
                        StatTile("Runtime", "%,.0f h".format(st.scenesDuration / 3600))
                        StatTile("Images", "%,d".format(st.imageCount))
                        StatTile("Image size", formatBytes(st.imagesSize))
                        StatTile("Galleries", "%,d".format(st.galleryCount))
                        StatTile("Performers", "%,d".format(st.performerCount))
                        StatTile("Studios", "%,d".format(st.studioCount))
                        StatTile("Groups", "%,d".format(st.groupCount))
                        StatTile("Tags", "%,d".format(st.tagCount))
                        StatTile("Plays", "%,d".format(st.totalPlayCount))
                        StatTile("Scenes played", "%,d".format(st.scenesPlayed))
                        StatTile("Watched", "%,.1f h".format(st.totalPlayDuration / 3600))
                        StatTile("O count", "%,d".format(st.totalOCount))
                    }
                }
            }

            SettingsCard("Playback") {
                Toggle("Resume where you left off", s.resumePlayback) { v -> change { it.copy(resumePlayback = v) } }
                Toggle("Send play activity to Stash", s.trackActivity, "Play count, resume point and watch time") { v ->
                    change { it.copy(trackActivity = v) }
                }
                Text(
                    "Count a play after ${s.playCountAfterSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = s.playCountAfterSeconds.toFloat(),
                    onValueChange = { v -> change { it.copy(playCountAfterSeconds = v.toInt()) } },
                    valueRange = 1f..120f,
                    colors = SliderDefaults.colors(thumbColor = Ink.Amber, activeTrackColor = Ink.Amber),
                )
            }

            SettingsCard("Display") {
                Text("Card size: ${s.gridCardWidth}dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.gridCardWidth.toFloat(),
                    onValueChange = { v -> change { it.copy(gridCardWidth = v.toInt()) } },
                    valueRange = 110f..280f,
                    colors = SliderDefaults.colors(thumbColor = Ink.Amber, activeTrackColor = Ink.Amber),
                )
            }

            SettingsCard("Network") {
                Toggle(
                    "Rewrite media URLs to this server",
                    s.rewriteHost,
                    "Fixes thumbnails/streams when Stash reports a different host (reverse proxy, Tailscale, Docker).",
                ) { v -> change { it.copy(rewriteHost = v) } }
            }

            Text(
                "PocketStash ${container.updater.installedVersionName()} (build ${container.updater.installedVersionCode()}) · unofficial client for stashapp/stash",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.Surface)
            .border(1.dp, Ink.Line, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.Amber)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
internal fun Toggle(label: String, value: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
        )
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Ink.Amber, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
        }
        badge?.let { Pill(it, accent = true); Spacer(Modifier.width(6.dp)) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.Muted)
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
                modifier = Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(a.key) }
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(a.main)
                        .border(if (on) 3.dp else 0.dp, if (on) Ink.Text else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Icon(Icons.Filled.Check, null, tint = a.on, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    a.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) a.main else Ink.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}
