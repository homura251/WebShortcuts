package io.github.homura251.webshortcuts.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.homura251.webshortcuts.data.BookmarkCodec
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.BookmarkRepository
import io.github.homura251.webshortcuts.data.LibrarySnapshot
import io.github.homura251.webshortcuts.icons.IconPolicy
import io.github.homura251.webshortcuts.icons.IconStore
import io.github.homura251.webshortcuts.icons.StoredIconBytes
import io.github.homura251.webshortcuts.shortcuts.ShortcutPublisher
import io.github.homura251.webshortcuts.ui.theme.ThemePreferences
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** I/O and transactions live here; neither parsing nor preflight mutates the collection. */
class TransferManager(
    context: Context,
    private val repository: BookmarkRepository,
    private val icons: IconStore,
    private val preferences: ThemePreferences,
    private val shortcuts: ShortcutPublisher,
    private val mutationMutex: Mutex,
) {
    private val context = context.applicationContext
    private val root = File(this.context.cacheDir, "transfers")
    private val imports = ConcurrentHashMap<String, PreparedImport>()

    suspend fun prepareImport(uri: Uri, token: String, progress: (TransferProgress) -> Unit): ImportPreview = withContext(Dispatchers.IO) {
        val directory = session(token)
        val jobContext = coroutineContext
        progress(TransferProgress("正在读取文件…"))
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        input.use { source -> File(directory, "input").outputStream().use { copyBounded(source, it, BackupArchive.MAX_ARCHIVE_BYTES) { jobContext.ensureActive() } } }
        val prepared = parseImport(token, progress)
        File(directory, "ready.json").writeText(JSONObject().put("kind", "import").toString())
        imports[token] = prepared
        preview(prepared)
    }

    suspend fun restoreImport(token: String, progress: (TransferProgress) -> Unit): ImportPreview = withContext(Dispatchers.IO) {
        require(readDescriptor(token).getString("kind") == "import") { "导入缓存已过期，请重新选择文件" }
        val prepared = parseImport(token, progress)
        imports[token] = prepared
        preview(prepared)
    }

    private suspend fun parseImport(token: String, progress: (TransferProgress) -> Unit): PreparedImport {
        val directory = session(token)
        val input = File(directory, "input")
        require(input.isFile && input.length() <= BackupArchive.MAX_ARCHIVE_BYTES) { "导入缓存已过期，请重新选择文件" }
        val prefix = ByteArray(4)
        val prefixSize = input.inputStream().use { it.read(prefix) }
        val zip = prefixSize >= 2 && prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()
        val jobContext = coroutineContext
        progress(TransferProgress("正在检查收藏数据…"))
        if (!zip) {
            require(input.length() <= BookmarkCodec.MAX_JSON_BYTES) { "HTML 书签文件不能超过 4 MiB" }
            val parsed = BookmarkHtmlCodec.decode(input.readBytes(), checkpoint = { jobContext.ensureActive() })
            return PreparedImport(token, TransferFormat.BOOKMARKS_HTML, parsed, emptyMap())
        }
        val staging = File(directory, "assets")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "无法准备图标缓存，请检查剩余空间" }
        val (parsed, assets) = BackupArchive.read(input, staging) { jobContext.ensureActive() }
        assets.entries.forEachIndexed { index, (path, file) ->
            jobContext.ensureActive()
            progress(TransferProgress("正在检查图像 ${index + 1} / ${assets.size}", index + 1, assets.size))
            val bytes = file.inputStream().use { IconPolicy.readBounded(it, BackupArchive.assetLimit(path)) }
            StoredIconBytes.validate(bytes, path.startsWith("icons/original/"), path.substringAfterLast('.'))
        }
        return PreparedImport(token, TransferFormat.BACKUP_ZIP, parsed, assets)
    }

    private suspend fun preview(prepared: PreparedImport): ImportPreview = mutationMutex.withLock {
        val current = repository.librarySnapshot()
        val merged = TagImport.merge(current, prepared.parsed, prepared.format)
        val duplicateCount = prepared.parsed.items.size - merged.incoming.size
        ImportPreview(prepared.format, prepared.parsed.items.size, merged.incoming.size,
            duplicateCount + prepared.parsed.duplicateCount, prepared.parsed.invalidCount, current.bookmarks.size,
            prepared.parsed.appearance != null, prepared.parsed.exportedAt, prepared.parsed.warnings,
            tagCount = prepared.parsed.tags.size, newTagCount = merged.addedTags,
            tagUpdatedCount = merged.updatedBookmarks, localTagCount = current.tags.size, mergedTagCount = merged.library.tags.size)
    }

    /** Only the bounded local commit is noncancellable; disk state is all-old or all-new. */
    suspend fun commitImport(token: String, mode: ImportMode, restoreAppearance: Boolean,
                             progress: (TransferProgress) -> Unit): ImportResult {
        val prepared = imports[token] ?: error("导入预览已过期，请重新选择文件")
        require(mode != ImportMode.REPLACE || prepared.format == TransferFormat.BACKUP_ZIP || prepared.parsed.items.isNotEmpty()) {
            "HTML 中没有可导入的网站，不会替换本机收藏"
        }
        coroutineContext.ensureActive()
        return withContext(NonCancellable + Dispatchers.IO) {
            mutationMutex.withLock {
                val beforeLibrary = repository.librarySnapshot()
                val before = beforeLibrary.bookmarks
                val merged = TagImport.merge(beforeLibrary, prepared.parsed, prepared.format)
                val incoming = if (mode == ImportMode.REPLACE) prepared.parsed.items else merged.incoming
                val retained = if (mode == ImportMode.MERGE) merged.retained else emptyList()
                val tags = if (mode == ImportMode.REPLACE) prepared.parsed.tags else merged.library.tags
                val duplicates = prepared.parsed.duplicateCount + if (mode == ImportMode.MERGE)
                    prepared.parsed.items.size - incoming.size - merged.updatedBookmarks else 0
                val oldVersions = before.associate { it.id to it.imageVersion }
                val versioned = incoming.map { item ->
                    val next = maxOf(item.imageVersion, oldVersions[item.id] ?: 0).toLong() + 1
                    require(next <= Int.MAX_VALUE) { "备份图标版本过大" }
                    item.copy(imageVersion = next.toInt())
                }
                val tentative = LibrarySnapshot(versioned + retained, tags)
                require(BookmarkCodec.encode(tentative).toByteArray(Charsets.UTF_8).size <= BookmarkCodec.MAX_JSON_BYTES) { "导入后收藏数据超过 4 MiB" }
                var committed = false
                val warnings = prepared.parsed.warnings.toMutableList()
                try {
                    val needed = BackupArchive.references(versioned)
                    val restored = mutableMapOf<String, String>()
                    needed.forEachIndexed { index, path ->
                        progress(TransferProgress("正在恢复图像 ${index + 1} / ${needed.size}", index + 1, needed.size))
                        val file = prepared.assets[path] ?: error("备份图像缺失，原收藏未更改")
                        val bytes = file.inputStream().use { IconPolicy.readBounded(it, BackupArchive.assetLimit(path)) }
                        restored[path] = icons.restoreBytes(path, bytes)
                    }
                    val imported = versioned.map { item -> item.copy(autoImagePath = item.autoImagePath?.let(restored::getValue),
                        customImagePath = item.customImagePath?.let(restored::getValue),
                        customOriginalPath = item.customOriginalPath?.let(restored::getValue)) }
                    val after = imported + retained
                    val afterLibrary = LibrarySnapshot(after, tags)
                    progress(TransferProgress("正在保存收藏…"))
                    // Existing duplicates do not get re-saved or a new version during merge.
                    if (afterLibrary != beforeLibrary) repository.replaceAll(afterLibrary)
                    committed = true
                    if (restoreAppearance && prepared.parsed.appearance != null) {
                        try {
                            if (!preferences.restore(prepared.parsed.appearance)) warnings += "收藏已导入，外观设置未能保存，请手动调整"
                        } catch (_: Exception) { warnings += "收藏已导入，外观设置恢复失败，请手动调整" }
                    }
                    progress(TransferProgress("正在更新已有桌面入口…"))
                    try {
                        if (!shortcuts.reconcileAfterImport(before, after)) warnings += "收藏已导入，部分桌面入口更新待重试"
                    } catch (_: Exception) { warnings += "收藏已导入，桌面入口更新待重试" }
                    ImportResult(imported.size, duplicates, prepared.parsed.invalidCount, mode == ImportMode.REPLACE, warnings,
                        addedTags = if (mode == ImportMode.REPLACE) tags.size else merged.addedTags,
                        tagUpdatedBookmarks = if (mode == ImportMode.MERGE) merged.updatedBookmarks else 0)
                } finally {
                    // On failure only unreferenced new files are removed; committed old files remain.
                    try { icons.cleanup(repository.snapshot()) } catch (_: Exception) {
                        if (committed) warnings += "收藏已导入，临时图像将在下次启动时清理"
                    }
                }
            }
        }
    }

    suspend fun prepareExport(format: TransferFormat, token: String,
                              progress: (TransferProgress) -> Unit): ExportPreview = withContext(Dispatchers.IO) {
        val directory = session(token)
        val jobContext = coroutineContext
        mutationMutex.withLock {
            val library = repository.librarySnapshot()
            val items = library.bookmarks
            val appearance = preferences.state.value
            progress(TransferProgress("正在准备导出…"))
            val output = File(directory, "export.${format.extension}")
            var missing = 0
            if (format == TransferFormat.BOOKMARKS_HTML) output.writeBytes(BookmarkHtmlCodec.encode(items))
            else {
                val assets = linkedMapOf<String, File>()
                val paths = BackupArchive.references(items)
                var snapshotBytes = 0L
                paths.forEachIndexed { index, path ->
                    jobContext.ensureActive()
                    progress(TransferProgress("正在备份图像 ${index + 1} / ${paths.size}", index + 1, paths.size))
                    val bytes = icons.exportBytes(path)
                    if (bytes == null) missing++ else {
                        snapshotBytes += bytes.size
                        require(snapshotBytes <= BackupArchive.MAX_ARCHIVE_BYTES) { "备份内容超过 256 MiB" }
                        val file = File(directory, "snapshot/$path")
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法准备备份，请检查剩余空间" }
                        file.writeBytes(bytes)
                        assets[path] = file
                    }
                }
                val cleaned = items.map { it.copy(autoImagePath = it.autoImagePath?.takeIf(assets::containsKey),
                    customImagePath = it.customImagePath?.takeIf(assets::containsKey),
                    customOriginalPath = it.customOriginalPath?.takeIf(assets::containsKey)) }
                val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                BackupArchive.write(output, cleaned, appearance, assets, appVersion, missing, library.tags) { jobContext.ensureActive() }
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val filename = "WebShortcuts-${if (format == TransferFormat.BACKUP_ZIP) "backup" else "bookmarks"}-$timestamp.${format.extension}"
            File(directory, "ready.json").writeText(JSONObject().put("kind", "export").put("format", format.name)
                .put("filename", filename).put("itemCount", items.size).put("missingImages", missing).toString())
            ExportPreview(token, format, filename, items.size, missing)
        }
    }

    suspend fun restoreExport(token: String): ExportPreview = withContext(Dispatchers.IO) {
        val descriptor = readDescriptor(token)
        require(descriptor.getString("kind") == "export")
        val format = TransferFormat.valueOf(descriptor.getString("format"))
        require(File(session(token), "export.${format.extension}").isFile) { "导出缓存已过期，请重新导出" }
        ExportPreview(token, format, descriptor.getString("filename"), descriptor.getInt("itemCount"), descriptor.optInt("missingImages"))
    }

    /** destination must be a new document returned by CreateDocument, never a user-selected input. */
    suspend fun writeExport(token: String, destination: Uri, progress: (TransferProgress) -> Unit): ExportPreview = withContext(Dispatchers.IO) {
        val preview = restoreExport(token)
        val file = File(session(token), "export.${preview.format.extension}")
        val jobContext = coroutineContext
        try {
            progress(TransferProgress("正在写入所选位置…"))
            val output = context.contentResolver.openOutputStream(destination, "w") ?: throw IOException("无法写入所选位置")
            output.use { target -> file.inputStream().use { copyBounded(it, target, BackupArchive.MAX_ARCHIVE_BYTES) { jobContext.ensureActive() } } }
            jobContext.ensureActive()
            preview
        } catch (failure: Exception) {
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) } }
            throw failure
        }
    }

    suspend fun discard(token: String) = withContext(Dispatchers.IO) {
        imports.remove(token)
        session(token).deleteRecursively()
        Unit
    }

    suspend fun prune(activeToken: String?) = withContext(Dispatchers.IO) {
        checkRoot()
        imports.keys.filter { it != activeToken }.forEach(imports::remove)
        root.listFiles()?.filter { it.name != activeToken && runCatching { UUID.fromString(it.name) }.isSuccess }
            ?.forEach { file -> if (file.canonicalFile == File(root.canonicalFile, file.name)) file.deleteRecursively() }
    }

    private fun readDescriptor(token: String): JSONObject {
        val file = File(session(token), "ready.json")
        require(file.isFile && file.length() <= 16 * 1024) { "操作缓存已过期，请重新选择文件" }
        return JSONObject(file.readText())
    }

    private fun checkRoot() {
        require(root.canonicalFile == File(context.cacheDir.canonicalFile, "transfers")) { "缓存目录无效" }
        check(root.isDirectory || root.mkdirs()) { "无法准备文件，请检查剩余空间" }
    }

    private fun session(token: String): File {
        require(UUID.fromString(token).toString() == token) { "操作缓存无效" }
        checkRoot()
        val file = File(root, token)
        require(file.canonicalFile == File(root.canonicalFile, token)) { "缓存路径无效" }
        check(file.isDirectory || file.mkdir()) { "无法准备文件，请检查剩余空间" }
        return file
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, checkpoint: () -> Unit) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            checkpoint()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= limit) { "文件超过 256 MiB，无法处理" }
            output.write(buffer, 0, count)
        }
    }
}
