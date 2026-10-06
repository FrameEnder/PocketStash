package com.frameender.pocketstash.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frameender.pocketstash.container
import com.frameender.pocketstash.data.BrowseSpec
import com.frameender.pocketstash.data.EntityKind
import com.frameender.pocketstash.data.HomeLayouts
import com.frameender.pocketstash.data.HomeWidget
import com.frameender.pocketstash.data.WidgetType
import com.frameender.pocketstash.ui.nav.LocalNavigator
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.Mono
import kotlinx.coroutines.launch
import java.util.UUID

private fun summary(w: HomeWidget): String = when (w.widgetType) {
    WidgetType.ROW -> {
        val sortLabel = BrowseSpec.sorts(w.entityKind).firstOrNull { it.key == w.sort }?.label ?: w.sort
        val quick = BrowseSpec.quickFilters(w.entityKind).filter { it.id in w.quick }.joinToString { it.label }
        listOfNotNull(
            w.entityKind.label,
            sortLabel + if (w.sort == "random") "" else if (w.descending) " ↓" else " ↑",
            quick.ifBlank { null },
            w.text.takeIf { it.isNotBlank() }?.let { "“$it”" },
            "${w.count} items",
            when (w.size) { "s" -> "small"; "l" -> "large"; else -> "medium" },
        ).joinToString(" · ")
    }
    WidgetType.STATS -> "Library counts and watch time"
    WidgetType.SHORTCUTS -> "Buttons for every library section"
}

