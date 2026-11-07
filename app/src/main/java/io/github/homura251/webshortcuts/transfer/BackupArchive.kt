package io.github.homura251.webshortcuts.transfer

import io.github.homura251.webshortcuts.data.BookmarkCodec
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.LibrarySnapshot
import io.github.homura251.webshortcuts.data.BookmarkTag
import io.github.homura251.webshortcuts.icons.IconPolicy
import io.github.homura251.webshortcuts.icons.IconProcessor
import io.github.homura251.webshortcuts.ui.theme.AppearancePreferences
import io.github.homura251.webshortcuts.ui.theme.LayoutMode
import io.github.homura251.webshortcuts.ui.theme.ThemeMode
import io.github.homura251.webshortcuts.ui.theme.ThemePalette
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Versioned interchange envelope; the existing bookmark schema remains independent. */
internal object BackupArchive {
    const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
    const val MAX_ENTRIES = BookmarkCodec.MAX_BOOKMARKS * 3 + 7
    private const val MAX_HEADER_BYTES = 16 * 1024
    private val assetPattern = Regex("icons/(?:(?:auto|custom)/[A-Za-z0-9_-]{1,100}\\.(?:png|webp)|original/[A-Za-z0-9_-]{1,100}\\.png)")

    fun isAssetPath(path: String) = assetPattern.matches(path)
    fun assetLimit(path: String) = if (path.startsWith("icons/original/")) IconProcessor.MAX_EDITABLE_BYTES else IconPolicy.MAX_IMAGE_BYTES
    fun references(items: List<BookmarkItem>) = items.flatMap { listOfNotNull(it.autoImagePath, it.customImagePath, it.customOriginalPath) }.toSet()

    fun write(file: File, items: List<BookmarkItem>, appearance: AppearancePreferences, assets: Map<String, File>,
              appVersion: String, missingImages: Int, tags: List<BookmarkTag> = emptyList(), checkpoint: () -> Unit = {}) {
        val records = BookmarkCodec.encode(LibrarySnapshot(items, tags)).toByteArray(Charsets.UTF_8)
        require(records.size <= BookmarkCodec.MAX_JSON_BYTES)
        require(references(items) == assets.keys) { "备份图标引用不完整" }
        val manifest = JSONObject().put("format", "WebShortcutsBackup").put("formatVersion", 1)
            .put("appVersion", appVersion).put("exportedAt", System.currentTimeMillis()).put("missingImages", missingImages)
        var total = 0L
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun bytes(name: String, data: ByteArray) {
                checkpoint(); total += data.size; require(total <= MAX_ARCHIVE_BYTES) { "备份内容超过 256 MiB" }
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            bytes("manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
            bytes("bookmarks.json", records)
            bytes("appearance.json", encodeAppearance(appearance).toByteArray(Charsets.UTF_8))
            assets.forEach { (path, source) ->
                require(isAssetPath(path))
                source.inputStream().use { bytes(path, IconPolicy.readBounded(it, assetLimit(path))) }
            }
        }
        require(file.length() <= MAX_ARCHIVE_BYTES) { "ZIP 备份超过 256 MiB" }
    }

    fun read(file: File, staging: File, checkpoint: () -> Unit = {}): Pair<ParsedImport, Map<String, File>> {
        require(file.length() <= MAX_ARCHIVE_BYTES) { "ZIP 备份超过 256 MiB" }
        val assets = linkedMapOf<String, File>()
        val documents = mutableMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipFile(file).use { zip ->
            require(zip.size() <= MAX_ENTRIES) { "备份文件数量超过上限" }
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkpoint()
                val entry = entries.nextElement()
                val name = entry.name
                require(seen.add(name)) { "备份包含重复文件" }
                if (entry.isDirectory) {
                    require(name in setOf("icons/", "icons/auto/", "icons/custom/", "icons/original/")) { "备份目录无效" }
                    require(entry.size == 0L) { "备份目录包含异常内容" }
                    continue
                }
                require(name in setOf("manifest.json", "bookmarks.json", "appearance.json") || isAssetPath(name)) { "备份包含不支持的路径或文件" }
                val limit = when (name) {
                    "bookmarks.json" -> BookmarkCodec.MAX_JSON_BYTES
                    "manifest.json", "appearance.json" -> MAX_HEADER_BYTES
                    else -> assetLimit(name)
                }
                val bytes = zip.getInputStream(entry).use { IconPolicy.readBounded(it, limit) }
                total += bytes.size
                require(total <= MAX_ARCHIVE_BYTES) { "备份解压内容超过 256 MiB" }
                require(entry.size == bytes.size.toLong() && entry.crc == CRC32().apply { update(bytes) }.value) { "备份文件校验失败" }
                if (isAssetPath(name)) {
                    val destination = File(staging, name)
                    require(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "无法准备图标缓存，请检查剩余空间" }
                    destination.writeBytes(bytes)
                    assets[name] = destination
                } else documents[name] = bytes
            }
        }
        val manifest = JSONObject(documents["manifest.json"]?.toString(Charsets.UTF_8) ?: error("备份缺少清单"))
        require(manifest.getString("format") == "WebShortcutsBackup") { "不是 WebShortcuts 备份" }
        require(manifest.getInt("formatVersion") == 1) { "不支持此备份版本，请更新应用" }
        val json = documents["bookmarks.json"]?.toString(Charsets.UTF_8) ?: error("备份缺少收藏数据")
        validateRawRecords(json)
        val library = BookmarkCodec.decodeLibrary(json)
        val items = library.bookmarks
        require(references(items) == assets.keys) { "备份图标缺失或包含未引用图片" }
        val warnings = mutableListOf<String>()
        var appearance: AppearancePreferences? = null
        documents["appearance.json"]?.let { bytes ->
            try { appearance = decodeAppearance(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { warnings += "备份外观设置无效，将保留当前外观" }
        }
        val missing = manifest.optInt("missingImages", 0).coerceIn(0, 6000)
        if (missing > 0) warnings += "备份导出时有 $missing 份图像不可读取，相关收藏使用可用图片或字母回退"
        return ParsedImport(items, appearance, exportedAt = manifest.optLong("exportedAt").takeIf { it > 0 }, warnings = warnings, tags = library.tags) to assets
    }

    private fun validateRawRecords(json: String) {
        val records = JSONObject(json).getJSONArray("bookmarks")
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            listOf("autoImagePath", "customImagePath", "customOriginalPath").forEach { key ->
                if (record.has(key) && !record.isNull(key)) require(isAssetPath(record.getString(key))) { "备份图标路径无效" }
            }
            if (record.has("selectedIconSource")) require(IconSourceMode.entries.any { it.name == record.getString("selectedIconSource") }) { "备份图标模式无效" }
        }
    }

    private fun encodeAppearance(value: AppearancePreferences): String = JSONObject()
        .put("themeMode", value.themeMode.name).put("dynamicColor", value.dynamicColor)
        .put("palette", value.palette.name).put("layoutMode", value.layoutMode.name).toString()

    private fun decodeAppearance(json: String): AppearancePreferences = JSONObject(json).let {
        AppearancePreferences(ThemeMode.valueOf(it.getString("themeMode")), it.getBoolean("dynamicColor"),
            ThemePalette.valueOf(it.getString("palette")), LayoutMode.valueOf(it.getString("layoutMode")))
    }
}
