package io.github.homura251.webshortcuts.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.homura251.webshortcuts.data.*
import io.github.homura251.webshortcuts.icons.IconProcessor
import io.github.homura251.webshortcuts.icons.IconResolver
import io.github.homura251.webshortcuts.icons.IconStore
import io.github.homura251.webshortcuts.ui.theme.*
import java.io.File

private val previewTags = listOf(BookmarkTag("docs", "开发文档"), BookmarkTag("work", "工作"),
    BookmarkTag("read", "稍后阅读"), BookmarkTag("long", "设计规范与参考资料"), BookmarkTag("empty", "待整理"))
private val previewBookmarks = listOf(
    BookmarkItem(id = "android", title = "Android 开发文档", url = "https://developer.android.com/", tagIds = setOf("docs", "work", "read")),
    BookmarkItem(id = "material", title = "Material Design", url = "https://m3.material.io/", tagIds = setOf("work", "long")),
    BookmarkItem(id = "github", title = "GitHub 项目", url = "https://github.com/", tagIds = setOf("work")),
    BookmarkItem(id = "kotlin", title = "Kotlin", url = "https://kotlinlang.org/", tagIds = setOf("docs", "read")),
    BookmarkItem(id = "plain", title = "未分类的网站", url = "https://example.com/"),
)

@Preview(name = "Tags list", widthDp = 360, heightDp = 800, showBackground = true)
@Composable fun TagListPreview() = TagHomePreview()

@Preview(name = "Tags dark grid", widthDp = 360, heightDp = 800, showBackground = true)
@Composable fun TagGridPreview() = TagHomePreview(grid = true, dark = true)

@Preview(name = "Tags batch", widthDp = 360, heightDp = 800, showBackground = true)
@Composable fun TagBatchPreview() = TagHomePreview(batch = true)

@Preview(name = "Tags large font", widthDp = 320, heightDp = 720, fontScale = 2f, showBackground = true)
@Composable fun TagLargeFontPreview() = TagHomePreview()

@Composable private fun TagHomePreview(grid: Boolean = false, dark: Boolean = false, batch: Boolean = false) {
    val context = LocalContext.current
    val resolver = remember(context) {
        val previewContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(System.getProperty("java.io.tmpdir"), "webshortcuts-tags-preview")
        }
        IconResolver(previewContext, IconStore(previewContext), IconProcessor())
    }
    val state = HomeUiState(loading = false, bookmarks = previewBookmarks, tags = previewTags,
        tagFilter = TagFilter(setOf("work")), selectionMode = batch, selectedIds = if (batch) setOf("android", "github") else emptySet())
    WebShortcutsTheme(darkTheme = dark, dynamicColor = false) {
        WebShortcutsApp(state, resolver, remember { SnackbarHostState() },
            onQueryChange = {}, onAdd = {}, onEdit = {}, onOpen = {}, onPin = {}, onUpdateShortcut = {},
            onRefresh = {}, onRefreshAll = {}, onDelete = {}, onDismissEditor = {}, onTitleChange = {},
            onUrlChange = {}, onUrlFocusLost = {}, onModeChange = {}, onPickImage = {}, onRefetchIcon = {}, onSave = {},
            appearance = AppearancePreferences(dynamicColor = false, layoutMode = if (grid) LayoutMode.GRID else LayoutMode.LIST))
    }
}

@Preview(name = "Tag filter", widthDp = 360, heightDp = 560, showBackground = true)
@Composable fun TagFilterPreview() = TagPanelPreview {
    Text("筛选标签", style = MaterialTheme.typography.titleLarge)
    TagFilterContent(HomeUiState(bookmarks = previewBookmarks, tags = previewTags, tagFilter = TagFilter(setOf("work", "docs")))) {}
}

@Preview(name = "Tag editor large font", widthDp = 320, heightDp = 640, fontScale = 2f, showBackground = true)
@Composable fun TagEditorLargeFontPreview() = TagPanelPreview {
    Text("添加标签", style = MaterialTheme.typography.titleLarge)
    TagEditorContent(previewTags, TagEditorUiState(setOf("android"), TagEditMode.ADD,
        selectedIds = setOf("docs"), newNames = listOf("待处理")), {}, {}, {}, {})
}

@Preview(name = "Tag manager", widthDp = 360, heightDp = 640, showBackground = true)
@Composable fun TagManagerPreview() = TagPanelPreview {
    Text("标签管理", style = MaterialTheme.typography.titleLarge)
    TagManagerContent(HomeUiState(bookmarks = previewBookmarks, tags = previewTags), {}, { _, _ -> }, {})
}

@Composable private fun TagPanelPreview(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    WebShortcutsTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxSize().padding(16.dp), content = content)
        }
    }
}