private fun icon(t: WidgetType): ImageVector = when (t) {
    WidgetType.ROW -> Icons.Outlined.ViewCarousel
    WidgetType.STATS -> Icons.Outlined.BarChart
    WidgetType.SHORTCUTS -> Icons.Outlined.TouchApp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLayoutScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val settings by container.settings.collectAsState()
    val layoutJson = settings?.homeLayout ?: ""
    val list = remember(layoutJson) { HomeLayouts.decode(layoutJson) }
    val isDefault = layoutJson.isBlank()

    var editing by remember { mutableStateOf<HomeWidget?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HomeWidget?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun save(newList: List<HomeWidget>) {
        scope.launch { container.settingsStore.update { it.copy(homeLayout = HomeLayouts.encode(newList)) } }
    }
    fun replace(w: HomeWidget) = save(list.map { if (it.id == w.id) w else it })
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in list.indices) return
        save(list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
    }

    Scaffold(
        containerColor = Ink.Bg,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Customize Home")
                        Text(
                            if (isDefault) "Default layout" else "${list.count { it.enabled }} of ${list.size} sections shown",
                            style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Reset to default") },
                            enabled = !isDefault,
                            onClick = { menu = false; confirmReset = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Copy layout") },
                            onClick = {
                                menu = false
                                clipboard.setText(AnnotatedString(HomeLayouts.encode(list)))
                                Toast.makeText(context, "Layout copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                        )
                        DropdownMenuItem(text = { Text("Paste layout…") }, onClick = { menu = false; importing = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Bg),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add section") },
                containerColor = Ink.Amber,
                contentColor = Ink.OnAmber,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Sections appear on Home from top to bottom. Tap the pencil to fine-tune one, use the arrows to " +
                        "reorder, or switch it off to hide it without losing its settings.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.Muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            itemsIndexed(list, key = { _, w -> w.id }) { i, w ->
                WidgetRow(
                    w = w,
                    first = i == 0,
                    last = i == list.lastIndex,
                    onToggle = { replace(w.copy(enabled = it)) },
                    onUp = { move(i, -1) },
                    onDown = { move(i, 1) },
                    onEdit = { editing = w },
                    onDuplicate = { save(list.toMutableList().also { it.add(i + 1, w.copy(id = UUID.randomUUID().toString())) }) },
                    onDelete = { deleting = w },
                )
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        "No sections. Add one, or reset to the default layout.",
                        color = Ink.Muted, modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        AddWidgetDialog(
            onDismiss = { adding = false },
            onPick = { type, kind ->
                adding = false
                val w = HomeLayouts.create(type, kind)
                save(list + w)
                if (type == WidgetType.ROW) editing = w
            },
        )
    }

    editing?.let { w ->
        WidgetEditDialog(
            initial = w,
            onDismiss = { editing = null },
            onSave = { updated -> editing = null; replace(updated) },
        )
    }

    deleting?.let { w ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove “${w.displayTitle}”?") },
            text = { Text("You can switch a section off instead to keep its settings.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; save(list.filterNot { it.id == w.id }) }) { Text("Remove", color = Ink.Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Home?") },
            text = { Text("This restores the original sections and drops your changes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { container.settingsStore.update { it.copy(homeLayout = "") } }
                }) { Text("Reset", color = Ink.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (importing) {
        var text by remember { mutableStateOf(clipboard.getText()?.text ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Paste layout") },
            text = {
                Column {
                    Text("Paste a layout copied from PocketStash. It replaces the current one.", color = Ink.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; error = null },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                        minLines = 4, maxLines = 10,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = HomeLayouts.tryDecode(text)
                    if (parsed == null) error = "That isn't a PocketStash layout"
                    else { importing = false; save(parsed) }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WidgetRow(
    w: HomeWidget,
    first: Boolean,
    last: Boolean,
    onToggle: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.Surface)
            .border(1.dp, if (w.enabled) Ink.Line else Ink.Line.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon(w.widgetType), null, tint = if (w.enabled) Ink.Amber else Ink.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    w.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (w.enabled) Ink.Text else Ink.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary(w), style = MaterialTheme.typography.labelSmall, color = Ink.Muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = w.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Ink.Amber, checkedThumbColor = Ink.Bg),
            )
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUp, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit", tint = Ink.Muted) }
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, "Duplicate", tint = Ink.Muted) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove", tint = Ink.Red) }
        }
    }
}

@Composable
private fun AddWidgetDialog(onDismiss: () -> Unit, onPick: (WidgetType, EntityKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add section") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("CAROUSEL OF…", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                EntityKind.entries.forEach { k ->
                    PickRow(Icons.Outlined.ViewCarousel, k.label, null) { onPick(WidgetType.ROW, k) }
                }
                Spacer(Modifier.height(8.dp))
                Text("OTHER", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                listOf(WidgetType.STATS, WidgetType.SHORTCUTS).forEach { t ->
                    PickRow(icon(t), t.label, t.description) { onPick(t, EntityKind.SCENES) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Ink.Amber)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Muted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WidgetEditDialog(initial: HomeWidget, onDismiss: () -> Unit, onSave: (HomeWidget) -> Unit) {
    var w by remember { mutableStateOf(initial) }
    val colors = OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Amber,
        unfocusedContainerColor = Ink.Surface, focusedContainerColor = Ink.Surface,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (w.widgetType == WidgetType.ROW) "Carousel" else w.widgetType.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = w.title, onValueChange = { w = w.copy(title = it) },
                    label = { Text("Title") },
                    placeholder = { Text(w.copy(title = "").displayTitle) },
                    singleLine = true, colors = colors, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (w.widgetType == WidgetType.ROW) {
                    val kind = w.entityKind
                    Dropdown(
                        label = "Shows",
                        value = kind.label,
                        options = EntityKind.entries.map { it.name to it.label },
                    ) { picked ->
                        val k = EntityKind.valueOf(picked)
                        val d = BrowseSpec.defaultSort(k, com.frameender.pocketstash.data.Scope.None)
                        // Sort keys and quick filters differ per kind, so reset them.
                        w = w.copy(kind = k.name, sort = d.sort, descending = d.descending, quick = emptyList())
                    }
                    val sorts = BrowseSpec.sorts(kind)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Dropdown(
                                label = "Sort by",
                                value = sorts.firstOrNull { it.key == w.sort }?.label ?: w.sort,
                                options = sorts.map { it.key to it.label },
                            ) { w = w.copy(sort = it) }
                        }
                        if (w.sort != "random") {
                            IconButton(onClick = { w = w.copy(descending = !w.descending) }) {
                                Icon(
                                    if (w.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                                    if (w.descending) "Descending" else "Ascending", tint = Ink.Amber,
                                )
                            }
                        }
                    }
                    val quick = BrowseSpec.quickFilters(kind)
                    if (quick.isNotEmpty()) {
                        Text("FILTERS", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            quick.forEach { f ->
                                FilterChip(
                                    selected = f.id in w.quick,
                                    onClick = { w = w.copy(quick = if (f.id in w.quick) w.quick - f.id else w.quick + f.id) },
                                    label = { Text(f.label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink.AmberDim, selectedLabelColor = Ink.Text),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = w.text, onValueChange = { w = w.copy(text = it) },
                        label = { Text("Search text (optional)") },
                        singleLine = true, colors = colors, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Items: ${w.count}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = w.count.toFloat(), onValueChange = { w = w.copy(count = it.toInt()) },
                        valueRange = 5f..60f,
                        colors = SliderDefaults.colors(thumbColor = Ink.Amber, activeTrackColor = Ink.Amber),
                    )
                    Text("CARD SIZE", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                    val sizes = listOf("s" to "Small", "m" to "Medium", "l" to "Large")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        sizes.forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = w.size == key,
                                onClick = { w = w.copy(size = key) },
                                shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = Ink.AmberDim, activeContentColor = Ink.Text,
                                    inactiveContainerColor = Ink.Surface, inactiveContentColor = Ink.Muted,
                                ),
                            ) { Text(label) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(w) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Dropdown(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(value, color = Ink.Text, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null, tint = Ink.Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (k, l) ->
                    DropdownMenuItem(
                        text = { Text(l, color = if (l == value) Ink.Amber else Ink.Text) },
                        onClick = { open = false; onPick(k) },
                    )
                }
            }
        }
    }
}
