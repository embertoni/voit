package com.voit.data

import androidx.room.withTransaction
import com.voit.data.db.TaskDao
import com.voit.data.db.VoitDatabase
import com.voit.data.db.toDomain
import com.voit.data.db.toEntity
import com.voit.domain.Clock
import com.voit.domain.RepositoryConflictException
import com.voit.domain.SystemClock
import com.voit.domain.TaskItem
import com.voit.domain.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [TaskRepository]. Create/update are atomic via a single transaction, with
 * duplicate prevention on create and missing-id detection on update.
 */
class RoomTaskRepository(
    private val db: VoitDatabase,
    private val dao: TaskDao = db.taskDao(),
    private val clock: Clock = SystemClock,
) : TaskRepository {

    override fun observeTasks(includeCompleted: Boolean): Flow<List<TaskItem>> =
        dao.observe(includeCompleted).map { list -> list.map { it.toDomain() } }

    override suspend fun getTask(id: String): TaskItem? = dao.getById(id)?.toDomain()

    override suspend fun create(task: TaskItem) {
        db.withTransaction {
            if (dao.getById(task.id) != null) {
                throw RepositoryConflictException("Task ${task.id} already exists")
            }
            dao.insert(task.toEntity())
        }
    }

    override suspend fun update(task: TaskItem) {
        db.withTransaction {
            val existing = dao.getById(task.id)
                ?: throw RepositoryConflictException("Task ${task.id} does not exist")
            dao.updateTask(task.toEntity(deleted = false).copy(createdAt = existing.createdAt))
        }
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        dao.setCompleted(id, completed, updatedAt = clock.nowEpochMillis())
    }

    override suspend fun delete(id: String) {
        dao.softDelete(id, updatedAt = clock.nowEpochMillis())
    }

    override suspend fun search(query: String): List<TaskItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return dao.search(buildLike(q)).map { it.toDomain() }
    }

    override suspend fun exportAll(): List<TaskItem> = dao.getAll().map { it.toDomain() }
}
