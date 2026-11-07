package io.github.homura251.webshortcuts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.homura251.webshortcuts.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WebShortcutsApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagViewModelTest {
    private lateinit var application: WebShortcutsApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val repo get() = application.container.repository
    private val work = BookmarkTag("work", "Work")
    private val docs = BookmarkTag("docs", "Docs")
    private val extra = BookmarkTag("extra", "Extra")
    private val item = BookmarkItem(id = "one", title = "One", url = "https://one.test/",
        selectedIconSource = IconSourceMode.GENERATED, tagIds = setOf(work.id), imageVersion = 8)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        application.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        application.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
    @After fun teardown() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun newBookmarkInheritsSelectedTagsAndCancelledDraftsLeaveNoCatalogEntries() = runBlocking {
        seed()
        val vm = viewModel()
        vm.setTagFilter(TagFilter(setOf(work.id)))
        vm.add()
        assertEquals(setOf(work.id), vm.state.value.editor!!.tagIds)
        vm.editEditorTags()
        vm.addDraftTag("Pending")
        vm.applyTagEditor()
        assertEquals(listOf("Pending"), vm.state.value.editor!!.newTagNames)
        assertFalse(repo.library.value.tags.any { it.name == "Pending" })
        vm.dismissEditor()
        assertFalse(repo.library.value.tags.any { it.name == "Pending" })
    }

    @Test fun savingNewBookmarkCommitsDraftTagAndAssociationsTogether() = runBlocking {
        seed()
        val vm = viewModel()
        vm.setTagFilter(TagFilter(setOf(work.id)))
        vm.add()
        vm.setMode(IconSourceMode.GENERATED)
        vm.setTitle("New")
        vm.setUrl("https://new.test/")
        vm.editEditorTags(); vm.addDraftTag(" Pending "); vm.applyTagEditor()
        vm.save()
        await { vm.state.value.editor == null }
        val tag = repo.library.value.tags.single { it.name == "Pending" }
        assertEquals(setOf(work.id, tag.id), repo.library.value.bookmarks.first().tagIds)
    }

    @Test fun oldEditorOnlyAppliesItsTagDeltaAndPreservesConcurrentTagsAndMetadata() = runBlocking {
        seed()
        val vm = viewModel()
        vm.edit(item)
        vm.editEditorTags(); vm.toggleEditorTag(docs.id); vm.applyTagEditor()
        repo.applyTags(setOf(item.id), TagChanges(addIds = setOf(extra.id)))
        val concurrent = repo.find(item.id)!!.copy(title = "Concurrent", imageVersion = 9)
        repo.save(concurrent)
        vm.save()
        await { vm.state.value.editor == null }
        val actual = repo.find(item.id)!!
        assertEquals(setOf(work.id, docs.id, extra.id), actual.tagIds)
        assertEquals("Concurrent", actual.title)
        assertEquals(9, actual.imageVersion)
    }

    @Test fun quickTagEditUsesDeltasInsteadOfOverwritingAnotherEdit() = runBlocking {
        seed()
        val vm = viewModel()
        vm.editTags(item)
        vm.toggleEditorTag(work.id); vm.toggleEditorTag(docs.id)
        repo.applyTags(setOf(item.id), TagChanges(addIds = setOf(extra.id)))
        vm.applyTagEditor()
        await { vm.state.value.tagEditor == null && !vm.state.value.tagSaving }
        val actual = repo.find(item.id)!!
        assertEquals(setOf(docs.id, extra.id), actual.tagIds)
        assertEquals(item.imageVersion, actual.imageVersion)
    }

    @Test fun ordinaryEditorSaveAlsoPreservesTagsAddedSinceTheEditorOpened() = runBlocking {
        seed()
        val vm = viewModel()
        vm.edit(item)
        vm.setTitle("Renamed")
        repo.applyTags(setOf(item.id), TagChanges(addIds = setOf(docs.id)))
        vm.save()
        await { vm.state.value.editor == null }
        assertEquals(setOf(work.id, docs.id), repo.find(item.id)!!.tagIds)
    }

    @Test fun batchSelectAllUsesFrozenIntersectionAndSearchResults() = runBlocking {
        val second = item.copy(id = "two", title = "Two", tagIds = setOf(work.id, docs.id))
        val third = item.copy(id = "three", title = "Third", tagIds = setOf(docs.id))
        repo.replaceAll(LibrarySnapshot(listOf(item, second, third), listOf(work, docs, extra)))
        val vm = viewModel()
        vm.setQuery("o")
        vm.setTagFilter(TagFilter(setOf(work.id, docs.id)))
        vm.select(second)
        vm.setQuery("changed"); vm.setTagFilter(TagFilter())
        vm.selectAll()
        assertEquals("o", vm.state.value.query)
        assertEquals(setOf(second.id), vm.state.value.selectedIds)
        vm.editBatchTags(false); vm.toggleEditorTag(extra.id); vm.applyTagEditor()
        await { !vm.state.value.selectionMode && !vm.state.value.tagSaving }
        assertEquals(setOf(work.id, docs.id, extra.id), repo.find(second.id)!!.tagIds)
        assertEquals(item, repo.find(item.id))
        assertEquals(third, repo.find(third.id))
    }

    @Test fun queryFilterSelectionAndPendingNamesRestoreAndDeletedIdsArePruned() = runBlocking {
        seed()
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.setQuery("One"); vm.setTagFilter(TagFilter(setOf(work.id)))
        vm.select(item); vm.editBatchTags(false); vm.addDraftTag("Pending")
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals("One", restored.state.value.query)
        assertEquals(setOf(work.id), restored.state.value.tagFilter.ids)
        assertEquals(setOf(item.id), restored.state.value.selectedIds)
        assertEquals(listOf("Pending"), restored.state.value.tagEditor!!.newNames)
        repo.deleteTag(work.id)
        await { !restored.state.value.tagFilter.active }
        val cold = viewModel()
        assertEquals("", cold.state.value.query)
        assertFalse(cold.state.value.tagFilter.active)
        assertFalse(cold.state.value.selectionMode)
    }

    @Test fun editorTagDraftRestoresWithoutPrematurelyCreatingTags() = runBlocking {
        seed()
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        vm.edit(item); vm.editEditorTags(); vm.addDraftTag("Pending"); vm.applyTagEditor()
        val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(listOf("Pending"), restored.state.value.editor!!.newTagNames)
        assertFalse(repo.library.value.tags.any { it.name == "Pending" })
        restored.save()
        await { restored.state.value.editor == null }
        assertTrue(repo.library.value.tags.any { it.name == "Pending" })
    }

    @Test fun queuedTagWriteCannotCrossACompletedTransferSession() = runBlocking {
        seed()
        val vm = viewModel()
        val mutex = application.container.mutationMutex
        mutex.lock()
        try {
            vm.createTag("Queued")
            val lease = application.container.transferGate.begin()!!
            application.container.transferGate.end(lease)
        } finally { mutex.unlock() }
        await { !vm.state.value.tagSaving }
        assertFalse(repo.library.value.tags.any { it.name == "Queued" })
        assertNotNull(vm.state.value.tagError)
    }

    @Test fun failedQuickTagSaveKeepsSelectionAndDraftForRetry() = runBlocking {
        seed()
        val vm = viewModel()
        vm.select(item); vm.editBatchTags(false); vm.addDraftTag("Pending")
        val block = File(application.filesDir, "bookmarks.json.new").apply { mkdirs() }
        try {
            vm.applyTagEditor()
            await { !vm.state.value.tagSaving }
            assertTrue(vm.state.value.selectionMode)
            assertEquals(listOf("Pending"), vm.state.value.tagEditor!!.newNames)
            assertNotNull(vm.state.value.tagEditor!!.error)
            assertFalse(repo.library.value.tags.any { it.name == "Pending" })
        } finally { block.deleteRecursively() }
        vm.applyTagEditor()
        await { vm.state.value.tagEditor == null && !vm.state.value.tagSaving }
        assertTrue(repo.library.value.tags.any { it.name == "Pending" })
    }

    private suspend fun seed() = repo.replaceAll(LibrarySnapshot(listOf(item), listOf(work, docs, extra)))
    private suspend fun viewModel(saved: SavedStateHandle = SavedStateHandle()): MainViewModel {
        val store = ViewModelStore().also(stores::add)
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application, saved) as T
        })[MainViewModel::class.java]
        withTimeout(5000) { vm.state.first { !it.loading } }
        return vm
    }
    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5000) { while (!predicate()) kotlinx.coroutines.yield() }
    }
}
