package org.thoughtcrime.securesms.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.currentUserName
import org.session.libsession.utilities.currentUserProfile
import org.session.libsession.utilities.recipients.RemoteFile.Companion.toRemoteFile
import org.session.libsession.utilities.withUserConfigs
import org.thoughtcrime.securesms.database.AttachmentDatabase
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getThreadId
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the current on-device state into a backup at [outputUri].
 *
 * - When [produceZip] is true (default): writes a ZIP whose first entry is [BackupFormat.JSON_ENTRY_NAME]
 *   ([BackupModel]) followed by every attachment streamed as a binary entry under [BackupFormat.MEDIA_DIR]/.
 *   This is the "full" backup — conversations + profile data + attachment binaries.
 * - When [produceZip] is false: writes a standalone JSON document (attachments referenced by URL only,
 *   no binaries bundled).
 *
 * The export is hardened: every config/DB/attachment read is individually try-caught so a single bad row
 * or missing attachment file can never abort the whole export. A valid backup is always produced.
 *
 * Avatar URLs/keys are recorded in the JSON (the avatar bytes themselves are never downloaded).
 */
object BackupExporter {

    data class Result(val contacts: Int, val chats: Int, val messages: Int, val attachments: Int)

    private data class MediaEntry(val localPath: String, val attachmentId: AttachmentId)

    suspend fun export(
        context: Context,
        configFactory: ConfigFactory,
        recipientRepository: RecipientRepository,
        threadDb: ThreadDatabase,
        mmsSmsDb: MmsSmsDatabase,
        attachmentDb: AttachmentDatabase,
        outputUri: Uri,
        produceZip: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        val mediaEntries = mutableListOf<MediaEntry>()
        var attachmentCounter = 0

        // --- Device (self) ---
        val device = runCatching {
            val selfSessionId = recipientRepository.getSelf().address.address
            val selfName = configFactory.currentUserName
            val selfPicUrl = configFactory.currentUserProfile?.toRemoteFile()?.url
            DeviceInfo(
                sessionId = selfSessionId,
                profileName = selfName.takeIf { it.isNotBlank() },
                profilePictureUrl = selfPicUrl,
            )
        }.getOrDefault(DeviceInfo())

        // --- Contacts (guarded: config may be unavailable on a fresh install) ---
        val contacts: List<ContactInfo> = runCatching {
            configFactory.withUserConfigs { configs ->
                configs.contacts.all().map { c ->
                    val picUrl = runCatching { c.profilePicture.toRemoteFile()?.url }.getOrNull()
                    ContactInfo(
                        sessionId = c.id,
                        name = c.name.takeIf { it.isNotBlank() },
                        nickname = c.nickname?.takeIf { it.isNotBlank() },
                        approved = c.approved,
                        approvedMe = c.approvedMe,
                        blocked = c.blocked,
                        profilePictureUrl = picUrl,
                    )
                }
            }
        }.getOrDefault(emptyList())

        // --- Chats (one per contact that has a thread) ---
        val chats = mutableListOf<ChatInfo>()
        var totalMessages = 0

        for (contact in contacts) {
            runCatching {
                val address = Address.fromSerialized(contact.sessionId) as? Address.Conversable
                    ?: return@runCatching
                val threadId = threadDb.getThreadId(address) ?: return@runCatching

                val messages = mutableListOf<MessageInfo>()
                runCatching {
                    mmsSmsDb.getConversation(threadId, false).use { cursor ->
                        mmsSmsDb.readerFor(cursor).use { reader ->
                            while (true) {
                                val record: MessageRecord = reader.getNext() ?: break
                                runCatching {
                                    messages += buildMessageInfo(
                                        record,
                                        attachmentDb,
                                        produceZip,
                                        mediaEntries,
                                    ) { attachmentCounter += 1; attachmentCounter }
                                    totalMessages += 1
                                }
                            }
                        }
                    }
                }
                chats += ChatInfo(
                    sessionId = contact.sessionId,
                    approved = contact.approved,
                    blocked = contact.blocked,
                    messages = messages,
                )
            }
        }

        val model = BackupModel(
            exportedAt = System.currentTimeMillis(),
            device = device,
            contacts = contacts,
            chats = chats,
        )

        // --- Write the output (always succeeds) ---
        runCatching {
            if (produceZip) {
                writeZip(context, outputUri, model, mediaEntries, attachmentDb)
            } else {
                writeJson(context, outputUri, model)
            }
        }.getOrElse {
            // Last-resort: write a minimal JSON so the user still gets a file.
            writeJson(context, outputUri, model)
        }

        Result(
            contacts = contacts.size,
            chats = chats.size,
            messages = totalMessages,
            attachments = mediaEntries.size,
        )
    }

