package org.thoughtcrime.securesms.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.loki.messenger.libsession_util.PRIORITY_VISIBLE
import network.loki.messenger.libsession_util.util.UserPic
import org.session.libsession.messaging.calls.CallMessageType
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage
import org.session.libsession.messaging.messages.signal.IncomingTextMessage
import org.session.libsession.messaging.messages.signal.OutgoingTextMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.attachments.Attachment
import org.session.libsession.messaging.sending_receiving.attachments.UriAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.upsertContact
import org.session.libsession.utilities.withMutableUserConfigs
import org.session.libsignal.utilities.AccountId
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.database.MmsDatabase
import org.thoughtcrime.securesms.database.RecipientSettingsDatabase
import org.thoughtcrime.securesms.database.SmsDatabase
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getOrCreateThreadIdFor
import org.thoughtcrime.securesms.database.model.NotifyType
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports a [BackupModel] (JSON or ZIP) from [inputUri] into the local database so the imported
 * chats/messages render through the existing UI — non-real, demonstration only.
 *
 * For each contact:
 *   - upserts the libsession Contacts config (name, approved, approvedMe, blocked, priority).
 *     `blocked = true` makes the contact appear in Settings -> Blocked with the same UI as a real
 *     block, and auto-filters it out of the Home conversation list.
 *   - sets `notifyType = NONE` on the recipient settings so message inserts do not fire notifications.
 *
 * For each chat:
 *   - creates the thread via [ThreadDatabase.getOrCreateThreadIdFor].
 *   - inserts each message (incoming text, outgoing text, incoming media) via the DB insert APIs.
 *
 * Attachments inside a ZIP (referenced by [AttachmentInfo.local]) are extracted to a temp dir and
 * attached via [UriAttachment] with transfer state DONE so images display inline.
 */
object BackupImporter {

    data class Result(val contacts: Int, val chats: Int, val messages: Int, val attachments: Int)

    suspend fun import(
        context: Context,
        configFactory: ConfigFactory,
        threadDb: ThreadDatabase,
        smsDb: SmsDatabase,
        mmsDb: MmsDatabase,
        recipientDb: RecipientSettingsDatabase,
        inputUri: Uri,
    ): Result = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
            ?: error("Unable to read backup from $inputUri")

