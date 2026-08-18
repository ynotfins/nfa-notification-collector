package com.nfaalerts.collector.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(
    entities = [
        CapturedNotificationEntity::class,
        DeliveryOutboxEntity::class,
        CaptureRetentionTombstoneEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NfaCollectorDatabase : RoomDatabase() {
    abstract fun captureWriteDao(): CaptureWriteDao

    abstract fun captureReadDao(): CaptureReadDao

    companion object {
        const val DATABASE_NAME = "nfa-notification-collector.db"

        fun create(context: Context): NfaCollectorDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    NfaCollectorDatabase::class.java,
                    DATABASE_NAME,
                ).build()
    }
}
