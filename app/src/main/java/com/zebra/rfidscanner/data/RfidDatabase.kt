package com.zebra.rfidscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TagEntry::class], version = 1, exportSchema = false)
abstract class RfidDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var INSTANCE: RfidDatabase? = null

        fun getDatabase(context: Context): RfidDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RfidDatabase::class.java,
                    "rfid_database"
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
