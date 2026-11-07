package io.github.homura251.webshortcuts.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryTagsTest {
    private val work = BookmarkTag("work", "工作")
    private val docs = BookmarkTag("docs", "Docs")
    private val first = BookmarkItem(id = "one", title = "Kotlin", url = "https://kotlin.test/", tagIds = setOf(work.id, docs.id))
    private val second = BookmarkItem(id = "two", title = "Read", url = "https://read.test/", tagIds = setOf(docs.id))
    private val third = BookmarkItem(id = "three", title = "Plain", url = "https://plain.test/")
    private val library = LibrarySnapshot(listOf(first, second, third), listOf(work, docs, BookmarkTag("empty", "空标签")))

    @Test fun schemaFourRoundTripPreservesCatalogEmptyTagsAndAssociations() {
        assertEquals(library, BookmarkCodec.decodeLibrary(BookmarkCodec.encode(library)))
        assertEquals(4, JSONObject(BookmarkCodec.encode(library)).getInt("schemaVersion"))
    }

    @Test fun oldSchemasAndRawListsLoadWithoutTagsAndKeepIdentity() {
        val item = """{"id":"legacy","title":"Old","url":"https://old.test/"}"""
        (1..3).forEach { version ->
            val old = BookmarkCodec.decodeLibrary("""{"schemaVersion":$version,"bookmarks":[$item]}""")
            assertTrue(old.tags.isEmpty())
            assertTrue(old.bookmarks.single().tagIds.isEmpty())
            assertEquals("legacy", old.bookmarks.single().id)
        }
        assertTrue(BookmarkCodec.decodeLibrary("[$item]").tags.isEmpty())
    }

    @Test fun corruptCatalogAndDanglingAssociationsAreRejected() {
        val cases = listOf(
            library.copy(tags = listOf(work, work.copy(name = "Other"))),
            library.copy(tags = library.tags + BookmarkTag("other", "docs")),
            library.copy(tags = listOf(work)),
            LibrarySnapshot(tags = listOf(BookmarkTag("bad", " padded "))),
            LibrarySnapshot(tags = listOf(BookmarkTag("bad", "a\nb"))),
        )
        cases.forEach { assertThrows(IllegalArgumentException::class.java) { BookmarkCodec.encode(it) } }
        assertThrows(Exception::class.java) { BookmarkCodec.decodeLibrary("""{"schemaVersion":4,"bookmarks":[]}""") }
    }

    @Test fun searchMatchesTagNamesAndCombinesWithIntersection() {
        assertEquals(listOf(first, second), LibraryTags.filter(library.bookmarks, library.tags, "dOcS", TagFilter()))
        assertEquals(listOf(first), LibraryTags.filter(library.bookmarks, library.tags, "docs", TagFilter(setOf(work.id, docs.id))))
        assertTrue(LibraryTags.filter(library.bookmarks, library.tags, "read", TagFilter(setOf(work.id))).isEmpty())
        assertEquals(listOf(first), LibraryTags.filter(library.bookmarks, library.tags, "kotlin.test", TagFilter()))
    }

    @Test fun untaggedIsDistinctFromAllAndDeletedFiltersFallBackToAll() {
        assertEquals(listOf(third), LibraryTags.filter(library.bookmarks, library.tags, "", TagFilter(untagged = true)))
        assertFalse(TagFilter(setOf("deleted")).prune(library.tags).active)
        assertEquals(TagFilter(setOf(work.id)), TagFilter(untagged = true).toggle(work.id))
        assertEquals(TagFilter(), TagFilter(setOf(work.id)).toggle(work.id))
    }

    @Test fun pendingNamesResolveAgainstLatestCatalogAndAreTrimmedAndDeduplicated() {
        val (catalog, ids) = LibraryTags.resolve(listOf(docs), TagChanges(newNames = listOf(" docs ", "New", "new")))
        assertEquals(2, catalog.size)
        assertEquals(setOf(docs.id, catalog.last().id), ids)
        assertEquals("New", catalog.last().name)
    }

    @Test fun invalidAndExcessiveTagNamesOrCatalogAreRejected() {
        listOf(" ", "x".repeat(41), "a\nb", "a\u0000b").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { LibraryTags.name(value) }
        }
        val full = (0 until LibraryTags.MAX_TAGS).map { BookmarkTag("$it", "Tag $it") }
        assertThrows(IllegalArgumentException::class.java) { LibraryTags.resolve(full, TagChanges(newNames = listOf("Extra"))) }
        assertEquals(2000, LibraryTags.resolve(full, TagChanges(newNames = listOf("tag 1"))).first.size)
    }
}
