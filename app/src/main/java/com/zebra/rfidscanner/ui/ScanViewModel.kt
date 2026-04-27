package com.zebra.rfidscanner.ui
 
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zebra.rfidscanner.data.RfidRepository
import com.zebra.rfidscanner.data.TagEntry
import com.zebra.rfidscanner.rfid.RfidManager
import com.zebra.rfidscanner.utils.SgtinDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
 
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val rfidManager: RfidManager,
    private val repository: RfidRepository
) : ViewModel() {
 
    val connectionState = rfidManager.connectionState
    val tagCount        = repository.tagCount
    val totalReads      = repository.totalReads
    val readRate        = repository.readRate
 
    val tags: StateFlow<List<TagEntry>> = repository.allTags.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )
 
    // FIX: decode se hace en Dispatchers.Default (hilo de cómputo) — no bloquea UI
    // Solo decodifica los primeros 300 para no gastar CPU con listas enormes
    val eanResults: StateFlow<List<SgtinDecoder.SgtinResult>> = repository.allTags
        .map { list ->
            list.take(300).map { SgtinDecoder.decode(it.epc) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
 
    private var isScanning = false
 
    fun initialize() = rfidManager.initialize()
    fun retry()      = rfidManager.retry()
 
    fun toggleScan(): Boolean {
        isScanning = if (isScanning) {
            rfidManager.stopInventory(); false
        } else {
            rfidManager.startInventory(); true
        }
        return isScanning
    }
 
    fun clearAll() = viewModelScope.launch { repository.clearAll() }
    fun getTagsForExport(): List<String> = repository.getTagList()
    fun release() = rfidManager.release()
 
    override fun onCleared() {
        super.onCleared()
        rfidManager.release()
    }
}
