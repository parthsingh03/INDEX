package com.parth.index.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Roles in the class. The Owner runs the class, Admins help manage it,
 *  Members just use it. Stored as plain text so the database stays readable. */
object Roles {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MEMBER = "MEMBER"

    /** Owner and Admin together are "the admins" in everyday language. */
    fun canManage(role: String): Boolean = role == OWNER || role == ADMIN
}

/** A subject folder, e.g. "Mathematics". Soft delete: [deleted] hides it and
 *  [deletedAt] starts the 30-day Trash clock — nothing is wiped instantly. */
@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String,
    val createdAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val deletedAt: Long = 0
)

/** One study-material file inside a subject. The actual file bytes are copied
 *  into the app's private storage; [localPath] points at that copy. Sample /
 *  demo rows have an empty [localPath] and cannot be previewed.
 *  [note] is the uploader's own caption — the ONLY text attached to a file.
 *  There are deliberately no comments on files. */
@Entity(tableName = "files")
data class MaterialFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val title: String,
    val note: String = "",
    val tags: String = "",
    val fileName: String,
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val localPath: String = "",
    val uploaderName: String,
    val uploaderRole: String = Roles.MEMBER,
    val createdAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Long = 0,
    /** Set when this file fulfilled a material request. */
    val requestId: Long = 0
)

/** A deadline card shown on Home. Days-left is never stored — it is always
 *  worked out from [dueDateMillis] so the countdown is never stale. */
@Entity(tableName = "deadlines")
data class DeadlineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subjectName: String = "",
    val dueDateMillis: Long,
    val createdBy: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** One group-chat message. Delete-for-everyone is a tombstone: the text is
 *  replaced and [deletedForEveryone] set, so every phone shows
 *  "This message was deleted" — the original words are gone.
 *  [hiddenForMe] is delete-for-me: only this phone hides it.
 *  [systemFileId] > 0 marks the automatic "X added <file> to <subject>"
 *  message posted on every upload; tapping it opens that file — and it
 *  renders as a file CARD, never as a plain text bubble.
 *  v1.4 chat pack: [senderUid] is the sender's account id (cloud mode;
 *  empty in local mode) — reactions, the unread count and the member
 *  profile card all key off it. [reactionsJson] holds the reaction map
 *  as JSON ({"👍":["uid1","uid2"]}) so the Room entity stays a plain
 *  string column; Firestore stores the same data as a real map and
 *  FirebaseRepository translates. [replyToId] > 0 marks a quote-reply:
 *  [replyToSender] / [replyToText] are the quoted snapshot shown in the
 *  bubble even if the original scrolls away. */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderName: String,
    val senderRole: String = Roles.MEMBER,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deletedForEveryone: Boolean = false,
    val hiddenForMe: Boolean = false,
    val systemFileId: Long = 0,
    val senderUid: String = "",
    val reactionsJson: String = "",
    val replyToId: Long = 0,
    val replyToSender: String = "",
    val replyToText: String = ""
)

/** An admin-only notice. Members can read, never post.
 *  [imageUrl] (v1.5) is an optional photo on the notice — a Cloudinary
 *  URL, empty when the notice is text-only. Video stays on WhatsApp by
 *  Parth's call; this is photos only. */
@Entity(tableName = "notices")
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val authorName: String,
    val authorRole: String = Roles.ADMIN,
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val seenByCount: Int = 0,
    val seenByMe: Boolean = false,
    val imageUrl: String = ""
)

/** "Please upload X" on the Request board. Fulfil = upload a file against
 *  it, which closes it and records who fulfilled it. */
@Entity(tableName = "requests")
data class MaterialRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subjectId: Long = 0,
    val subjectName: String = "",
    val requesterName: String,
    val status: String = STATUS_OPEN,
    val fulfilledBy: String = "",
    val fulfilledFileId: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_FULFILLED = "FULFILLED"
    }
}
