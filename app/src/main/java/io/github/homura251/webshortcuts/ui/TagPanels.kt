package io.github.homura251.webshortcuts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.homura251.webshortcuts.R
import io.github.homura251.webshortcuts.data.BookmarkTag
import io.github.homura251.webshortcuts.data.LibraryTags
import io.github.homura251.webshortcuts.data.TagFilter

data class TagActions(
    val filter: (TagFilter) -> Unit = {},
    val toggleFilter: (String) -> Unit = {},
    val select: (io.github.homura251.webshortcuts.data.BookmarkItem) -> Unit = {},
    val selectAll: () -> Unit = {},
    val cancelSelection: () -> Unit = {},
    val edit: (io.github.homura251.webshortcuts.data.BookmarkItem) -> Unit = {},
    val editEditor: () -> Unit = {},
    val editBatch: (Boolean) -> Unit = {},
    val toggle: (String) -> Unit = {},
    val toggleNew: (String) -> Unit = {},
    val createDraft: (String) -> Unit = {},
    val apply: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val create: (String) -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPanel(title: String, onDismiss: () -> Unit, canDismiss: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val dismissAllowed by rememberUpdatedState(canDismiss)
    val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ModalBottomSheet(onDismissRequest = { if (dismissAllowed) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { dismissAllowed || it != androidx.compose.material3.SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().heightIn(max = availableHeight * .85f)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, enabled = canDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.close))
                }
            }
            content()
        }
    }
}

