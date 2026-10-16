package com.voit.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query(
        "SELECT * FROM notes WHERE deleted = 0 AND (:includeArchived = 1 OR archived = 0) " +
            "ORDER BY updatedAt DESC",
    )
    fun observe(includeArchived: Boolean): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): NoteEntity?

    @Query("SELECT COUNT(*) FROM notes WHERE id = :id")
    suspend fun exists(id: String): Int

    /** Fails on an existing id so create is strictly an insert (duplicate prevention). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun updateNote(note: NoteEntity): Int

    @Query("UPDATE notes SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("UPDATE notes SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query(
        "SELECT * FROM notes WHERE deleted = 0 AND " +
            "(LOWER(title) LIKE :like OR LOWER(tags) LIKE :like OR LOWER(blocks) LIKE :like) " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun search(like: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted = 0 ORDER BY updatedAt DESC")
    suspend fun getAll(): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)
}

@Dao
interface TaskDao {

    @Query(
        "SELECT * FROM tasks WHERE deleted = 0 AND (:includeCompleted = 1 OR completed = 0) " +
            "ORDER BY updatedAt DESC",
    )
    fun observe(includeCompleted: Boolean): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun updateTask(task: TaskEntity): Int

    @Query("UPDATE tasks SET completed = :completed, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean, updatedAt: Long)

    @Query("UPDATE tasks SET deleted = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: String, updatedAt: Long)

    @Query(
        "SELECT * FROM tasks WHERE deleted = 0 AND " +
            "(LOWER(title) LIKE :like OR LOWER(body) LIKE :like) ORDER BY updatedAt DESC",
    )
    suspend fun search(like: String): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE deleted = 0 ORDER BY updatedAt DESC")
    suspend fun getAll(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)
}

@Dao
interface CommandDao {

    @Query("SELECT * FROM command_definitions ORDER BY name ASC")
    fun observe(): Flow<List<CommandDefinitionEntity>>

    @Query("SELECT * FROM command_definitions WHERE id = :id")
    suspend fun getById(id: String): CommandDefinitionEntity?

    @Upsert
    suspend fun upsert(command: CommandDefinitionEntity)

    @Query("DELETE FROM command_definitions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM command_definitions ORDER BY name ASC")
    suspend fun getAll(): List<CommandDefinitionEntity>
}

@Dao
interface ActionRunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: ActionRunEntity)

    @Query("SELECT * FROM action_runs ORDER BY startedAt DESC")
    suspend fun getAll(): List<ActionRunEntity>

    @Query("DELETE FROM action_runs")
    suspend fun clear()
}

@Dao
interface VoiceProfileDao {

    @Upsert
    suspend fun upsert(profile: VoiceProfileMetadataEntity)

    @Query("SELECT * FROM voice_profile_metadata")
    suspend fun getAll(): List<VoiceProfileMetadataEntity>

    /** User-initiated deletion of the (metadata-only) voice profile. */
    @Query("DELETE FROM voice_profile_metadata WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM voice_profile_metadata")
    suspend fun clear()
}