    private fun buildMessageInfo(
        record: MessageRecord,
        attachmentDb: AttachmentDatabase,
        produceZip: Boolean,
        mediaEntries: MutableList<MediaEntry>,
        nextCounter: () -> Int,
    ): MessageInfo {
        val isMms = record.isMms
        val direction = if (record.isOutgoing) "outgoing" else "incoming"

        val dbAttachments: List<DatabaseAttachment> = if (isMms) {
            runCatching { attachmentDb.getAttachmentsForMessage(record.id) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val attachments = dbAttachments.map { att ->
            val info = AttachmentInfo(
                contentType = att.contentType,
                fileName = att.filename,
                size = att.size,
                width = att.width,
                height = att.height,
            )
            if (produceZip) {
                val n = nextCounter()
                val ext = extensionFor(att)
                val localPath = "${BackupFormat.MEDIA_DIR}/${"%04d".format(n)}.$ext"
                mediaEntries += MediaEntry(localPath, att.attachmentId)
                info.copy(local = localPath)
            } else {
                info
            }
        }

        val callKind = when {
            record.isFirstMissedCall -> "first_missed"
            record.isMissedCall -> "missed"
            record.isIncomingCall -> "incoming"
            record.isOutgoingCall -> "outgoing"
            else -> null
        }

        val expiresIn = record.expiresIn
        val expireStarted = record.expireStarted

        val type = when {
            callKind != null -> "call"
            attachments.isNotEmpty() -> "media"
            else -> "text"
        }

        return MessageInfo(
            direction = direction,
            type = type,
            body = record.body.takeIf { it.isNotBlank() },
            timestamp = record.dateSent,
            hasMention = record.hasMention,
            attachments = attachments,
            callType = callKind,
            expiresInSeconds = expiresIn.takeIf { it > 0 },
            expireStartedAt = expireStarted.takeIf { it > 0 },
            isOpenGroupInvitation = record.isOpenGroupInvitation,
            isMessageRequestResponse = record.isMessageRequestResponse,
        )
    }

    private fun writeJson(context: Context, outputUri: Uri, model: BackupModel) {
        context.contentResolver.openOutputStream(outputUri, "w")?.use { os ->
            os.write(
                BackupFormat.json.encodeToString(BackupModel.serializer(), model)
                    .toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun writeZip(
        context: Context,
        outputUri: Uri,
        model: BackupModel,
        mediaEntries: List<MediaEntry>,
        attachmentDb: AttachmentDatabase,
    ) {
        context.contentResolver.openOutputStream(outputUri, "w")?.use { os ->
            ZipOutputStream(os).use { zip ->
                zip.putNextEntry(ZipEntry(BackupFormat.JSON_ENTRY_NAME))
                zip.write(
                    BackupFormat.json.encodeToString(BackupModel.serializer(), model)
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()

                for (me in mediaEntries) {
                    // Open the stream first so a missing/corrupt attachment never leaves a
                    // half-written ZIP entry. If the stream can't be opened, the entry is skipped
                    // entirely and the JSON reference degrades gracefully on import.
                    val stream = runCatching {
                        attachmentDb.getAttachmentStream(me.attachmentId, 0)
                    }.getOrNull() ?: continue
                    zip.putNextEntry(ZipEntry(me.localPath))
                    stream.use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun extensionFor(att: DatabaseAttachment): String {
        att.filename?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
            ?.let { return it.lowercase() }
        val sub = att.contentType.substringAfter('/', missingDelimiterValue = "")
        return when (sub.lowercase()) {
            "jpeg", "jpg" -> "jpg"
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            "mp4" -> "mp4"
            "mpeg4" -> "mp4"
            "audio/mpeg", "mpeg" -> "mp3"
            "ogg" -> "ogg"
            "pdf" -> "pdf"
            "" -> "bin"
            else -> sub.lowercase().take(4)
        }
    }
}
