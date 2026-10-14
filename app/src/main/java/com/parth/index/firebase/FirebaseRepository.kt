package com.parth.index.firebase

import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.SubjectEntity
import com.parth.index.data.repository.IndexRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * STUB — NOT WIRED IN, DO NOT INSTANTIATE.
 *
 * This is where the shared cloud backend plugs in. It implements the exact
 * same [IndexRepository] interface as LocalRepository, so when it is ready,
 * switching the whole app from "one phone" to "the whole class" is a
 * one-line change in IndexApp (repository = FirebaseRepository(...)).
 *
 * It compiles today because every method is a safe no-op / empty flow and
 * there are NO Firebase imports anywhere in it. Follow FIREBASE_SETUP.md
 * (project root) step by step to turn it into the real thing:
 *
 *  1. Auth (Google sign-in)        -> who the user is; replaces Prefs role.
 *  2. Cloud Firestore collections  -> classes/{classId}/subjects, files,
 *                                     deadlines, chat, notices, requests.
 *  3. Cloud Storage                -> the actual PDF/image bytes; the
 *                                     Firestore "files" document keeps the
 *                                     download URL in localPath.
 *  4. Security rules               -> members of the class only; subject
 *                                     delete restricted to the owner;
 *                                     deletes are soft (Trash) everywhere.
 */
class FirebaseRepository : IndexRepository {

    private fun notWired(): Nothing =
        throw IllegalStateException("FirebaseRepository is a stub. See FIREBASE_SETUP.md.")

    override fun observeSubjects(): Flow<List<SubjectEntity>> = flowOf(emptyList())
    override fun observeTrashedSubjects(): Flow<List<SubjectEntity>> = flowOf(emptyList())
    override suspend fun subjectById(id: Long): SubjectEntity? = null
    override suspend fun addSubject(name: String, colorHex: String): Long = notWired()
    override suspend fun renameSubject(id: Long, name: String) = notWired()
    override suspend fun deleteSubject(id: Long) = notWired()
    override suspend fun restoreSubject(id: Long) = notWired()
    override suspend fun deleteSubjectPermanently(id: Long) = notWired()

    override fun observeFiles(): Flow<List<MaterialFileEntity>> = flowOf(emptyList())
    override fun observeFilesForSubject(subjectId: Long): Flow<List<MaterialFileEntity>> =
        flowOf(emptyList())
    override fun observeTrashedFiles(): Flow<List<MaterialFileEntity>> = flowOf(emptyList())
    override suspend fun fileById(id: Long): MaterialFileEntity? = null
    override suspend fun fileCountForSubject(subjectId: Long): Int = 0
    override suspend fun findDuplicates(
        subjectId: Long, fileName: String, sizeBytes: Long
    ): List<MaterialFileEntity> = emptyList()
    override suspend fun addFile(file: MaterialFileEntity): Long = notWired()
    override suspend fun updateFile(file: MaterialFileEntity) = notWired()
    override suspend fun deleteFile(id: Long) = notWired()
    override suspend fun restoreFile(id: Long) = notWired()
    override suspend fun deleteFilePermanently(id: Long) = notWired()
    override suspend fun totalStoredBytes(): Long = 0

    override fun observeDeadlines(): Flow<List<DeadlineEntity>> = flowOf(emptyList())
    override suspend fun addDeadline(deadline: DeadlineEntity): Long = notWired()
    override suspend fun updateDeadline(deadline: DeadlineEntity) = notWired()
    override suspend fun deleteDeadline(id: Long) = notWired()

    override fun observeChat(): Flow<List<ChatMessageEntity>> = flowOf(emptyList())
    override suspend fun sendChat(text: String, senderName: String, senderRole: String): Long =
        notWired()
    override suspend fun postUploadNotice(file: MaterialFileEntity, subjectName: String) =
        notWired()
    override suspend fun updateChatMessage(message: ChatMessageEntity) = notWired()

    override fun observeNotices(): Flow<List<NoticeEntity>> = flowOf(emptyList())
    override suspend fun addNotice(notice: NoticeEntity): Long = notWired()
    override suspend fun markNoticesSeen() = Unit

    override fun observeRequests(): Flow<List<MaterialRequestEntity>> = flowOf(emptyList())
    override suspend fun requestById(id: Long): MaterialRequestEntity? = null
    override suspend fun addRequest(request: MaterialRequestEntity): Long = notWired()
    override suspend fun fulfilRequest(requestId: Long, fileId: Long, fulfilledBy: String) =
        notWired()

    override suspend fun completeSetup(className: String, subjects: List<Pair<String, String>>) =
        notWired()
}
