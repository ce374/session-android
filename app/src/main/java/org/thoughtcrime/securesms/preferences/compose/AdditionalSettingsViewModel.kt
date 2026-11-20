package org.thoughtcrime.securesms.preferences.compose

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.backup.BackupExporter
import org.thoughtcrime.securesms.backup.BackupImporter
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import javax.inject.Inject

@HiltViewModel
class AdditionalSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configFactory: ConfigFactory,
    private val recipientRepository: RecipientRepository,
    private val threadDatabase: ThreadDatabase,
    private val smsDatabase: SmsDatabase,
    private val mmsDatabase: MmsDatabase,
    private val mmsSmsDatabase: MmsSmsDatabase,
    private val attachmentDatabase: AttachmentDatabase,
    private val recipientSettingsDatabase: RecipientSettingsDatabase,
) : ViewModel() {

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    fun handleBackupImport(uri: Uri) {
        viewModelScope.launch {
            val message = runCatching {
                val r = BackupImporter.import(
                    context = context,
                    configFactory = configFactory,
                    threadDb = threadDatabase,
                    smsDb = smsDatabase,
                    mmsDb = mmsDatabase,
                    recipientDb = recipientSettingsDatabase,
                    inputUri = uri,
                )
                "Imported ${r.contacts} contacts, ${r.chats} chats, ${r.messages} messages, ${r.attachments} attachments."
            }.getOrElse { "Import failed: ${it.message ?: it.toString()}" }
            _events.emit(message)
        }
    }

    fun handleBackupExport(uri: Uri) {
        viewModelScope.launch {
            val message = runCatching {
                val r = BackupExporter.export(
                    context = context,
                    configFactory = configFactory,
                    recipientRepository = recipientRepository,
                    threadDb = threadDatabase,
                    mmsSmsDb = mmsSmsDatabase,
                    attachmentDb = attachmentDatabase,
                    outputUri = uri,
                )
                "Exported ${r.contacts} contacts, ${r.chats} chats, ${r.messages} messages, ${r.attachments} attachments."
            }.getOrElse { "Export failed: ${it.message ?: it.toString()}" }
            _events.emit(message)
        }
    }
}
