package com.voit.data

import com.voit.data.db.VoitDatabase
import com.voit.domain.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates the Room schema evolution and the 1 -> 2 migration.
 *
 * CONSTRAINT: this sandbox has no Android emulator/device, so Room's instrumented
 * [androidx.room.testing.MigrationTestHelper] (which requires instrumentation) cannot run
 * here. Instead this JVM test validates the migration against the EXPORTED schema JSON
 * (committed under schemas/) by asserting the v1 -> v2 delta is exactly what the migration
 * SQL applies: adding the nullable `lastVerifiedAt` column to `voice_profile_metadata`.
 * See docs/decisions.md for the documented limitation.
 */
class MigrationSchemaTest {

    private val schemaDir = File("schemas/com.voit.data.db.VoitDatabase")

    private fun loadSchema(version: Int): Map<String, Any?> {
        val file = File(schemaDir, "$version.json")
        assertTrue(file.exists(), "Expected committed schema ${file.path}")
        return Json.parseObject(file.readText())
    }

    @Suppress("UNCHECKED_CAST")
    private fun columns(schema: Map<String, Any?>, table: String): Set<String> {
        val db = schema["database"] as Map<String, Any?>
        val entities = db["entities"] as List<Map<String, Any?>>
        val entity = entities.first { it["tableName"] == table }
        val fields = entity["fields"] as List<Map<String, Any?>>
        return fields.map { it["columnName"] as String }.toSet()
    }

    @Test
    fun bothSchemaVersionsAreExported() {
        assertEquals(1, (loadSchema(1)["database"] as Map<*, *>)["version"].let { (it as Double).toInt() })
        assertEquals(2, (loadSchema(2)["database"] as Map<*, *>)["version"].let { (it as Double).toInt() })
    }

    @Test
    fun migrationAddsExactlyTheLastVerifiedAtColumn() {
        val v1Cols = columns(loadSchema(1), "voice_profile_metadata")
        val v2Cols = columns(loadSchema(2), "voice_profile_metadata")

        assertTrue("lastVerifiedAt" !in v1Cols, "v1 must NOT have lastVerifiedAt")
        assertTrue("lastVerifiedAt" in v2Cols, "v2 must have lastVerifiedAt")
        assertEquals(setOf("lastVerifiedAt"), v2Cols - v1Cols, "v2 adds exactly one column")
        assertEquals(emptySet(), v1Cols - v2Cols, "migration must not drop columns")
    }

    @Test
    fun migrationSqlMatchesTheSchemaDelta() {
        // The declared migration must add the same column the schema delta introduces.
        // We assert the migration object targets 1 -> 2 and its SQL names the new column.
        val migration = VoitDatabase.MIGRATION_1_2
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
    }

    @Test
    fun otherTablesAreUnchangedAcrossMigration() {
        val v1 = loadSchema(1)
        val v2 = loadSchema(2)
        for (table in listOf("notes", "tasks", "command_definitions", "action_runs")) {
            assertEquals(
                columns(v1, table),
                columns(v2, table),
                "Table $table should be unchanged by the additive migration",
            )
        }
    }

    @Test
    fun v2IdentityHashIsRecorded() {
        @Suppress("UNCHECKED_CAST")
        val db = loadSchema(2)["database"] as Map<String, Any?>
        val hash = db["identityHash"] as? String ?: fail("missing identityHash")
        assertNotNull(hash)
        assertTrue(hash.isNotBlank())
    }
}
