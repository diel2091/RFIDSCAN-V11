package com.zebra.rfidscanner.ui
 
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zebra.rfidscanner.data.RfidRepository
import com.zebra.rfidscanner.data.TagEntry
import com.zebra.rfidscanner.rfid.RfidManager
import com.zebra.rfidscanner.utils.SgtinDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
 
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val rfidManager: RfidManager,
    private val repository: RfidRepository
) : ViewModel() {
 
    val connectionState = rfidManager.connectionState
    val tagCount   = repository.tagCount
    val totalReads = repository.totalReads
    val readRate   = repository.readRate
 
    val tags: StateFlow<List<TagEntry>> = repository.allTags.stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )
 
    val eanResults: StateFlow<List<SgtinDecoder.SgtinResult>> = repository.allTags.map { list ->
        list.map { SgtinDecoder.decode(it.epc) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
 
    private var isScanning = false
 
    fun initialize() = rfidManager.initialize()
    fun retry() = rfidManager.retry()
 
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
 
    // Llamado desde ScanActivity antes de reiniciar — libera SDK Bluetooth limpiamente
    fun release() = rfidManager.release()
 
    override fun onCleared() {
        super.onCleared()
        rfidManager.release()
    }
}
 
