package com.zebra.rfidscanner.data
 
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
 
@Singleton
class RfidRepository @Inject constructor(private val tagDao: TagDao) {
 
    // Mapa en memoria: epc -> readCount
    private val tagMap = ConcurrentHashMap<String, Int>(8192)
 
    private val _tagCount   = MutableStateFlow(0)
    private val _totalReads = MutableStateFlow(0)
    private val _readRate   = MutableStateFlow(0f)
    private val _tags       = MutableStateFlow<List<TagEntry>>(emptyList())
 
    val tagCount:   StateFlow<Int>          = _tagCount.asStateFlow()
    val totalReads: StateFlow<Int>          = _totalReads.asStateFlow()
    val readRate:   StateFlow<Float>        = _readRate.asStateFlow()
 
    // allTags ahora es un StateFlow en memoria — no dispara query Room en cada lectura
    val allTags: StateFlow<List<TagEntry>>  = _tags.asStateFlow()
 
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
 
    // Rate
    private var readsInWindow = 0
    private var windowStart   = System.currentTimeMillis()
 
    // Throttle de UI: actualizar la lista visible máximo cada 500ms
    // Evita que con alto volumen de tags se congele la UI con renders continuos
    private var lastUiUpdate  = 0L
    private val UI_THROTTLE_MS = 500L
 
    // Cola de escrituras pendientes a DB — evita saturar Room con miles de coroutines
    private val dbQueue = ArrayDeque<Pair<String, Boolean>>() // epc, isNew
    private var dbJobRunning = false
 
    fun onEpcReceived(epc: String) {
        val isNew = tagMap.putIfAbsent(epc, 1) == null
        if (!isNew) tagMap.merge(epc, 1, Int::plus)
 
        // Actualizar contadores — son operaciones atómicas rápidas
        _totalReads.value = _totalReads.value + 1
 
        // Rate: rolling 2s window
        readsInWindow++
        val now = System.currentTimeMillis()
        val elapsed = (now - windowStart) / 1000f
        if (elapsed >= 2f) {
            _readRate.value = readsInWindow / elapsed
            readsInWindow   = 0
            windowStart     = now
        }
 
        // Throttle de actualización de UI de la lista
        // Con alto volumen (>500 tags/s) no tiene sentido re-renderizar en cada tag
        if (now - lastUiUpdate >= UI_THROTTLE_MS) {
            lastUiUpdate    = now
            _tagCount.value = tagMap.size
            // Snapshot de la lista en memoria — sin tocar Room
            val snapshot = tagMap.entries.map { (epc, count) ->
                TagEntry(epc = epc, readCount = count)
            }
            _tags.value = snapshot
        }
 
        // Encolar escritura a DB — procesada en batch para no saturar
        synchronized(dbQueue) { dbQueue.add(Pair(epc, isNew)) }
        if (!dbJobRunning) flushDbQueue()
    }
 
    private fun flushDbQueue() {
        dbJobRunning = true
        scope.launch {
            while (true) {
                val batch = synchronized(dbQueue) {
                    if (dbQueue.isEmpty()) return@launch
                    val b = dbQueue.toList()
                    dbQueue.clear()
                    b
                }
                try {
                    batch.forEach { (epc, isNew) ->
                        if (isNew) tagDao.insert(TagEntry(epc = epc))
                        else tagDao.incrementCount(epc)
                    }
                } catch (e: Exception) {
                    Log.e("Repository", "DB batch error", e)
                }
                // Si quedaron más en cola durante el flush, procesar de nuevo
                if (synchronized(dbQueue) { dbQueue.isEmpty() }) break
            }
            dbJobRunning = false
        }
    }
 
    fun getTagList(): List<String> = tagMap.keys().toList()
 
    suspend fun clearAll() {
        tagMap.clear()
        _tagCount.value   = 0
        _totalReads.value = 0
        _readRate.value   = 0f
        _tags.value       = emptyList()
        readsInWindow     = 0
        windowStart       = System.currentTimeMillis()
        lastUiUpdate      = 0L
        withContext(Dispatchers.IO) { tagDao.deleteAll() }
    }
}
