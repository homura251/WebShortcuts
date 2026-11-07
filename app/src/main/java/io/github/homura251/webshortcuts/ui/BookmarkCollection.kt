package io.github.homura251.webshortcuts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.homura251.webshortcuts.R
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.LibraryTags
import io.github.homura251.webshortcuts.data.TagFilter
import io.github.homura251.webshortcuts.icons.IconResolver
import io.github.homura251.webshortcuts.ui.theme.LayoutMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookmarkCollection(
    state: HomeUiState,
    iconResolver: IconResolver,
    layoutMode: LayoutMode,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onLayoutChange: (LayoutMode) -> Unit,
    onOpen: (BookmarkItem) -> Unit,
    onPin: (BookmarkItem) -> Unit,
    onEdit: (BookmarkItem) -> Unit,
    onRefresh: (BookmarkItem) -> Unit,
    onUpdateShortcut: (BookmarkItem) -> Unit,
    onDelete: (BookmarkItem) -> Unit,
    onClearRefreshSummary: () -> Unit,
    tagActions: TagActions = TagActions(),
    onShowTagFilter: () -> Unit = {},
) {
    val filtered = remember(state.bookmarks, state.tags, state.query, state.tagFilter) {
        LibraryTags.filter(state.bookmarks, state.tags, state.query, state.tagFilter)
    }
    val autoCount = state.bookmarks.count { it.selectedIconSource == IconSourceMode.AUTO }
    val grid = layoutMode == LayoutMode.GRID
    // Two narrow cells cannot accommodate enlarged text and both 48dp action targets.
    val columns = if (grid && LocalDensity.current.fontScale < 1.5f) 2 else 1
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("bookmark_collection"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.bookmarks.isNotEmpty()) {
            item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CountBadge(stringResource(if (state.query.isBlank() && !state.tagFilter.active) R.string.bookmark_count else R.string.filtered_count,
                            filtered.size), Icons.Default.Language)
                        if (state.query.isBlank() && !state.tagFilter.active) CountBadge(stringResource(R.string.auto_count, autoCount), Icons.Default.Sync)
                    }
                    FilledTonalIconButton(onClick = { onLayoutChange(if (grid) LayoutMode.LIST else LayoutMode.GRID) },
                        modifier = Modifier.testTag("layout_toggle")) {
                        Icon(if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = stringResource(if (grid) R.string.show_list else R.string.show_grid))
                    }
                }
            }
        }
        if (state.error != null) item(key = "load_error", span = { GridItemSpan(maxLineSpan) }) {
            ErrorNotice(state.error, title = stringResource(R.string.read_error_title))
        }
        when {
            state.loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.loading_bookmarks), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.bookmarks.isEmpty() && state.error == null -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyBookmarks(onAdd)
            }
            state.bookmarks.isNotEmpty() -> {
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.weight(1f)) { BookmarkSearch(state.query, onQueryChange, !state.selectionMode) }
                        FilledTonalIconButton(onClick = onShowTagFilter, enabled = !state.selectionMode,
                            modifier = Modifier.testTag("open_tag_filter")) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.filter_tags)) }
                    }
                }
                if (state.tagFilter.active) item(key = "tag_filters", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.tagFilter.untagged) FilterChip(true, { tagActions.filter(TagFilter()) },
                            label = { Text(stringResource(R.string.untagged)) }, enabled = !state.selectionMode,
                            modifier = Modifier.testTag("selected_untagged"))
                        state.tags.filter { it.id in state.tagFilter.ids }.forEach { tag ->
                            FilterChip(true, { tagActions.toggleFilter(tag.id) }, label = { Text(tag.name, maxLines = 1) },
                                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }, enabled = !state.selectionMode,
                                modifier = Modifier.testTag("selected_filter_${tag.id}"))
                        }
                        TextButton(onClick = { tagActions.filter(TagFilter()) }, enabled = !state.selectionMode,
                            modifier = Modifier.testTag("clear_tag_filters")) { Text(stringResource(R.string.clear_tag_filters)) }
                    }
                }
                if (state.selectionMode) item(key = "batch_tags", span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { tagActions.editBatch(false) }, enabled = state.selectedIds.isNotEmpty() && !state.tagSaving,
                            modifier = Modifier.testTag("batch_add_tags")) { Text(stringResource(R.string.add_tags)) }
                        TextButton(onClick = { tagActions.editBatch(true) }, enabled = state.selectedIds.isNotEmpty() && !state.tagSaving,
                            modifier = Modifier.testTag("batch_remove_tags")) { Text(stringResource(R.string.remove_tags)) }
                    }
                }
                if (state.refreshing || state.refreshSummary != null) {
                    item(key = "refresh_feedback", span = { GridItemSpan(maxLineSpan) }) {
                        RefreshFeedback(state, onClearRefreshSummary)
                    }
                }
                if (filtered.isEmpty()) item(key = "no_results", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.no_results_title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.no_results_body), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onQueryChange("") }, Modifier.padding(top = 12.dp), enabled = !state.selectionMode) { Text(stringResource(R.string.clear_search)) }
                    }
                }
                items(filtered, key = { it.id }, contentType = { "bookmark" }) { bookmark ->
                    BookmarkCard(
                        bookmark, iconResolver, bookmark.id in state.refreshingIds, state.refreshMessages[bookmark.id],
                        onOpen = { onOpen(bookmark) }, onPin = { onPin(bookmark) }, onEdit = { onEdit(bookmark) },
                        onRefresh = { onRefresh(bookmark) }, onUpdateShortcut = { onUpdateShortcut(bookmark) },
                        onDelete = { onDelete(bookmark) }, compact = columns > 1,
                        tags = state.tags.filter { it.id in bookmark.tagIds }, selectedTags = state.tagFilter.ids,
                        onTagClick = tagActions.toggleFilter, onEditTags = { tagActions.edit(bookmark) },
                        onLongClick = { tagActions.select(bookmark) },
                        selectionMode = state.selectionMode, selected = bookmark.id in state.selectedIds,
                        onSelect = { tagActions.select(bookmark) }, interactionBlocked = state.transferBusy || state.tagSaving,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountBadge(text: String, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BookmarkSearch(query: String, onQueryChange: (String) -> Unit, enabled: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (focused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        TextField(
            value = query, onValueChange = onQueryChange, enabled = enabled,
            placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                }
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            shape = RoundedCornerShape(28.dp), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun RefreshFeedback(state: HomeUiState, onClear: () -> Unit) {
    var lastSummary by remember { mutableStateOf(state.refreshSummary.orEmpty()) }
    LaunchedEffect(state.refreshSummary) { state.refreshSummary?.let { lastSummary = it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(state.refreshing) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { if (state.refreshTotal > 0) (state.refreshCompleted.toFloat() / state.refreshTotal).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.refresh_progress_count, state.refreshCompleted, state.refreshTotal),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(state.refreshSummary != null) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(lastSummary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss_refresh_summary))
                    }
                }
            }
        }
    }
}
