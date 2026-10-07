package com.frameender.pocketstash.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.frameender.pocketstash.data.Format
import com.frameender.pocketstash.data.UpdateCheck
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.launch

private val D = SettingsDefaults

@Composable
fun UpdatesScreen() {
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

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.updateSettings { it.copy(updateNotify = granted) }
        if (!granted) context.toast("Notifications are off for PocketStash")
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
        unfocusedContainerColor = Ink.Bg, focusedContainerColor = Ink.Bg,
    )

    SettingsPage("Updates") {
        // ---------------- status ----------------
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Filled.NewReleases, Ink.Amber, size = 44)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PocketStash ${updater.installedVersionName()}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Build ${updater.installedVersionCode()} · ${s.updateChannel} channel",
                            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
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
                        Text(info.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                "build ${info.versionCode}",
                                info.asset.size.takeIf { it > 0 }?.let { Format.bytes(it) },
                                info.release.publishedAt?.let { Format.ago(it) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                        )
                        info.release.body?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(10.dp))
                            Surface(color = Ink.Bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    it.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                                )
                            }
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
                                            context.toast(e.friendly())
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
        }

        // ---------------- channel ----------------
        GroupLabel("Channel")
        SegmentedSetting(
            listOf(
                Triple("stable", "Stable", Icons.Filled.Verified),
                Triple("nightly", "Nightly", Icons.Filled.Science),
            ),
            selected = s.updateChannel,
        ) { key ->
            context.updateSettings { it.copy(updateChannel = key) }
            scope.launch { updater.check(s.copy(updateChannel = key)) }
        }
        Hint(
            if (s.updateChannel == "nightly") "Every push to main. Newest features, may be rough."
            else "Tagged releases only. Their notes list every change since the previous release.",
        )

        // ---------------- automatic ----------------
        GroupLabel("Automatic")
        SettingsCard {
            SwitchSetting(
                "Check for updates automatically", "On launch, when you come back after 30 minutes, and every 6 hours",
                s.autoUpdateCheck,
                onReset = { it.copy(autoUpdateCheck = D.autoUpdateCheck) },
            ) { v -> context.updateSettings { it.copy(autoUpdateCheck = v) } }
            CardDivider()
            SwitchSetting(
                "Notify me about new builds", "A notification, plus a pop-up in the app",
                s.updateNotify,
                enabled = s.autoUpdateCheck,
                onReset = { it.copy(updateNotify = D.updateNotify) },
            ) { v ->
                if (v && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.updateSettings { it.copy(updateNotify = v) }
                }
            }
            if (s.skippedUpdate > 0) {
                CardDivider()
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Pop-up skipped for build ${s.skippedUpdate}",
                        style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        context.updateSettings { it.copy(skippedUpdate = 0) }
                        updater.popupDismissed.value = 0
                    }) { Text("Show again", maxLines = 1) }
                }
            }
        }
        Hint("The pop-up offers Update now, Later (until the app next opens), or Skip this build.")

        // ---------------- source ----------------
        GroupLabel("Source")
        SettingsCard {
            Column(Modifier.padding(14.dp)) {
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
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TextButton(onClick = { repo = D.updateRepo }) { Text("Default", maxLines = 1) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            context.updateSettings { it.copy(updateRepo = repo.trim(), githubToken = token.trim()) }
                            scope.launch { updater.check(s.copy(updateRepo = repo.trim(), githubToken = token.trim())) }
                        },
                        enabled = repo.trim() != s.updateRepo || token.trim() != s.githubToken,
                    ) { Text("Save & check", maxLines = 1) }
                }
            }
        }
        Hint("Use a fine-grained token with read-only Contents access to this repo. Leave it blank if the repo is public.")
    }
}
