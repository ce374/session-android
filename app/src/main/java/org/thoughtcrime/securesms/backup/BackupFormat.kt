package org.thoughtcrime.securesms.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk backup format for the Session conference-demo "Additional (Backup Import / Export)" feature.
 *
 * A backup is either:
 *   - a standalone JSON file ([BackupModel] pretty-printed), where attachments are referenced by
 *     remote [AttachmentInfo.url] only, OR
 *   - a ZIP whose first entry is [JSON_ENTRY_NAME] ([BackupModel]) followed by binary entries under
 *     [MEDIA_DIR]/ and [AVATARS_DIR]/. In a ZIP, [AttachmentInfo.local] / [ContactInfo.profilePictureLocal]
 *     hold the relative path of the bundled binary file.
 *
 * The format is intentionally human-readable ("complete clean json") so the exported state is easy to
 * inspect and hand-edit for the conference demo.
 */

object BackupFormat {
    const val FORMAT_ID = "session-demo-backup"
    const val VERSION = 1

    const val JSON_ENTRY_NAME = "backup.json"
    const val MEDIA_DIR = "media"
    const val AVATARS_DIR = "avatars"

    /** MIME type used when offering a destination via SAF CreateDocument. */
    const val MIME = "application/zip"

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
data class BackupModel(
    val format: String = BackupFormat.FORMAT_ID,
    val version: Int = BackupFormat.VERSION,
    val exportedAt: Long,
    val device: DeviceInfo = DeviceInfo(),
    val contacts: List<ContactInfo> = emptyList(),
    val chats: List<ChatInfo> = emptyList(),
)

@Serializable
data class DeviceInfo(
    /** This device's own Session ID (hex, 66 chars). null if not logged in. */
    val sessionId: String? = null,
    val profileName: String? = null,
    /** CDN / file-server URL of the profile avatar, if any. */
    val profilePictureUrl: String? = null,
    /** Base64 of the avatar encryption key, if any. */
    val profilePictureKey: String? = null,
    /** Relative path of the avatar inside the ZIP (e.g. "avatars/me.jpg"), if bundled locally. */
    val profilePictureLocal: String? = null,
)

@Serializable
data class ContactInfo(
    /** Contact Session ID (hex, 66 chars). */
    val sessionId: String,
    val name: String? = null,
    val nickname: String? = null,
    /** If false, the chat surfaces as a Message Request rather than in the conversation list. */
    val approved: Boolean = true,
    val approvedMe: Boolean = true,
    /** If true, the contact shows in Settings -> Blocked with the same UI as a real block. */
    val blocked: Boolean = false,
    val profilePictureUrl: String? = null,
    val profilePictureKey: String? = null,
    val profilePictureLocal: String? = null,
)

@Serializable
data class ChatInfo(
    val sessionId: String,
    val approved: Boolean = true,
    val blocked: Boolean = false,
    val messages: List<MessageInfo> = emptyList(),
)

@Serializable
data class MessageInfo(
    /** "incoming" or "outgoing". */
    val direction: String,
    /** "text" or "media". */
    val type: String,
    val body: String? = null,
    val timestamp: Long,
    val hasMention: Boolean = false,
    val attachments: List<AttachmentInfo> = emptyList(),
)

@Serializable
data class AttachmentInfo(
    val contentType: String,
    val fileName: String? = null,
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** Relative path inside the ZIP, e.g. "media/0001.jpg". Null for JSON-only backups. */
    val local: String? = null,
    /** Remote URL. Used when there is no bundled local file. */
    val url: String? = null,
)
