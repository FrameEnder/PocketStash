package com.frameender.pocketstash.ui.edit

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.frameender.pocketstash.data.EditSpecs
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.FType
import com.frameender.pocketstash.data.FieldSpec
import com.frameender.pocketstash.data.FormValue
import com.frameender.pocketstash.data.Ref
import com.frameender.pocketstash.ui.common.friendly
import com.frameender.pocketstash.ui.components.StarRating
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
    unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
    errorContainerColor = Ink.Surface,
)

/** Renders the right editor for one field. */
@Composable
fun FieldEditor(field: FieldSpec, value: FormValue?, error: String?, vm: EditViewModel, onChange: (FormValue) -> Unit) {
    when (field.type) {
        FType.TEXT, FType.MULTILINE, FType.INT, FType.FLOAT, FType.TIME ->
            TextEditor(field, (value as? FormValue.Text)?.value ?: "", error) { onChange(FormValue.Text(it)) }
        FType.DATE -> DateEditor(field, (value as? FormValue.Text)?.value ?: "", error) { onChange(FormValue.Text(it)) }
        FType.ENUM -> EnumEditor(field, (value as? FormValue.Text)?.value ?: "") { onChange(FormValue.Text(it)) }
        FType.BOOL -> BoolEditor(field, (value as? FormValue.Flag)?.value ?: false) { onChange(FormValue.Flag(it)) }
        FType.RATING -> RatingEditor(field, (value as? FormValue.Rating)?.value) { onChange(FormValue.Rating(it)) }
        FType.STRINGS -> StringsEditor(field, (value as? FormValue.Strings)?.value ?: emptyList()) { onChange(FormValue.Strings(it)) }
        FType.REF, FType.REFS, FType.LINKS ->
            RefsEditor(field, (value as? FormValue.Refs)?.value ?: emptyList(), error, vm) { onChange(FormValue.Refs(it)) }
        FType.IMAGE -> ImageEditor(field, value as? FormValue.Image ?: FormValue.Image(null)) { onChange(it) }
    }
}

@Composable
private fun Label(field: FieldSpec, error: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            field.label.uppercase() + if (field.required) " *" else "",
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) Ink.Red else Ink.Muted,
        )
        if (error != null) {
            Spacer(Modifier.width(8.dp))
            Text(error, style = MaterialTheme.typography.labelSmall, color = Ink.Red)
        }
    }
}

@Composable
private fun TextEditor(field: FieldSpec, value: String, error: String?, onChange: (String) -> Unit) {
    val numeric = field.type in setOf(FType.INT, FType.FLOAT)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(field.label + if (field.required) " *" else "") },
        placeholder = field.hint?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = field.type != FType.MULTILINE,
        minLines = if (field.type == FType.MULTILINE) 3 else 1,
        maxLines = if (field.type == FType.MULTILINE) 12 else 1,
        textStyle = if (numeric || field.type == FType.TIME) MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono)
        else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (field.type) {
                FType.INT -> KeyboardType.Number
                FType.FLOAT -> KeyboardType.Decimal
                FType.TIME -> KeyboardType.Text
                else -> KeyboardType.Text
            },
        ),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateEditor(field: FieldSpec, value: String, error: String?, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(field.label) },
        placeholder = { Text("YYYY-MM-DD") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
        trailingIcon = { IconButton(onClick = { picking = true }) { Icon(Icons.Filled.CalendarMonth, "Pick date") } },
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) {
        val initial = runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun EnumEditor(field: FieldSpec, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Label(field)
        Box {
            OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    field.options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "—" },
                    modifier = Modifier.weight(1f), color = Ink.Text,
                )
                Icon(Icons.Filled.ArrowDropDown, null, tint = Ink.Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.options.forEach { (v, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (v == value) Ink.Amber else Ink.Text) },
                        onClick = { open = false; onChange(v) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoolEditor(field: FieldSpec, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            field.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
        )
    }
}

@Composable
private fun RatingEditor(field: FieldSpec, value: Int?, onChange: (Int?) -> Unit) {
    Column {
        Label(field)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StarRating(value, onChange, size = 34.dp)
            Spacer(Modifier.width(8.dp))
            if (value != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
        }
    }
}

@Composable
private fun StringsEditor(field: FieldSpec, value: List<String>, onChange: (List<String>) -> Unit) {
    Column {
        Label(field)
        value.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                OutlinedTextField(
                    value = s,
                    onValueChange = { nv -> onChange(value.toMutableList().also { it[i] = nv }) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(value.toMutableList().also { it.removeAt(i) }) }) {
                    Icon(Icons.Filled.Close, "Remove", tint = Ink.Muted)
                }
            }
        }
        TextButton(onClick = { onChange(value + "") }) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add ${field.label.lowercase().removeSuffix("s")}")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefsEditor(field: FieldSpec, value: List<Ref>, error: String?, vm: EditViewModel, onChange: (List<Ref>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val single = field.type == FType.REF
    Column {
        Label(field, error)
        if (field.type == FType.LINKS) {
            value.forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    RefChip(r, onRemove = { onChange(value.toMutableList().also { it.removeAt(i) }) }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = r.extra,
                        onValueChange = { nv -> onChange(value.toMutableList().also { it[i] = r.copy(extra = nv) }) },
                        placeholder = { Text(field.hint ?: "") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.linkExtra == "scene_index") KeyboardType.Number else KeyboardType.Text,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = fieldColors(),
                        modifier = Modifier.width(if (field.linkExtra == "scene_index") 90.dp else 160.dp),
                    )
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                value.forEach { r ->
                    RefChip(r, onRemove = if (field.readOnly) null else ({ onChange(value - r) }))
                }
            }
        }
        if (!field.readOnly && !(single && value.isNotEmpty())) {
            TextButton(onClick = { picking = true }) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (single) "Choose ${field.label.lowercase()}" else "Add ${field.label.lowercase()}")
            }
        } else if (single && !field.readOnly) {
            TextButton(onClick = { picking = true }) { Text("Change") }
        }
    }
    if (picking && field.refKind != null) {
        RefPickerDialog(
            kind = field.refKind,
            title = field.label,
            multi = !single,
            selected = value,
            vm = vm,
            onDismiss = { picking = false },
            onPick = { picked ->
                onChange(
                    if (single) listOf(picked)
                    else if (value.any { it.id == picked.id }) value.filterNot { it.id == picked.id }
                    else value + picked,
                )
                if (single) picking = false
            },
        )
    }
}

