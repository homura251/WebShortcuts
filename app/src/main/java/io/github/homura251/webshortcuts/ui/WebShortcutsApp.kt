package io.github.homura251.webshortcuts.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.homura251.webshortcuts.R
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.BookmarkTag
import io.github.homura251.webshortcuts.icons.CropRect
import io.github.homura251.webshortcuts.icons.IconPolicy
import io.github.homura251.webshortcuts.icons.IconResolver
import io.github.homura251.webshortcuts.ui.theme.AppearancePreferences
import io.github.homura251.webshortcuts.ui.theme.animatedIconTint
import io.github.homura251.webshortcuts.ui.theme.rememberIconAccent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebShortcutsApp(
    state: HomeUiState,
    iconResolver: IconResolver,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (BookmarkItem) -> Unit,
    onOpen: (BookmarkItem) -> Unit,
    onPin: (BookmarkItem) -> Unit,
    onUpdateShortcut: (BookmarkItem) -> Unit,
    onRefresh: (BookmarkItem) -> Unit,
    onRefreshAll: () -> Unit,
    onDelete: (BookmarkItem) -> Unit,
    onDismissEditor: () -> Unit,
    onTitleChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUrlFocusLost: () -> Unit,
    onModeChange: (IconSourceMode) -> Unit,
    onPickImage: () -> Unit,
    onRefetchIcon: () -> Unit,
    onSave: () -> Unit,
    onCenterCropChange: (Boolean) -> Unit = {},
    onCropApply: (CropRect?) -> Unit = {},
    onClearRefreshSummary: () -> Unit = {},
    appearance: AppearancePreferences = AppearancePreferences(),
    onAppearanceChange: (AppearancePreferences) -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    tagActions: TagActions = TagActions(),
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showTagFilter by rememberSaveable { mutableStateOf(false) }
    var showTagManager by rememberSaveable { mutableStateOf(false) }
    BackHandler(state.selectionMode && state.tagEditor == null) { tagActions.cancelSelection() }
    var deleteTarget by remember { mutableStateOf<BookmarkItem?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val autoCount = state.bookmarks.count { it.selectedIconSource == IconSourceMode.AUTO }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("home_screen").nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.selectionMode) stringResource(R.string.selected_bookmark_count, state.selectedIds.size) else stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (state.selectionMode) IconButton(onClick = tagActions.cancelSelection, enabled = !state.tagSaving) {
                        Icon(Icons.Default.Close, stringResource(R.string.cancel_selection))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                actions = {
                    if (state.selectionMode) {
                        TextButton(onClick = tagActions.selectAll, enabled = !state.tagSaving,
                            modifier = Modifier.testTag("select_all_bookmarks")) { Text(stringResource(R.string.select_all)) }
                    } else {
                        IconButton(onClick = { showAppearance = true }, enabled = !state.transferBusy, modifier = Modifier.testTag("open_appearance")) {
                            Icon(Icons.Default.Palette, contentDescription = stringResource(R.string.appearance_title))
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (state.refreshing) R.string.refreshing_all else R.string.refresh_all)) },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    enabled = autoCount > 0 && !state.refreshing && !state.transferBusy,
                                    onClick = { menuExpanded = false; onRefreshAll() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfer_title)) },
                                    leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) },
                                    enabled = !state.loading && state.error == null && !state.transferBusy && !state.tagSaving,
                                    onClick = { menuExpanded = false; onOpenTransfer() },
                                    modifier = Modifier.testTag("open_transfer"),
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manage_tags)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) }, enabled = !state.transferBusy && !state.tagSaving,
                                    onClick = { menuExpanded = false; showTagManager = true }, modifier = Modifier.testTag("manage_tags"))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.about_menu)) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = { menuExpanded = false; showAbout = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.loading && state.error == null && state.bookmarks.isNotEmpty() && !state.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.transferBusy) onAdd() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_site)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing,
            onRefresh = { if (!state.loading && state.error == null && autoCount > 0 && !state.transferBusy && !state.selectionMode) onRefreshAll() },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            BookmarkCollection(
                state = state, iconResolver = iconResolver, layoutMode = appearance.layoutMode,
                onQueryChange = onQueryChange, onAdd = onAdd,
                onLayoutChange = { onAppearanceChange(appearance.copy(layoutMode = it)) },
                onOpen = onOpen, onPin = onPin, onEdit = onEdit, onRefresh = onRefresh,
                onUpdateShortcut = onUpdateShortcut, onDelete = { deleteTarget = it },
                onClearRefreshSummary = onClearRefreshSummary,
                tagActions = tagActions, onShowTagFilter = { showTagFilter = true },
            )
        }
    }

    if (showAppearance) {
        AppearanceSheet(appearance, onAppearanceChange, onDismiss = { showAppearance = false })
    }
    state.editor?.let { editor ->
        BookmarkEditor(
            state = editor,
            onDismiss = onDismissEditor,
            onTitleChange = onTitleChange,
            onUrlChange = onUrlChange,
            onUrlFocusLost = onUrlFocusLost,
            onModeChange = onModeChange,
            onPickImage = onPickImage,
            onRefetchIcon = onRefetchIcon,
            onSave = onSave,
            onCenterCropChange = onCenterCropChange,
            onCropApply = onCropApply,
            tags = state.tags, onEditTags = tagActions.editEditor,
        )
    }
    if (showTagFilter && !state.selectionMode) TagPanel(stringResource(R.string.filter_tags), { showTagFilter = false }) {
        TagFilterContent(state, tagActions.filter)
    }
    if (showTagManager) TagPanel(stringResource(R.string.manage_tags), { showTagManager = false }, !state.tagSaving) {
        TagManagerContent(state, tagActions.create, tagActions.rename, tagActions.delete)
    }
    state.tagEditor?.let { sheet ->
        val title = stringResource(when (sheet.mode) {
            TagEditMode.SET -> R.string.bookmark_tags
            TagEditMode.ADD -> R.string.add_tags
            TagEditMode.REMOVE -> R.string.remove_tags
        })
        TagPanel(title, tagActions.dismiss, !sheet.saving) {
            TagEditorContent(state.tags, sheet, tagActions.toggle, tagActions.toggleNew, tagActions.createDraft, tagActions.apply)
        }
    }
    deleteTarget?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_title, bookmark.title)) },
            text = { Text(stringResource(R.string.delete_body)) },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(bookmark) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text(stringResource(R.string.about_title)) },
            text = { Text(stringResource(R.string.about_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.understood)) } },
        )
    }
}

