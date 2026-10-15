package com.parth.index.data.repository

import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.SubjectEntity
import kotlinx.coroutines.flow.Flow

/** Every screen talks ONLY to this interface — never to a database or a
 *  network call directly. Today [LocalRepository] answers everything from
 *  the on-phone Room database. When the Firebase project is ready,
 *  FirebaseRepository (see the firebase package + FIREBASE_SETUP.md)
 *  implements this same interface against Firestore/Storage and the screens
 *  do not change at all. That is the whole point of the interface. */
interface IndexRepository {

    // Subjects
    fun observeSubjects(): Flow<List<SubjectEntity>>
    fun observeTrashedSubjects(): Flow<List<SubjectEntity>>
    suspend fun subjectById(id: Long): SubjectEntity?
    suspend fun addSubject(name: String, colorHex: String): Long
    suspend fun renameSubject(id: Long, name: String)
    suspend fun deleteSubject(id: Long)            // soft delete -> Trash
    suspend fun restoreSubject(id: Long)
    suspend fun deleteSubjectPermanently(id: Long)

    // Files / material
    fun observeFiles(): Flow<List<MaterialFileEntity>>
    fun observeFilesForSubject(subjectId: Long): Flow<List<MaterialFileEntity>>
    fun observeTrashedFiles(): Flow<List<MaterialFileEntity>>
    suspend fun fileById(id: Long): MaterialFileEntity?
    suspend fun fileCountForSubject(subjectId: Long): Int
    suspend fun findDuplicates(subjectId: Long, fileName: String, sizeBytes: Long): List<MaterialFileEntity>
    suspend fun addFile(file: MaterialFileEntity): Long
    suspend fun updateFile(file: MaterialFileEntity)
    suspend fun deleteFile(id: Long)               // soft delete -> Trash
    suspend fun restoreFile(id: Long)
    suspend fun deleteFilePermanently(id: Long)
    suspend fun totalStoredBytes(): Long

    // Deadlines
    fun observeDeadlines(): Flow<List<DeadlineEntity>>
    suspend fun addDeadline(deadline: DeadlineEntity): Long
    suspend fun updateDeadline(deadline: DeadlineEntity)
    suspend fun deleteDeadline(id: Long)

    // Chat
    fun observeChat(): Flow<List<ChatMessageEntity>>
    /** [replyTo], when given, makes this a quote-reply: the message stores
     *  a snapshot of the original (sender + text) alongside its own text. */
    suspend fun sendChat(
        text: String,
        senderName: String,
        senderRole: String,
        replyTo: ChatMessageEntity? = null
    ): Long

    suspend fun postUploadNotice(file: MaterialFileEntity, subjectName: String)
    suspend fun updateChatMessage(message: ChatMessageEntity)

    /** Flip the signed-in user's [emoji] reaction on one message (v1.4). */
    suspend fun toggleReaction(messageId: Long, emoji: String)

    // Owner reset (v1.4). Both HARD-delete: the documents are gone, not
    // tombstoned — this is the "start the class from a clean slate" tool.
    // The Profile screen shows these only to the Owner, and firestore.rules
    // backs that up (chat/notices/deadline deletes are manager-only, file
    // deletes owner-or-uploader).
    /** Delete every chat message. Nothing else is touched. */
    suspend fun clearChat()

    /** Delete chat + files + notices + deadlines. Members and subjects
     *  are kept — the class stays enrolled, its content restarts empty. */
    suspend fun clearAllContent()

    // Notices
    fun observeNotices(): Flow<List<NoticeEntity>>
    suspend fun addNotice(notice: NoticeEntity): Long
    suspend fun markNoticesSeen()

    // Requests
    fun observeRequests(): Flow<List<MaterialRequestEntity>>
    suspend fun requestById(id: Long): MaterialRequestEntity?
    suspend fun addRequest(request: MaterialRequestEntity): Long
    suspend fun fulfilRequest(requestId: Long, fileId: Long, fulfilledBy: String)

    /** First-run setup: creates the class subjects and the starter demo
     *  content (one deadline, sample notices, chat messages, materials and
     *  one open request) so a fresh install is explorable immediately. */
    suspend fun completeSetup(className: String, subjects: List<Pair<String, String>>)
}
