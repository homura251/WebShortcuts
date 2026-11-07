package io.github.homura251.webshortcuts.transfer

import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.LibrarySnapshot
import io.github.homura251.webshortcuts.data.LibraryTags

/** Preview and commit share the same deterministic ID/name mapping. */
internal data class TagImport(
    val library: LibrarySnapshot,
    val incoming: List<BookmarkItem>,
    val retained: List<BookmarkItem>,
    val addedTags: Int,
    val updatedBookmarks: Int,
) {
    companion object {
        fun merge(local: LibrarySnapshot, parsed: ParsedImport, format: TransferFormat): TagImport {
            val tags = local.tags.toMutableList()
            val byId = tags.associateBy { it.id }.toMutableMap()
            val byName = tags.associateBy { LibraryTags.key(it.name) }.toMutableMap()
            val mapping = mutableMapOf<String, String>()
            parsed.tags.forEach { incoming ->
                val match = byId[incoming.id] ?: byName[LibraryTags.key(incoming.name)] ?: incoming.also {
                    tags += it; byId[it.id] = it; byName[LibraryTags.key(it.name)] = it
                }
                mapping[incoming.id] = match.id
            }
            val existing = local.bookmarks.associateBy { if (format == TransferFormat.BACKUP_ZIP) it.id else it.url }
            val mapped = parsed.items.map { it.copy(tagIds = it.tagIds.mapTo(linkedSetOf(), mapping::getValue)) }
            val incoming = mapped.filter { (if (format == TransferFormat.BACKUP_ZIP) it.id else it.url) !in existing }
            val additions = if (format == TransferFormat.BACKUP_ZIP) mapped.associate { it.id to it.tagIds } else emptyMap()
            var updated = 0
            val retained = local.bookmarks.map { item ->
                val union = item.tagIds + additions[item.id].orEmpty()
                if (union == item.tagIds) item else {
                    updated++
                    item.copy(tagIds = union, updatedAt = System.currentTimeMillis())
                }
            }
            return TagImport(LibrarySnapshot(incoming + retained, tags), incoming, retained, tags.size - local.tags.size, updated)
        }
    }
}
