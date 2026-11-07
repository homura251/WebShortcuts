package io.github.homura251.webshortcuts.data

import java.util.Locale
import java.util.UUID

data class BookmarkTag(val id: String = UUID.randomUUID().toString(), val name: String)

/** Catalog and associations always form one persisted, observable transaction. */
data class LibrarySnapshot(
    val bookmarks: List<BookmarkItem> = emptyList(),
    val tags: List<BookmarkTag> = emptyList(),
)

data class TagChanges(
    val addIds: Set<String> = emptySet(),
    val removeIds: Set<String> = emptySet(),
    val newNames: List<String> = emptyList(),
)

data class TagFilter(val ids: Set<String> = emptySet(), val untagged: Boolean = false) {
    val active get() = untagged || ids.isNotEmpty()
    fun prune(tags: List<BookmarkTag>) = copy(ids = ids.intersect(tags.map { it.id }.toSet()))
    fun toggle(id: String) = TagFilter(if (id in ids) ids - id else ids + id)
}

object LibraryTags {
    const val MAX_TAGS = 2000
    fun name(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 40 && it.none(Char::isISOControl)) { "标签名称应为 1–40 个字符，且不能包含换行" }
    }
    fun key(value: String) = value.lowercase(Locale.ROOT)

    fun filter(items: List<BookmarkItem>, tags: List<BookmarkTag>, query: String, filter: TagFilter): List<BookmarkItem> {
        val q = query.trim()
        val matchingTags = if (q.isEmpty()) emptySet() else tags.filter { it.name.contains(q, true) }.map { it.id }.toSet()
        return items.filter { item ->
            (if (filter.untagged) item.tagIds.isEmpty() else item.tagIds.containsAll(filter.ids)) &&
                (q.isEmpty() || item.title.contains(q, true) || item.url.contains(q, true) || item.tagIds.any(matchingTags::contains))
        }
    }

    /** Resolve names against the latest catalog so concurrent same-name creations coalesce. */
    fun resolve(tags: List<BookmarkTag>, changes: TagChanges): Pair<List<BookmarkTag>, Set<String>> {
        val catalog = tags.toMutableList()
        val byName = catalog.associateBy { key(it.name) }.toMutableMap()
        val known = tags.map { it.id }.toSet()
        val added = changes.addIds.intersect(known).toMutableSet()
        changes.newNames.forEach { raw ->
            val name = name(raw)
            val tag = byName[key(name)] ?: BookmarkTag(name = name).also {
                require(catalog.size < MAX_TAGS) { "标签数量已达到上限（$MAX_TAGS）" }
                catalog += it
                byName[key(name)] = it
            }
            added += tag.id
        }
        return catalog.toList() to added.toSet()
    }
}