@Composable
internal fun EmptyBookmarks(onAdd: () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 640
    val heroSize = if (compact) 64.dp else 80.dp
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.size(heroSize), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(heroSize - 12.dp), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Default.BookmarkBorder, contentDescription = null,
                                modifier = Modifier.padding(if (compact) 10.dp else 16.dp).size(if (compact) 24.dp else 36.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 390.dp).padding(top = 8.dp),
        )
        Button(onClick = onAdd, modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_first_site))
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.padding(top = 20.dp).widthIn(max = 440.dp).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EmptyStep(Icons.Default.Link, stringResource(R.string.empty_step_one))
                EmptyStep(Icons.Default.Image, stringResource(R.string.empty_step_two))
                EmptyStep(Icons.Default.PushPin, stringResource(R.string.empty_step_three))
            }
        }
    }
}

@Composable
private fun EmptyStep(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookmarkCard(
    bookmark: BookmarkItem,
    iconResolver: IconResolver,
    refreshing: Boolean,
    refreshMessage: String?,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateShortcut: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean = false,
    tags: List<BookmarkTag> = emptyList(),
    selectedTags: Set<String> = emptySet(),
    onTagClick: (String) -> Unit = {},
    onEditTags: () -> Unit = {},
    onLongClick: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    interactionBlocked: Boolean = false,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = bookmark.copy(tagIds = emptySet(), updatedAt = 0), key2 = iconResolver) {
        value = try {
            withContext(Dispatchers.IO) { iconResolver.resolve(bookmark) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val accent by rememberIconAccent(bitmap)
    val colors = MaterialTheme.colorScheme
    val container = animatedIconTint(colors.surfaceContainerLow, accent, colors.onSurface, colors.onSurfaceVariant)
    val badge = animatedIconTint(colors.secondaryContainer, accent, colors.onSecondaryContainer)
    val actions: @Composable () -> Unit = {
        if (selectionMode) Checkbox(selected, onCheckedChange = { if (!interactionBlocked) onSelect() }, enabled = !interactionBlocked)
        else BookmarkActions(bookmark, refreshing, onOpen, onPin, onEdit, onRefresh, onUpdateShortcut, onDelete, onEditTags, !interactionBlocked)
    }
    val information: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(bookmark.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val domain = remember(bookmark.url) {
                runCatching { java.net.URI(bookmark.url).host }.getOrNull()
                    ?: bookmark.url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringAfter('@')
            }
            Text(domain, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = badge, contentColor = colors.onSecondaryContainer) {
                    Text(modeLabel(bookmark.selectedIconSource), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (refreshing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.primary)
                        Text(stringResource(R.string.fetching_icon), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                } else if (refreshMessage != null) {
                    Text(refreshMessage, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else if (bookmark.selectedIconSource == IconSourceMode.AUTO && bookmark.lastFetchedAt != null) {
                    val description = stringResource(R.string.last_successful_fetch, formatIconTime(bookmark.lastFetchedAt))
                    Text(formatIconTime(bookmark.lastFetchedAt, compact = true), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = description })
                }
            }
        }
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("bookmark_card_${bookmark.id}")
            .combinedClickable(enabled = !interactionBlocked, onClick = { if (selectionMode) onSelect() else onOpen() },
                onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.select_bookmark))
            .semantics { if (selectionMode) this.selected = selected },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = container),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.5f)),
    ) {
        if (compact) {
            Column(Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BookmarkIcon(bookmark, bitmap, badge)
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                information()
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BookmarkIcon(bookmark, bitmap, badge)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) { information() }
                actions()
            }
        }
        if (tags.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            BookmarkTagRow(tags, selectedTags, { if (selectionMode) onSelect() else onTagClick(it) }, !interactionBlocked, onMoreClick = onSelect.takeIf { selectionMode })
        }
    }
}

