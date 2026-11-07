package io.github.homura251.webshortcuts

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.homura251.webshortcuts.data.BookmarkItem
import io.github.homura251.webshortcuts.data.IconSourceMode
import io.github.homura251.webshortcuts.data.UrlNormalizer
import io.github.homura251.webshortcuts.data.LibraryTags
import io.github.homura251.webshortcuts.data.TagChanges
import io.github.homura251.webshortcuts.data.TagFilter
import io.github.homura251.webshortcuts.ui.TagEditorUiState
import io.github.homura251.webshortcuts.ui.TagEditMode
import io.github.homura251.webshortcuts.icons.IconFetchResult
import io.github.homura251.webshortcuts.icons.CropRect
import io.github.homura251.webshortcuts.ui.EditorUiState
import io.github.homura251.webshortcuts.ui.HomeUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Owns editor lifetime and guards late network results against URL/mode/editor changes. */
class MainViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val container = (application as WebShortcutsApplication).container
    private val repository = container.repository
    private val drafts = EditorDraftStore(application)
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var original: BookmarkItem? = null
    private var autoImage: IconFetchResult? = null
    private var autoImageUrl: String? = null
    private var autoImageFetchedAt: Long? = null
    private var customImage: Bitmap? = null
    private var customOriginal: Bitmap? = null
    private var customOriginalDirty = false
    private var customDraftName: String? = null
    private var originalDraftName: String? = null
    private var lastAttemptedUrl: String? = null
    private var fetchJob: Job? = null
    private var previewJob: Job? = null
    private var importJob: Job? = null
    private var sourceJob: Job? = null
    private var editorEpoch = 0
    private var fetchEpoch = 0
    private var importEpoch = 0
    private var previewEpoch = 0
    private val refreshingIds = mutableSetOf<String>()
    private var libraryUiRestored = false

    init {
        viewModelScope.launch {
            container.transferGate.busy.collect { busy -> mutableState.update { it.copy(transferBusy = busy) } }
        }
        viewModelScope.launch {
            repository.library.collect { library ->
                mutableState.update { home ->
                    val known = library.tags.map { it.id }.toSet()
                    home.copy(bookmarks = library.bookmarks, tags = library.tags,
                        tagFilter = if (libraryUiRestored) home.tagFilter.prune(library.tags) else home.tagFilter,
                        selectedIds = home.selectedIds.intersect(library.bookmarks.map { it.id }.toSet()),
                        editor = home.editor?.let { it.copy(tagIds = it.tagIds.intersect(known)) },
                        tagEditor = home.tagEditor?.let { sheet -> sheet.copy(selectedIds = sheet.selectedIds.intersect(known)) })
                }
                if (libraryUiRestored) persistLibraryUi()
            }
        }
        viewModelScope.launch {
            repository.loadError.collect { error -> mutableState.update { it.copy(error = error) } }
        }
        viewModelScope.launch {
            try {
                repository.load()
                if (repository.loadError.value == null) {
                    restoreLibraryUi()
                    restoreEditor()
                    restoreTagEditor()
                    container.mutationMutex.withLock { cleanUnusedIcons() }
                }
                drafts.prune(listOfNotNull(customDraftName, originalDraftName).toSet())
                mutableState.update { it.copy(loading = false) }
            } catch (error: Exception) {
                rethrowCancellation(error)
                mutableState.update { it.copy(loading = false, error = "无法读取本地收藏，原文件已保留。请重新打开应用重试。") }
            }
        }
    }

    fun setQuery(value: String) {
        if (mutableState.value.selectionMode) return
        savedState["home.query"] = value
        mutableState.update { it.copy(query = value) }
    }

    fun setTagFilter(filter: TagFilter) {
        if (mutableState.value.selectionMode) return
        val next = filter.prune(repository.library.value.tags)
        savedState["home.tagIds"] = ArrayList(next.ids)
        savedState["home.untagged"] = next.untagged
        mutableState.update { it.copy(tagFilter = next) }
    }

    fun toggleTagFilter(id: String) = setTagFilter(mutableState.value.tagFilter.toggle(id))

    fun select(item: BookmarkItem) {
        if (transferBlocked() || mutableState.value.tagSaving) return
        val home = mutableState.value
        if (home.loading || home.error != null) return
        val selected = if (item.id in home.selectedIds) home.selectedIds - item.id else home.selectedIds + item.id
        savedState["home.selecting"] = true
        savedState["home.selected"] = ArrayList(selected)
        mutableState.update { it.copy(selectionMode = true, selectedIds = selected) }
    }

    fun selectAll() {
        val home = mutableState.value
        if (!home.selectionMode || home.tagSaving) return
        val ids = LibraryTags.filter(home.bookmarks, home.tags, home.query, home.tagFilter).map { it.id }.toSet()
        savedState["home.selected"] = ArrayList(ids)
        mutableState.update { it.copy(selectedIds = ids) }
    }

    fun cancelSelection() {
        if (mutableState.value.tagSaving) return
        savedState["home.selecting"] = false
        savedState["home.selected"] = arrayListOf<String>()
        mutableState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun editTags(item: BookmarkItem) {
        if (transferBlocked()) return
        val latest = repository.library.value.bookmarks.firstOrNull { it.id == item.id } ?: return
        publishTagEditor(TagEditorUiState(setOf(item.id), initialIds = latest.tagIds, selectedIds = latest.tagIds))
    }

    fun editEditorTags() {
        val editor = mutableState.value.editor ?: return
        if (editor.saving || transferBlocked()) return
        publishTagEditor(TagEditorUiState(setOf(editor.id), initialIds = editor.tagIds,
            selectedIds = editor.tagIds, newNames = editor.newTagNames, forEditor = true))
    }

    fun editBatchTags(remove: Boolean) {
        val home = mutableState.value
        if (home.selectedIds.isEmpty() || home.tagSaving || transferBlocked()) return
        publishTagEditor(TagEditorUiState(home.selectedIds, if (remove) TagEditMode.REMOVE else TagEditMode.ADD))
    }

    fun toggleEditorTag(id: String) {
        val sheet = mutableState.value.tagEditor ?: return
        if (sheet.saving) return
        publishTagEditor(sheet.copy(selectedIds = if (id in sheet.selectedIds) sheet.selectedIds - id else sheet.selectedIds + id, error = null))
    }

    fun toggleNewTag(name: String) {
        val sheet = mutableState.value.tagEditor ?: return
        if (sheet.saving) return
        publishTagEditor(sheet.copy(newNames = sheet.newNames.filterNot { it == name }))
    }

    fun addDraftTag(raw: String) {
        val sheet = mutableState.value.tagEditor ?: return
        if (sheet.saving || sheet.mode == TagEditMode.REMOVE) return
        try {
            val name = LibraryTags.name(raw)
            val existing = repository.library.value.tags.firstOrNull { LibraryTags.key(it.name) == LibraryTags.key(name) }
            val pending = (sheet.newNames + name).distinctBy(LibraryTags::key)
            val unresolved = pending.count { value -> repository.library.value.tags.none { LibraryTags.key(it.name) == LibraryTags.key(value) } }
            if (existing == null) require(repository.library.value.tags.size + unresolved <= LibraryTags.MAX_TAGS) { "标签数量已达到上限（2000）" }
            val next = if (existing != null) sheet.copy(selectedIds = sheet.selectedIds + existing.id, error = null)
                else sheet.copy(newNames = pending, error = null)
            publishTagEditor(next)
        } catch (error: IllegalArgumentException) { publishTagEditor(sheet.copy(error = error.message)) }
    }

    fun dismissTagEditor() {
        if (mutableState.value.tagEditor?.saving == true) return
        publishTagEditor(null)
    }

    fun applyTagEditor() {
        val epoch = container.transferGate.epoch
        val sheet = mutableState.value.tagEditor ?: return
        if (sheet.saving || transferBlocked()) return
        if (sheet.forEditor) {
            changeEditor { it.copy(tagIds = sheet.selectedIds, newTagNames = sheet.newNames, dirty = true) }
            publishTagEditor(null)
            return
        }
        val changes = when (sheet.mode) {
            TagEditMode.SET -> TagChanges(sheet.selectedIds - sheet.initialIds, sheet.initialIds - sheet.selectedIds, sheet.newNames)
            TagEditMode.ADD -> TagChanges(addIds = sheet.selectedIds, newNames = sheet.newNames)
            TagEditMode.REMOVE -> TagChanges(removeIds = sheet.selectedIds)
        }
        publishTagEditor(sheet.copy(saving = true, error = null))
        mutableState.update { it.copy(tagSaving = true) }
        viewModelScope.launch {
            try {
                val count = container.mutationMutex.withLock {
                    container.transferGate.requireWritable(epoch)
                    repository.applyTags(sheet.targetIds, changes)
                }
                publishTagEditor(null)
                mutableState.update { it.copy(tagSaving = false) }
                cancelSelection()
                notify("已更新 $count 条收藏的标签")
            } catch (error: Exception) {
                rethrowCancellation(error)
                publishTagEditor(sheet.copy(error = "标签未保存。${error.message.orEmpty().take(120)}"))
                mutableState.update { it.copy(tagSaving = false) }
            }
        }
    }

    fun createTag(name: String) = mutateCatalog { repository.createTag(name); Unit }
    fun renameTag(id: String, name: String) = mutateCatalog { repository.renameTag(id, name) }
    fun deleteTag(id: String) = mutateCatalog { repository.deleteTag(id) }

    private fun mutateCatalog(block: suspend () -> Unit) {
        val epoch = container.transferGate.epoch
        if (transferBlocked() || mutableState.value.tagSaving) return
        mutableState.update { it.copy(tagSaving = true, tagError = null) }
        viewModelScope.launch {
            try {
                container.mutationMutex.withLock { container.transferGate.requireWritable(epoch); block() }
                notify("标签已保存")
            } catch (error: Exception) { rethrowCancellation(error); mutableState.update { it.copy(tagError = error.message ?: "标签保存失败，请重试") } }
            finally { mutableState.update { it.copy(tagSaving = false) } }
        }
    }

    private fun persistLibraryUi() {
        val home = mutableState.value
        savedState["home.query"] = home.query
        savedState["home.tagIds"] = ArrayList(home.tagFilter.ids)
        savedState["home.untagged"] = home.tagFilter.untagged
        savedState["home.selecting"] = home.selectionMode
        savedState["home.selected"] = ArrayList(home.selectedIds)
    }

    private fun restoreLibraryUi() {
        val library = repository.library.value
        val filter = TagFilter(savedState.get<ArrayList<String>>("home.tagIds").orEmpty().toSet(),
            savedState["home.untagged"] ?: false).prune(library.tags)
        val selected = savedState.get<ArrayList<String>>("home.selected").orEmpty().intersect(library.bookmarks.map { it.id }.toSet())
        mutableState.update { it.copy(bookmarks = library.bookmarks, tags = library.tags,
            query = savedState["home.query"] ?: "", tagFilter = filter,
            selectionMode = savedState["home.selecting"] ?: false, selectedIds = selected) }
        libraryUiRestored = true
        persistLibraryUi()
    }

    private fun publishTagEditor(sheet: TagEditorUiState?) {
        TAG_SHEET_KEYS.forEach { savedState.remove<Any?>(it) }
        if (sheet != null) {
            savedState["tags.targets"] = ArrayList(sheet.targetIds)
            savedState["tags.mode"] = sheet.mode.name
            savedState["tags.initial"] = ArrayList(sheet.initialIds)
            savedState["tags.selected"] = ArrayList(sheet.selectedIds)
            savedState["tags.names"] = ArrayList(sheet.newNames)
            savedState["tags.editor"] = sheet.forEditor
        }
        mutableState.update { it.copy(tagEditor = sheet) }
    }

    private fun restoreTagEditor() {
        val targets = savedState.get<ArrayList<String>>("tags.targets")?.toSet() ?: return
        val forEditor = savedState.get<Boolean>("tags.editor") ?: false
        val validTargets = if (forEditor) targets.intersect(setOfNotNull(mutableState.value.editor?.id))
            else targets.intersect(repository.library.value.bookmarks.map { it.id }.toSet())
        if (validTargets.isEmpty()) { publishTagEditor(null); return }
        val known = repository.library.value.tags.map { it.id }.toSet()
        publishTagEditor(TagEditorUiState(validTargets,
            mode = runCatching { TagEditMode.valueOf(savedState["tags.mode"] ?: "SET") }.getOrDefault(TagEditMode.SET),
            initialIds = savedState.get<ArrayList<String>>("tags.initial").orEmpty().intersect(known),
            selectedIds = savedState.get<ArrayList<String>>("tags.selected").orEmpty().intersect(known),
            newNames = savedState.get<ArrayList<String>>("tags.names").orEmpty(), forEditor = forEditor))
    }

    fun add(url: String = "", title: String = "") {
        if (transferBlocked()) return
        if (mutableState.value.loading || mutableState.value.selectionMode) return
        if (mutableState.value.error != null) {
            notify("本地数据尚未成功读取，请重新打开应用后重试")
            return
        }
        if (mutableState.value.editor?.dirty == true) {
            notify("请先保存或关闭正在编辑的收藏")
            return
        }
        beginEditor(null, EditorUiState(id = UUID.randomUUID().toString(), isNew = true, title = title, url = url, dirty = url.isNotBlank(), tagIds = mutableState.value.tagFilter.ids))
        if (url.isNotBlank()) onUrlFocusLost()
    }

    fun edit(item: BookmarkItem) {
        if (transferBlocked()) return
        beginEditor(item, EditorUiState(
            id = item.id, isNew = false, title = item.title, url = item.url,
            mode = item.selectedIconSource, sourceLabel = item.iconDescription,
            hasCustomImage = item.customImagePath != null, cropRect = item.customCrop,
            tagIds = item.tagIds, initialTagIds = item.tagIds,
        ))
    }

    private fun beginEditor(item: BookmarkItem?, editor: EditorUiState, loadCropSource: Boolean = true) {
        cancelEditorJobs()
        original = item
        autoImage = null
        autoImageUrl = null
        autoImageFetchedAt = null
        customImage = null
        customOriginal = null
        customOriginalDirty = false
        customDraftName = null
        originalDraftName = null
        lastAttemptedUrl = null
        persistEditor(editor)
        mutableState.update { it.copy(editor = editor) }
        updatePreview()
        if (loadCropSource && item?.customOriginalPath != null) {
            val epoch = editorEpoch
            sourceJob = viewModelScope.launch {
                val source = container.iconStore.readOriginal(item.customOriginalPath)
                if (epoch != editorEpoch) return@launch
                customOriginal = source
                changeEditor { it.copy(cropSource = source, canCrop = source != null) }
            }
        }
    }

    fun dismissEditor() {
        if (mutableState.value.editor?.saving == true) return
        closeEditor()
    }

    private fun closeEditor() {
        val discarded = customDraftName
        val discardedOriginal = originalDraftName
        cancelEditorJobs()
        original = null
        autoImage = null
        customImage = null
        customOriginal = null
        customOriginalDirty = false
        customDraftName = null
        originalDraftName = null
        DRAFT_KEYS.forEach { savedState.remove<Any?>(it) }
        if (mutableState.value.tagEditor?.forEditor == true) publishTagEditor(null)
        // A closed editor must already have no restorable draft when subscribers observe it.
        mutableState.update { it.copy(editor = null) }
        viewModelScope.launch { drafts.delete(discarded); drafts.delete(discardedOriginal) }
    }

    fun setTitle(value: String) {
        if (mutableState.value.editor?.saving == true) return
        changeEditor { it.copy(title = value, titleError = null, dirty = true) }
        updatePreview()
    }

    fun setUrl(value: String) {
        if (mutableState.value.editor?.saving == true) return
        fetchJob?.cancel()
        fetchEpoch++
        lastAttemptedUrl = null
        changeEditor { it.copy(url = value, urlError = null, loading = importJob?.isActive == true, error = null, statusMessage = null, dirty = true) }
        updatePreview()
    }

    fun setMode(value: IconSourceMode) {
        if (mutableState.value.editor?.saving == true || importJob?.isActive == true) return
        fetchJob?.cancel()
        fetchEpoch++
        changeEditor { it.copy(mode = value, loading = false, error = null, dirty = true) }
        updatePreview()
        if (value == IconSourceMode.AUTO && !hasAutoImageForCurrentUrl()) fetchIcon(force = true)
    }

    fun onUrlFocusLost() {
        val editor = mutableState.value.editor ?: return
        if (editor.saving || editor.url.isBlank()) return
        val normalized = normalizeOrShowError(editor.url) ?: return
        changeEditor { current -> current.copy(
            url = normalized,
            title = current.title.ifBlank { Uri.parse(normalized).host.orEmpty().removePrefix("www.") },
            urlError = null,
        ) }
        updatePreview()
        if (editor.mode == IconSourceMode.AUTO && !hasAutoImageForCurrentUrl()) fetchIcon(force = false)
    }

    fun refetchIcon() = fetchIcon(force = true)

    private fun fetchIcon(force: Boolean) {
        val editor = mutableState.value.editor ?: return
        if (editor.mode != IconSourceMode.AUTO || editor.saving || importJob?.isActive == true) return
        val target = normalizeOrShowError(editor.url) ?: return
        if (fetchJob?.isActive == true && lastAttemptedUrl == target) return
        if (!force && lastAttemptedUrl == target) return
        fetchJob?.cancel()
        lastAttemptedUrl = target
        val epoch = editorEpoch
        val requestEpoch = ++fetchEpoch
        changeEditor { it.copy(loading = true, error = null, statusMessage = null, urlError = null) }
        fetchJob = viewModelScope.launch {
            try {
                val result = container.siteIconDiscoverer.fetch(target)
                if (!isCurrentRequest(epoch, requestEpoch, target)) return@launch
                if (result != null) {
                    autoImage = result
                    autoImageUrl = target
                    autoImageFetchedAt = System.currentTimeMillis()
                    changeEditor { it.copy(loading = false, sourceLabel = result.description, error = null,
                        statusMessage = "已获取新图标，保存后应用并请求桌面同步", dirty = true) }
                } else {
                    val cached = hasAutoImageForCurrentUrl()
                    changeEditor { it.copy(loading = false, error = if (cached) "暂未获取到新图标，已保留缓存图标" else "未能获取高清图标，已使用默认字母图标") }
                }
                updatePreview()
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (isCurrentRequest(epoch, requestEpoch, target)) {
                    changeEditor { it.copy(loading = false, error = "未能获取高清图标，已保留可用图标或使用字母图标") }
                    updatePreview()
                }
            }
        }
    }

    fun importImage(uri: Uri) {
        val editor = mutableState.value.editor ?: return
        if (editor.saving) return
        fetchJob?.cancel()
        fetchEpoch++
        importJob?.cancel()
        sourceJob?.cancel()
        val epoch = editorEpoch
        val requestEpoch = ++importEpoch
        changeEditor { it.copy(loading = true, error = null) }
        importJob = viewModelScope.launch {
            try {
                val imported = container.iconImporter.importEditable(uri)
                val bitmap = imported.fitted
                if (epoch != editorEpoch || requestEpoch != importEpoch) return@launch
                val name = drafts.write(editor.id, bitmap)
                val sourceName = try { drafts.write(editor.id, imported.original) }
                    catch (error: Exception) { drafts.delete(name); throw error }
                if (epoch != editorEpoch || requestEpoch != importEpoch) {
                    drafts.delete(name); drafts.delete(sourceName); return@launch
                }
                val previousName = customDraftName
                val previousOriginal = originalDraftName
                customImage = bitmap
                customOriginal = imported.original
                customOriginalDirty = true
                customDraftName = name
                originalDraftName = sourceName
                importJob = null // The image is ready before the UI enables Save or another edit.
                changeEditor { it.copy(mode = IconSourceMode.CUSTOM, loading = false, error = null, statusMessage = null,
                    hasCustomImage = true, dirty = true, canCrop = true, centerCrop = false,
                    cropSource = imported.original, cropRect = null) }
                updatePreview()
                drafts.delete(previousName)
                drafts.delete(previousOriginal)
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (epoch == editorEpoch && requestEpoch == importEpoch) {
                    importJob = null
                    changeEditor { it.copy(loading = false, error = "无法导入这张图片。请选择不超过 5 MB、边长不超过 4096 像素的 PNG、JPEG 或 WebP 图片。") }
                }
            }
        }
    }

    fun setCenterCrop(crop: Boolean) {
        val source = customOriginal ?: return
        val side = minOf(source.width, source.height).toFloat()
        val rect = if (crop) CropRect((source.width - side) / (2f * source.width),
            (source.height - side) / (2f * source.height),
            (source.width + side) / (2f * source.width), (source.height + side) / (2f * source.height)) else null
        applyCustomCrop(rect, centerCrop = crop)
    }

    fun applyCustomCrop(crop: CropRect?) = applyCustomCrop(crop, centerCrop = false)

    private fun applyCustomCrop(crop: CropRect?, centerCrop: Boolean) {
        val editor = mutableState.value.editor ?: return
        val source = customOriginal ?: return
        if (editor.saving || importJob?.isActive == true || editor.mode != IconSourceMode.CUSTOM) return
        val epoch = editorEpoch
        val requestEpoch = ++importEpoch
        changeEditor { it.copy(loading = true, error = null, statusMessage = null) }
        importJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) { container.iconProcessor.crop(source, crop) }
                val name = drafts.write(editor.id, bitmap)
                if (epoch != editorEpoch || requestEpoch != importEpoch) { drafts.delete(name); return@launch }
                val previousName = customDraftName
                customImage = bitmap
                customDraftName = name
                importJob = null
                changeEditor { it.copy(centerCrop = centerCrop, cropRect = crop, loading = false, dirty = true) }
                updatePreview()
                drafts.delete(previousName)
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (epoch == editorEpoch && requestEpoch == importEpoch) {
                    importJob = null
                    changeEditor { it.copy(loading = false, error = "无法保存裁切预览，请重试") }
                }
            }
        }
    }

    fun save() {
        val collectionEpoch = container.transferGate.epoch
        if (transferBlocked()) return
        val editor = mutableState.value.editor ?: return
        if (editor.saving) return
        if (importJob?.isActive == true) { notify("请等待图片处理完成后保存"); return }
        if (customOriginalDirty && customOriginal == null) {
            changeEditor { it.copy(error = "原图草稿已过期，请重新导入图片后保存，或放弃本次编辑") }
            return
        }
        val url = normalizeOrShowError(editor.url) ?: return
        val title = editor.title.trim()
        if (title.isEmpty() || title.length > 160 || title.any { it.isISOControl() }) {
            changeEditor { it.copy(titleError = "请输入 1–160 个字符的网站名称，不包含换行") }
            return
        }
        if (editor.mode == IconSourceMode.CUSTOM && customImage == null && original?.customImagePath == null) {
            changeEditor { it.copy(error = "请先选择一张自定义图片，或切换图标模式") }
            return
        }
        val continueAutomaticFetch = editor.mode == IconSourceMode.AUTO &&
            (fetchJob?.isActive == true || lastAttemptedUrl != url)
        fetchJob?.cancel()
        fetchEpoch++
        changeEditor { it.copy(saving = true, loading = false, error = null) }
        val pendingAuto = autoImage.takeIf { autoImageUrl == url }
        val pendingCustom = customImage
        val pendingOriginal = customOriginal.takeIf { customOriginalDirty }
        val changes = TagChanges(editor.tagIds - editor.initialTagIds, editor.initialTagIds - editor.tagIds, editor.newTagNames)
        val tagsOnly = !editor.isNew && original?.let {
            it.title == title && it.url == url && it.selectedIconSource == editor.mode
        } == true && pendingAuto == null && pendingCustom == null && pendingOriginal == null
        viewModelScope.launch {
            var saved = false
            var wasPinned = false
            var synced = false
            var backgroundFetch: BookmarkItem? = null
            try {
                container.mutationMutex.withLock {
                    container.transferGate.requireWritable(collectionEpoch)
                    val latest = repository.find(editor.id)
                    check(editor.isNew || latest != null) { "此收藏已被删除" }
                    val previous = latest ?: original
                    if (tagsOnly) {
                        repository.applyTags(setOf(editor.id), changes)
                        saved = true
                        synced = true
                        return@withLock
                    }
                    val version = (previous?.imageVersion ?: 0) + 1
                    val sameUrl = previous?.url == url
                    var autoPath = previous?.autoImagePath.takeIf { sameUrl }
                    var customPath = previous?.customImagePath
                    var customOriginalPath = previous?.customOriginalPath
                    if (pendingAuto != null) autoPath = container.iconStore.save(editor.id, version, IconSourceMode.AUTO, pendingAuto.bitmap)
                    if (pendingCustom != null) customPath = container.iconStore.save(editor.id, version, IconSourceMode.CUSTOM, pendingCustom)
                    if (pendingOriginal != null) customOriginalPath = container.iconStore.saveOriginal(editor.id, version, pendingOriginal)
                    val now = System.currentTimeMillis()
                    val item = BookmarkItem(
                        id = editor.id, title = title, url = url, selectedIconSource = editor.mode,
                        autoImagePath = autoPath, customImagePath = customPath,
                        customOriginalPath = customOriginalPath,
                        customCrop = if (pendingCustom != null) editor.cropRect else previous?.customCrop,
                        sourceUrl = pendingAuto?.sourceUrl ?: previous?.sourceUrl.takeIf { sameUrl },
                        imageVersion = version,
                        lastFetchedAt = if (pendingAuto != null) autoImageFetchedAt ?: now else previous?.lastFetchedAt.takeIf { sameUrl },
                        iconDescription = pendingAuto?.description ?: previous?.iconDescription.takeIf { sameUrl },
                        createdAt = previous?.createdAt ?: now, updatedAt = now,
                        tagIds = previous?.tagIds.orEmpty(),
                    )
                    repository.save(item, changes)
                    saved = true
                    if (continueAutomaticFetch && item.autoImagePath == null) backgroundFetch = item
                    wasPinned = container.shortcutPublisher.isPinned(item.id)
                    synced = if (wasPinned) try { container.shortcutPublisher.update(item) } catch (error: Exception) { rethrowCancellation(error); false } else true
                    cleanUnusedIcons()
                }
            } catch (error: Exception) {
                rethrowCancellation(error)
                if (!saved) {
                    changeEditor { it.copy(saving = false, error = "保存失败，原有收藏未被覆盖。${error.message.orEmpty().take(120)}") }
                    return@launch
                }
            }
            closeEditor()
            notify(when {
                tagsOnly -> "标签已保存"
                backgroundFetch != null -> "已保存，正在继续获取网站图标"
                !synced -> "已保存；桌面更新暂未完成，可在收藏菜单中重试"
                wasPinned -> "已保存并请求更新桌面快捷方式"
                else -> "已保存，可在收藏菜单中添加到桌面"
            })
            // Save is immediate. A late AUTO image still goes through refreshOne's mode/URL/version
            // checks, so it cannot replace a custom image selected after the editor closes.
            backgroundFetch?.let(::refresh)
        }
    }

    fun delete(item: BookmarkItem) {
        val collectionEpoch = container.transferGate.epoch
        if (transferBlocked()) return
        viewModelScope.launch {
            try {
                container.mutationMutex.withLock {
                    container.transferGate.requireWritable(collectionEpoch)
                    repository.delete(item.id)
                    try { container.shortcutPublisher.disable(item.id) } catch (error: Exception) { rethrowCancellation(error) }
                    cleanUnusedIcons()
                }
                notify("已删除收藏；对应的旧桌面快捷方式将不可用")
            } catch (error: Exception) { rethrowCancellation(error); notify("删除失败，请重试") }
        }
    }

    fun pin(item: BookmarkItem) {
        val collectionEpoch = container.transferGate.epoch
        if (transferBlocked()) return
        viewModelScope.launch {
            try {
                val accepted = container.mutationMutex.withLock {
                    container.transferGate.requireWritable(collectionEpoch)
                    repository.find(item.id)?.let { container.shortcutPublisher.pin(it) } ?: false
                }
                notify(if (accepted) "已向桌面发送添加请求，请在系统提示中确认" else "当前桌面不支持添加，或系统暂时无法处理请求")
            } catch (error: Exception) { rethrowCancellation(error); notify("暂时无法添加到桌面，请稍后重试") }
        }
    }

    fun updateShortcut(item: BookmarkItem) {
        val collectionEpoch = container.transferGate.epoch
        if (transferBlocked()) return
        viewModelScope.launch {
            try {
                if (!container.shortcutPublisher.isPinned(item.id)) {
                    notify("请先将这个网站添加到桌面")
                    return@launch
                }
                val updated = container.mutationMutex.withLock {
                    container.transferGate.requireWritable(collectionEpoch)
                    repository.find(item.id)?.let { container.shortcutPublisher.update(it) } ?: false
                }
                notify(if (updated) "已请求更新桌面快捷方式，实际刷新时间取决于桌面启动器" else "系统暂未接受更新，请稍后重试")
            } catch (error: Exception) { rethrowCancellation(error); notify("桌面更新失败，请稍后重试") }
        }
    }

    fun refresh(item: BookmarkItem) {
        if (transferBlocked()) return
        if (item.selectedIconSource != IconSourceMode.AUTO) { notify("只有自动模式的图标会被刷新"); return }
        if (item.id in mutableState.value.refreshingIds) return
        viewModelScope.launch {
            val result = refreshOne(item)
            // Another refresh already owns the row; do not replace its live feedback.
            if (item.id !in mutableState.value.refreshingIds) notify(result.message)
        }
    }

    fun clearRefreshSummary() { mutableState.update { it.copy(refreshSummary = null) } }

    fun refreshAll() {
        if (transferBlocked()) return
        if (mutableState.value.refreshing) return
        val candidates = repository.bookmarks.value.filter { it.selectedIconSource == IconSourceMode.AUTO }
        if (candidates.isEmpty()) { notify("暂无需要刷新的自动图标"); return }
        mutableState.update { it.copy(refreshing = true, refreshCompleted = 0, refreshTotal = candidates.size, refreshSummary = null) }
        viewModelScope.launch {
            var updated = 0
            var pendingSync = 0
            var failed = 0
            var skipped = 0
            var completed = 0
            try {
                for (item in candidates) {
                    when (refreshOne(item)) {
                        RefreshResult.UPDATED, RefreshResult.SYNC_REQUESTED -> updated++
                        RefreshResult.SYNC_PENDING -> { updated++; pendingSync++ }
                        RefreshResult.FAILED -> failed++
                        RefreshResult.SKIPPED -> skipped++
                    }
                    completed++
                    mutableState.update { it.copy(refreshCompleted = completed) }
                }
            } finally {
                val summary = (if (completed < candidates.size) "刷新已停止（$completed/${candidates.size}）：" else "刷新完成：") +
                    "已更新 $updated 个图标，$failed 个保留原图" +
                    (if (skipped > 0) "，$skipped 个已跳过" else "") +
                    (if (pendingSync > 0) "；$pendingSync 个桌面同步待重试" else "")
                mutableState.update { it.copy(refreshing = false, refreshSummary = summary) }
                notify(summary)
            }
        }
    }

    private suspend fun refreshOne(snapshot: BookmarkItem): RefreshResult {
        val collectionEpoch = container.transferGate.epoch
        if (!container.transferGate.allowsMutation(collectionEpoch)) return RefreshResult.SKIPPED
        synchronized(refreshingIds) {
            if (!refreshingIds.add(snapshot.id)) return RefreshResult.SKIPPED
            mutableState.update { it.copy(
                refreshingIds = refreshingIds.toSet(),
                refreshMessages = it.refreshMessages + (snapshot.id to "正在获取网站图标…"),
            ) }
        }
        var fetched: IconFetchResult? = null
        var outcome = RefreshResult.FAILED
        var cancelled = false
        try {
            val result = container.siteIconDiscoverer.fetch(snapshot.url) ?: return outcome
            fetched = result
            outcome = container.mutationMutex.withLock {
                if (!container.transferGate.allowsMutation(collectionEpoch)) return@withLock RefreshResult.SKIPPED
                val latest = repository.find(snapshot.id) ?: return@withLock RefreshResult.SKIPPED
                // An editor may have saved a custom icon, different URL, or newer auto image during IO.
                if (latest.selectedIconSource != IconSourceMode.AUTO || latest.url != snapshot.url || latest.imageVersion != snapshot.imageVersion) return@withLock RefreshResult.SKIPPED
                val version = latest.imageVersion + 1
                val path = container.iconStore.save(latest.id, version, IconSourceMode.AUTO, result.bitmap)
                val now = System.currentTimeMillis()
                val updated = latest.copy(autoImagePath = path, sourceUrl = result.sourceUrl, iconDescription = result.description, imageVersion = version, lastFetchedAt = now, updatedAt = now)
                repository.save(updated)
                // A saved image is already a successful refresh, even if its launcher update
                // is rate-limited or rejected. Do not report it as a failed download.
                outcome = RefreshResult.UPDATED
                val sync = try {
                    if (!container.shortcutPublisher.isPinned(updated.id)) RefreshResult.UPDATED
                    else if (container.shortcutPublisher.update(updated)) RefreshResult.SYNC_REQUESTED
                    else RefreshResult.SYNC_PENDING
                } catch (error: Exception) { rethrowCancellation(error); RefreshResult.SYNC_PENDING }
                outcome = sync
                cleanUnusedIcons()
                sync
            }
            return outcome
        } catch (error: Exception) {
            cancelled = error is CancellationException
            rethrowCancellation(error)
            return outcome
        } finally {
            fetched?.bitmap?.recycle()
            synchronized(refreshingIds) {
                refreshingIds.remove(snapshot.id)
                mutableState.update { it.copy(
                    refreshingIds = refreshingIds.toSet(),
                    refreshMessages = it.refreshMessages + (snapshot.id to
                        if (cancelled && outcome == RefreshResult.FAILED) "刷新已停止，已保留原图" else outcome.message),
                ) }
            }
        }
    }

    private fun updatePreview() {
        val editor = mutableState.value.editor ?: return
        previewJob?.cancel()
        val epoch = ++previewEpoch
        val origin = original
        val normalizedUrl = runCatching { UrlNormalizer.normalize(editor.url) }.getOrNull()
        val newAuto = autoImage?.bitmap.takeIf { autoImageUrl == normalizedUrl }
        val newCustom = customImage
        val oldAutoValid = origin?.url == normalizedUrl
        val label = when (editor.mode) {
            IconSourceMode.CUSTOM -> if (editor.hasCustomImage) "本地自定义图片 · 仅存于此设备" else "请选择一张图片"
            IconSourceMode.GENERATED -> "字母生成 · 根据域名分配配色"
            IconSourceMode.AUTO -> autoImage?.description.takeIf { autoImageUrl == normalizedUrl } ?: origin?.iconDescription.takeIf { oldAutoValid } ?: "自动发现 · Manifest / Touch Icon / Favicon"
        }
        val sourceUrl = if (editor.mode == IconSourceMode.AUTO)
            autoImage?.sourceUrl.takeIf { autoImageUrl == normalizedUrl } ?: origin?.sourceUrl.takeIf { oldAutoValid } else null
        val fetchedAt = if (editor.mode == IconSourceMode.AUTO)
            autoImageFetchedAt.takeIf { autoImageUrl == normalizedUrl } ?: origin?.lastFetchedAt.takeIf { oldAutoValid } else null
        mutableState.update { it.copy(editor = it.editor?.copy(sourceLabel = label, sourceUrl = sourceUrl, lastFetchedAt = fetchedAt)) }
        previewJob = viewModelScope.launch {
            val bitmap = try {
                withContext(Dispatchers.Default) {
                    when (editor.mode) {
                        IconSourceMode.CUSTOM -> newCustom ?: origin?.let { container.iconResolver.resolve(it.copy(title = editor.title, url = editor.url, selectedIconSource = IconSourceMode.CUSTOM)) } ?: container.iconResolver.generated(editor.title, editor.url)
                        IconSourceMode.GENERATED -> container.iconResolver.generated(editor.title, editor.url)
                        IconSourceMode.AUTO -> newAuto ?: origin?.takeIf { oldAutoValid }?.let { container.iconResolver.resolve(it.copy(title = editor.title, selectedIconSource = IconSourceMode.AUTO)) } ?: container.iconResolver.generated(editor.title, editor.url)
                    }
                }
            } catch (error: Exception) { rethrowCancellation(error); container.iconResolver.generated(editor.title, editor.url) }
            if (epoch == previewEpoch && mutableState.value.editor?.id == editor.id) mutableState.update { it.copy(editor = it.editor?.copy(preview = bitmap)) }
        }
    }

    private fun hasAutoImageForCurrentUrl(): Boolean {
        val url = mutableState.value.editor?.url?.let { runCatching { UrlNormalizer.normalize(it) }.getOrNull() } ?: return false
        return (autoImage != null && autoImageUrl == url) || (original?.url == url && original?.autoImagePath != null)
    }

    private fun isCurrentRequest(epoch: Int, requestEpoch: Int, url: String): Boolean {
        val editor = mutableState.value.editor ?: return false
        return epoch == editorEpoch && requestEpoch == fetchEpoch && editor.mode == IconSourceMode.AUTO && !editor.saving && runCatching { UrlNormalizer.normalize(editor.url) }.getOrNull() == url
    }

    private fun normalizeOrShowError(url: String): String? = try {
        UrlNormalizer.normalize(url)
    } catch (error: IllegalArgumentException) {
        changeEditor { it.copy(urlError = error.message ?: "请输入有效的 http 或 https 网址") }
        null
    }

    private fun changeEditor(transform: (EditorUiState) -> EditorUiState) {
        val editor = mutableState.value.editor ?: return
        val epoch = editorEpoch
        // Subscribers can save or recreate the editor as soon as they observe this state.
        // Commit the matching draft snapshot before publishing readiness or changed coordinates.
        persistEditor(transform(editor))
        mutableState.update { home ->
            if (editorEpoch == epoch && home.editor?.id == editor.id)
                home.copy(editor = home.editor?.let(transform)) else home
        }
    }

    private fun persistEditor(editor: EditorUiState? = mutableState.value.editor) {
        editor ?: return
        savedState["draft.id"] = editor.id
        savedState["draft.new"] = editor.isNew
        savedState["draft.title"] = editor.title
        savedState["draft.url"] = editor.url
        savedState["draft.mode"] = editor.mode.name
        savedState["draft.dirty"] = editor.dirty
        savedState["draft.custom"] = customDraftName
        savedState["draft.original"] = originalDraftName
        savedState["draft.tags"] = ArrayList(editor.tagIds)
        savedState["draft.initialTags"] = ArrayList(editor.initialTagIds)
        savedState["draft.newTags"] = ArrayList(editor.newTagNames)
        savedState["draft.crop"] = editor.cropRect?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) }
    }

    private suspend fun restoreEditor() {
        val id = savedState.get<String>("draft.id") ?: return
        val isNew = savedState.get<Boolean>("draft.new") ?: true
        val previous = if (isNew) null else repository.find(id)
        if (!isNew && previous == null) { DRAFT_KEYS.forEach { savedState.remove<Any?>(it) }; return }
        val title = savedState.get<String>("draft.title").orEmpty()
        val url = savedState.get<String>("draft.url").orEmpty()
        val mode = runCatching { IconSourceMode.valueOf(savedState.get<String>("draft.mode") ?: "AUTO") }.getOrDefault(IconSourceMode.AUTO)
        val dirty = savedState.get<Boolean>("draft.dirty") ?: false
        val customName = savedState.get<String>("draft.custom")
        val sourceName = savedState.get<String>("draft.original")
        val crop = if (savedState.contains("draft.crop")) savedState.get<FloatArray>("draft.crop")?.let { values ->
            runCatching { require(values.size == 4); CropRect(values[0], values[1], values[2], values[3]) }.getOrNull()
        } else previous?.customCrop
        val knownTags = repository.library.value.tags.map { it.id }.toSet()
        val tagIds = if (savedState.contains("draft.tags")) savedState.get<ArrayList<String>>("draft.tags").orEmpty().intersect(knownTags) else previous?.tagIds.orEmpty()
        val initialTags = if (savedState.contains("draft.initialTags")) savedState.get<ArrayList<String>>("draft.initialTags").orEmpty().intersect(knownTags) else previous?.tagIds.orEmpty()
        val newTags = savedState.get<ArrayList<String>>("draft.newTags").orEmpty().toList()
        beginEditor(previous, EditorUiState(id = id, isNew = isNew, title = title, url = url, mode = mode, dirty = dirty,
            hasCustomImage = previous?.customImagePath != null, cropRect = crop,
            tagIds = tagIds, initialTagIds = initialTags, newTagNames = newTags), loadCropSource = false)
        customImage = drafts.read(customName)
        customDraftName = customName.takeIf { customImage != null }
        val restoredSource = drafts.read(sourceName)
        customOriginal = if (sourceName != null) restoredSource else container.iconStore.readOriginal(previous?.customOriginalPath)
        originalDraftName = sourceName
        customOriginalDirty = sourceName != null
        if (customImage == null && customOriginal != null && (customName != null || sourceName != null)) {
            // A disposable preview can be rebuilt; the original plus coordinates are authoritative.
            try {
                val recovered = withContext(Dispatchers.Default) { container.iconProcessor.crop(customOriginal!!, crop) }
                customDraftName = drafts.write(id, recovered)
                customImage = recovered
            } catch (error: Exception) { rethrowCancellation(error) }
        }
        changeEditor { it.copy(hasCustomImage = customImage != null || previous?.customImagePath != null,
            cropSource = customOriginal, canCrop = customOriginal != null,
            error = when {
                sourceName != null && restoredSource == null -> "原图草稿已过期，请重新导入图片后保存，或放弃本次编辑"
                customName != null && customImage == null -> "图片草稿已过期，请重新选择图片"
                else -> null
            }) }
        updatePreview()
        if (mode == IconSourceMode.AUTO && !hasAutoImageForCurrentUrl() && url.isNotBlank()) fetchIcon(force = true)
    }

    private suspend fun cleanUnusedIcons() {
        try { container.iconStore.cleanup(repository.bookmarks.value) } catch (error: Exception) { rethrowCancellation(error) }
    }

    private fun cancelEditorJobs() {
        editorEpoch++
        fetchEpoch++
        importEpoch++
        previewEpoch++
        fetchJob?.cancel()
        previewJob?.cancel()
        importJob?.cancel()
        sourceJob?.cancel()
    }

    fun notify(message: String) { messageChannel.trySend(message) }
    private fun transferBlocked(): Boolean {
        if (!container.transferGate.busy.value) return false
        notify("导入导出进行中，请完成或取消当前操作")
        return true
    }
    private fun rethrowCancellation(error: Exception) { if (error is CancellationException) throw error }
    private enum class RefreshResult(val message: String) {
        UPDATED("已更新图标，保存在此设备"),
        SYNC_REQUESTED("已更新图标并请求桌面同步，显示时间由启动器决定"),
        SYNC_PENDING("图标已更新，桌面同步待重试"),
        FAILED("未获取到新图标，已保留原图"),
        SKIPPED("收藏已修改或正在刷新，本次未覆盖"),
    }
    private companion object {
        val DRAFT_KEYS = listOf("draft.id", "draft.new", "draft.title", "draft.url", "draft.mode", "draft.dirty", "draft.custom", "draft.original", "draft.crop", "draft.tags", "draft.initialTags", "draft.newTags")
        val TAG_SHEET_KEYS = listOf("tags.targets", "tags.mode", "tags.initial", "tags.selected", "tags.names", "tags.editor")
    }
}
