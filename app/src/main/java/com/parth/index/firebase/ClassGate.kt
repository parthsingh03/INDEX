package com.parth.index.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.parth.index.data.model.Roles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

/** Membership of the one class — everything about "who is in, who is
 *  waiting, and who runs it". This is NOT part of IndexRepository (the
 *  screens never ask the data layer about people); the gate activities
 *  (SignIn / JoinPending / Members) work through here.
 *
 *  A member document is classes/index-class/members/{uid}:
 *      { name, email, role: OWNER|ADMIN|MEMBER, status: APPROVED|PENDING,
 *        joinedAt, lastReadChatAt }
 *  [Member.lastReadChatAt] (v1.4) is the newest group-chat timestamp this
 *  member has seen — it powers the "new messages" divider in chat and the
 *  unread badge on the Chat tab.
 *
 *  The lifecycle every non-owner goes through:
 *    sign in -> no member doc -> they create their own doc, PENDING
 *            -> an owner/admin approves -> APPROVED, the app opens
 *            -> an owner/admin rejects -> the doc is deleted; the person
 *               sees the declined state and may ask again (a fresh doc).
 */
object ClassGate {

    data class Member(
        val uid: String,
        val name: String,
        val email: String,
        val role: String,
        val status: String,
        val joinedAt: Long,
        val lastReadChatAt: Long = 0L
    ) {
        val isApproved: Boolean get() = status == CloudConfig.STATUS_APPROVED
        val canManage: Boolean get() = isApproved && Roles.canManage(role)
    }

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    fun currentUser(): FirebaseUser? = auth.currentUser
    fun currentUid(): String = auth.currentUser?.uid.orEmpty()
    fun isOwnerEmail(email: String?): Boolean =
        email != null && email.equals(CloudConfig.OWNER_EMAIL, ignoreCase = true)

    private fun classRef() = db.collection("classes").document(CloudConfig.CLASS_ID)
    private fun membersRef() = classRef().collection("members")
    private fun memberRef(uid: String) = membersRef().document(uid)

    // ---- Reads ----------------------------------------------------------------

    suspend fun memberFor(uid: String): Member? {
        if (uid.isEmpty()) return null
        val doc = memberRef(uid).get().awaitTask()
        return if (doc.exists()) doc.toMember() else null
    }

    suspend fun myMember(): Member? = memberFor(currentUid())

    /** The class display name, or null if the class does not exist yet. */
    suspend fun classNameOrNull(): String? {
        val doc = classRef().get().awaitTask()
        return if (doc.exists()) doc.getString("name") else null
    }

    suspend fun classExists(): Boolean = classRef().get().awaitTask().exists()

    fun observeMember(uid: String): Flow<Member?> = callbackFlow {
        if (uid.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val registration = memberRef(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(if (snapshot != null && snapshot.exists()) snapshot.toMember() else null)
        }
        awaitClose { registration.remove() }
    }

    fun observeMyMember(): Flow<Member?> = observeMember(currentUid())

    fun observeMembers(): Flow<List<Member>> = callbackFlow {
        val registration = membersRef().addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val members = snapshot?.documents.orEmpty().map { it.toMember() }
            trySend(
                members.sortedWith(
                    compareBy<Member> { roleRank(it.role) }
                        .thenBy { it.name.lowercase() }
                )
            )
        }
        awaitClose { registration.remove() }
    }

    private fun roleRank(role: String): Int = when (role) {
        Roles.OWNER -> 0
        Roles.ADMIN -> 1
        else -> 2
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toMember(): Member = Member(
        uid = id,
        name = getString("name").orEmpty(),
        email = getString("email").orEmpty(),
        role = getString("role") ?: Roles.MEMBER,
        status = getString("status") ?: CloudConfig.STATUS_PENDING,
        joinedAt = getLong("joinedAt") ?: 0L,
        lastReadChatAt = getLong("lastReadChatAt") ?: 0L
    )

    // ---- Writes -----------------------------------------------------------------

    /** Create (or repair) the OWNER member record for the signed-in owner.
     *  Used by the bootstrap: the person whose Google account matches
     *  OWNER_EMAIL IS the owner — no approval step, ever. */
    suspend fun writeOwnerMember(user: FirebaseUser) {
        memberRef(user.uid).set(
            mapOf(
                "name" to (user.displayName ?: user.email ?: "Owner"),
                "email" to (user.email.orEmpty()),
                "role" to Roles.OWNER,
                "status" to CloudConfig.STATUS_APPROVED,
                "joinedAt" to System.currentTimeMillis(),
                "lastReadChatAt" to System.currentTimeMillis()
            )
        ).awaitTask()
    }

    /** A signed-in non-member asks to join: their own doc, PENDING. */
    suspend fun requestToJoin(user: FirebaseUser) {
        memberRef(user.uid).set(
            mapOf(
                "name" to (user.displayName ?: user.email ?: "Student"),
                "email" to (user.email.orEmpty()),
                "role" to Roles.MEMBER,
                "status" to CloudConfig.STATUS_PENDING,
                "joinedAt" to System.currentTimeMillis(),
                // Joining is not "unread": what was said before you arrived
                // is history, not news.
                "lastReadChatAt" to System.currentTimeMillis()
            )
        ).awaitTask()
    }

    /** Mark the group chat read up to [timestamp] for the signed-in user.
     *  Rules allow an approved member to change ONLY this field on their
     *  own member document. Callers fire-and-forget (runCatching): a
     *  failed mark just leaves the badge/divider where it was. */
    suspend fun updateMyLastReadChatAt(timestamp: Long) {
        val uid = currentUid()
        if (uid.isEmpty()) return
        memberRef(uid).update("lastReadChatAt", timestamp).awaitTask()
    }

    /** Owner/admin decision on a pending request: approve the join. */
    suspend fun approve(uid: String) {
        memberRef(uid).update("status", CloudConfig.STATUS_APPROVED).awaitTask()
    }

    /** Reject a pending request — the doc is deleted, so the person sees
     *  the declined state on their phone and can ask again later. */
    suspend fun reject(uid: String) {
        memberRef(uid).delete().awaitTask()
    }

    /** Owner only (enforced in the UI and in firestore.rules): flip a
     *  member between MEMBER and ADMIN. */
    suspend fun setRole(uid: String, role: String) {
        require(role == Roles.ADMIN || role == Roles.MEMBER) {
            "setRole only flips MEMBER <-> ADMIN; OWNER is permanent"
        }
        memberRef(uid).update("role", role).awaitTask()
    }

    /** Owner only (UI + rules): remove a member from the class. */
    suspend fun removeMember(uid: String) {
        memberRef(uid).delete().awaitTask()
    }

    fun signOut() {
        auth.signOut()
    }
}