@Composable
private fun BookmarkActions(
    bookmark: BookmarkItem, refreshing: Boolean, onOpen: () -> Unit, onPin: () -> Unit,
    onEdit: () -> Unit, onRefresh: () -> Unit, onUpdateShortcut: () -> Unit, onDelete: () -> Unit,
    onEditTags: () -> Unit, enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.site_options, bookmark.title))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BookmarkMenuItem(R.string.open_website, Icons.AutoMirrored.Filled.OpenInNew) { expanded = false; onOpen() }
            BookmarkMenuItem(R.string.pin_shortcut, Icons.Default.PushPin) { expanded = false; onPin() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            BookmarkMenuItem(R.string.edit_bookmark, Icons.Default.Edit) { expanded = false; onEdit() }
            BookmarkMenuItem(R.string.bookmark_tags, Icons.AutoMirrored.Filled.Label) { expanded = false; onEditTags() }
            if (bookmark.selectedIconSource == IconSourceMode.AUTO) {
                BookmarkMenuItem(R.string.refresh_icon, Icons.Default.Refresh, enabled = !refreshing) { expanded = false; onRefresh() }
            }
            BookmarkMenuItem(R.string.update_shortcut, Icons.Default.Sync) { expanded = false; onUpdateShortcut() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_bookmark), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun BookmarkMenuItem(text: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun BookmarkIcon(bookmark: BookmarkItem, bitmap: Bitmap?, container: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = container, modifier = Modifier.size(48.dp)) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().adaptiveIconViewport().testTag("bookmark_icon_${bookmark.id}"))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(bookmark.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

// Adaptive foreground layers are 108dp, while the launcher's visible viewport is 72dp.
// The surrounding Surface supplies the selected mask; showing the whole layer adds false padding.
private fun Modifier.adaptiveIconViewport(): Modifier = graphicsLayer {
    scaleX = 1f / IconPolicy.ADAPTIVE_VIEWPORT_RATIO
    scaleY = 1f / IconPolicy.ADAPTIVE_VIEWPORT_RATIO
}

@Composable
private fun modeLabel(mode: IconSourceMode): String = stringResource(
    when (mode) {
        IconSourceMode.AUTO -> R.string.mode_label_auto
        IconSourceMode.CUSTOM -> R.string.mode_label_custom
        IconSourceMode.GENERATED -> R.string.mode_label_generated
    },
)

@Composable
internal fun ErrorNotice(message: String, title: String? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookmarkEditor(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onTitleChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUrlFocusLost: () -> Unit,
    onModeChange: (IconSourceMode) -> Unit,
    onPickImage: () -> Unit,
    onRefetchIcon: () -> Unit,
    onSave: () -> Unit,
    onCenterCropChange: (Boolean) -> Unit,
    onCropApply: (CropRect?) -> Unit,
    tags: List<BookmarkTag>,
    onEditTags: () -> Unit,
) {
    var askDiscard by rememberSaveable(state.id) { mutableStateOf(false) }
    var roundPreview by rememberSaveable(state.id) { mutableStateOf(false) }
    var fullCanvasPreview by rememberSaveable(state.id) { mutableStateOf(false) }
    var showSafeZone by rememberSaveable(state.id) { mutableStateOf(false) }
    var showCrop by rememberSaveable(state.id) { mutableStateOf(false) }
    var urlWasFocused by remember(state.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val editorScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val processingCustomImage = state.loading && state.mode == IconSourceMode.CUSTOM
    val requestDismiss: () -> Unit = {
        if (!state.saving) {
            if (state.dirty) askDiscard = true else onDismiss()
        }
    }
    EditorWindow(onDismiss = requestDismiss) {
        val view = LocalView.current
        val lightSystemBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { dialogWindow ->
                WindowCompat.getInsetsController(dialogWindow, view).apply {
                    isAppearanceLightStatusBars = lightSystemBars
                    isAppearanceLightNavigationBars = lightSystemBars
                }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("editor_screen"), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.imePadding().nestedScroll(editorScrollBehavior.nestedScrollConnection),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    CenterAlignedTopAppBar(
                        scrollBehavior = editorScrollBehavior,
                        colors = TopAppBarDefaults.topAppBarColors(
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        title = { Text(stringResource(if (state.isNew) R.string.editor_new_title else R.string.editor_edit_title),
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = requestDismiss, enabled = !state.saving) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                            }
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Button(
                                onClick = onSave,
                                enabled = !state.saving && !processingCustomImage,
                                modifier = Modifier.widthIn(max = 608.dp).fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                if (state.saving || processingCustomImage) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                } else {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(when {
                                    state.saving -> R.string.saving
                                    processingCustomImage -> R.string.processing_image
                                    else -> R.string.save
                                }))
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconPreview(state, roundPreview, fullCanvasPreview, showSafeZone,
                            onRoundChange = { roundPreview = it }, onFullCanvasChange = { fullCanvasPreview = it },
                            onSafeZoneChange = { showSafeZone = it })
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.icon_mode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            if (LocalDensity.current.fontScale >= 1.5f) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconSourceMode.entries.forEach { mode ->
                                        FilterChip(selected = state.mode == mode, enabled = !state.saving,
                                            onClick = { focusManager.clearFocus(); onModeChange(mode) },
                                            label = { Text(modeLabel(mode)) })
                                    }
                                }
                            } else SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                val modes = IconSourceMode.entries
                                modes.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = state.mode == mode,
                                        onClick = { focusManager.clearFocus(); onModeChange(mode) },
                                        enabled = !state.saving,
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                        label = {
                                            Text(
                                                stringResource(when (mode) {
                                                    IconSourceMode.AUTO -> R.string.mode_auto
                                                    IconSourceMode.CUSTOM -> R.string.mode_custom
                                                    IconSourceMode.GENERATED -> R.string.mode_generated
                                                }),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 2,
                                                textAlign = TextAlign.Center,
                                            )
                                        },
                                    )
                                }
                            }
                            ModeControls(state, onPickImage, onRefetchIcon, onCenterCropChange, onFreeCrop = { showCrop = true })
                        }
                        if (state.statusMessage != null && state.error == null) {
                            Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("icon_fetch_status"))
                        }
                        if (state.error != null) ErrorNotice(state.error)
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.website_details), style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.semantics { heading() })
                                OutlinedTextField(
                                    value = state.url,
                                    onValueChange = onUrlChange,
                                    enabled = !state.saving,
                                    label = { Text(stringResource(R.string.url_label)) },
                                    placeholder = { Text(stringResource(R.string.url_placeholder)) },
                                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                    supportingText = if (state.urlError != null || urlWasFocused) {
                                        { Text(state.urlError ?: stringResource(R.string.url_supporting)) }
                                    } else null,
                                    isError = state.urlError != null,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                                    modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                                        if (urlWasFocused && !focus.isFocused) onUrlFocusLost()
                                        urlWasFocused = focus.isFocused
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                )
                                OutlinedTextField(
                                    value = state.title,
                                    onValueChange = onTitleChange,
                                    enabled = !state.saving,
                                    label = { Text(stringResource(R.string.title_label)) },
                                    placeholder = { Text(stringResource(R.string.title_placeholder)) },
                                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) },
                                    supportingText = if (state.titleError != null) { { Text(state.titleError) } } else null,
                                    isError = state.titleError != null,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                )
                            }
                        }
                        TextButton(onClick = onEditTags, enabled = !state.saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor_tags")) {
                            Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            val names = tags.filter { it.id in state.tagIds }.map { it.name } + state.newTagNames
                            Text(if (names.isEmpty()) stringResource(R.string.bookmark_tags) else names.joinToString(" · "),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(if (state.isNew) R.string.save_new_hint else R.string.save_sync_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (askDiscard) {
            AlertDialog(
                onDismissRequest = { askDiscard = false },
                title = { Text(stringResource(R.string.discard_title)) },
                text = { Text(stringResource(R.string.discard_body)) },
                confirmButton = { TextButton(onClick = { askDiscard = false; onDismiss() }) { Text(stringResource(R.string.discard_changes)) } },
                dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
            )
        }
        if (showCrop && state.cropSource != null) {
            FreeCropDialog(source = state.cropSource, initialCrop = state.cropRect, onDismiss = { showCrop = false },
                onApply = { crop -> showCrop = false; onCropApply(crop) })
        }
    }
}

@Composable
private fun EditorWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        content()
    } else {
        Dialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false)) {
            BackHandler(onBack = onDismiss)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPreview(state: EditorUiState, round: Boolean, fullCanvas: Boolean, showSafeZone: Boolean,
                        onRoundChange: (Boolean) -> Unit, onFullCanvasChange: (Boolean) -> Unit,
                        onSafeZoneChange: (Boolean) -> Unit) {
    val accent by rememberIconAccent(state.preview)
    val colors = MaterialTheme.colorScheme
    val stageColor = animatedIconTint(colors.surfaceContainerLow, accent, colors.onSurface, colors.onSurfaceVariant)
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    val viewControls: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !fullCanvas, onClick = { onFullCanvasChange(false) }, modifier = Modifier.testTag("preview_launcher"), label = { Text(stringResource(R.string.preview_launcher)) })
            FilterChip(selected = fullCanvas, onClick = { onFullCanvasChange(true) }, modifier = Modifier.testTag("preview_full_canvas"), label = { Text(stringResource(R.string.preview_full_canvas)) })
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = stageColor, modifier = Modifier.fillMaxWidth().testTag("icon_preview_panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    IconArtworkPreview(state.preview, round, fullCanvas, showSafeZone, Modifier.size(88.dp))
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.size(96.dp), strokeWidth = 3.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.title.ifBlank { stringResource(R.string.icon_preview) }, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (!largeFont) viewControls()
                    if (!showSafeZone) Text(stringResource(R.string.icon_preview_hint), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }
            if (largeFont) viewControls()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !round, onClick = { onRoundChange(false) }, label = { Text(stringResource(R.string.preview_rounded)) })
                FilterChip(selected = round, onClick = { onRoundChange(true) }, label = { Text(stringResource(R.string.preview_round)) })
                FilterChip(selected = showSafeZone, onClick = { onSafeZoneChange(!showSafeZone) },
                    modifier = Modifier.testTag("preview_safe_zone"), label = { Text(stringResource(R.string.show_safe_zone)) })
            }
            if (showSafeZone) Text(
                stringResource(if (fullCanvas) R.string.full_canvas_safe_hint else R.string.safe_zone_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeControls(
    state: EditorUiState,
    onPickImage: () -> Unit,
    onRefetchIcon: () -> Unit,
    onCenterCropChange: (Boolean) -> Unit,
    onFreeCrop: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(when (state.mode) {
                    IconSourceMode.AUTO -> R.string.auto_explanation
                    IconSourceMode.CUSTOM -> R.string.custom_explanation
                    IconSourceMode.GENERATED -> R.string.generated_explanation
                }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.mode) {
                IconSourceMode.AUTO -> {
                    if (state.sourceLabel != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = CircleShape,
                        ) {
                            Text(state.sourceLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    IconSourceDetails(state.sourceUrl, state.lastFetchedAt)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefetchIcon, enabled = !state.loading && !state.saving && state.url.isNotBlank()) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.refetch_icon))
                        }
                        if (state.loading) {
                            Text(stringResource(R.string.fetching_icon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconSourceMode.CUSTOM -> {
                    FilledTonalButton(onClick = onPickImage, enabled = !state.saving && !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.hasCustomImage) R.string.replace_image else R.string.choose_image))
                    }
                    if (state.hasCustomImage) {
                        Text(stringResource(R.string.custom_retained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (state.canCrop) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.custom_framing), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.cropRect == null,
                                    onClick = { onCenterCropChange(false) },
                                    enabled = !state.loading && !state.saving,
                                    modifier = Modifier.testTag("crop_fit"),
                                    label = { Text(stringResource(R.string.crop_fit), textAlign = TextAlign.Center) },
                                    leadingIcon = if (state.cropRect == null) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    } else null,
                                )
                                FilterChip(
                                    selected = state.centerCrop,
                                    onClick = { onCenterCropChange(true) },
                                    enabled = !state.loading && !state.saving,
                                    modifier = Modifier.testTag("crop_center"),
                                    label = { Text(stringResource(R.string.crop_center), textAlign = TextAlign.Center) },
                                    leadingIcon = if (state.centerCrop) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    } else null,
                                )
                            }
                            FilledTonalButton(onClick = onFreeCrop, enabled = !state.loading && !state.saving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("free_crop")) {
                                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.free_crop_entry))
                            }
                            Text(stringResource(R.string.crop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (state.hasCustomImage && !state.loading) {
                        Text(stringResource(R.string.reimport_to_crop), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconSourceMode.GENERATED -> Unit
            }
        }
    }
}

private fun formatIconTime(timestamp: Long, compact: Boolean = false): String =
    DateFormat.getDateTimeInstance(if (compact) DateFormat.SHORT else DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun IconSourceDetails(sourceUrl: String?, lastFetchedAt: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.icon_source_url_label), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(sourceUrl ?: stringResource(R.string.icon_source_url_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("icon_source_url"))
        }
        Text(if (lastFetchedAt == null) stringResource(R.string.icon_fetch_time_empty)
            else stringResource(R.string.last_successful_fetch, formatIconTime(lastFetchedAt)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("icon_sync_time"))
    }
}
