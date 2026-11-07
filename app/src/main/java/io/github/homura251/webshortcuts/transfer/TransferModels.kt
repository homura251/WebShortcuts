package io.github.homura251.webshortcuts.transfer

import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.BookmarkTag
import io.github.homura251.webshortcuts.ui.theme.AppearancePreferences
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferFormat(val mimeType: String, val extension: String) {
    BACKUP_ZIP("application/zip", "zip"), BOOKMARKS_HTML("text/html", "html"),
}

enum class ImportMode { MERGE, REPLACE }

/** A lease also invalidates fetches started before a transfer, even if an ID/version is restored. */
class TransferGate {
    private val generation = AtomicLong()
    private var lease: Long? = null
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val epoch: Long get() = generation.get()

    @Synchronized fun begin(): Long? {
        if (lease != null) return null
        return generation.incrementAndGet().also { lease = it; mutableBusy.value = true }
    }

    @Synchronized fun end(owner: Long) {
        if (lease == owner) { lease = null; mutableBusy.value = false }
    }

    fun allowsMutation(epoch: Long = this.epoch): Boolean = !busy.value && this.epoch == epoch
    fun requireWritable(epoch: Long = this.epoch) = check(allowsMutation(epoch)) { "导入导出已改变收藏状态，请重新操作" }
}

data class TransferProgress(val message: String, val completed: Int = 0, val total: Int = 0) {
    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

data class ImportPreview(
    val format: TransferFormat,
    val itemCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val skippedCount: Int,
    val localCount: Int,
    val hasAppearance: Boolean,
    val exportedAt: Long? = null,
    val warnings: List<String> = emptyList(),
    val tagCount: Int = 0,
    val newTagCount: Int = 0,
    val tagUpdatedCount: Int = 0,
    val localTagCount: Int = 0,
    val mergedTagCount: Int = 0,
) {
    val canMerge get() = localCount + newCount <= 2000 && mergedTagCount <= 2000 && (format == TransferFormat.BACKUP_ZIP || itemCount > 0 || duplicateCount > 0)
    val canReplace get() = format == TransferFormat.BACKUP_ZIP || itemCount > 0
}

data class ExportPreview(
    val token: String,
    val format: TransferFormat,
    val filename: String,
    val itemCount: Int,
    val missingImages: Int = 0,
)

data class ImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
    val skippedInvalid: Int,
    val replaced: Boolean,
    val warnings: List<String> = emptyList(),
    val addedTags: Int = 0,
    val tagUpdatedBookmarks: Int = 0,
)

internal data class ParsedImport(
    val items: List<BookmarkItem>,
    val appearance: AppearancePreferences? = null,
    val invalidCount: Int = 0,
    val duplicateCount: Int = 0,
    val exportedAt: Long? = null,
    val warnings: List<String> = emptyList(),
    val tags: List<BookmarkTag> = emptyList(),
)

internal data class PreparedImport(
    val token: String,
    val format: TransferFormat,
    val parsed: ParsedImport,
    val assets: Map<String, File>,
)

enum class TransferPhase {
    IDLE, PICK_IMPORT, READING, PREVIEW, PREPARING_EXPORT, EXPORT_READY, PICK_EXPORT,
    WRITING, COMMITTING, RESULT, ERROR,
}

data class TransferUiState(
    val visible: Boolean = false,
    val phase: TransferPhase = TransferPhase.IDLE,
    val preview: ImportPreview? = null,
    val export: ExportPreview? = null,
    val mode: ImportMode = ImportMode.MERGE,
    val restoreAppearance: Boolean = false,
    val progress: TransferProgress = TransferProgress(""),
    val message: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val working get() = phase in setOf(TransferPhase.READING, TransferPhase.PREPARING_EXPORT, TransferPhase.WRITING, TransferPhase.COMMITTING)
    val canDismiss get() = phase != TransferPhase.COMMITTING
}
