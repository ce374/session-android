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
import org.thoughtcrime.securesms.database.ConfigFactory
import org.thoughtcrime.securesms.database.MmsSmsDatabase
import org.thoughtcrime.securesms.database.RecipientRepository
import org.thoughtcrime.securesms.database.ThreadDatabase
import org.thoughtcrime.securesms.database.getThreadId
import org.thoughtcrime.securesms.database.model.MessageRecord
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the current on-device state (device profile, contacts, chats, messages, attachments) into a
 * ZIP backup at [outputUri]. The ZIP's first entry is [BackupFormat.JSON_ENTRY_NAME] (a clean,
 * human-readable [BackupModel]); every attachment is then streamed as a binary entry under
 * [BackupFormat.MEDIA_DIR]/.
 *
 * Avatar URLs/keys are recorded in the JSON (not downloaded), so the export never touches the network.
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
    ): Result = withContext(Dispatchers.IO) {
        val mediaEntries = mutableListOf<MediaEntry>()
        var attachmentCounter = 0

        // --- Device (self) ---
        val selfSessionId = runCatching { recipientRepository.getSelf().address.address }.getOrNull()
        val selfName = runCatching { configFactory.currentUserName }.getOrDefault("")
        val selfPicUrl = runCatching { configFactory.currentUserProfile?.toRemoteFile()?.url }.getOrNull()
        val device = DeviceInfo(
            sessionId = selfSessionId,
            profileName = selfName.takeIf { it.isNotBlank() },
            profilePictureUrl = selfPicUrl,
        )

        // --- Contacts ---
        val contacts: List<ContactInfo> = configFactory.withUserConfigs { configs ->
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

        // --- Chats (one per contact that has a thread) ---
        val chats = mutableListOf<ChatInfo>()
        var totalMessages = 0

        for (contact in contacts) {
            val address = Address.fromSerialized(contact.sessionId) as? Address.Conversable ?: continue
            val threadId = threadDb.getThreadId(address) ?: continue

            val messages = mutableListOf<MessageInfo>()
            mmsSmsDb.getConversation(threadId, false).use { cursor ->
                mmsSmsDb.readerFor(cursor).use { reader ->
                    while (true) {
                        val record: MessageRecord = reader.getNext() ?: break
                        val isMms = record.isMms()
                        val direction = if (record.isOutgoing()) "outgoing" else "incoming"

                        val attachmentList: List<DatabaseAttachment> =
                            if (isMms) attachmentDb.getAttachmentsForMessage(record.getId())
                            else emptyList()

                        val attachments = attachmentList.mapNotNull { att ->
                            val aid: AttachmentId = att.attachmentId
                            attachmentCounter += 1
                            val ext = extensionFor(att)
                            val localPath = "${BackupFormat.MEDIA_DIR}/${"%04d".format(attachmentCounter)}.$ext"
                            mediaEntries += MediaEntry(localPath, aid)
                            AttachmentInfo(
                                contentType = att.contentType,
                                fileName = att.fileName,
                                size = att.size,
                                width = att.width,
                                height = att.height,
                                local = localPath,
                            )
                        }

                        val type = if (attachments.isNotEmpty()) "media" else "text"
                        messages += MessageInfo(
                            direction = direction,
                            type = type,
                            body = record.body.takeIf { it.isNotBlank() },
                            timestamp = record.dateSent,
                            hasMention = false,
                            attachments = attachments,
                        )
                        totalMessages += 1
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

        val model = BackupModel(
            exportedAt = System.currentTimeMillis(),
            device = device,
            contacts = contacts,
            chats = chats,
        )

        // --- Write the ZIP ---
        context.contentResolver.openOutputStream(outputUri, "w")?.use { os ->
            ZipOutputStream(os).use { zip ->
                zip.putNextEntry(ZipEntry(BackupFormat.JSON_ENTRY_NAME))
                zip.write(
                    BackupFormat.json.encodeToString(BackupModel.serializer(), model)
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()

                for (me in mediaEntries) {
                    zip.putNextEntry(ZipEntry(me.localPath))
                    attachmentDb.getAttachmentStream(me.attachmentId, 0).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to open output stream for $outputUri")

        Result(
            contacts = contacts.size,
            chats = chats.size,
            messages = totalMessages,
            attachments = mediaEntries.size,
        )
    }

    private fun extensionFor(att: DatabaseAttachment): String {
        att.fileName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
            ?.let { return it.lowercase() }
        // Derive from content type, e.g. "image/jpeg" -> "jpg"
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
