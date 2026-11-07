package io.github.homura251.webshortcuts.data

import java.util.UUID
import io.github.homura251.webshortcuts.icons.CropRect

/** Explicit icon selection. AUTO retains its mode even when rendering falls back to a monogram. */
enum class IconSourceMode { AUTO, CUSTOM, GENERATED }

/** The ID is stable for the bookmark's lifetime and is also its launcher shortcut ID. */
data class BookmarkItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val selectedIconSource: IconSourceMode = IconSourceMode.AUTO,
    val autoImagePath: String? = null,
    val customImagePath: String? = null,
    val sourceUrl: String? = null,
    val imageVersion: Int = 1,
    val lastFetchedAt: Long? = null,
    val iconDescription: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val customOriginalPath: String? = null,
    val customCrop: CropRect? = null,
    val tagIds: Set<String> = emptySet(),
)
