package com.parth.index.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.SubjectEntity
import com.parth.index.data.repository.IndexRepository
import com.parth.index.util.Reactions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import java.io.File
import kotlin.random.Random

/**
 * Cloud mode: the whole class shares ONE Firestore document tree —
 * classes/index-class/{subjects,files,deadlines,chat,notices,requests} —
 * and the actual file bytes live on Cloudinary (the Firestore "files"
 * document keeps the Cloudinary URL in the entity's localPath field, the
 * same field local mode uses for the on-disk copy).
 *
 * ID SCHEME (one scheme, everywhere): an entity's Long id IS its Firestore
 * document id, rendered as a decimal string — doc "1728661234567890123" is
 * entity id 1728661234567890123L. Ids are minted here at insert time as
 * (currentTimeMillis * 1024 + random 0..1023), so they sort roughly by
 * creation time and two phones minting in the same millisecond still
 * collide only if they also roll the same 1-in-1024 suffix. Foreign keys
 * (a file's subjectId, a request's fulfilledFileId, a chat message's
 * systemFileId) are stored as plain numbers, exactly as Room stored them,
 * so every existing screen works unchanged.
 *
 * Per-user state on shared documents (a notice's seenByMe, a message's
 * hiddenForMe) lives in uid arrays on the document — seenByUids /
 * hiddenForUids — and is translated into the entity's per-user fields for
 * whoever is signed in on this phone.
 *
 * Semantics mirror LocalRepository method for method: soft deletes with
 * deletedAt (Trash + restore), pinned-first file lists, chat tombstones
 * (text wiped + deletedForEveryone), upload auto-posts, request fulfilment.
 * Hard delete (empty Trash) removes only the Firestore document — the
 * Cloudinary bytes behind it are NOT deleted (unsigned presets cannot
 * delete); the file is unlinked and unreachable from the app.
 */
class FirebaseRepository : IndexRepository {

    private companion object {
        const val REPLY_SNIPPET_MAX = 120
    }

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    private val myUid: String get() = auth.currentUser?.uid.orEmpty()
    private val myEmail: String get() = auth.currentUser?.email.orEmpty()

    private fun classRef() = db.collection("classes").document(CloudConfig.CLASS_ID)
    private fun subjectsRef() = classRef().collection("subjects")
    private fun filesRef() = classRef().collection("files")
    private fun deadlinesRef() = classRef().collection("deadlines")
    private fun chatRef() = classRef().collection("chat")
    private fun noticesRef() = classRef().collection("notices")
    private fun requestsRef() = classRef().collection("requests")

    private fun newId(): Long =
        System.currentTimeMillis() * 1024L + Random.nextInt(1024)

    // ---- Snapshot -> Flow plumbing -------------------------------------------

