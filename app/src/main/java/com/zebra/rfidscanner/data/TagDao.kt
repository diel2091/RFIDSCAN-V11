package com.zebra.rfidscanner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY firstSeen DESC")
    fun getAllTags(): Flow<List<TagEntry>>

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntry): Long

    @Query("UPDATE tags SET readCount = readCount + 1 WHERE epc = :epc")
    suspend fun incrementCount(epc: String)

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}
