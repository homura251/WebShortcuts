package io.github.homura251.webshortcuts.data

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local metadata storage. A completed disk transaction precedes every observable state update.
 * Corrupt or unsupported data remains untouched; subsequent writes are blocked until a successful
 * load, so an empty screen can never accidentally overwrite the user's collection.
 */
class BookmarkRepository(context: Context) {
    private val metadata = AtomicFile(File(context.filesDir, "bookmarks.json"))
    private val mutex = Mutex()
    private var attemptedLoad = false
    private val mutableLibrary = MutableStateFlow(LibrarySnapshot())
    val library: StateFlow<LibrarySnapshot> = mutableLibrary.asStateFlow()
    private val mutableLoadError = MutableStateFlow<String?>(null)
    // Compatibility projection; value reads and collection both use the same committed snapshot.
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkItem>> = object : StateFlow<List<BookmarkItem>> {
        override val value get() = library.value.bookmarks
        override val replayCache get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<List<BookmarkItem>>): Nothing =
            library.collect { collector.emit(it.bookmarks) }
    }
    val loadError: StateFlow<String?> = mutableLoadError.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { readFromDisk() }
    }

    suspend fun save(item: BookmarkItem, changes: TagChanges? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val (tags, added) = LibraryTags.resolve(mutableLibrary.value.tags, changes ?: TagChanges())
            val normalized = item.copy(title = item.title.trim(), url = UrlNormalizer.normalize(item.url),
                tagIds = if (changes == null) item.tagIds else (item.tagIds - changes.removeIds) + added)
            BookmarkCodec.validate(normalized)
            val old = mutableLibrary.value.bookmarks
            val index = old.indexOfFirst { it.id == normalized.id }
            val updated = old.toMutableList().apply {
                if (index < 0) add(0, normalized) else set(index, normalized)
            }
            writeToDisk(LibrarySnapshot(updated, tags))
        }
    }

    suspend fun delete(id: String): BookmarkItem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val deleted = mutableLibrary.value.bookmarks.firstOrNull { it.id == id } ?: return@withLock null
            writeToDisk(mutableLibrary.value.copy(bookmarks = mutableLibrary.value.bookmarks.filterNot { it.id == id }))
            deleted
        }
    }

    suspend fun find(id: String): BookmarkItem? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            mutableLibrary.value.bookmarks.firstOrNull { it.id == id }
        }
    }

    suspend fun snapshot(): List<BookmarkItem> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureAvailable(); mutableLibrary.value.bookmarks.toList() }
    }

    /** Caller holds the app mutation mutex while staging images and committing the collection. */
    suspend fun replaceAll(items: List<BookmarkItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val normalized = items.map { it.copy(title = it.title.trim(), url = UrlNormalizer.normalize(it.url)) }
            writeToDisk(mutableLibrary.value.copy(bookmarks = normalized))
        }
    }

    suspend fun librarySnapshot(): LibrarySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { ensureAvailable(); mutableLibrary.value }
    }

    suspend fun replaceAll(snapshot: LibrarySnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            writeToDisk(snapshot.copy(bookmarks = snapshot.bookmarks.map {
                it.copy(title = it.title.trim(), url = UrlNormalizer.normalize(it.url))
            }))
        }
    }

    suspend fun createTag(rawName: String): BookmarkTag = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val name = LibraryTags.name(rawName)
            require(mutableLibrary.value.tags.none { LibraryTags.key(it.name) == LibraryTags.key(name) }) { "标签名称已存在" }
            require(mutableLibrary.value.tags.size < LibraryTags.MAX_TAGS) { "标签数量已达到上限" }
            val tag = BookmarkTag(name = name)
            writeToDisk(mutableLibrary.value.copy(tags = mutableLibrary.value.tags + tag))
            tag
        }
    }

    suspend fun renameTag(id: String, rawName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val old = mutableLibrary.value
            check(old.tags.any { it.id == id }) { "标签已被删除" }
            val name = LibraryTags.name(rawName)
            require(old.tags.none { it.id != id && LibraryTags.key(it.name) == LibraryTags.key(name) }) { "标签名称已存在" }
            val updated = old.copy(tags = old.tags.map { if (it.id == id) it.copy(name = name) else it })
            if (updated != old) writeToDisk(updated)
        }
    }

    suspend fun deleteTag(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val old = mutableLibrary.value
            val updated = old.copy(tags = old.tags.filterNot { it.id == id }, bookmarks = old.bookmarks.map {
                if (id in it.tagIds) it.copy(tagIds = it.tagIds - id, updatedAt = System.currentTimeMillis()) else it
            })
            if (updated != old) writeToDisk(updated)
        }
    }

    /** Deltas apply to current records, preserving all unrelated fields and labels. */
    suspend fun applyTags(ids: Set<String>, changes: TagChanges): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureAvailable()
            val old = mutableLibrary.value
            check(ids.isNotEmpty() && old.bookmarks.any { it.id in ids }) { "所选收藏已被删除" }
            val (tags, added) = LibraryTags.resolve(old.tags, changes)
            var changed = 0
            val items = old.bookmarks.map { item ->
                val next = if (item.id in ids) (item.tagIds - changes.removeIds) + added else item.tagIds
                if (next == item.tagIds) item else {
                    changed++
                    item.copy(tagIds = next, updatedAt = System.currentTimeMillis())
                }
            }
            val updated = LibrarySnapshot(items, tags)
            if (updated != old) writeToDisk(updated)
            changed
        }
    }

    private fun ensureAvailable() {
        if (!attemptedLoad) readFromDisk()
        mutableLoadError.value?.let { throw IOException(it) }
    }

    private fun readFromDisk() {
        attemptedLoad = true
        try {
            // AtomicFile.openRead also restores an interrupted write's backup when applicable.
            val input = try { metadata.openRead() } catch (missing: java.io.FileNotFoundException) {
                if (metadata.baseFile.exists() || File(metadata.baseFile.path + ".bak").exists()) throw missing
                mutableLibrary.value = LibrarySnapshot()
                mutableLoadError.value = null
                return
            }
            val json = input.use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > BookmarkCodec.MAX_JSON_BYTES) throw IOException("收藏文件超过安全读取上限")
                    output.write(chunk, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
            mutableLibrary.value = BookmarkCodec.decodeLibrary(json)
            mutableLoadError.value = null
        } catch (failure: Exception) {
            mutableLoadError.value = "无法读取本地收藏，原文件已保留。${failure.message.orEmpty().take(160)}"
        }
    }

    private fun writeToDisk(snapshot: LibrarySnapshot) {
        val committed = snapshot.copy(bookmarks = snapshot.bookmarks.map { it.copy(tagIds = it.tagIds.toSet()) }, tags = snapshot.tags.toList())
        val bytes = BookmarkCodec.encode(committed).toByteArray(Charsets.UTF_8)
        require(bytes.size <= BookmarkCodec.MAX_JSON_BYTES) { "收藏文件超过安全存储上限" }
        val stream = metadata.startWrite()
        try {
            stream.write(bytes)
            metadata.finishWrite(stream)
        } catch (failure: Exception) {
            metadata.failWrite(stream)
            throw failure
        }
        mutableLibrary.value = committed
        mutableLoadError.value = null
    }
}