    private fun <T> CollectionReference.observeList(
        mapper: (QuerySnapshot) -> List<T>
    ): Flow<List<T>> = callbackFlow {
        val registration = addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) trySend(mapper(snapshot))
        }
        awaitClose { registration.remove() }
    }

    // ---- Document <-> entity mapping ------------------------------------------

    private fun DocumentSnapshot.toSubject(): SubjectEntity = SubjectEntity(
        id = id.toLongOrNull() ?: 0L,
        name = getString("name").orEmpty(),
        colorHex = getString("colorHex").orEmpty(),
        createdAt = getLong("createdAt") ?: 0L,
        deleted = getBoolean("deleted") ?: false,
        deletedAt = getLong("deletedAt") ?: 0L
    )

    private fun SubjectEntity.toMap(): Map<String, Any> = mapOf(
        "name" to name,
        "colorHex" to colorHex,
        "createdAt" to createdAt,
        "deleted" to deleted,
        "deletedAt" to deletedAt
    )

    private fun DocumentSnapshot.toFile(): MaterialFileEntity = MaterialFileEntity(
        id = id.toLongOrNull() ?: 0L,
        subjectId = getLong("subjectId") ?: 0L,
        title = getString("title").orEmpty(),
        note = getString("note").orEmpty(),
        tags = getString("tags").orEmpty(),
        fileName = getString("fileName").orEmpty(),
        mimeType = getString("mimeType").orEmpty(),
        sizeBytes = getLong("sizeBytes") ?: 0L,
        localPath = getString("localPath").orEmpty(),
        uploaderName = getString("uploaderName").orEmpty(),
        uploaderRole = getString("uploaderRole") ?: "MEMBER",
        createdAt = getLong("createdAt") ?: 0L,
        pinned = getBoolean("pinned") ?: false,
        deleted = getBoolean("deleted") ?: false,
        deletedAt = getLong("deletedAt") ?: 0L,
        requestId = getLong("requestId") ?: 0L
    )

    /** Fields written on create. The uploader's uid rides along (not part
     *  of the entity) so security rules can tell "the uploader" apart. */
    private fun MaterialFileEntity.toCreateMap(uploaderUid: String): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "title" to title,
        "note" to note,
        "tags" to tags,
        "fileName" to fileName,
        "mimeType" to mimeType,
        "sizeBytes" to sizeBytes,
        "localPath" to localPath,
        "uploaderName" to uploaderName,
        "uploaderRole" to uploaderRole,
        "uploaderUid" to uploaderUid,
        "createdAt" to createdAt,
        "pinned" to pinned,
        "deleted" to deleted,
        "deletedAt" to deletedAt,
        "requestId" to requestId
    )

    /** Fields an update may touch. localPath and uploaderUid are set once
     *  at creation and deliberately excluded here. */
    private fun MaterialFileEntity.toUpdateMap(): Map<String, Any> = mapOf(
        "subjectId" to subjectId,
        "title" to title,
        "note" to note,
        "tags" to tags,
        "fileName" to fileName,
        "mimeType" to mimeType,
        "sizeBytes" to sizeBytes,
        "uploaderName" to uploaderName,
        "uploaderRole" to uploaderRole,
        "createdAt" to createdAt,
        "pinned" to pinned,
        "deleted" to deleted,
        "deletedAt" to deletedAt,
        "requestId" to requestId
    )

    private fun DocumentSnapshot.toDeadline(): DeadlineEntity = DeadlineEntity(
        id = id.toLongOrNull() ?: 0L,
        title = getString("title").orEmpty(),
        subjectName = getString("subjectName").orEmpty(),
        dueDateMillis = getLong("dueDateMillis") ?: 0L,
        createdBy = getString("createdBy").orEmpty(),
        createdAt = getLong("createdAt") ?: 0L
    )

    private fun DeadlineEntity.toMap(): Map<String, Any> = mapOf(
        "title" to title,
        "subjectName" to subjectName,
        "dueDateMillis" to dueDateMillis,
        "createdBy" to createdBy,
        "createdAt" to createdAt
    )

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun DocumentSnapshot.toChatMessage(): ChatMessageEntity {
        val hiddenFor = stringList("hiddenForUids")
        return ChatMessageEntity(
            id = id.toLongOrNull() ?: 0L,
            senderName = getString("senderName").orEmpty(),
            senderRole = getString("senderRole") ?: "MEMBER",
            text = getString("text").orEmpty(),
            timestamp = getLong("timestamp") ?: 0L,
            deletedForEveryone = getBoolean("deletedForEveryone") ?: false,
            hiddenForMe = hiddenFor.contains(myUid),
            systemFileId = getLong("systemFileId") ?: 0L,
            senderUid = getString("senderUid").orEmpty(),
            reactionsJson = Reactions.serialize(Reactions.fromFirestore(get("reactions"))),
            replyToId = getLong("replyToId") ?: 0L,
            replyToSender = getString("replyToSender").orEmpty(),
            replyToText = getString("replyToText").orEmpty()
        )
    }

    private fun DocumentSnapshot.toNotice(): NoticeEntity {
        val seenBy = stringList("seenByUids")
        return NoticeEntity(
            id = id.toLongOrNull() ?: 0L,
            authorName = getString("authorName").orEmpty(),
            authorRole = getString("authorRole") ?: "ADMIN",
            title = getString("title").orEmpty(),
            body = getString("body").orEmpty(),
            timestamp = getLong("timestamp") ?: 0L,
            seenByCount = seenBy.size,
            seenByMe = seenBy.contains(myUid)
        )
    }

    private fun DocumentSnapshot.toRequest(): MaterialRequestEntity = MaterialRequestEntity(
        id = id.toLongOrNull() ?: 0L,
        title = getString("title").orEmpty(),
        subjectId = getLong("subjectId") ?: 0L,
        subjectName = getString("subjectName").orEmpty(),
        requesterName = getString("requesterName").orEmpty(),
        status = getString("status") ?: MaterialRequestEntity.STATUS_OPEN,
        fulfilledBy = getString("fulfilledBy").orEmpty(),
        fulfilledFileId = getLong("fulfilledFileId") ?: 0L,
        createdAt = getLong("createdAt") ?: 0L
    )

    private fun MaterialRequestEntity.toMap(requesterUid: String): Map<String, Any> = mapOf(
        "title" to title,
        "subjectId" to subjectId,
        "subjectName" to subjectName,
        "requesterName" to requesterName,
        "requesterUid" to requesterUid,
        "status" to status,
        "fulfilledBy" to fulfilledBy,
        "fulfilledFileId" to fulfilledFileId,
        "createdAt" to createdAt
    )

    // ---- Subjects ---------------------------------------------------------------

    override fun observeSubjects(): Flow<List<SubjectEntity>> =
        subjectsRef().observeList { snap ->
            snap.documents.map { it.toSubject() }
                .filter { !it.deleted }
                .sortedBy { it.name }
        }

    override fun observeTrashedSubjects(): Flow<List<SubjectEntity>> =
        subjectsRef().observeList { snap ->
            snap.documents.map { it.toSubject() }
                .filter { it.deleted }
                .sortedByDescending { it.deletedAt }
        }

    override suspend fun subjectById(id: Long): SubjectEntity? {
        val doc = subjectsRef().document(id.toString()).get().awaitTask()
        return if (doc.exists()) doc.toSubject() else null
    }

    override suspend fun addSubject(name: String, colorHex: String): Long {
        val id = newId()
        val subject = SubjectEntity(id = id, name = name, colorHex = colorHex)
        subjectsRef().document(id.toString()).set(subject.toMap()).awaitTask()
        return id
    }

    override suspend fun renameSubject(id: Long, name: String) {
        subjectsRef().document(id.toString()).update("name", name).awaitTask()
    }

    override suspend fun deleteSubject(id: Long) {
        subjectsRef().document(id.toString())
            .update(
                mapOf(
                    "deleted" to true,
                    "deletedAt" to System.currentTimeMillis()
                )
            ).awaitTask()
    }

    override suspend fun restoreSubject(id: Long) {
        subjectsRef().document(id.toString())
            .update(mapOf("deleted" to false, "deletedAt" to 0L)).awaitTask()
    }

    override suspend fun deleteSubjectPermanently(id: Long) {
        subjectsRef().document(id.toString()).delete().awaitTask()
    }

    // ---- Files --------------------------------------------------------------------

    override fun observeFiles(): Flow<List<MaterialFileEntity>> =
        filesRef().observeList { snap ->
            snap.documents.map { it.toFile() }
                .filter { !it.deleted }
                .sortedWith(
                    compareByDescending<MaterialFileEntity> { it.pinned }
                        .thenByDescending { it.createdAt }
                )
        }

    override fun observeFilesForSubject(subjectId: Long): Flow<List<MaterialFileEntity>> =
        filesRef().observeList { snap ->
            snap.documents.map { it.toFile() }
                .filter { !it.deleted && it.subjectId == subjectId }
                .sortedWith(
                    compareByDescending<MaterialFileEntity> { it.pinned }
                        .thenByDescending { it.createdAt }
                )
        }

    override fun observeTrashedFiles(): Flow<List<MaterialFileEntity>> =
        filesRef().observeList { snap ->
            snap.documents.map { it.toFile() }
                .filter { it.deleted }
                .sortedByDescending { it.deletedAt }
        }

    override suspend fun fileById(id: Long): MaterialFileEntity? {
        val doc = filesRef().document(id.toString()).get().awaitTask()
        return if (doc.exists()) doc.toFile() else null
    }

    override suspend fun fileCountForSubject(subjectId: Long): Int {
        val snap = filesRef().get().awaitTask()
        return snap.documents.map { it.toFile() }
            .count { !it.deleted && it.subjectId == subjectId }
    }

    override suspend fun findDuplicates(
        subjectId: Long, fileName: String, sizeBytes: Long
    ): List<MaterialFileEntity> {
        val snap = filesRef().get().awaitTask()
        return snap.documents.map { it.toFile() }.filter {
            !it.deleted && it.subjectId == subjectId &&
                it.fileName == fileName && it.sizeBytes == sizeBytes
        }
    }

    /** Upload path: the entity arrives with localPath pointing at the copy
     *  the upload screen / PDF maker saved on THIS phone. The bytes go to
     *  Cloudinary first; only when the URL comes back is the Firestore
     *  record written, with localPath holding the URL from then on. If the
     *  upload fails this throws, and the caller shows "upload failed" —
     *  no record pointing at nothing is ever created. */
    override suspend fun addFile(file: MaterialFileEntity): Long {
        val id = newId()
        var record = file.copy(id = id)
        val path = file.localPath
        if (path.isNotEmpty() && !path.startsWith("http://") && !path.startsWith("https://")) {
            val uploaded = CloudinaryUploader.upload(File(path), file.fileName)
            record = record.copy(
                localPath = uploaded.secureUrl,
                sizeBytes = if (file.sizeBytes > 0) file.sizeBytes else uploaded.bytes
            )
        }
        filesRef().document(id.toString()).set(record.toCreateMap(myUid)).awaitTask()
        return id
    }

    override suspend fun updateFile(file: MaterialFileEntity) {
        filesRef().document(file.id.toString()).update(file.toUpdateMap()).awaitTask()
    }

    override suspend fun deleteFile(id: Long) {
        filesRef().document(id.toString())
            .update(
                mapOf(
                    "deleted" to true,
                    "deletedAt" to System.currentTimeMillis()
                )
            ).awaitTask()
    }

    override suspend fun restoreFile(id: Long) {
        filesRef().document(id.toString())
            .update(mapOf("deleted" to false, "deletedAt" to 0L)).awaitTask()
    }

    /** Removes the Firestore record only. The Cloudinary object behind it
     *  cannot be deleted with an unsigned preset — it stays, unlinked and
     *  invisible. See WIRING_NOTES.md. */
    override suspend fun deleteFilePermanently(id: Long) {
        filesRef().document(id.toString()).delete().awaitTask()
    }

    override suspend fun totalStoredBytes(): Long {
        val snap = filesRef().get().awaitTask()
        return snap.documents.map { it.toFile() }
            .filter { !it.deleted }
            .sumOf { it.sizeBytes }
    }

    // ---- Deadlines ------------------------------------------------------------------

    override fun observeDeadlines(): Flow<List<DeadlineEntity>> =
        deadlinesRef().observeList { snap ->
            snap.documents.map { it.toDeadline() }.sortedBy { it.dueDateMillis }
        }

    override suspend fun addDeadline(deadline: DeadlineEntity): Long {
        val id = newId()
        deadlinesRef().document(id.toString())
            .set(deadline.copy(id = id).toMap()).awaitTask()
        return id
    }

    override suspend fun updateDeadline(deadline: DeadlineEntity) {
        deadlinesRef().document(deadline.id.toString())
            .update(deadline.toMap()).awaitTask()
    }

    override suspend fun deleteDeadline(id: Long) {
        deadlinesRef().document(id.toString()).delete().awaitTask()
    }

    // ---- Chat ------------------------------------------------------------------------

    override fun observeChat(): Flow<List<ChatMessageEntity>> =
        chatRef().observeList { snap ->
            snap.documents.map { it.toChatMessage() }
                .filter { !it.hiddenForMe }
                .sortedBy { it.timestamp }
        }

    override suspend fun sendChat(
        text: String,
        senderName: String,
        senderRole: String,
        replyTo: ChatMessageEntity?
    ): Long {
        val id = newId()
        chatRef().document(id.toString()).set(
            mapOf(
                "senderName" to senderName,
                "senderRole" to senderRole,
                "senderUid" to myUid,
                "text" to text,
                "timestamp" to System.currentTimeMillis(),
                "deletedForEveryone" to false,
                "hiddenForUids" to emptyList<String>(),
                "systemFileId" to 0L,
                "reactions" to emptyMap<String, List<String>>(),
                "replyToId" to (replyTo?.id ?: 0L),
                "replyToSender" to (replyTo?.senderName.orEmpty()),
                "replyToText" to (replyTo?.text.orEmpty().take(REPLY_SNIPPET_MAX))
            )
        ).awaitTask()
        return id
    }

    override suspend fun postUploadNotice(file: MaterialFileEntity, subjectName: String) {
        if (file.id == 0L) return
        val id = newId()
        chatRef().document(id.toString()).set(
            mapOf(
                "senderName" to file.uploaderName,
                "senderRole" to file.uploaderRole,
                "senderUid" to myUid,
                "text" to "${file.uploaderName} added ${file.title} to $subjectName",
                "timestamp" to System.currentTimeMillis(),
                "deletedForEveryone" to false,
                "hiddenForUids" to emptyList<String>(),
                "systemFileId" to file.id,
                "reactions" to emptyMap<String, List<String>>(),
                "replyToId" to 0L,
                "replyToSender" to "",
                "replyToText" to ""
            )
        ).awaitTask()
    }

    /** Read-modify-write of ONLY the reactions field. firestore.rules
     *  lets any approved member touch that one field on anyone's message
     *  (that is what reacting IS); the text and tombstone fields are never
     *  part of this write. */
    override suspend fun toggleReaction(messageId: Long, emoji: String) {
        val uid = myUid
        if (uid.isEmpty()) return
        val ref = chatRef().document(messageId.toString())
        val doc = ref.get().awaitTask()
        if (!doc.exists()) return
        val current = Reactions.fromFirestore(doc.get("reactions"))
        val next = Reactions.toggled(current, emoji, uid)
        ref.update("reactions", Reactions.toFirestore(next)).awaitTask()
    }

    // ---- Owner reset ----------------------------------------------------------------

    /** Firestore caps a WriteBatch at 500 operations, so a collection is
     *  emptied in 500-document bites until a query comes back empty. */
    private suspend fun deleteAllIn(ref: CollectionReference) {
        while (true) {
            val snap = ref.limit(500).get().awaitTask()
            if (snap.isEmpty) return
            val batch = db.batch()
            for (doc in snap.documents) batch.delete(doc.reference)
            batch.commit().awaitTask()
        }
    }

    override suspend fun clearChat() {
        deleteAllIn(chatRef())
    }

    override suspend fun clearAllContent() {
        deleteAllIn(chatRef())
        deleteAllIn(filesRef())
        deleteAllIn(noticesRef())
        deleteAllIn(deadlinesRef())
    }

    /** Covers both chat actions the UI uses: tombstone (text wiped +
     *  deletedForEveryone) and delete-for-me (hiddenForMe). The tombstone
     *  writes the empty text to Firestore too, so the original words are
     *  gone from the server, exactly like the local behaviour promises. */
    override suspend fun updateChatMessage(message: ChatMessageEntity) {
        val ref = chatRef().document(message.id.toString())
        val updates = mutableMapOf<String, Any>(
            "text" to message.text,
            "deletedForEveryone" to message.deletedForEveryone
        )
        updates["hiddenForUids"] =
            if (message.hiddenForMe) FieldValue.arrayUnion(myUid)
            else FieldValue.arrayRemove(myUid)
        ref.update(updates).awaitTask()
    }

    // ---- Notices -----------------------------------------------------------------------

    override fun observeNotices(): Flow<List<NoticeEntity>> =
        noticesRef().observeList { snap ->
            snap.documents.map { it.toNotice() }.sortedByDescending { it.timestamp }
        }

    override suspend fun addNotice(notice: NoticeEntity): Long {
        val id = newId()
        noticesRef().document(id.toString()).set(
            mapOf(
                "authorName" to notice.authorName,
                "authorRole" to notice.authorRole,
                "title" to notice.title,
                "body" to notice.body,
                "timestamp" to notice.timestamp,
                "seenByUids" to emptyList<String>()
            )
        ).awaitTask()
        return id
    }

    override suspend fun markNoticesSeen() {
        val uid = myUid
        if (uid.isEmpty()) return
        val snap = noticesRef().get().awaitTask()
        val unseen = snap.documents.filter { doc -> !doc.stringList("seenByUids").contains(uid) }
        if (unseen.isEmpty()) return
        val batch = db.batch()
        for (doc in unseen) {
            batch.update(doc.reference, "seenByUids", FieldValue.arrayUnion(uid))
        }
        batch.commit().awaitTask()
    }

    // ---- Requests ------------------------------------------------------------------------

    override fun observeRequests(): Flow<List<MaterialRequestEntity>> =
        requestsRef().observeList { snap ->
            snap.documents.map { it.toRequest() }.sortedWith(
                compareBy<MaterialRequestEntity> {
                    if (it.status == MaterialRequestEntity.STATUS_OPEN) 0 else 1
                }.thenByDescending { it.createdAt }
            )
        }

    override suspend fun requestById(id: Long): MaterialRequestEntity? {
        val doc = requestsRef().document(id.toString()).get().awaitTask()
        return if (doc.exists()) doc.toRequest() else null
    }

    override suspend fun addRequest(request: MaterialRequestEntity): Long {
        val id = newId()
        requestsRef().document(id.toString())
            .set(request.copy(id = id).toMap(myUid)).awaitTask()
        return id
    }

    override suspend fun fulfilRequest(requestId: Long, fileId: Long, fulfilledBy: String) {
        requestsRef().document(requestId.toString()).update(
            mapOf(
                "status" to MaterialRequestEntity.STATUS_FULFILLED,
                "fulfilledBy" to fulfilledBy,
                "fulfilledFileId" to fileId
            )
        ).awaitTask()
    }

    // ---- First-run setup -------------------------------------------------------------------

    /** Cloud bootstrap: make sure the class document exists and the chosen
     *  subjects are seeded — once, only if the class has no subjects yet.
     *  Unlike local mode there is NO sample content here: a real class must
     *  not open to fake files and fake classmates. JoinPendingActivity
     *  drives this for the owner's first sign-in (after writing the OWNER
     *  member record), so by the time anyone reads, the class is real. */
    override suspend fun completeSetup(className: String, subjects: List<Pair<String, String>>) {
        val classDoc = classRef().get().awaitTask()
        if (!classDoc.exists()) {
            classRef().set(
                mapOf(
                    "name" to className,
                    "ownerUid" to myUid,
                    "ownerEmail" to myEmail,
                    "createdAt" to System.currentTimeMillis()
                )
            ).awaitTask()
        }
        val existing = subjectsRef().get().awaitTask()
        if (!existing.isEmpty) return
        val batch = db.batch()
        for ((name, color) in subjects) {
            val id = newId()
            val subject = SubjectEntity(id = id, name = name, colorHex = color)
            batch.set(subjectsRef().document(id.toString()), subject.toMap())
        }
        batch.commit().awaitTask()
    }
}
