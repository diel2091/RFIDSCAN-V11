package com.zebra.rfidscanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tags", indices = [Index(value = ["epc"], unique = true)])
data class TagEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epc: String,
    val firstSeen: Long = System.currentTimeMillis(),
    val readCount: Int = 1
)
