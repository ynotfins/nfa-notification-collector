package com.nfaalerts.collector.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        CapturedNotificationEntity::class,
        DeliveryOutboxEntity::class,
        CaptureRetentionTombstoneEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NfaCollectorDatabase : RoomDatabase() {
    abstract fun captureWriteDao(): CaptureWriteDao

    abstract fun captureReadDao(): CaptureReadDao

    abstract fun deliveryDao(): DeliveryDao

    abstract fun retentionDao(): RetentionDao

    internal abstract fun diagnosticsDao(): DiagnosticsDao

    companion object {
        const val DATABASE_NAME = "nfa-notification-collector.db"

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE delivery_outbox ADD COLUMN lastAttemptAtEpochMillis INTEGER")
                    connection.execSQL("ALTER TABLE delivery_outbox ADD COLUMN pausedAtConfigRevision INTEGER")
                    connection.execSQL("ALTER TABLE delivery_outbox ADD COLUMN sentAtEpochMillis INTEGER")
                }
            }

        fun create(
            context: Context,
            databaseName: String = DATABASE_NAME,
        ): NfaCollectorDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    NfaCollectorDatabase::class.java,
                    databaseName,
                ).addMigrations(MIGRATION_1_2)
                .build()
    }
}
