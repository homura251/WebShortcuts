package io.github.homura251.webshortcuts.transfer

import android.app.Application
import android.content.Context
import android.content.pm.ShortcutManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.homura251.webshortcuts.AppContainer
import io.github.homura251.webshortcuts.data.*
import io.github.homura251.webshortcuts.ui.theme.AppearancePreferences
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowShortcutManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagTransferTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private val repo get() = container.repository
    private val manager get() = container.transferManager
    private val localTag = BookmarkTag("local", "Work")
    private val item = BookmarkItem(id = "same", title = "Local", url = "https://local.test/", imageVersion = 7,
        selectedIconSource = IconSourceMode.GENERATED, tagIds = setOf(localTag.id), updatedAt = 12)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        container = AppContainer(context)
    }

    @Test fun zipRoundTripRestoresEmptyTagsAndAssociations() = runBlocking {
        val original = LibrarySnapshot(listOf(item), listOf(localTag, BookmarkTag("empty", "Empty")))
        repo.replaceAll(original)
        val export = manager.prepareExport(TransferFormat.BACKUP_ZIP, token()) {}
        val backup = File(context.cacheDir, "transfers/${export.token}/export.zip")
        repo.replaceAll(LibrarySnapshot())
        val t = token()
        val preview = manager.prepareImport(Uri.fromFile(backup), t) {}
        assertEquals(2, preview.tagCount)
        manager.commitImport(t, ImportMode.REPLACE, false) {}
        assertEquals(original.tags, repo.library.value.tags)
        assertEquals(original.bookmarks.single().tagIds, repo.find(item.id)!!.tagIds)
    }

    @Test fun zipMergeMapsIdsThenNamesAndOnlyUnionsDuplicateTags() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(localTag)))
        val sameId = localTag.copy(name = "Remote rename")
        val sameName = BookmarkTag("remote-work", "work")
        val newTag = BookmarkTag("new", "Reference")
        val backupItem = item.copy(title = "Remote title", url = "https://remote.test/", imageVersion = 90,
            tagIds = setOf(sameId.id, sameName.id, newTag.id))
        val other = backupItem.copy(id = "other", tagIds = setOf(sameName.id))
        val backup = archive(LibrarySnapshot(listOf(backupItem, other), listOf(sameId, sameName, newTag)))
        val t = token()
        val preview = manager.prepareImport(Uri.fromFile(backup), t) {}
        assertEquals(1, preview.newTagCount)
        assertEquals(1, preview.tagUpdatedCount)
        val result = manager.commitImport(t, ImportMode.MERGE, false) {}
        assertEquals(1, result.addedTags)
        assertEquals(1, result.tagUpdatedBookmarks)
        assertEquals(0, result.skippedDuplicates)
        val actual = repo.find(item.id)!!
        assertEquals(item.copy(tagIds = setOf(localTag.id, newTag.id), updatedAt = actual.updatedAt), actual)
        assertEquals(setOf(localTag.id), repo.find(other.id)!!.tagIds)
        assertEquals(listOf(localTag, newTag), repo.library.value.tags)
        val committed = repo.library.value
        val disk = File(context.filesDir, "bookmarks.json").readBytes()
        val again = token()
        val nextPreview = manager.prepareImport(Uri.fromFile(backup), again) {}
        assertEquals(0, nextPreview.tagUpdatedCount)
        assertEquals(0, nextPreview.newTagCount)
        val next = manager.commitImport(again, ImportMode.MERGE, false) {}
        assertEquals(0, next.tagUpdatedBookmarks)
        assertEquals(committed, repo.library.value)
        assertArrayEquals(disk, File(context.filesDir, "bookmarks.json").readBytes())
    }

    @Test fun tagOnlyMergeDoesNotReenableOrUpdatePinnedShortcut() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(localTag)))
        Shadows.shadowOf(context.getSystemService(ShortcutManager::class.java)).setIsRequestPinShortcutSupported(true)
        assertTrue(container.shortcutPublisher.pin(item))
        container.shortcutPublisher.disable(item.id)
        val added = BookmarkTag("new", "Extra")
        val backup = archive(LibrarySnapshot(listOf(item.copy(tagIds = setOf(added.id))), listOf(added)))
        val t = token()
        manager.prepareImport(Uri.fromFile(backup), t) {}
        manager.commitImport(t, ImportMode.MERGE, false) {}
        val disabled = ReflectionHelpers.getStaticField<Map<String, android.content.pm.ShortcutInfo>>(
            ShadowShortcutManager::class.java, "disabledPinnedShortcuts")
        assertTrue(item.id in disabled)
        assertEquals(7, repo.find(item.id)!!.imageVersion)
    }

    @Test fun htmlMergeIgnoresFolderAndTagsAttributesAndKeepsLocalAssociations() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(localTag)))
        val html = File(context.cacheDir, "browser.html").apply {
            writeText("<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><DT><H3>Folder</H3><DL>" +
                "<DT><A HREF='https://local.test/' TAGS='Remote'>Duplicate</A>" +
                "<DT><A HREF='https://new.test/' TAGS='Remote'>New</A></DL></DL>")
        }
        val t = token()
        manager.prepareImport(Uri.fromFile(html), t) {}
        manager.commitImport(t, ImportMode.MERGE, false) {}
        assertEquals(item, repo.find(item.id))
        assertEquals(listOf(localTag), repo.library.value.tags)
        assertTrue(repo.library.value.bookmarks.first().tagIds.isEmpty())
        val exported = manager.prepareExport(TransferFormat.BOOKMARKS_HTML, token()) {}
        val text = File(context.cacheDir, "transfers/${exported.token}/export.html").readText()
        assertFalse(text.contains("TAGS="))
        assertFalse(text.contains(">Work<"))
    }

    @Test fun htmlReplaceClearsCatalogAndPreviewExplicitlyReportsLocalTags() = runBlocking {
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(localTag)))
        val html = File(context.cacheDir, "browser.html").apply {
            writeText("<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><DT><A HREF='https://new.test/'>New</A></DL>")
        }
        val t = token()
        val preview = manager.prepareImport(Uri.fromFile(html), t) {}
        assertEquals(0, preview.tagCount)
        assertEquals(1, preview.localTagCount)
        manager.commitImport(t, ImportMode.REPLACE, false) {}
        assertTrue(repo.library.value.tags.isEmpty())
        assertTrue(repo.library.value.bookmarks.single().tagIds.isEmpty())
    }

    @Test fun oldZipMergeKeepsTagsAndOldZipReplaceClearsTags() = runBlocking {
        val legacy = File(context.cacheDir, "old.zip")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"format":"WebShortcutsBackup","formatVersion":1}""",
                "bookmarks.json" to """{"schemaVersion":3,"bookmarks":[{"id":"same","title":"Old","url":"https://old.test/"}]}""").forEach { (path, data) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        }
        repo.replaceAll(LibrarySnapshot(listOf(item), listOf(localTag)))
        val merge = token()
        manager.prepareImport(Uri.fromFile(legacy), merge) {}
        manager.commitImport(merge, ImportMode.MERGE, false) {}
        assertEquals(item, repo.find(item.id))
        val replace = token()
        manager.prepareImport(Uri.fromFile(legacy), replace) {}
        manager.commitImport(replace, ImportMode.REPLACE, false) {}
        assertTrue(repo.library.value.tags.isEmpty())
        assertTrue(repo.find(item.id)!!.tagIds.isEmpty())
    }

    @Test fun tagsNeverReprocessStoredIconBytesIncludingDuringZipRoundTrip() = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val path = try { container.iconStore.save(item.id, 7, IconSourceMode.CUSTOM, bitmap) } finally { bitmap.recycle() }
        val custom = item.copy(selectedIconSource = IconSourceMode.CUSTOM, customImagePath = path)
        repo.replaceAll(LibrarySnapshot(listOf(custom), listOf(localTag)))
        val bytes = container.iconStore.exportBytes(path)!!
        repo.applyTags(setOf(item.id), TagChanges(newNames = listOf("Extra")))
        assertArrayEquals(bytes, container.iconStore.exportBytes(path)!!)
        assertEquals(7, repo.find(item.id)!!.imageVersion)
        val expected = repo.library.value
        val exported = manager.prepareExport(TransferFormat.BACKUP_ZIP, token()) {}
        val backup = File(context.cacheDir, "transfers/${exported.token}/export.zip")
        val t = token()
        manager.prepareImport(Uri.fromFile(backup), t) {}
        manager.commitImport(t, ImportMode.REPLACE, false) {}
        val restored = repo.find(item.id)!!
        assertEquals(expected.tags, repo.library.value.tags)
        assertEquals(expected.bookmarks.single().tagIds, restored.tagIds)
        assertArrayEquals(bytes, container.iconStore.exportBytes(restored.customImagePath!!)!!)
    }

    @Test fun failedZipMergeRollsBackBothNewTagsAndDuplicateAssociations() = runBlocking {
        val original = LibrarySnapshot(listOf(item), listOf(localTag))
        repo.replaceAll(original)
        val added = BookmarkTag("new", "Extra")
        val backup = archive(LibrarySnapshot(listOf(item.copy(tagIds = setOf(added.id))), listOf(added)))
        val t = token()
        manager.prepareImport(Uri.fromFile(backup), t) {}
        val file = File(context.filesDir, "bookmarks.json")
        val bytes = file.readBytes()
        val block = File(file.path + ".new").apply { mkdirs() }
        try {
            assertTrue(runCatching { manager.commitImport(t, ImportMode.MERGE, false) {} }.isFailure)
            assertEquals(original, repo.library.value)
            assertArrayEquals(bytes, file.readBytes())
        } finally { block.deleteRecursively() }
    }

    @Test fun catalogLimitBlocksMergeButAllowsReplacement() = runBlocking {
        repo.replaceAll(LibrarySnapshot(tags = (0 until 2000).map { BookmarkTag("local-$it", "Local $it") }))
        val file = archive(LibrarySnapshot(tags = listOf(BookmarkTag("remote", "Remote"))))
        val t = token()
        val preview = manager.prepareImport(Uri.fromFile(file), t) {}
        assertFalse(preview.canMerge)
        assertTrue(preview.canReplace)
        assertTrue(runCatching { manager.commitImport(t, ImportMode.MERGE, false) {} }.isFailure)
        assertEquals(2000, repo.library.value.tags.size)
        manager.commitImport(t, ImportMode.REPLACE, false) {}
        assertEquals(1, repo.library.value.tags.size)
    }

    private fun archive(library: LibrarySnapshot) = File(context.cacheDir, "${token()}.zip").apply {
        BackupArchive.write(this, library.bookmarks, AppearancePreferences(), emptyMap(), "1.3.0", 0, library.tags)
    }
    private fun token() = UUID.randomUUID().toString()
}
