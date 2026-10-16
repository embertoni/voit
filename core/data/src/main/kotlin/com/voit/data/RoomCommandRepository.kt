package com.voit.data

import com.voit.data.db.CommandDao
import com.voit.data.db.VoitDatabase
import com.voit.data.db.toDomain
import com.voit.data.db.toEntity
import com.voit.domain.CommandDefinition
import com.voit.domain.CommandRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [CommandRepository]. Upsert-based so restore from a backup is idempotent.
 */
class RoomCommandRepository(
    db: VoitDatabase,
    private val dao: CommandDao = db.commandDao(),
) : CommandRepository {

    override fun observeCommands(): Flow<List<CommandDefinition>> =
        dao.observe().map { list -> list.map { it.toDomain() } }

    override suspend fun getCommand(id: String): CommandDefinition? =
        dao.getById(id)?.toDomain()

    override suspend fun upsert(command: CommandDefinition) {
        dao.upsert(command.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun exportAll(): List<CommandDefinition> =
        dao.getAll().map { it.toDomain() }
}
