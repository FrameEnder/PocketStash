package com.frameender.pocketstash.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.formatBytes
import com.frameender.pocketstash.data.model.Scene
import com.frameender.pocketstash.ui.settings.toast
import com.frameender.pocketstash.ui.settings.updateSettings
import com.frameender.pocketstash.ui.theme.Ink

/**
 * Pick a quality and start downloading [scene]. The original file is the default: it's the
 * fastest to get (no conversion) and resumes if interrupted. MP4 conversions are smaller or
 * play on phones that can't decode the original, but Stash makes them live, so they're slower.
 */
@Composable
fun DownloadDialog(scene: Scene, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val downloads = container.downloads
    val settings by container.settings.collectAsState()
    val options = remember(scene.id) { downloads.options(scene) }
    var picked by remember { mutableStateOf(options.firstOrNull()?.key) }
    val free = remember { downloads.freeBytes() }

    fun start() {
        val option = options.firstOrNull { it.key == picked } ?: return
        downloads.enqueue(scene, option)
        context.toast(
            if (settings?.downloadWifiOnly != false) "Download queued · starts on Wi-Fi"
            else "Download started",
        )
        onDismiss()
    }

    // Progress shows in a notification; ask for permission the first time (Android 13+).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DownloadForOffline, null, tint = Ink.Amber) },
        title = { Text("Download for offline") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (options.isEmpty()) {
                    Text("Stash didn't offer anything to download for this scene.", color = Ink.Muted)
                }
                options.forEach { o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { picked = o.key }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = picked == o.key, onClick = { picked = o.key },
                            colors = RadioButtonDefaults.colors(selectedColor = Ink.Amber),
                        )
                        Column(Modifier.weight(1f).heightIn(min = 40.dp)) {
                            Text(o.label, style = MaterialTheme.typography.titleSmall)
                            Text(o.detail, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${formatBytes(free.toDouble())} free on this phone",
                            style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = settings?.downloadWifiOnly != false,
                        onCheckedChange = { v -> context.updateSettings { it.copy(downloadWifiOnly = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked != null,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        start()
                    }
                },
            ) { Text("Download", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1) } },
        containerColor = Ink.Raised,
    )
}
