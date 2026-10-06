package com.frameender.pocketstash.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.AppSettings
import com.frameender.pocketstash.data.UpdateCheck
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.InfoRow
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = context.container
    val updater = container.updater
    val scope = rememberCoroutineScope()

    val settings by container.settings.collectAsState()
    val s = settings ?: AppSettings()
    val checking by updater.checking.collectAsState()
    val result by updater.lastResult.collectAsState()
    val progress by updater.downloadProgress.collectAsState()
    val downloaded by updater.downloaded.collectAsState()
    var canInstall by remember { mutableStateOf(updater.canInstall()) }
    var busy by remember { mutableStateOf(false) }

    // Coming back from the "install unknown apps" settings page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = updater.canInstall() }
    LaunchedEffect(Unit) { if (result == null && !checking) updater.check() }

    fun change(t: (AppSettings) -> AppSettings) = scope.launch { container.settingsStore.update(t) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        change { it.copy(updateNotify = granted) }
        if (!granted) Toast.makeText(context, "Notifications are off for PocketStash", Toast.LENGTH_LONG).show()
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
        unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
    )

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                title = { Text("Updates") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard("Installed") {
                InfoRow("Version", updater.installedVersionName())
                InfoRow("Build", updater.installedVersionCode().toString())
            }

            SettingsCard("Channel") {
                val options = listOf("stable" to "Stable", "nightly" to "Nightly")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = s.updateChannel == key,
                            onClick = {
                                scope.launch {
                                    container.settingsStore.update { it.copy(updateChannel = key) }
                                    updater.check(s.copy(updateChannel = key))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Ink.AmberDim, activeContentColor = Ink.Text,
                                inactiveContainerColor = Ink.Surface, inactiveContentColor = Ink.Muted,
                            ),
                        ) { Text(label) }
                    }
                }
                Text(
                    if (s.updateChannel == "nightly") "Every push to main. Newest features, may be rough."
                    else "Tagged releases only.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            SettingsCard("Status") {
                when {
                    checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Ink.Amber, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Checking GitHub…")
                    }
                    result is UpdateCheck.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = Ink.Green, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("You're on the latest ${s.updateChannel} build.")
                    }
                    result is UpdateCheck.Failed -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.Red, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text((result as UpdateCheck.Failed).message, color = Ink.Red)
                    }
                    result is UpdateCheck.Available -> {
                        val info = (result as UpdateCheck.Available).info
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SystemUpdate, null, tint = Ink.Amber, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(info.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Build ${info.versionCode} · ${"%.1f".format(info.asset.size / 1_048_576.0)} MB" +
                                        (info.release.publishedAt?.let { " · ${it.take(10)}" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                                )
                            }
                        }
                        info.release.body?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.Text.copy(alpha = 0.85f),
                                maxLines = 14,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        progress?.let { p ->
                            LinearProgressIndicator(
                                progress = { p }, color = Ink.Amber, trackColor = Ink.Raised,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (!canInstall) {
                            Text(
                                "Android needs your OK before PocketStash can install updates.",
                                style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { updater.openInstallPermission(context) }) { Text("Allow installing updates") }
                        } else {
                            Button(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val f = downloaded?.takeIf { it.name == info.asset.name } ?: updater.download(info)
                                            updater.install(context, f)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.friendly(), Toast.LENGTH_LONG).show()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.OnAmber),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (downloaded?.name == info.asset.name) "Install" else "Download & install")
                            }
                        }
                    }
                    else -> Text("Not checked yet.", color = Ink.Muted)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { scope.launch { updater.check() } }, enabled = !checking) { Text("Check now") }
            }

            SettingsCard("Automatic") {
                Toggle("Check for updates automatically", s.autoUpdateCheck, "On launch and every 6 hours") { v ->
                    change { it.copy(autoUpdateCheck = v) }
                }
                Toggle("Notify me about new builds", s.updateNotify, "A notification once per new build") { v ->
                    if (v && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        change { it.copy(updateNotify = v) }
                    }
                }
            }

            SettingsCard("Source") {
                var repo by remember(s.updateRepo) { mutableStateOf(s.updateRepo) }
                var token by remember(s.githubToken) { mutableStateOf(s.githubToken) }
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text("GitHub repo (owner/name)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub token (private repos only)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Use a fine-grained token with read-only Contents access to this repo. Leave blank if the repo is public.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            container.settingsStore.update { it.copy(updateRepo = repo.trim(), githubToken = token.trim()) }
                            updater.check(s.copy(updateRepo = repo.trim(), githubToken = token.trim()))
                        }
                    },
                    enabled = repo.trim() != s.updateRepo || token.trim() != s.githubToken,
                ) { Text("Save & check") }
            }
        }
    }
}