        val model: BackupModel
        val mediaFiles: Map<String, File> // local path -> extracted temp file

        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            // Standalone JSON.
            model = BackupFormat.json.decodeFromString(
                BackupModel.serializer(),
                String(bytes, Charsets.UTF_8),
            )
            mediaFiles = emptyMap()
        } else {
            // ZIP: backup.json + media/* (+ avatars/*).
            val media = mutableMapOf<String, File>()
            var parsed: BackupModel? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    when {
                        entry.name == BackupFormat.JSON_ENTRY_NAME -> {
                            parsed = BackupFormat.json.decodeFromString(
                                BackupModel.serializer(),
                                zis.readBytes().toString(Charsets.UTF_8),
                            )
                        }
                        entry.name.startsWith("${BackupFormat.MEDIA_DIR}/") ||
                                entry.name.startsWith("${BackupFormat.AVATARS_DIR}/") -> {
                            val tmp = File.createTempFile("imp_", ".bin", context.cacheDir)
                            tmp.outputStream().use { out -> zis.copyTo(out) }
                            media[entry.name] = tmp
                        }
                    }
                    zis.closeEntry()
                }
            }
            model = parsed ?: error("Backup ZIP is missing ${BackupFormat.JSON_ENTRY_NAME}")
            mediaFiles = media
        }

        var insertedContacts = 0
        var insertedMessages = 0
        var insertedAttachments = 0

        // Pre-index contacts by sessionId so chats can look up approved/blocked.
        val contactById: Map<String, ContactInfo> =
            model.contacts.associateBy { it.sessionId }

        // --- Contacts ---
        for (c in model.contacts) {
            val address = runCatching { Address.Standard(AccountId(c.sessionId)) }.getOrNull()
                ?: continue

            configFactory.withMutableUserConfigs { configs ->
                configs.contacts.upsertContact(address) {
                    name = c.name.orEmpty()
                    nickname = c.nickname.orEmpty()
                    approved = c.approved
                    approvedMe = c.approvedMe
                    blocked = c.blocked
                    priority = PRIORITY_VISIBLE
                    // v1 demo: monogram avatar (offline-safe). The recorded URL is preserved in the
                    // backup JSON for future online display.
                    profilePicture = UserPic.DEFAULT
                    createdEpochSeconds = System.currentTimeMillis() / 1000
                }
            }

            // Silence notifications for imported recipients (blocked ones are auto-silenced anyway).
            runCatching {
                recipientDb.save(address) { it.copy(notifyType = NotifyType.NONE) }
            }

            insertedContacts += 1
        }

        // --- Chats ---
        for (chat in model.chats) {
            val address = runCatching { Address.Standard(AccountId(chat.sessionId)) }.getOrNull()
                ?: continue
            val threadId = threadDb.getOrCreateThreadIdFor(address)

            for (m in chat.messages) {
                val ok = runCatching {
                    val expiresIn = m.expiresInSeconds ?: 0L
                    val expireStarted = m.expireStartedAt ?: 0L
                    when {
                        m.callType != null && m.direction == "incoming" -> {
                            val cmt = mapCallType(m.callType)
                            if (cmt != null) {
                                val incoming = IncomingTextMessage(
                                    callMessageType = cmt,
                                    sender = address,
                                    group = null,
                                    sentTimestampMillis = m.timestamp,
                                    expiresInMillis = expiresIn,
                                    expireStartedAt = expireStarted,
                                )
                                smsDb.insertMessageInbox(incoming, threadId, m.timestamp)
                            }
                        }
                        m.direction == "incoming" && m.type == "media" -> {
                            val attachments = m.attachments.mapNotNull { buildAttachment(it, mediaFiles) }
                            if (attachments.isEmpty() && m.body.isNullOrBlank()) {
                                // Nothing to insert.
                            } else {
                                val mediaMsg = IncomingMediaMessage(
                                    from = address,
                                    sentTimeMillis = m.timestamp,
                                    expiresIn = expiresIn,
                                    expireStartedAt = expireStarted,
                                    isMessageRequestResponse = m.isMessageRequestResponse,
                                    hasMention = m.hasMention,
                                    body = m.body,
                                    group = null,
                                    attachments = attachments,
                                    proFeatures = emptySet(),
                                    messageContent = null,
                                    quote = null,
                                    linkPreviews = emptyList(),
                                    dataExtractionNotification = null,
                                )
                                mmsDb.insertSecureDecryptedMessageInbox(mediaMsg, threadId, m.timestamp)
                                insertedAttachments += attachments.size
                            }
                        }
                        m.direction == "incoming" -> {
                            val incoming = IncomingTextMessage(
                                message = m.body,
                                sender = address,
                                sentTimestampMillis = m.timestamp,
                                group = null,
                                push = true,
                                expiresInMillis = expiresIn,
                                expireStartedAt = expireStarted,
                                callType = -1,
                                hasMention = m.hasMention,
                                isOpenGroupInvitation = m.isOpenGroupInvitation,
                                isSecureMessage = true,
                                proFeatures = emptySet(),
                            )
                            smsDb.insertMessageInbox(incoming, threadId, m.timestamp)
                        }
                        m.direction == "outgoing" -> {
                            val visible = VisibleMessage(text = m.body).apply { sentTimestamp = m.timestamp }
                            val outgoing = OutgoingTextMessage(
                                message = visible,
                                recipient = address,
                                expiresInMillis = expiresIn,
                                expireStartedAtMillis = expireStarted,
                            )
                            smsDb.insertMessageOutbox(threadId, outgoing, m.timestamp)
                        }
                    }
                }.isSuccess
                if (ok) insertedMessages += 1
            }
        }

        // Best-effort cleanup of extracted temp files (the DB has copied the bytes into its own store).
        mediaFiles.values.forEach { runCatching { it.delete() } }

        Result(
            contacts = insertedContacts,
            chats = model.chats.size,
            messages = insertedMessages,
            attachments = insertedAttachments,
        )
    }

    private fun mapCallType(kind: String): CallMessageType? = when (kind) {
        "missed" -> CallMessageType.CALL_MISSED
        "incoming" -> CallMessageType.CALL_INCOMING
        "outgoing" -> CallMessageType.CALL_OUTGOING
        "first_missed" -> CallMessageType.CALL_FIRST_MISSED
        else -> null
    }

    private fun buildAttachment(info: AttachmentInfo, mediaFiles: Map<String, File>): Attachment? {
        val file = info.local?.let { mediaFiles[it] }
        if (file == null || !file.exists() || file.length() == 0L) return null
        return UriAttachment(
            /* uri          = */ Uri.fromFile(file),
            /* contentType  = */ info.contentType.ifBlank { "application/octet-stream" },
            /* transferState = */ 0, // AttachmentState.DONE -> shown inline
            /* size         = */ if (info.size > 0) info.size else file.length(),
            /* fileName     = */ info.fileName,
            /* voiceNote    = */ false,
            /* quote        = */ false,
            /* caption      = */ null,
        )
    }
}
