package com.frameender.pocketstash.ui.settings

import com.frameender.pocketstash.security.appLock
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.Format
import com.frameender.pocketstash.data.UpdateInfo
import com.frameender.pocketstash.ui.theme.Ink
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Update available" pop-up, shown over whatever screen you're on.
 *
 * It appears only when both "Check for updates automatically" and "Notify me about new
 * builds" are on, and a newer build than the installed one has been found.
 *  - Update now: downloads and opens the installer right here.
 *  - Later: hides it until the app is next opened.
 *  - Skip this build: never pops up for this build again (a newer one will still show).
 *  - Details: opens the Updates screen.
 * [suppressed] is true where it would get in the way (the Updates screen itself, first-run setup).
 */
@Composable
fun UpdatePopup(suppressed: Boolean, onDetails: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val updater = container.updater
    val settings by container.settings.collectAsState()
    val available by updater.available.collectAsState()
    val dismissed by updater.popupDismissed.collectAsState()
    // Never over the lock screen: it waits until the passcode or fingerprint has been given.
    val locked by context.appLock.locked.collectAsState()
    val s = settings ?: return
    val info = available ?: return
    val scope = rememberCoroutineScope()

    val wanted = s.autoUpdateCheck && s.updateNotify &&
        info.versionCode != dismissed &&
        info.versionCode != s.skippedUpdate &&
        info.versionCode > updater.installedVersionCode()
    if (!wanted || suppressed || locked) return

    UpdateDialog(
        info = info,
        onLater = { updater.popupDismissed.value = info.versionCode },
        onSkip = {
            scope.launch { container.settingsStore.update { it.copy(skippedUpdate = info.versionCode) } }
            Toast.makeText(context, "Skipped build ${info.versionCode}. It's still on the Updates screen.", Toast.LENGTH_LONG).show()
        },
        onDetails = {
            updater.popupDismissed.value = info.versionCode
            onDetails()
        },
    )
}

@Composable
private fun UpdateDialog(info: UpdateInfo, onLater: () -> Unit, onSkip: () -> Unit, onDetails: () -> Unit) {
    val context = LocalContext.current
    val updater = context.container.updater
    val scope = rememberCoroutineScope()
    val progress by updater.downloadProgress.collectAsState()
    val downloaded by updater.downloaded.collectAsState()
    val file = downloaded?.takeIf { it.name == info.asset.name && it.exists() }
    val pct = progress
    val busy = pct != null

    fun installOrAskPermission(f: File) {
        if (updater.canInstall()) {
            updater.install(context, f)
        } else {
            Toast.makeText(context, "Allow PocketStash to install apps, then tap Install", Toast.LENGTH_LONG).show()
            updater.openInstallPermission(context)
        }
    }

    AlertDialog(
        // Tapping outside or pressing back counts as "Later", except mid-download.
        onDismissRequest = { if (!busy) onLater() },
        icon = { Icon(Icons.Filled.NewReleases, null, tint = Ink.Amber) },
        title = { Text("Update available") },
        text = {
            Column {
                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        "build ${info.versionCode}",
                        "you have ${updater.installedVersionCode()}",
                        info.asset.size.takeIf { it > 0 }?.let { Format.bytes(it) },
                        info.release.publishedAt?.let { Format.ago(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                )
                val notes = info.release.body?.trim().orEmpty()
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Ink.Bg, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                val buttonColors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.OnAmber)
                when {
                    busy -> {
                        Text("Downloading… ${((pct ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { pct ?: 0f }, color = Ink.Amber, trackColor = Ink.Raised,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    file != null -> Button(onClick = { installOrAskPermission(file) }, colors = buttonColors, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.SystemUpdate, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Install update")
                    }
                    else -> Button(
                        onClick = {
                            scope.launch {
                                try {
                                    installOrAskPermission(updater.download(info))
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = buttonColors,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Update now")
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip, enabled = !busy) { Text("Skip this build", color = Ink.Muted, maxLines = 1) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDetails, enabled = !busy) { Text("Details", maxLines = 1) }
                TextButton(onClick = onLater, enabled = !busy) { Text("Later", maxLines = 1) }
            }
        },
        containerColor = Ink.Raised,
    )
}
