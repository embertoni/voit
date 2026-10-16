package com.voit.data

import androidx.room.withTransaction
import com.voit.data.db.NoteDao
import com.voit.data.db.VoitDatabase
import com.voit.data.db.toDomain
import com.voit.data.db.toEntity
import com.voit.domain.Clock
import com.voit.domain.Note
import com.voit.domain.NoteRepository
import com.voit.domain.RepositoryConflictException
import com.voit.domain.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [NoteRepository].
 *
 * Create/update run inside a single [withTransaction] so a note and its serialized block
 * content are persisted atomically. Create aborts on a duplicate id and update aborts when
 * the target id is missing, surfacing [RepositoryConflictException] to callers.
 */
class RoomNoteRepository(
    private val db: VoitDatabase,
    private val dao: NoteDao = db.noteDao(),
    private val clock: Clock = SystemClock,
) : NoteRepository {

    override fun observeNotes(includeArchived: Boolean): Flow<List<Note>> =
        dao.observe(includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun getNote(id: String): Note? = dao.getById(id)?.toDomain()

    override suspend fun create(note: Note) {
        db.withTransaction {
            if (dao.exists(note.id) > 0) {
                throw RepositoryConflictException("Note ${note.id} already exists")
            }
            dao.insert(note.toEntity())
        }
    }

    override suspend fun update(note: Note) {
        db.withTransaction {
            // Read inside the transaction so the archived/deleted flags (which the domain
            // Note does not carry) are preserved across the content update.
            val existing = dao.getById(note.id)
                ?: throw RepositoryConflictException("Note ${note.id} does not exist")
            val merged = note.toEntity(
                archived = existing.archived,
                deleted = existing.deleted,
            )
            val updated = dao.updateNote(merged)
            if (updated == 0) {
                throw RepositoryConflictException("Note ${note.id} does not exist")
            }
        }
    }

    override suspend fun archive(id: String) {
        dao.setArchived(id, archived = true, updatedAt = clock.nowEpochMillis())
    }

    override suspend fun unarchive(id: String) {
        dao.setArchived(id, archived = false, updatedAt = clock.nowEpochMillis())
    }

    override suspend fun delete(id: String) {
        dao.softDelete(id, updatedAt = clock.nowEpochMillis())
    }

    override suspend fun search(query: String): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return dao.search(buildLike(q)).map { it.toDomain() }
    }

    override suspend fun exportAll(): List<Note> = dao.getAll().map { it.toDomain() }
}

/** Build a case-insensitive LIKE pattern, escaping the SQL wildcards in the query. */
internal fun buildLike(query: String): String {
    val escaped = query.lowercase()
        .replace("%", "")
        .replace("_", "")
    return "%$escaped%"
}
