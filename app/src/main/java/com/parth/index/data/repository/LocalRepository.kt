package com.parth.index.data.repository

import com.parth.index.data.local.AppDatabase
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.Roles
import com.parth.index.data.model.SubjectEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

/** Local mode: everything lives in the Room database on this one phone.
 *  Fully functional for the owner to use and test solo, and for friends to
 *  try the whole flow on their own phones. Shared class data arrives when
 *  FirebaseRepository takes over this same interface. */
class LocalRepository(private val db: AppDatabase) : IndexRepository {

    private companion object {
        const val LOCAL_UID = "local-user"
        const val REPLY_SNIPPET_MAX = 120
    }

    // ---- Subjects ------------------------------------------------------------
    override fun observeSubjects(): Flow<List<SubjectEntity>> = db.subjectDao().observeAll()
    override fun observeTrashedSubjects(): Flow<List<SubjectEntity>> = db.subjectDao().observeTrashed()
    override suspend fun subjectById(id: Long): SubjectEntity? = db.subjectDao().byId(id)

    override suspend fun addSubject(name: String, colorHex: String): Long =
        db.subjectDao().insert(SubjectEntity(name = name, colorHex = colorHex))

    override suspend fun renameSubject(id: Long, name: String) {
        db.subjectDao().byId(id)?.let { db.subjectDao().update(it.copy(name = name)) }
    }

    override suspend fun deleteSubject(id: Long) =
        db.subjectDao().softDelete(id, System.currentTimeMillis())

    override suspend fun restoreSubject(id: Long) = db.subjectDao().restore(id)
    override suspend fun deleteSubjectPermanently(id: Long) = db.subjectDao().deletePermanently(id)

    // ---- Files ---------------------------------------------------------------
    override fun observeFiles(): Flow<List<MaterialFileEntity>> = db.fileDao().observeAll()
    override fun observeFilesForSubject(subjectId: Long): Flow<List<MaterialFileEntity>> =
        db.fileDao().observeForSubject(subjectId)
    override fun observeTrashedFiles(): Flow<List<MaterialFileEntity>> = db.fileDao().observeTrashed()
    override suspend fun fileById(id: Long): MaterialFileEntity? = db.fileDao().byId(id)
    override suspend fun fileCountForSubject(subjectId: Long): Int =
        db.fileDao().countForSubject(subjectId)

    override suspend fun findDuplicates(
        subjectId: Long, fileName: String, sizeBytes: Long
    ): List<MaterialFileEntity> = db.fileDao().sameNameAndSize(subjectId, fileName, sizeBytes)

    override suspend fun addFile(file: MaterialFileEntity): Long = db.fileDao().insert(file)
    override suspend fun updateFile(file: MaterialFileEntity) = db.fileDao().update(file)
    override suspend fun deleteFile(id: Long) =
        db.fileDao().softDelete(id, System.currentTimeMillis())
    override suspend fun restoreFile(id: Long) = db.fileDao().restore(id)
    override suspend fun deleteFilePermanently(id: Long) = db.fileDao().deletePermanently(id)
    override suspend fun totalStoredBytes(): Long = db.fileDao().totalStoredBytes()

    // ---- Deadlines -------------------------------------------------------------
    override fun observeDeadlines(): Flow<List<DeadlineEntity>> = db.deadlineDao().observeAll()
    override suspend fun addDeadline(deadline: DeadlineEntity): Long = db.deadlineDao().insert(deadline)
    override suspend fun updateDeadline(deadline: DeadlineEntity) = db.deadlineDao().update(deadline)
    override suspend fun deleteDeadline(id: Long) = db.deadlineDao().delete(id)

    // ---- Chat ------------------------------------------------------------------
    override fun observeChat(): Flow<List<ChatMessageEntity>> = db.chatDao().observeAll()

    override suspend fun sendChat(
        text: String,
        senderName: String,
        senderRole: String,
        replyTo: ChatMessageEntity?
    ): Long = db.chatDao().insert(
        ChatMessageEntity(
            senderName = senderName,
            senderRole = senderRole,
            text = text,
            replyToId = replyTo?.id ?: 0,
            replyToSender = replyTo?.senderName.orEmpty(),
            replyToText = replyTo?.text.orEmpty().take(REPLY_SNIPPET_MAX)
        )
    )

    /** Local mode has no accounts, so reactions key off one fixed pseudo
     *  uid — enough for the owner to try the whole flow on one phone. */
    override suspend fun toggleReaction(messageId: Long, emoji: String) {
        val message = db.chatDao().byId(messageId) ?: return
        val next = com.parth.index.util.Reactions.toggled(
            com.parth.index.util.Reactions.parse(message.reactionsJson), emoji, LOCAL_UID
        )
        db.chatDao().update(
            message.copy(reactionsJson = com.parth.index.util.Reactions.serialize(next))
        )
    }

    override suspend fun clearChat() = db.chatDao().clearAll()

    override suspend fun clearAllContent() {
        db.chatDao().clearAll()
        db.fileDao().clearAll()
        db.noticeDao().clearAll()
        db.deadlineDao().clearAll()
    }