@Composable
internal fun TagFilterContent(state: HomeUiState, onChange: (TagFilter) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_tags)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("filter_tag_search"))
    Text(stringResource(R.string.tag_filter_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { TagChoice(stringResource(R.string.all_bookmarks), !state.tagFilter.active, state.bookmarks.size,
            "filter_all") { onChange(TagFilter()) } }
        item { TagChoice(stringResource(R.string.untagged), state.tagFilter.untagged,
            state.bookmarks.count { it.tagIds.isEmpty() }, "filter_untagged") { onChange(TagFilter(untagged = true)) } }
        items(state.tags.filter { it.name.contains(query.trim(), true) }.sortedBy { LibraryTags.key(it.name) }, key = { it.id }) { tag ->
            TagChoice(tag.name, tag.id in state.tagFilter.ids,
                state.bookmarks.count { tag.id in it.tagIds }, "filter_tag_${tag.id}") { onChange(state.tagFilter.toggle(tag.id)) }
        }
        if (state.tags.none { it.name.contains(query.trim(), true) }) item {
            Text(stringResource(R.string.no_matching_tags), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TagChoice(name: String, selected: Boolean, count: Int?, key: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = enabled, role = Role.Checkbox,
        onValueChange = { onClick() }).testTag(key), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled)
        Text(name, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
        if (count != null) Text(count.toString(), Modifier.padding(start = 8.dp, end = 8.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ColumnScope.TagEditorContent(tags: List<BookmarkTag>, state: TagEditorUiState, onToggle: (String) -> Unit,
                              onToggleNew: (String) -> Unit, onCreate: (String) -> Unit, onApply: () -> Unit) {
    var query by rememberSaveable(state.targetIds.sorted().joinToString(), state.forEditor, state.mode) { mutableStateOf("") }
    val removing = state.mode == TagEditMode.REMOVE
    Text(stringResource(when {
        removing -> R.string.remove_tags_hint
        state.forEditor -> R.string.edit_editor_tags_hint
        else -> R.string.edit_tags_hint
    }),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.search_tags)) },
            enabled = !state.saving, singleLine = true, modifier = Modifier.weight(1f).testTag("edit_tag_search"))
        if (!removing) IconButton(onClick = { onCreate(query); if (runCatching { LibraryTags.name(query) }.isSuccess) query = "" },
            enabled = !state.saving && query.isNotBlank(), modifier = Modifier.testTag("create_draft_tag")) {
            Icon(Icons.Default.Add, stringResource(R.string.create_tag))
        }
    }
    if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tag_error"))
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("edit_tag_list")) {
        items(tags.filter { it.name.contains(query.trim(), true) }.sortedBy { LibraryTags.key(it.name) }, key = { it.id }) { tag ->
            TagChoice(tag.name, tag.id in state.selectedIds, null, "edit_tag_${tag.id}", !state.saving) { onToggle(tag.id) }
        }
        items(state.newNames.filter { it.contains(query.trim(), true) }, key = { "new:$it" }) { name ->
            TagChoice(stringResource(R.string.draft_tag_name, name), true, null, "draft_tag_$name", !state.saving) { onToggleNew(name) }
        }
        if (tags.isEmpty() && state.newNames.isEmpty()) item {
            Text(stringResource(R.string.no_tags_yet), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Button(onClick = onApply, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("apply_tags")) {
        Text(stringResource(when {
            state.saving -> R.string.saving_tags
            state.forEditor -> R.string.apply_editor_tags
            else -> R.string.apply_tags
        }))
    }
}

@Composable
internal fun TagManagerContent(state: HomeUiState, onCreate: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameName by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.tags) {
        if (state.tags.any { LibraryTags.key(it.name) == LibraryTags.key(query.trim()) }) query = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it; validationError = null }, label = { Text(stringResource(R.string.search_or_create_tag)) },
            modifier = Modifier.weight(1f).testTag("manage_tag_name"), enabled = !state.tagSaving, singleLine = true)
        IconButton(onClick = {
            val name = runCatching { LibraryTags.name(query) }
            validationError = name.exceptionOrNull()?.message
            if (name.isSuccess) onCreate(name.getOrThrow())
        }, enabled = !state.tagSaving && query.isNotBlank(), modifier = Modifier.testTag("create_tag")) {
            Icon(Icons.Default.Add, stringResource(R.string.create_tag))
        }
    }
    (validationError ?: state.tagError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    LazyColumn(Modifier.fillMaxWidth().testTag("manage_tag_list")) {
        items(state.tags.filter { it.name.contains(query.trim(), true) }.sortedBy { LibraryTags.key(it.name) }, key = { it.id }) { tag ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.tag_bookmark_count, state.bookmarks.count { tag.id in it.tagIds }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { renameId = tag.id; renameName = tag.name }, enabled = !state.tagSaving) {
                    Icon(Icons.Default.Edit, stringResource(R.string.rename_tag_name, tag.name))
                }
                IconButton(onClick = { deleteId = tag.id }, enabled = !state.tagSaving) {
                    Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete_tag_name, tag.name))
                }
            }
        }
        if (state.tags.isEmpty()) item { Text(stringResource(R.string.no_tags_yet), Modifier.padding(vertical = 16.dp)) }
    }
    if (renameId != null) AlertDialog(onDismissRequest = { renameId = null },
        title = { Text(stringResource(R.string.rename_tag)) }, text = {
            OutlinedTextField(renameName, { renameName = it }, label = { Text(stringResource(R.string.tag_name)) }, singleLine = true)
        }, confirmButton = { TextButton(onClick = {
            val result = runCatching { LibraryTags.name(renameName) }
            validationError = result.exceptionOrNull()?.message
            if (result.isSuccess) { onRename(renameId!!, result.getOrThrow()); renameId = null }
        }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text(stringResource(R.string.cancel)) } })
    state.tags.firstOrNull { it.id == deleteId }?.let { tag ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete_tag_name, tag.name)) },
            text = { Text(stringResource(R.string.delete_tag_body)) },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(tag.id) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun BookmarkTagRow(tags: List<BookmarkTag>, selected: Set<String>, onClick: (String) -> Unit, enabled: Boolean = true, onMoreClick: (() -> Unit)? = null) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val visible = if (maxWidth >= 280.dp) 2 else 1
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tags.take(visible).forEach { tag ->
                FilterChip(selected = tag.id in selected, onClick = { onClick(tag.id) }, enabled = enabled,
                    label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.weight(1f, fill = false).testTag("card_tag_${tag.id}"))
            }
            if (tags.size > visible) TextButton(onClick = { if (onMoreClick != null) onMoreClick() else showAll = true }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("more_tags")) { Text("+${tags.size - visible}") }
        }
    }
    if (showAll) TagPanel(stringResource(R.string.bookmark_tags), { showAll = false }) {
        LazyColumn {
            items(tags, key = { it.id }) { tag ->
                TagChoice(tag.name, tag.id in selected, null, "all_card_tag_${tag.id}") { onClick(tag.id); showAll = false }
            }
        }
    }
}
