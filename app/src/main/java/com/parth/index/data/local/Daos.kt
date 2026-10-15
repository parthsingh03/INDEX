package com.parth.index.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.parth.index.data.model.ChatMessageEntity
import com.parth.index.data.model.DeadlineEntity
import com.parth.index.data.model.MaterialFileEntity
import com.parth.index.data.model.MaterialRequestEntity
import com.parth.index.data.model.NoticeEntity
import com.parth.index.data.model.SubjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects WHERE deleted = 0 ORDER BY name ASC")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE deleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun byId(id: Long): SubjectEntity?

    @Insert suspend fun insert(subject: SubjectEntity): Long
    @Update suspend fun update(subject: SubjectEntity)

    @Query("UPDATE subjects SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE subjects SET deleted = 0, deletedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("SELECT COUNT(*) FROM subjects")
    suspend fun countAll(): Int
}

@Dao
interface FileDao {
    @Query("SELECT * FROM files WHERE deleted = 0 ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<MaterialFileEntity>>

    @Query("SELECT * FROM files WHERE subjectId = :subjectId AND deleted = 0 ORDER BY pinned DESC, createdAt DESC")
    fun observeForSubject(subjectId: Long): Flow<List<MaterialFileEntity>>

    @Query("SELECT * FROM files WHERE deleted = 1 ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<MaterialFileEntity>>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun byId(id: Long): MaterialFileEntity?

    @Query("SELECT COUNT(*) FROM files WHERE subjectId = :subjectId AND deleted = 0")
    fun observeCountForSubject(subjectId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM files WHERE subjectId = :subjectId AND deleted = 0")
    suspend fun countForSubject(subjectId: Long): Int

    @Query("SELECT * FROM files WHERE subjectId = :subjectId AND deleted = 0 AND fileName = :name AND sizeBytes = :size")
    suspend fun sameNameAndSize(subjectId: Long, name: String, size: Long): List<MaterialFileEntity>

    @Insert suspend fun insert(file: MaterialFileEntity): Long
    @Update suspend fun update(file: MaterialFileEntity)

    @Query("UPDATE files SET deleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE files SET deleted = 0, deletedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM files WHERE deleted = 0")
    suspend fun totalStoredBytes(): Long

    @Query("DELETE FROM files")
    suspend fun clearAll()
}

@Dao
interface DeadlineDao {
    @Query("SELECT * FROM deadlines ORDER BY dueDateMillis ASC")
    fun observeAll(): Flow<List<DeadlineEntity>>

    @Insert suspend fun insert(deadline: DeadlineEntity): Long
    @Update suspend fun update(deadline: DeadlineEntity)
    @Query("DELETE FROM deadlines WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM deadlines") suspend fun clearAll()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE hiddenForMe = 0 ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun byId(id: Long): ChatMessageEntity?

    @Insert suspend fun insert(message: ChatMessageEntity): Long
    @Update suspend fun update(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}

@Dao
interface NoticeDao {
    @Query("SELECT * FROM notices ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NoticeEntity>>

    @Insert suspend fun insert(notice: NoticeEntity): Long
    @Update suspend fun update(notice: NoticeEntity)

    @Query("UPDATE notices SET seenByMe = 1, seenByCount = seenByCount + 1 WHERE seenByMe = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM notices")
    suspend fun clearAll()
}

@Dao
interface RequestDao {
    @Query("SELECT * FROM requests ORDER BY CASE WHEN status = 'OPEN' THEN 0 ELSE 1 END, createdAt DESC")
    fun observeAll(): Flow<List<MaterialRequestEntity>>

    @Query("SELECT * FROM requests WHERE id = :id")
    suspend fun byId(id: Long): MaterialRequestEntity?

    @Insert suspend fun insert(request: MaterialRequestEntity): Long
    @Update suspend fun update(request: MaterialRequestEntity)
}
