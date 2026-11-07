package io.github.homura251.webshortcuts.transfer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.homura251.webshortcuts.WebShortcutsApplication
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Keeps picker requests and private prepared files stable across Activity recreation. */
class TransferViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val container = (application as WebShortcutsApplication).container
    private val manager = container.transferManager
    private val gate = container.transferGate
    private val mutableState = MutableStateFlow(TransferUiState())
    val state = mutableState.asStateFlow()
    private var token: String? = saved["transfer.token"]
    private var lease: Long? = null
    private var operation: Job? = null
    private var closing = false

    init {
        if (saved.get<Boolean>("transfer.visible") == true) {
            lease = gate.begin()
            if (lease != null) {
                val oldPhase = saved.get<String>("transfer.phase")?.let { runCatching { TransferPhase.valueOf(it) }.getOrNull() }
                val mode = saved.get<String>("transfer.mode")?.let { runCatching { ImportMode.valueOf(it) }.getOrNull() } ?: ImportMode.MERGE
                val restore = saved.get<Boolean>("transfer.appearance") ?: false
                mutableState.value = TransferUiState(visible = true, phase = TransferPhase.READING, mode = mode, restoreAppearance = restore,
                    progress = TransferProgress("正在恢复操作…"))
                operation = viewModelScope.launch {
                    try {
                        manager.prune(token)
                        when {
                            token == null -> setPhase(TransferPhase.IDLE)
                            oldPhase in setOf(TransferPhase.EXPORT_READY, TransferPhase.PICK_EXPORT) -> {
                                val preview = manager.restoreExport(token!!)
                                mutableState.update { it.copy(export = preview) }
                                setPhase(TransferPhase.PICK_EXPORT) // Do not launch a second picker after process recreation.
                            }
                            oldPhase == TransferPhase.PREVIEW -> {
                                val preview = manager.restoreImport(token!!, ::progress)
                                mutableState.update { it.copy(preview = preview) }
                                setPhase(TransferPhase.PREVIEW)
                            }
                            else -> fail(IllegalStateException("上次操作已中断，请重新选择文件；已保存的收藏保持有效"))
                        }
                    } catch (error: Exception) { handleFailure(error) }
                }
            }
        }
    }

    fun open(): Boolean {
        if (mutableState.value.visible) return true
        val owner = gate.begin() ?: return false
        lease = owner
        closing = false
        publish(TransferUiState(visible = true, phase = TransferPhase.READING, progress = TransferProgress("正在准备…")))
        operation = viewModelScope.launch {
            try { manager.prune(null); setPhase(TransferPhase.IDLE) }
            catch (error: Exception) { handleFailure(error) }
        }
        return true
    }

    fun close() {
        if (!mutableState.value.canDismiss || closing) return
        closing = true
        val previous = operation
        viewModelScope.launch {
            previous?.cancelAndJoin()
            token?.let { runCatching { manager.discard(it) } }
            token = null
            persist(TransferUiState())
            lease?.let(gate::end)
            lease = null
            mutableState.value = TransferUiState()
            closing = false
        }
    }

    fun importPickerLaunched() {
        if (!mutableState.value.working) setPhase(TransferPhase.PICK_IMPORT)
    }

    fun onImportPicked(uri: Uri?) {
        val previous = operation
        operation = viewModelScope.launch {
            previous?.join()
            if (lease == null || closing) return@launch
            if (uri == null) { setPhase(TransferPhase.IDLE); return@launch }
            try {
                discardPrepared()
                token = UUID.randomUUID().toString()
                mutableState.update { it.copy(mode = ImportMode.MERGE, restoreAppearance = false, preview = null, message = null, warnings = emptyList()) }
                setPhase(TransferPhase.READING)
                val preview = manager.prepareImport(uri, token!!, ::progress)
                mutableState.update { it.copy(preview = preview) }
                setPhase(TransferPhase.PREVIEW)
            } catch (error: Exception) { handleFailure(error) }
        }
    }

    fun setMode(mode: ImportMode) {
        if (mutableState.value.phase == TransferPhase.PREVIEW) publish(mutableState.value.copy(mode = mode))
    }

    fun setRestoreAppearance(restore: Boolean) {
        if (mutableState.value.phase == TransferPhase.PREVIEW) publish(mutableState.value.copy(restoreAppearance = restore))
    }

    fun commitImport() {
        val state = mutableState.value
        val preview = state.preview ?: return
        if (state.phase != TransferPhase.PREVIEW ||
            !(if (state.mode == ImportMode.MERGE) preview.canMerge else preview.canReplace)) return
        val preparedToken = token ?: return
        setPhase(TransferPhase.COMMITTING)
        operation = viewModelScope.launch {
            try {
                val result = manager.commitImport(preparedToken, state.mode, state.restoreAppearance, ::progress)
                val message = (if (result.replaced) "已恢复 ${result.imported} 条收藏" else "已新增 ${result.imported} 条收藏") +
                    (if (result.addedTags > 0) "，新增 ${result.addedTags} 个标签" else "") +
                    (if (result.tagUpdatedBookmarks > 0) "，为 ${result.tagUpdatedBookmarks} 条收藏补充标签" else "") +
                    "，跳过 ${result.skippedDuplicates} 条重复、${result.skippedInvalid} 条无效书签。"
                runCatching { manager.discard(preparedToken) }
                token = null
                mutableState.update { it.copy(message = message, warnings = result.warnings) }
                setPhase(TransferPhase.RESULT)
            } catch (error: Exception) { handleFailure(error) }
        }
    }

    fun prepareExport(format: TransferFormat) {
        if (mutableState.value.working || lease == null) return
        val previous = operation
        operation = viewModelScope.launch {
            previous?.join()
            try {
                discardPrepared()
                token = UUID.randomUUID().toString()
                mutableState.update { it.copy(export = null, message = null, warnings = emptyList()) }
                setPhase(TransferPhase.PREPARING_EXPORT)
                val preview = manager.prepareExport(format, token!!, ::progress)
                mutableState.update { it.copy(export = preview) }
                setPhase(TransferPhase.EXPORT_READY)
            } catch (error: Exception) { handleFailure(error) }
        }
    }

    fun exportPickerLaunched() {
        if (mutableState.value.export != null) setPhase(TransferPhase.PICK_EXPORT)
    }

    fun retryExportPicker() {
        if (mutableState.value.phase == TransferPhase.PICK_EXPORT) setPhase(TransferPhase.EXPORT_READY)
    }

    fun onExportPicked(uri: Uri?) {
        val previous = operation
        operation = viewModelScope.launch {
            previous?.join()
            if (lease == null || closing) return@launch
            try {
                if (uri == null) { discardPrepared(); setPhase(TransferPhase.IDLE); return@launch }
                val preparedToken = token ?: error("导出缓存已过期，请重试")
                setPhase(TransferPhase.WRITING)
                val result = manager.writeExport(preparedToken, uri, ::progress)
                runCatching { manager.discard(preparedToken) }
                token = null
                mutableState.update { it.copy(message = "已导出 ${result.itemCount} 条收藏。",
                    warnings = if (result.missingImages > 0) listOf("${result.missingImages} 份图像不可读取，备份保留了收藏及可用图片") else emptyList()) }
                setPhase(TransferPhase.RESULT)
            } catch (error: Exception) { handleFailure(error) }
        }
    }

    fun pickerFailed(error: Exception) = fail(error)

    fun restart() {
        if (mutableState.value.working) return
        operation = viewModelScope.launch {
            discardPrepared()
            publish(TransferUiState(visible = true))
        }
    }

    private suspend fun discardPrepared() {
        token?.let { manager.discard(it) }
        token = null
    }

    private fun progress(value: TransferProgress) { mutableState.update { it.copy(progress = value) } }
    private fun setPhase(value: TransferPhase) {
        publish(mutableState.value.copy(phase = value))
    }

    private fun handleFailure(error: Exception) {
        if (error is CancellationException) throw error
        fail(error)
    }

    private fun fail(error: Exception) {
        val prefix = when (mutableState.value.phase) {
            TransferPhase.COMMITTING -> "导入失败，原有收藏未更改。"
            TransferPhase.WRITING -> "导出未完成，请重新选择保存位置。"
            else -> "操作未完成："
        }
        publish(mutableState.value.copy(phase = TransferPhase.ERROR,
            message = "$prefix${error.message.orEmpty().take(180).ifBlank { "请检查文件和剩余空间后重试" }}"))
    }

    private fun publish(value: TransferUiState) {
        persist(value)
        mutableState.value = value
    }

    private fun persist(current: TransferUiState = mutableState.value) {
        saved["transfer.visible"] = current.visible
        saved["transfer.phase"] = current.phase.name
        saved["transfer.token"] = token
        saved["transfer.mode"] = current.mode.name
        saved["transfer.appearance"] = current.restoreAppearance
    }

    override fun onCleared() {
        val owner = lease
        if (owner != null) {
            val active = operation
            if (active?.isCompleted == false) active.invokeOnCompletion { gate.end(owner) } else gate.end(owner)
        }
        super.onCleared()
    }
}
