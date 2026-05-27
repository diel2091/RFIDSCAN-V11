package com.zebra.rfidscanner.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RfidRepository @Inject constructor(private val tagDao: TagDao) {

    // Mapa en memoria: epc -> readCount
    private val tagMap = ConcurrentHashMap<String, Int>(65536) // Capacidad inicial alta

    // FIX: contadores atómicos — sin contención entre hilos
    private val _totalReadsAtomic = AtomicInteger(0)
    private val _tagCountAtomic   = AtomicInteger(0)

    private val _tagCount   = MutableStateFlow(0)
    private val _totalReads = MutableStateFlow(0)
    private val _readRate   = MutableStateFlow(0f)
    private val _tags       = MutableStateFlow<List<TagEntry>>(emptyList())

    val tagCount:   StateFlow<Int>         = _tagCount.asStateFlow()
    val totalReads: StateFlow<Int>         = _totalReads.asStateFlow()
    val readRate:   StateFlow<Float>       = _readRate.asStateFlow()
    val allTags:    StateFlow<List<TagEntry>> = _tags.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Rate
    private var readsInWindow = 0
    private var windowStart   = System.currentTimeMillis()

    // FIX: throttle aumentado a 1000ms para alto volumen
    // La UI no necesita actualizarse más de 1 vez por segundo con miles de tags
    private val UI_THROTTLE_MS = 1000L
    private val lastUiUpdate   = AtomicLong(0L)

    // FIX: lista visible limitada a 500 tags máximo
    // Renderizar 50,000 filas congela la UI — el usuario no puede ver más de ~20 a la vez
    private val MAX_VISIBLE_TAGS = 500

    // FIX: cola sin-lock usando ConcurrentLinkedQueue — elimina contención en hilo SDK
    private val dbQueue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Boolean>>()
    private val dbJobRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    // FIX: contador de tags nuevos pendientes de flush — evita revisar la cola constantemente
    private val pendingCount = AtomicInteger(0)

    fun onEpcReceived(epc: String) {
        val isNew = tagMap.putIfAbsent(epc, 1) == null
        if (!isNew) tagMap.merge(epc, 1, Int::plus)

        // Contadores atómicos — sin bloqueo entre hilos
        val total = _totalReadsAtomic.incrementAndGet()
        if (isNew) _tagCountAtomic.incrementAndGet()

        // FIX TOTAL: actualizar totalReads en UI siempre que haya cambio visible
        // No dentro del throttle — así Total siempre es mayor o igual que Únicos
        _totalReads.value = total

        // Rate
        readsInWindow++
        val now = System.currentTimeMillis()
        val elapsed = (now - windowStart) / 1000f
        if (elapsed >= 2f) {
            _readRate.value = readsInWindow / elapsed
            readsInWindow   = 0
            windowStart     = now
        }

        // Throttle para actualizar lista y contador Únicos en UI
        val last = lastUiUpdate.get()
        if (now - last >= UI_THROTTLE_MS && lastUiUpdate.compareAndSet(last, now)) {
            _tagCount.value = _tagCountAtomic.get()

            val snapshot = tagMap.entries
                .take(MAX_VISIBLE_TAGS)
                .map { (epc, count) -> TagEntry(epc = epc, readCount = count) }
            _tags.value = snapshot
        }

        // Encolar escritura DB sin lock
        dbQueue.offer(Pair(epc, isNew))
        pendingCount.incrementAndGet()

        // Lanzar flush solo si no hay uno corriendo
        if (!dbJobRunning.get()) flushDbQueue()
    }

    private fun flushDbQueue() {
        if (!dbJobRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (pendingCount.get() > 0) {
                    // Drenar toda la cola en un solo batch
                    val batch = mutableListOf<Pair<String, Boolean>>()
                    var item = dbQueue.poll()
                    while (item != null) {
                        batch.add(item)
                        item = dbQueue.poll()
                    }
                    if (batch.isEmpty()) break
                    pendingCount.addAndGet(-batch.size)

                    // FIX: usar transacción Room para batch completo — mucho más rápido
                    try {
                        tagDao.insertOrUpdateBatch(batch.map { (epc, _) -> TagEntry(epc = epc) })
                    } catch (e: Exception) {
                        Log.e("Repository", "DB batch error", e)
                    }

                    // Pequeña pausa para no saturar CPU con flush continuo
                    if (pendingCount.get() > 0) delay(50)
                }
            } finally {
                dbJobRunning.set(false)
                // Si quedaron items mientras terminábamos, relanzar
                if (pendingCount.get() > 0) flushDbQueue()
            }
        }
    }

    fun getTagList(): List<String> = tagMap.keys().toList()

    suspend fun clearAll() {
        tagMap.clear()
        _totalReadsAtomic.set(0)
        _tagCountAtomic.set(0)
        _tagCount.value   = 0
        _totalReads.value = 0
        _readRate.value   = 0f
        _tags.value       = emptyList()
        readsInWindow     = 0
        windowStart       = System.currentTimeMillis()
        lastUiUpdate.set(0L)
        pendingCount.set(0)
        dbQueue.clear()
        withContext(Dispatchers.IO) { tagDao.deleteAll() }
    }
}
