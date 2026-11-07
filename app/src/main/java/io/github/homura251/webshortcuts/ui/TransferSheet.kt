package io.github.homura251.webshortcuts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.homura251.webshortcuts.R
import io.github.homura251.webshortcuts.transfer.ImportMode
import io.github.homura251.webshortcuts.transfer.TransferFormat
import io.github.homura251.webshortcuts.transfer.TransferPhase
import io.github.homura251.webshortcuts.transfer.TransferUiState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(state: TransferUiState, onDismiss: () -> Unit, onChooseImport: () -> Unit,
                  onExport: (TransferFormat) -> Unit, onModeChange: (ImportMode) -> Unit,
                  onRestoreAppearance: (Boolean) -> Unit, onCommit: () -> Unit,
                  onRetry: () -> Unit, onChooseDestination: () -> Unit) {
    val canDismiss by rememberUpdatedState(state.canDismiss)
    ModalBottomSheet(onDismissRequest = { if (state.canDismiss) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { canDismiss }),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        TransferContent(state, onDismiss, onChooseImport, onExport, onModeChange, onRestoreAppearance,
            onCommit, onRetry, onChooseDestination)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransferContent(state: TransferUiState, onDismiss: () -> Unit = {}, onChooseImport: () -> Unit = {},
                             onExport: (TransferFormat) -> Unit = {}, onModeChange: (ImportMode) -> Unit = {},
                             onRestoreAppearance: (Boolean) -> Unit = {}, onCommit: () -> Unit = {},
                             onRetry: () -> Unit = {}, onChooseDestination: () -> Unit = {}) {
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("transfer_sheet").verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onDismiss, enabled = state.canDismiss, modifier = Modifier.testTag("close_transfer")) {
                Icon(Icons.Default.Close, stringResource(R.string.close))
            }
        }
        when (state.phase) {
            TransferPhase.IDLE -> {
                SupportingText(stringResource(R.string.transfer_subtitle))
                Button(onClick = onChooseImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("choose_import")) {
                    Icon(Icons.Default.FileDownload, null, Modifier.padding(end = 8.dp).size(20.dp))
                    Text(stringResource(R.string.transfer_choose))
                }
                HorizontalDivider()
                TransferExportOption(R.string.transfer_export_zip, R.string.transfer_zip_detail, "export_zip") { onExport(TransferFormat.BACKUP_ZIP) }
                TransferExportOption(R.string.transfer_export_html, R.string.transfer_html_detail, "export_html") { onExport(TransferFormat.BOOKMARKS_HTML) }
                SupportingText(stringResource(R.string.transfer_backup_note))
            }
            TransferPhase.PREVIEW -> state.preview?.let { preview ->
                Text(stringResource(R.string.transfer_preview_title), style = MaterialTheme.typography.titleMedium)
                SupportingText(stringResource(if (preview.format == TransferFormat.BACKUP_ZIP) R.string.transfer_zip_format else R.string.transfer_html_format))
                preview.exportedAt?.let { SupportingText(stringResource(R.string.transfer_exported_at,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))) }
                Text(stringResource(R.string.transfer_preview_counts, preview.itemCount, preview.duplicateCount, preview.skippedCount),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("import_counts"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.mode == ImportMode.MERGE, onClick = { onModeChange(ImportMode.MERGE) },
                        label = { Text(stringResource(R.string.transfer_merge)) }, modifier = Modifier.testTag("import_merge"))
                    FilterChip(selected = state.mode == ImportMode.REPLACE, onClick = { onModeChange(ImportMode.REPLACE) },
                        label = { Text(stringResource(R.string.transfer_replace)) }, modifier = Modifier.testTag("import_replace"))
                }
                SupportingText(stringResource(if (state.mode == ImportMode.MERGE) R.string.transfer_new_counts else R.string.transfer_replace_counts,
                    if (state.mode == ImportMode.MERGE) preview.newCount else preview.itemCount, preview.localCount))
                if (state.mode == ImportMode.MERGE) SupportingText(stringResource(R.string.transfer_keep_local))
                if (preview.format == TransferFormat.BOOKMARKS_HTML) SupportingText(stringResource(R.string.transfer_html_note))
                if (preview.format == TransferFormat.BACKUP_ZIP && state.mode == ImportMode.MERGE) SupportingText(stringResource(R.string.transfer_tag_counts,
                    preview.tagCount, preview.newTagCount, preview.tagUpdatedCount))
                if (preview.format == TransferFormat.BACKUP_ZIP && state.mode == ImportMode.REPLACE)
                    SupportingText(stringResource(R.string.transfer_tag_total, preview.tagCount))
                if (state.mode == ImportMode.REPLACE) SupportingText(stringResource(
                    if (preview.tagCount == 0 && preview.localTagCount > 0) R.string.transfer_no_tags_replace else R.string.transfer_tags_replace))
                if (state.mode == ImportMode.MERGE && preview.mergedTagCount > 2000) SupportingText(stringResource(R.string.transfer_tag_limit))
                if (preview.hasAppearance) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = state.restoreAppearance, role = Role.Checkbox,
                        onValueChange = onRestoreAppearance).testTag("restore_appearance"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.restoreAppearance, onCheckedChange = null)
                        Text(stringResource(R.string.transfer_restore_appearance), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                preview.warnings.forEach { SupportingText(it) }
                if (!preview.canReplace) SupportingText(stringResource(R.string.transfer_empty_html))
                if (state.mode == ImportMode.MERGE && preview.localCount + preview.newCount > 2000) SupportingText(stringResource(R.string.transfer_limit))
                Button(onClick = { if (state.mode == ImportMode.REPLACE) confirmReplace = true else onCommit() },
                    enabled = if (state.mode == ImportMode.MERGE) preview.canMerge else preview.canReplace,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm_import")) {
                    Text(stringResource(R.string.transfer_confirm))
                }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.transfer_retry)) }
            }
            TransferPhase.PICK_IMPORT -> SupportingText(stringResource(R.string.transfer_choose))
            TransferPhase.EXPORT_READY, TransferPhase.PICK_EXPORT -> {
                SupportingText(stringResource(R.string.transfer_waiting_destination))
                FilledTonalButton(onClick = onChooseDestination, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.transfer_choose_destination))
                }
            }
            TransferPhase.RESULT, TransferPhase.ERROR -> {
                if (state.phase == TransferPhase.RESULT) Text(stringResource(R.string.transfer_result), style = MaterialTheme.typography.titleMedium)
                Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = if (state.phase == TransferPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("transfer_result"))
                state.warnings.forEach { SupportingText(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.transfer_done)) }
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.transfer_retry)) }
                }
            }
            else -> {
                val fraction = state.progress.fraction
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(state.progress.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("transfer_progress"))
                if (state.phase == TransferPhase.COMMITTING) SupportingText(stringResource(R.string.transfer_committing))
            }
        }
        if (state.working && state.canDismiss) TextButton(onClick = onDismiss) { Text(stringResource(R.string.transfer_cancel_operation)) }
    }
    if (confirmReplace && state.phase == TransferPhase.PREVIEW) {
        AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text(stringResource(R.string.transfer_replace_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.transfer_replace_body, state.preview?.localCount ?: 0, state.preview?.itemCount ?: 0))
                Text(stringResource(if (state.preview?.tagCount == 0 && (state.preview?.localTagCount ?: 0) > 0)
                    R.string.transfer_no_tags_replace else R.string.transfer_tags_replace))
            } },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; onCommit() }, modifier = Modifier.testTag("confirm_replace")) {
                    Text(stringResource(R.string.transfer_replace_confirm), color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TransferExportOption(title: Int, detail: Int, tag: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
            Icon(Icons.Default.FileUpload, null, Modifier.padding(end = 8.dp).size(20.dp))
            Text(stringResource(title))
        }
        SupportingText(stringResource(detail))
    }
}
