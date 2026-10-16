package com.voit.data

import com.voit.data.db.ActionRunDao
import com.voit.data.db.VoitDatabase
import com.voit.data.db.toDomain
import com.voit.data.db.toEntity
import com.voit.domain.ActionRun
import com.voit.domain.ActionRunRepository

/**
 * Room-backed [ActionRunRepository] over the existing `action_runs` table. Append-only
 * (`record`) plus a user-initiated `clear`, mirroring the other Room repositories. The
 * entity holds only redacted codes/ids, so nothing user-content-bearing is ever persisted
 * here (the redaction happens upstream in `ActionRunRecorder`).
 */
class RoomActionRunRepository(
    db: VoitDatabase,
    private val dao: ActionRunDao = db.actionRunDao(),
) : ActionRunRepository {

    override suspend fun record(run: ActionRun) {
        dao.insert(run.toEntity())
    }

    override suspend fun history(): List<ActionRun> =
        dao.getAll().map { it.toDomain() }

    override suspend fun clear() {
        dao.clear()
    }
}
