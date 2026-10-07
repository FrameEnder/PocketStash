package com.frameender.pocketstash.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.Format
import com.frameender.pocketstash.data.OfflineCollection
import com.frameender.pocketstash.ui.theme.Ink

/**
 * Asks how much of a list to keep for offline viewing, then starts the save.
 * [total] is how many entries the list has (null if unknown).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveOfflineDialog(base: OfflineCollection, total: Int?, onDismiss: () -> Unit) {
    val saver = LocalContext.current.container.offlineSaver
    val kind = base.entityKind
    val options = listOf(40, 120, 400, 1000).let { o -> if (total != null) o.filter { it < total } + total else o }.distinct()
    var max by remember { mutableStateOf(options.firstOrNull { it >= 120 } ?: options.last()) }
    var full by remember { mutableStateOf(true) }
    val noun = kind.label.lowercase()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.DownloadForOffline, null, tint = Ink.Amber) },
        title = { Text("Save for offline") },
        text = {
            Column {
                Text(
                    "Keeps “${base.label}” on this phone so it still opens when your Stash server can't be reached.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("How many $noun", style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { n ->
                        FilterChip(
                            selected = max == n,
                            onClick = { max = n },
                            label = { Text(if (n == total) "All ${Format.count(n)}" else Format.count(n)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink.AmberDim, selectedLabelColor = Ink.Text),
                        )
                    }
                }
                if (kind == EntityKind.IMAGES) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(full, { full = it }, colors = CheckboxDefaults.colors(checkedColor = Ink.Amber))
                        Text("Include full-size images", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    when (kind) {
                        EntityKind.IMAGES ->
                            if (full) "Uses more space. Video clips keep only their thumbnails."
                            else "Thumbnails only: quick and small."
                        EntityKind.SCENES, EntityKind.MARKERS ->
                            "Saves the list, each scene's page and its screenshot. The videos themselves still need the server."
                        EntityKind.GALLERIES ->
                            "Saves the list, each gallery's page, and the first page of its images."
                        else -> "Saves the list, each page, and the first page of its scenes."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saver.save(base.copy(max = max, fullImages = full && kind == EntityKind.IMAGES))
                onDismiss()
            }) { Text("Save", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", maxLines = 1) } },
        containerColor = Ink.Raised,
    )
}
