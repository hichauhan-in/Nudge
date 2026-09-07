package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.BackupEncryption
import com.example.data.LocalDataTransfer
import com.example.domain.SessionManager
import com.example.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransferAction { CSV, BACKUP, RESTORE }
data class TransferStatus(val busy: Boolean = false, val message: String? = null)

class DataTransferViewModel(application: Application) : AndroidViewModel(application) {
    val status = MutableStateFlow(TransferStatus())

    fun run(action: TransferAction, uri: Uri, passphrase: CharArray = charArrayOf()) {
        if (status.value.busy) { passphrase.fill('\u0000'); return }
        status.value = TransferStatus(busy = true)
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                SessionManager.init(context)
                SessionManager.flushForegroundUsage()
                withContext(Dispatchers.IO) {
                    when (action) {
                        TransferAction.CSV -> checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { LocalDataTransfer.exportCsv(context, it) }
                        TransferAction.BACKUP -> {
                            val bytes = LocalDataTransfer.backup(context, passphrase)
                            try { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
                            finally { bytes.fill(0) }
                        }
                        TransferAction.RESTORE -> {
                            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val block = ByteArray(8192)
                                while (true) {
                                    val count = input.read(block)
                                    if (count < 0) break
                                    require(output.size().toLong() + count <= BackupEncryption.MAX_BYTES + 56L) { "Backup exceeds 32 MB." }
                                    output.write(block, 0, count)
                                }
                                output.toByteArray()
                            }
                            try { LocalDataTransfer.restore(context, bytes, passphrase) } finally { bytes.fill(0) }
                        }
                    }
                }
                status.value = TransferStatus(message = when (action) {
                    TransferAction.CSV -> "History exported. The CSV is not encrypted."
                    TransferAction.BACKUP -> "Encrypted backup saved. Keep your passphrase; it cannot be recovered."
                    TransferAction.RESTORE -> "Backup restored. Monitoring is off; review accessibility consent to enable it again."
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: javax.crypto.AEADBadTagException) {
                status.value = TransferStatus(message = "Incorrect passphrase or damaged backup. No data was imported.")
            } catch (exception: Exception) {
                status.value = TransferStatus(message = if (exception is IllegalArgumentException)
                    "The backup is invalid, unsupported, or larger than 32 MB. No history was imported."
                else "The file operation could not finish. Check the selected file and storage availability.")
            } finally {
                passphrase.fill('\u0000')
                if (status.value.busy) status.value = TransferStatus(message = "File operation cancelled.")
            }
        }
    }
}

@Composable
fun DataTransferControls() {
    val model: DataTransferViewModel = viewModel()
    val status by model.status.collectAsStateWithLifecycle()
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var documentPickerOpen by rememberSaveable { mutableStateOf(false) }
    var action by rememberSaveable { mutableStateOf<TransferAction?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCsv by remember { mutableStateOf(false) }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        documentPickerOpen = false
        if (uri != null) model.run(TransferAction.CSV, uri)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        documentPickerOpen = false
        if (uri != null) { action = TransferAction.BACKUP; pendingUri = uri.toString() }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        documentPickerOpen = false
        if (uri != null) { action = TransferAction.RESTORE; pendingUri = uri.toString() }
    }
    SettingsBlock(stringResource(com.example.R.string.ui_backup_and_restore), stringResource(com.example.R.string.ui_backup_restore_summary),
        Icons.Default.SettingsBackupRestore, onClick = { showTransfers = true },
        enabled = !status.busy, modifier = Modifier.testTag("setting-data-transfer"))
    if (showTransfers && !confirmCsv && pendingUri == null && !documentPickerOpen && !status.busy) {
        EditorDialog(
            onDismissRequest = { showTransfers = false },
            title = { Text(stringResource(com.example.R.string.ui_backup_and_restore), color = GuardTextPrimary) },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsBlock(stringResource(com.example.R.string.ui_export_csv), stringResource(com.example.R.string.ui_export_summary),
                        Icons.Default.Download, onClick = { confirmCsv = true }, modifier = Modifier.testTag("setting-export"))
                    SettingsBlock(stringResource(com.example.R.string.ui_backup), stringResource(com.example.R.string.ui_backup_summary),
                        Icons.Default.Lock, onClick = {
                            documentPickerOpen = true
                            backupPicker.launch("nudge-${java.time.LocalDate.now()}.nudgebak")
                        }, modifier = Modifier.testTag("setting-backup"))
                    SettingsBlock(stringResource(com.example.R.string.ui_restore_backup), stringResource(com.example.R.string.ui_restore_summary),
                        Icons.Default.Restore, onClick = {
                            documentPickerOpen = true
                            restorePicker.launch(arrayOf("application/octet-stream", "application/*"))
                        }, modifier = Modifier.testTag("setting-restore"))
                    status.message?.let { Text(it, color = GuardTextSecondary, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTransfers = false }) { Text(stringResource(com.example.R.string.ui_close)) } }
        )
    }
    if (confirmCsv) {
        AlertDialog(onDismissRequest = { confirmCsv = false }, title = { Text("Export local history?") },
            text = { Text("The unencrypted CSV contains monitored app names, usage times, and decisions. Anyone with the file can read it. The storage provider you choose may upload it under its own policy.") },
            confirmButton = { TextButton(onClick = {
                confirmCsv = false
                documentPickerOpen = true
                csvPicker.launch("nudge-history-${java.time.LocalDate.now()}.csv")
            }) { Text("Choose destination") } },
            dismissButton = { TextButton(onClick = { confirmCsv = false }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
    }
    if (pendingUri != null && action != null) {
        val activity = androidx.activity.compose.LocalActivity.current
        DisposableEffect(activity) {
            val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
            val wasSecure = activity?.window?.attributes?.flags?.and(flag) != 0
            activity?.window?.addFlags(flag)
            onDispose { if (!wasSecure) activity?.window?.clearFlags(flag) }
        }
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        val restoring = action == TransferAction.RESTORE
        EditorDialog(onDismissRequest = { pendingUri = null; action = null },
            title = { Text(if (restoring) "Restore encrypted backup" else "Protect your backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (restoring) "This replaces local history and monitored-app selections. Monitoring turns off. Consent, active timers, and cooldowns are not restored. Today's existing quota usage will not be reduced."
                        else "Your history and app rules are encrypted before saving. Use at least 12 characters. We cannot recover a forgotten passphrase. Your chosen storage provider may store the encrypted file online.")
                    OutlinedTextField(password, onValueChange = { password = it.take(1024) }, singleLine = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_passphrase)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (!restoring) OutlinedTextField(confirmation, onValueChange = { confirmation = it.take(1024) }, singleLine = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_confirm_passphrase)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            }, confirmButton = { TextButton(enabled = password.length >= 12 && (restoring || password == confirmation), onClick = {
                model.run(action!!, Uri.parse(pendingUri), password.toCharArray())
                password = ""; confirmation = ""; pendingUri = null; action = null
            }) { Text(if (restoring) androidx.compose.ui.res.stringResource(com.example.R.string.ui_restore) else androidx.compose.ui.res.stringResource(com.example.R.string.ui_save_backup)) } },
            dismissButton = { TextButton(onClick = { pendingUri = null; action = null }) { Text(androidx.compose.ui.res.stringResource(com.example.R.string.ui_cancel)) } })
    }
    if (status.busy) {
        AlertDialog(onDismissRequest = {}, title = { Text("Working with local data") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    }
}