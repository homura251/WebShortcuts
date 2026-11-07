package io.github.homura251.webshortcuts.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.homura251.webshortcuts.data.*
import io.github.homura251.webshortcuts.icons.IconProcessor
import io.github.homura251.webshortcuts.icons.IconResolver
import io.github.homura251.webshortcuts.icons.IconStore
import io.github.homura251.webshortcuts.ui.theme.LayoutMode
import io.github.homura251.webshortcuts.ui.theme.WebShortcutsTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagUiTest {
    @get:Rule val compose = createComposeRule()
    private val tag = BookmarkTag("work", "Work")
    private val secondTag = BookmarkTag("docs", "Docs")
    private val item = BookmarkItem(id = "one", title = "Site", url = "https://site.test/",
        selectedIconSource = IconSourceMode.GENERATED, tagIds = setOf(tag.id))

    @Test fun clickingACardTagFiltersWithoutOpeningTheWebsite() {
        val opens = AtomicInteger()
        var filter by mutableStateOf(TagFilter())
        compose.setContent {
            val context = LocalContext.current
            WebShortcutsTheme(dynamicColor = false) {
                BookmarkCard(item, IconResolver(context, IconStore(context), IconProcessor()), false, null,
                    { opens.incrementAndGet() }, {}, {}, {}, {}, {}, tags = listOf(tag), selectedTags = filter.ids,
                    onTagClick = { filter = filter.toggle(it) })
            }
        }
        compose.onNodeWithTag("card_tag_work").performClick()
        assertEquals(setOf("work"), filter.ids)
        assertEquals(0, opens.get())
        compose.onNodeWithTag("card_tag_work").performClick()
        assertFalse(filter.active)
        assertEquals(0, opens.get())
    }

    @Test fun longPressEntersSelectionAndClickingATagThenSelectsTheBookmark() {
        val opens = AtomicInteger()
        val selections = AtomicInteger()
        var selecting by mutableStateOf(false)
        compose.setContent {
            val context = LocalContext.current
            WebShortcutsTheme(dynamicColor = false) {
                BookmarkCard(item, IconResolver(context, IconStore(context), IconProcessor()), false, null,
                    { opens.incrementAndGet() }, {}, {}, {}, {}, {}, tags = listOf(tag),
                    onLongClick = { selecting = true; selections.incrementAndGet() },
                    selectionMode = selecting, onSelect = { selections.incrementAndGet() })
            }
        }
        compose.onNodeWithTag("bookmark_card_one").performTouchInput { longClick() }
        assertTrue(selecting)
        compose.onNodeWithTag("card_tag_work").performClick()
        assertEquals(2, selections.get())
        assertEquals(0, opens.get())
    }

    @Test fun tagPickerSearchSupportsIntersectionAndVirtualUntaggedChoice() {
        var home by mutableStateOf(HomeUiState(loading = false, bookmarks = listOf(item), tags = listOf(tag, secondTag)))
        compose.setContent { WebShortcutsTheme(dynamicColor = false) {
            Column { TagFilterContent(home) { home = home.copy(tagFilter = it) } }
        } }
        compose.onNodeWithTag("filter_tag_work").performClick()
        compose.onNodeWithTag("filter_tag_docs").performClick()
        assertEquals(setOf("work", "docs"), home.tagFilter.ids)
        compose.onNodeWithTag("filter_tag_search").performTextInput("do")
        compose.onNodeWithTag("filter_tag_work").assertDoesNotExist()
        compose.onNodeWithTag("filter_tag_docs").assertIsDisplayed()
        compose.onNodeWithTag("filter_untagged").performClick()
        assertTrue(home.tagFilter.untagged)
        assertTrue(home.tagFilter.ids.isEmpty())
        compose.onNodeWithTag("filter_all").performClick()
        assertFalse(home.tagFilter.active)
    }

    @Test fun clearingTagsKeepsSearchAndClearingSearchKeepsTags() {
        var home by mutableStateOf(HomeUiState(loading = false, bookmarks = listOf(item), tags = listOf(tag),
            query = "Site", tagFilter = TagFilter(setOf(tag.id))))
        compose.setContent {
            val context = LocalContext.current
            WebShortcutsTheme(dynamicColor = false) {
                BookmarkCollection(home, IconResolver(context, IconStore(context), IconProcessor()), LayoutMode.LIST,
                    onQueryChange = { home = home.copy(query = it) }, onAdd = {}, onLayoutChange = {}, onOpen = {},
                    onPin = {}, onEdit = {}, onRefresh = {}, onUpdateShortcut = {}, onDelete = {}, onClearRefreshSummary = {},
                    tagActions = TagActions(filter = { home = home.copy(tagFilter = it) }))
            }
        }
        compose.onNodeWithTag("clear_tag_filters").performClick()
        assertEquals("Site", home.query)
        assertFalse(home.tagFilter.active)
        compose.runOnIdle { home = home.copy(tagFilter = TagFilter(setOf(tag.id))) }
        compose.onNodeWithContentDescription("清除搜索").performClick()
        assertEquals("", home.query)
        assertEquals(setOf(tag.id), home.tagFilter.ids)
    }

    @Test fun twoHundredPercentTextStillReachesTagSaveAndScrollableChoices() {
        val applies = AtomicInteger()
        compose.setContent { WebShortcutsTheme(dynamicColor = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Column(Modifier.requiredSize(320.dp, 480.dp)) {
                    TagEditorContent((0..30).map { BookmarkTag("tag-$it", "Label $it") }, TagEditorUiState(setOf(item.id)),
                        onToggle = {}, onToggleNew = {}, onCreate = {}, onApply = { applies.incrementAndGet() })
                }
            }
        } }
        compose.onNodeWithTag("edit_tag_list").performScrollToNode(hasTestTag("edit_tag_tag-30"))
        compose.onNodeWithTag("edit_tag_tag-30").assertIsDisplayed()
        compose.onNodeWithTag("apply_tags").assertIsDisplayed().performClick()
        assertEquals(1, applies.get())
    }
}
