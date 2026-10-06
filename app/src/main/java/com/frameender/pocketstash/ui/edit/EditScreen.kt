package com.frameender.pocketstash.ui.edit

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.data.EditKind
import com.frameender.pocketstash.ui.components.ErrorBox
import com.frameender.pocketstash.ui.components.LoadingBox
import com.frameender.pocketstash.ui.theme.Ink

/**
 * Full-screen edit / create form for any entity.
 * @param onSaved called with the entity id after a successful save.
 * @param onDeleted called after the entity was deleted.
 * @param onClose leave without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    vm: EditViewModel,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    fun tryClose() { if (vm.dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = vm.dirty) { confirmDiscard = true }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { tryClose() }) { Icon(Icons.Filled.Close, "Close") } },
                title = { Text((if (vm.creating) "New " else "Edit ") + vm.kind.label.lowercase()) },
                actions = {
                    if (vm.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), color = Ink.Amber, strokeWidth = 2.dp)
                    } else {
                        Button(
                            onClick = { vm.save(onSaved) },
                            enabled = !vm.loading && vm.loadError == null,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Ink.Amber, contentColor = Ink.OnAmber),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) { Text(if (vm.creating) "Create" else "Save") }
                    }
                    if (!vm.creating && vm.spec.destroyMutation != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete ${vm.kind.label.lowercase()}", color = Ink.Red) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Ink.Red) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
    ) { pad ->
        when {
            vm.loading -> LoadingBox(Modifier.padding(pad).fillMaxSize())
            vm.loadError != null -> ErrorBox(vm.loadError!!, onRetry = vm::load, modifier = Modifier.padding(pad))
            else -> LazyColumn(
                Modifier.padding(pad).fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(vm.spec.fields, key = { it.key }) { f ->
                    FieldEditor(f, vm.values[f.key], vm.errors[f.key], vm) { vm.set(f.key, it) }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard", color = Ink.Red) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (confirmDelete) {
        var deleteFile by remember { mutableStateOf(false) }
        var deleteGenerated by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this ${vm.kind.label.lowercase()}?") },
            text = {
                Column {
                    Text(
                        when (vm.kind) {
                            EditKind.SCENE, EditKind.IMAGE, EditKind.GALLERY ->
                                "This removes it from Stash. Files stay on disk unless you tick the box below."
                            EditKind.MARKER -> "The marker is removed from its scene."
                            else -> "It's removed from Stash and unlinked from everything that uses it."
                        },
                    )
                    if (vm.spec.destroyFileOptions) {
                        CheckRow("Also delete the file from disk", deleteFile) { deleteFile = it }
                        CheckRow("Delete generated files (previews, sprites…)", deleteGenerated) { deleteGenerated = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(deleteFile, deleteGenerated, onDeleted)
                }) { Text("Delete", color = Ink.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CheckRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = value, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = if (label.contains("disk")) Ink.Red else Ink.Amber),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