    override suspend fun postUploadNotice(file: MaterialFileEntity, subjectName: String) {
        if (file.id == 0L) return
        db.chatDao().insert(
            ChatMessageEntity(
                senderName = file.uploaderName,
                senderRole = file.uploaderRole,
                text = "${file.uploaderName} added ${file.title} to $subjectName",
                systemFileId = file.id
            )
        )
    }

    override suspend fun updateChatMessage(message: ChatMessageEntity) = db.chatDao().update(message)

    // ---- Notices -----------------------------------------------------------------
    override fun observeNotices(): Flow<List<NoticeEntity>> = db.noticeDao().observeAll()
    override suspend fun addNotice(notice: NoticeEntity): Long = db.noticeDao().insert(notice)
    override suspend fun markNoticesSeen() = db.noticeDao().markAllSeen()

    // ---- Requests ------------------------------------------------------------------
    override fun observeRequests(): Flow<List<MaterialRequestEntity>> = db.requestDao().observeAll()
    override suspend fun requestById(id: Long): MaterialRequestEntity? = db.requestDao().byId(id)

    override suspend fun addRequest(request: MaterialRequestEntity): Long =
        db.requestDao().insert(request)

    override suspend fun fulfilRequest(requestId: Long, fileId: Long, fulfilledBy: String) {
        db.requestDao().byId(requestId)?.let {
            db.requestDao().update(
                it.copy(
                    status = MaterialRequestEntity.STATUS_FULFILLED,
                    fulfilledBy = fulfilledBy,
                    fulfilledFileId = fileId
                )
            )
        }
    }

    // ---- First-run setup + starter content -----------------------------------------
    override suspend fun completeSetup(className: String, subjects: List<Pair<String, String>>) {
        if (db.subjectDao().countAll() > 0) return
        val now = System.currentTimeMillis()
        val ids = HashMap<String, Long>()
        for ((name, color) in subjects) {
            ids[name] = db.subjectDao().insert(SubjectEntity(name = name, colorHex = color))
        }
        val mathsId = ids["Mathematics"] ?: ids.values.firstOrNull() ?: return
        val mechId = ids["Mechanical Engg."] ?: mathsId

        // The deadline from the design mockup: Friday, 16 October 2026.
        val due = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 16, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        db.deadlineDao().insert(
            DeadlineEntity(
                title = "Mechanical assignment",
                subjectName = "Mechanical Engg.",
                dueDateMillis = due,
                createdBy = "Parth Singh"
            )
        )

        db.noticeDao().insert(
            NoticeEntity(
                authorName = "Parth Singh", authorRole = Roles.OWNER,
                title = "Physics lab record submission",
                body = "Bring your completed lab record on Monday. No submissions will be accepted after the viva starts.",
                timestamp = now - 3_600_000L, seenByCount = 41
            )
        )
        db.noticeDao().insert(
            NoticeEntity(
                authorName = "Parth Singh", authorRole = Roles.OWNER,
                title = "Welcome to INDEX",
                body = "All class study material now lives here, inside subject folders. If something is on WhatsApp, share it into INDEX so nobody has to hunt for it again.",
                timestamp = now - 86_400_000L, seenByCount = 41
            )
        )

        db.fileDao().insert(
            MaterialFileEntity(
                subjectId = mathsId,
                title = "Laplace Transform Notes.pdf",
                note = "Unit 3 full notes, examples included",
                tags = "unit 3, laplace, notes",
                fileName = "Laplace Transform Notes.pdf",
                mimeType = "application/pdf",
                sizeBytes = 1_258_291L,
                uploaderName = "Aman Gupta", uploaderRole = Roles.ADMIN,
                createdAt = now - 2 * 3_600_000L, pinned = true
            )
        )
        db.fileDao().insert(
            MaterialFileEntity(
                subjectId = mechId,
                title = "Mechanical Project — Final.pdf",
                note = "Final project report, as submitted",
                tags = "project, report",
                fileName = "Mechanical Project — Final.pdf",
                mimeType = "application/pdf",
                sizeBytes = 5_033_164L,
                uploaderName = "Rohit Sharma", uploaderRole = Roles.MEMBER,
                createdAt = now - 86_400_000L
            )
        )

        db.chatDao().insert(
            ChatMessageEntity(
                senderName = "Aman Gupta", senderRole = Roles.ADMIN,
                text = "Maths Unit 3 notes are in the folder now, pinned at the top.",
                timestamp = now - 2 * 3_600_000L
            )
        )
        db.chatDao().insert(
            ChatMessageEntity(
                senderName = "Sneha", senderRole = Roles.MEMBER,
                text = "Thanks! Did anyone get the mechanical project report?",
                timestamp = now - 3_600_000L
            )
        )

        db.requestDao().insert(
            MaterialRequestEntity(
                title = "Thermodynamics handwritten notes",
                subjectId = mechId, subjectName = "Mechanical Engg.",
                requesterName = "Sneha"
            )
        )
    }
}
