package com.zebra.rfidscanner.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RfidRepository @Inject constructor(private val tagDao: TagDao) {

    private val tagMap = ConcurrentHashMap<String, Int>(8192) // epc -> readCount
    private val _tagCount    = MutableStateFlow(0)
    private val _totalReads  = MutableStateFlow(0)
    private val _readRate    = MutableStateFlow(0f)

    val tagCount:   StateFlow<Int>   = _tagCount.asStateFlow()
    val totalReads: StateFlow<Int>   = _totalReads.asStateFlow()
    val readRate:   StateFlow<Float> = _readRate.asStateFlow()
    val allTags: Flow<List<TagEntry>> = tagDao.getAllTags()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readsInWindow = 0
    private var windowStart = System.currentTimeMillis()

    fun onEpcReceived(epc: String) {
        val isNew = tagMap.putIfAbsent(epc, 1) == null
        if (!isNew) tagMap.merge(epc, 1, Int::plus)

        _tagCount.value   = tagMap.size
        _totalReads.value = _totalReads.value + 1

        // Read rate: tags/sec rolling 2s window
        readsInWindow++
        val now = System.currentTimeMillis()
        val elapsed = (now - windowStart) / 1000f
        if (elapsed >= 2f) {
            _readRate.value = readsInWindow / elapsed
            readsInWindow = 0
            windowStart = now
        }

        scope.launch {
            try {
                if (isNew) tagDao.insert(TagEntry(epc = epc))
                else tagDao.incrementCount(epc)
            } catch (e: Exception) {
                Log.e("Repository", "DB error", e)
            }
        }
    }

    fun getTagList(): List<String> = tagMap.keys().toList()

    suspend fun clearAll() {
        tagMap.clear()
        _tagCount.value   = 0
        _totalReads.value = 0
        _readRate.value   = 0f
        readsInWindow     = 0
        windowStart       = System.currentTimeMillis()
        tagDao.deleteAll()
    }
}
