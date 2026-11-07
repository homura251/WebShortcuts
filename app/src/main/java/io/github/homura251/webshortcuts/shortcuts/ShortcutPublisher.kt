package io.github.homura251.webshortcuts.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.icons.IconResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Launcher writes keep the original UUID, enabling pinned icons to update in place. */
class ShortcutPublisher(
    private val context: Context,
    private val iconResolver: IconResolver,
) {
    fun isPinned(id: String): Boolean = try {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == id }
    } catch (_: Exception) {
        false
    }

    /** True means the request was accepted; the launcher still requires the user's confirmation. */
    suspend fun pin(bookmark: BookmarkItem): Boolean = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return@withContext false
        try {
            val shortcut = build(bookmark)
            // Restoring a previously deleted ID must also clear its disabled state.
            ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True means the platform accepted the update, not that every OEM launcher has redrawn it.
     * A bookmark with no pinned shortcut has nothing to update and returns false.
     */
    suspend fun update(bookmark: BookmarkItem): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isPinned(bookmark.id) || ShortcutManagerCompat.isRateLimitingActive(context)) {
                return@withContext false
            }
            ShortcutManagerCompat.updateShortcuts(context, listOf(build(bookmark)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** Reconcile existing entries only; importing on a new device never pins icons automatically. */
    suspend fun reconcileAfterImport(before: List<BookmarkItem>, after: List<BookmarkItem>): Boolean = withContext(Dispatchers.IO) {
        try {
            val retained = after.map { it.id }.toSet()
            val removed = before.map { it.id }.filterNot(retained::contains)
            if (removed.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, removed, "此收藏已被替换，请在 WebShortcuts 中重新添加")
                ShortcutManagerCompat.removeDynamicShortcuts(context, removed)
            }
            val previous = before.associateBy { it.id }
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }.toSet()
            for (item in after.filter { it.id in pinned && previous[it.id]?.let { old ->
                old.copy(tagIds = it.tagIds, updatedAt = it.updatedAt) != it
            } != false }) {
                if (ShortcutManagerCompat.isRateLimitingActive(context)) return@withContext false
                val shortcut = build(item)
                ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
                if (!ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))) return@withContext false
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    /** Existing desktop entries become unavailable instead of opening a deleted/stale URL. */
    fun disable(id: String) {
        try {
            ShortcutManagerCompat.disableShortcuts(context, listOf(id), "此收藏已删除，请在 WebShortcuts 中重新添加")
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(id))
        } catch (_: IllegalStateException) {
            // A launcher can reject changes while the user profile is locked.
        } catch (_: SecurityException) {
            // User data deletion remains valid even when device policy blocks a launcher change.
        }
    }

    private suspend fun build(bookmark: BookmarkItem): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, bookmark.id)
            .setShortLabel(shortLabel(bookmark.title))
            .setLongLabel(bookmark.title)
            .setIcon(IconCompat.createWithAdaptiveBitmap(iconResolver.resolve(bookmark)))
            .setIntent(ShortcutIntents.forBookmark(context, bookmark.id))
            .build()

    private fun shortLabel(title: String): String {
        val count = minOf(40, title.codePointCount(0, title.length))
        return title.substring(0, title.offsetByCodePoints(0, count))
    }
}
