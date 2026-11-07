package io.github.homura251.webshortcuts.ui

import android.graphics.Bitmap
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.BookmarkTag
import io.github.homura251.webshortcuts.data.TagFilter
import io.github.homura251.webshortcuts.icons.CropRect

data class HomeUiState(
    val bookmarks: List<BookmarkItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val refreshing: Boolean = false,
    val refreshingIds: Set<String> = emptySet(),
    val refreshCompleted: Int = 0,
    val refreshTotal: Int = 0,
    val refreshSummary: String? = null,
    val refreshMessages: Map<String, String> = emptyMap(),
    val query: String = "",
    val editor: EditorUiState? = null,
    val transferBusy: Boolean = false,
    val tags: List<BookmarkTag> = emptyList(),
    val tagFilter: TagFilter = TagFilter(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val tagEditor: TagEditorUiState? = null,
    val tagSaving: Boolean = false,
    val tagError: String? = null,
)

enum class TagEditMode { SET, ADD, REMOVE }

data class TagEditorUiState(
    val targetIds: Set<String>,
    val mode: TagEditMode = TagEditMode.SET,
    val initialIds: Set<String> = emptySet(),
    val selectedIds: Set<String> = emptySet(),
    val newNames: List<String> = emptyList(),
    val forEditor: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class EditorUiState(
    val id: String,
    val isNew: Boolean,
    val title: String = "",
    val url: String = "",
    val mode: IconSourceMode = IconSourceMode.AUTO,
    val preview: Bitmap? = null,
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val lastFetchedAt: Long? = null,
    val statusMessage: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val urlError: String? = null,
    val titleError: String? = null,
    val hasCustomImage: Boolean = false,
    val dirty: Boolean = false,
    val canCrop: Boolean = false,
    val centerCrop: Boolean = false,
    val cropSource: Bitmap? = null,
    val cropRect: CropRect? = null,
    val tagIds: Set<String> = emptySet(),
    val initialTagIds: Set<String> = emptySet(),
    val newTagNames: List<String> = emptyList(),
)
