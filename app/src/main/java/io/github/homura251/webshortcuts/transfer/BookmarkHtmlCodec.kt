package io.github.homura251.webshortcuts.transfer

import io.github.homura251.webshortcuts.data.BookmarkCodec
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.UrlNormalizer
import java.net.URI
import java.nio.charset.Charset
import org.jsoup.parser.Parser
import org.jsoup.parser.StreamParser

/** Netscape bookmark HTML only. Parsing never loads a resource or executes document content. */
internal object BookmarkHtmlCodec {
    fun decode(bytes: ByteArray, now: Long = System.currentTimeMillis(), checkpoint: () -> Unit = {}): ParsedImport {
        require(bytes.size <= BookmarkCodec.MAX_JSON_BYTES) { "HTML 书签文件不能超过 4 MiB" }
        val text = decodeText(bytes).removePrefix("\uFEFF")
        require(Regex("(?i)<!DOCTYPE\\s+NETSCAPE-Bookmark-file-1").containsMatchIn(text) ||
            (Regex("(?i)<dl\\b").containsMatchIn(text) && Regex("(?i)<dt\\b").containsMatchIn(text))) {
            "请选择浏览器导出的 HTML 书签文件或 WebShortcuts ZIP 备份"
        }
        val items = mutableListOf<BookmarkItem>()
        val seen = mutableSetOf<String>()
        var invalid = 0
        var duplicates = 0
        StreamParser(Parser.htmlParser()).parse(text, "").use { parser ->
            while (true) {
                checkpoint()
                val anchor = parser.selectNext("a[href]") ?: break
                try {
                    val href = anchor.attr("href").trim()
                    require(href.startsWith("https://", true) || href.startsWith("http://", true))
                    val url = UrlNormalizer.normalize(href)
                    val title = anchor.text().trim().ifBlank { URI(url).host ?: "网站" }
                    val created = timestamp(anchor.attr("add_date")) ?: now
                    val item = BookmarkItem(title = title, url = url, selectedIconSource = IconSourceMode.AUTO,
                        createdAt = created, updatedAt = timestamp(anchor.attr("last_modified")) ?: created)
                    BookmarkCodec.validate(item)
                    if (seen.add(url)) items += item else duplicates++
                } catch (_: IllegalArgumentException) {
                    invalid++
                } finally {
                    // Drop consumed nodes rather than retaining every link in the parser's tree.
                    val parent = anchor.parent()
                    if (parent?.normalName() == "dt") parent.remove() else anchor.remove()
                }
                require(items.size <= BookmarkCodec.MAX_BOOKMARKS) { "HTML 书签超过 2000 条，请分批导入" }
            }
        }
        return ParsedImport(items, invalidCount = invalid, duplicateCount = duplicates)
    }

    fun encode(items: List<BookmarkItem>): ByteArray {
        // HTML intentionally carries no catalog or associations.
        BookmarkCodec.encode(items.map { it.copy(tagIds = emptySet()) }) // Apply the same field and collection limits as local storage.
        val html = buildString {
            append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            append("<TITLE>WebShortcuts Bookmarks</TITLE>\n<H1>WebShortcuts</H1>\n<DL><p>\n")
            items.forEach { item ->
                append("    <DT><A HREF=\"").append(escape(item.url)).append('"')
                if (item.createdAt > 0) append(" ADD_DATE=\"").append(item.createdAt / 1000).append('"')
                if (item.updatedAt > 0) append(" LAST_MODIFIED=\"").append(item.updatedAt / 1000).append('"')
                append('>').append(escape(item.title)).append("</A>\n")
            }
            append("</DL><p>\n")
        }.toByteArray(Charsets.UTF_8)
        require(html.size <= BookmarkCodec.MAX_JSON_BYTES) { "HTML 导出超过 4 MiB，请使用 ZIP 完整备份" }
        return html
    }

    private fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8
            else -> {
                val prefix = String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1)
                val name = Regex("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._-]+)").find(prefix)?.groupValues?.get(1)
                name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            }
        }
        return bytes.toString(charset)
    }

    private fun timestamp(value: String): Long? = value.toLongOrNull()
        ?.takeIf { it in 0..(Long.MAX_VALUE / 1000) }?.times(1000)

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
