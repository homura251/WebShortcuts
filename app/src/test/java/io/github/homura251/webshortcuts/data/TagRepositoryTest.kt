package io.github.homura251.webshortcuts.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TagRepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: BookmarkRepository
    private val work = BookmarkTag("work", "Work")
    private val docs = BookmarkTag("docs", "Docs")
    private val item = BookmarkItem(id = "one", title = "Site", url = "https://site.test/",
        tagIds = setOf(work.id), imageVersion = 7, customImagePath = "icons/custom/image.png", updatedAt = 12)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        repo = BookmarkRepository(context)
    }

    @Test fun batchDeltasPreserveUnrelatedTagsImagesAndVersions() = runBlocking {
        val second = item.copy(id = "two", tagIds = setOf(docs.id))
        repo.replaceAll(LibrarySnapshot(listOf(item, second), listOf(work, docs)))
        assertEquals(2, repo.applyTags(setOf(item.id, second.id), TagChanges(newNames = listOf("Common"))))
        val added = repo.library.value.tags.single { it.name == "Common" }
        assertEquals(setOf(work.id, added.id), repo.find(item.id)!!.tagIds)
        assertEquals(setOf(docs.id, added.id), repo.find(second.id)!!.tagIds)
        repo.applyTags(setOf(item.id), TagChanges(removeIds = setOf(work.id)))
        val actual = repo.find(item.id)!!
        assertEquals(item.customImagePath, actual.customImagePath)
        assertEquals(7, actual.imageVersion)
        assertEquals(setOf(added.id), actual.tagIds)
        val reopened = BookmarkRepository(context)
        reopened.load()
        assertEquals(repo.library.value, reopened.library.value)
    }

    @Test fun renamingKeepsIdsAndDeletingOnlyRemovesAssociations() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(work, docs)))
        repo.renameTag(work.id, " Renamed ")
        assertEquals(item, repo.find(item.id))
        assertEquals("Renamed", repo.library.value.tags.first().name)
        repo.deleteTag(work.id)
        val actual = repo.find(item.id)!!
        assertEquals(item.copy(tagIds = emptySet(), updatedAt = actual.updatedAt), actual)
        assertEquals(listOf(docs), repo.library.value.tags)
    }

    @Test fun failedBatchCommitRollsBackCatalogAndAssociationsTogether() = runBlocking {
        val original = LibrarySnapshot(listOf(item), listOf(work))
        repo.replaceAll(original)
        val metadata = File(context.filesDir, "bookmarks.json")
        val bytes = metadata.readBytes()
        File(metadata.path + ".new").mkdirs()
        try {
            assertTrue(runCatching { repo.applyTags(setOf(item.id), TagChanges(newNames = listOf("New"))) }.isFailure)
            assertEquals(original, repo.library.value)
            assertArrayEquals(bytes, metadata.readBytes())
        } finally { File(metadata.path + ".new").deleteRecursively() }
        assertEquals(original, BookmarkCodec.decodeLibrary(metadata.readText()))
    }

    @Test fun loadingLegacyDoesNotWriteUntilASuccessfulMutation() = runBlocking {
        val legacy = """{"schemaVersion":3,"bookmarks":[{"id":"one","title":"Old","url":"https://old.test/"}]}"""
        val metadata = File(context.filesDir, "bookmarks.json").apply { writeText(legacy) }
        repo.load()
        assertEquals(legacy, metadata.readText())
        repo.createTag("Empty")
        assertEquals(4, org.json.JSONObject(metadata.readText()).getInt("schemaVersion"))
        assertTrue(repo.find("one")!!.tagIds.isEmpty())
    }

    @Test fun duplicateNamesAndDanglingIdsCannotOverwriteExistingMetadata() = runBlocking {
        val original = LibrarySnapshot(listOf(item), listOf(work, docs))
        repo.replaceAll(original)
        assertTrue(runCatching { repo.renameTag(work.id, " docs ") }.isFailure)
        assertTrue(runCatching { repo.createTag("WORK") }.isFailure)
        assertTrue(runCatching { repo.save(item.copy(tagIds = setOf("missing"))) }.isFailure)
        assertEquals(original, repo.library.value)
    }

    @Test fun repeatedBatchWithExistingNameIsAnExactNoop() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(work)))
        val bytes = File(context.filesDir, "bookmarks.json").readBytes()
        assertEquals(0, repo.applyTags(setOf(item.id), TagChanges(newNames = listOf(" work "))))
        assertEquals(item, repo.find(item.id))
        assertArrayEquals(bytes, File(context.filesDir, "bookmarks.json").readBytes())
    }
}
