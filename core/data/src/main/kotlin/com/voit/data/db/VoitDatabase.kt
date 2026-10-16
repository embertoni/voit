package com.voit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The Voit local database.
 *
 * Schema export is enabled (see build.gradle room.schemaLocation); the exported schema
 * JSON under `schemas/` is committed so migrations have a baseline and can be validated.
 *
 * Version history:
 * - 1: initial schema (notes, tasks, command_definitions, action_runs, voice_profile_metadata).
 * - 2: additive — adds voice_profile_metadata.lastVerifiedAt (nullable).
 */
@Database(
    entities = [
        NoteEntity::class,
        TaskEntity::class,
        CommandDefinitionEntity::class,
        ActionRunEntity::class,
        VoiceProfileMetadataEntity::class,
    ],
    version = VoitDatabase.VERSION,
    exportSchema = true,
)
abstract class VoitDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun commandDao(): CommandDao
    abstract fun actionRunDao(): ActionRunDao
    abstract fun voiceProfileDao(): VoiceProfileDao

    companion object {
        const val NAME = "voit.db"
        const val VERSION = 2

        /**
         * Additive migration: add a nullable `lastVerifiedAt` column to the metadata-only
         * voice profile table. Purely additive, so existing rows are preserved.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE voice_profile_metadata ADD COLUMN lastVerifiedAt INTEGER",
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