@Composable
private fun RefChip(r: Ref, onRemove: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.Raised)
            .border(1.dp, Ink.Line, RoundedCornerShape(50))
            .padding(start = if (r.image != null) 3.dp else 12.dp, end = if (onRemove != null) 2.dp else 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.image != null) {
            AsyncImage(
                model = r.image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Ink.Surface),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 5.dp).weight(1f, fill = false))
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, "Remove ${r.name}", tint = Ink.Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Search-as-you-type picker, with "Create …" for kinds Stash can create by name. */
@Composable
private fun RefPickerDialog(
    kind: EntityKind,
    title: String,
    multi: Boolean,
    selected: List<Ref>,
    vm: EditViewModel,
    onDismiss: () -> Unit,
    onPick: (Ref) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Ref>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canCreate = EditSpecs.quickCreate(kind) != null

    LaunchedEffect(text) {
        delay(if (text.isEmpty()) 0 else 300)
        loading = true
        error = null
        try {
            results = vm.search(kind, text)
        } catch (e: Exception) {
            error = e.friendly()
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Search ${kind.label.lowercase()}") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                error?.let { Text(it, color = Ink.Red, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    val exact = results.any { it.name.equals(text.trim(), ignoreCase = true) }
                    if (canCreate && text.isNotBlank() && !exact) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !creating) {
                                        creating = true
                                        scope.launch {
                                            try {
                                                onPick(vm.quickCreate(kind, text.trim()))
                                                text = ""
                                            } catch (e: Exception) {
                                                error = e.friendly()
                                            } finally {
                                                creating = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (creating) CircularProgressIndicator(Modifier.size(20.dp), color = Ink.Amber, strokeWidth = 2.dp)
                                else Icon(Icons.Filled.Add, null, tint = Ink.Amber)
                                Spacer(Modifier.width(10.dp))
                                Text("Create “${text.trim()}”", color = Ink.Amber)
                            }
                        }
                    }
                    if (loading && results.isEmpty()) {
                        item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = Ink.Amber, strokeWidth = 2.dp)
                        } }
                    }
                    items(results, key = { it.id }) { r ->
                        val isSel = selected.any { it.id == r.id }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(r) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (r.image != null) {
                                AsyncImage(
                                    model = r.image, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Ink.Surface),
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                r.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (isSel) Ink.Amber else Ink.Text,
                            )
                            if (isSel) Icon(Icons.Filled.Check, null, tint = Ink.Amber)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (multi) "Done" else "Close") } },
    )
}

@Composable
private fun ImageEditor(field: FieldSpec, value: FormValue.Image, onChange: (FormValue) -> Unit) {
    val context = LocalContext.current
    var urlMode by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't read the file")
            if (bytes.size > 20 * 1024 * 1024) throw IllegalStateException("Image is over 20 MB")
            val data = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            onChange(value.copy(upload = data))
            error = null
        } catch (e: Exception) {
            error = e.friendly()
        }
    }

    Column {
        Label(field)
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink.Surface)
                    .border(1.dp, if (value.upload.isNotBlank()) Ink.Amber else Ink.Line, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val model = value.upload.takeIf { it.startsWith("http") } ?: value.upload.takeIf { it.startsWith("data:") }?.let {
                    Base64.decode(it.substringAfter(","), Base64.DEFAULT)
                } ?: value.preview
                if (model != null) {
                    AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(96.dp))
                } else {
                    Icon(Icons.Outlined.Image, null, tint = Ink.Line, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                OutlinedButton(onClick = { picker.launch("image/*") }) {
                    Icon(Icons.Outlined.Image, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("From device")
                }
                OutlinedButton(onClick = { urlMode = !urlMode }) {
                    Icon(Icons.Outlined.Link, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("From URL")
                }
                if (value.upload.isNotBlank()) {
                    TextButton(onClick = { onChange(value.copy(upload = "")) }) { Text("Keep current") }
                }
            }
        }
        if (urlMode) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    placeholder = { Text("https://…") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    if (url.isNotBlank()) {
                        onChange(value.copy(upload = url.trim()))
                        urlMode = false
                    }
                }) { Text("Use") }
            }
        }
        if (value.upload.isNotBlank()) {
            Text("New image will be uploaded on save", style = MaterialTheme.typography.bodySmall, color = Ink.Amber)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Red) }
    }
}
