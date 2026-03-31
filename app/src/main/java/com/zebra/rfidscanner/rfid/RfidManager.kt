package com.zebra.rfidscanner.rfid
 
import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import com.zebra.rfidscanner.data.RfidRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
 
private const val TAG = "RfidManager"
 
@Singleton
class RfidManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RfidRepository
) : RfidEventsListener {
 
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val readerName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
 
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
 
    private var readers: Readers? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
 
    // Nombre del lector confirmado en RFD123
    private val READER_NAME = "RFD4030-G00B700-US"
 
    fun initialize() {
        scope.launch {
            // Limpiar sesión SDK anterior al iniciar — fix para reinstalación / reinicio PDT
            try {
                reader?.Events?.removeEventsListener(this@RfidManager)
                reader?.disconnect()
            } catch (_: Exception) {}
            try { readers?.Dispose() } catch (_: Exception) {}
            reader = null
            readers = null
            delay(1000)
            createAndConnect()
        }
    }
 
    private suspend fun createAndConnect() {
        _connectionState.value = ConnectionState.Connecting
        try {
            try { readers?.Dispose() } catch (_: Exception) {}
            readers = null
            reader = null
            delay(500)
 
            // Intentar los 4 transportes igual que el ejemplo oficial de Zebra
            readers = Readers(context, ENUM_TRANSPORT.BLUETOOTH)
            var list = readers?.GetAvailableRFIDReaderList()
            Log.i(TAG, "BLUETOOTH: ${list?.size ?: 0} readers")
            list?.forEach { Log.i(TAG, "  -> ${it.name}") }
 
            if (list.isNullOrEmpty()) {
                Log.i(TAG, "Probando SERVICE_SERIAL...")
                readers?.setTransport(ENUM_TRANSPORT.SERVICE_SERIAL)
                list = readers?.GetAvailableRFIDReaderList()
                Log.i(TAG, "SERVICE_SERIAL: ${list?.size ?: 0} readers")
            }
 
            if (list.isNullOrEmpty()) {
                Log.i(TAG, "Probando SERVICE_USB...")
                readers?.setTransport(ENUM_TRANSPORT.SERVICE_USB)
                list = readers?.GetAvailableRFIDReaderList()
                Log.i(TAG, "SERVICE_USB: ${list?.size ?: 0} readers")
            }
 
            if (list.isNullOrEmpty()) {
                Log.i(TAG, "Probando ALL...")
                readers?.setTransport(ENUM_TRANSPORT.ALL)
                list = readers?.GetAvailableRFIDReaderList()
                Log.i(TAG, "ALL: ${list?.size ?: 0} readers")
                list?.forEach { Log.i(TAG, "  -> ${it.name}") }
            }
 
            if (list.isNullOrEmpty()) {
                _connectionState.value = ConnectionState.Error(
                    "No se encontró el RFD4030.\n• Verifique que esté encendido\n• Presione Reintentar"
                )
                return
            }
 
            // Buscar por nombre exacto, si no tomar el primero
            readerDevice = list.firstOrNull { it.name.startsWith(READER_NAME) } ?: list[0]
            Log.i(TAG, "Usando reader: ${readerDevice?.name}")
 
            reader = readerDevice!!.getRFIDReader()
 
            if (reader == null) {
                _connectionState.value = ConnectionState.Error("getRFIDReader() devolvió null")
                return
            }
 
            doConnect()
 
        } catch (e: Exception) {
            Log.e(TAG, "createAndConnect error", e)
            _connectionState.value = ConnectionState.Error("Error: ${e.message}")
        }
    }
 
    private fun doConnect() {
        try {
            // Siempre desconectar limpio antes de conectar — evita estado fantasma
            // que ocurre al reinstalar o reiniciar la PDT
            if (reader?.isConnected == true) {
                Log.i(TAG, "Desconectando sesión anterior...")
                try {
                    reader?.Events?.removeEventsListener(this)
                    reader?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Disconnect previo falló (ignorado): ${e.message}")
                }
                Thread.sleep(800)
            }
 
            Log.i(TAG, "Conectando a ${readerDevice?.name}...")
            reader?.connect()
            configureReader()
            _connectionState.value = ConnectionState.Connected(readerDevice?.name ?: "RFD4030")
            Log.i(TAG, "Conectado OK")
 
        } catch (e: InvalidUsageException) {
            Log.e(TAG, "InvalidUsage: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("InvalidUsage: ${e.message}")
        } catch (e: OperationFailureException) {
            Log.e(TAG, "OperationFailure: ${e.results} ${e.vendorMessage}", e)
            _connectionState.value = ConnectionState.Error("Fallo: ${e.results} ${e.vendorMessage}")
        }
    }
 
    private fun configureReader() {
        if (reader?.isConnected != true) return
        try {
            val triggerInfo = TriggerInfo()
            triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE)
            triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE)
 
            reader!!.Events.addEventsListener(this)
            reader!!.Events.setHandheldEvent(true)
            reader!!.Events.setTagReadEvent(true)
            reader!!.Events.setAttachTagDataWithReadEvent(false)
            reader!!.Events.setReaderDisconnectEvent(true)
 
            reader!!.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)
            reader!!.Config.setStartTrigger(triggerInfo.StartTrigger)
            reader!!.Config.setStopTrigger(triggerInfo.StopTrigger)
 
            val maxPower = reader!!.ReaderCapabilities.transmitPowerLevelValues.size - 1
            val antennaConfig = reader!!.Config.Antennas.getAntennaRfConfig(1)
            antennaConfig.setTransmitPowerIndex(maxPower)
            antennaConfig.setrfModeTableIndex(0)
            antennaConfig.setTari(0)
            reader!!.Config.Antennas.setAntennaRfConfig(1, antennaConfig)
 
            val singControl = reader!!.Config.Antennas.getSingulationControl(1)
            singControl.setSession(SESSION.SESSION_S0)
            singControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A)
            singControl.Action.setSLFlag(SL_FLAG.SL_ALL)
            reader!!.Config.Antennas.setSingulationControl(1, singControl)
 
            reader!!.Actions.PreFilters.deleteAll()
 
            // Silenciar beep del RFD4030
            try {
                val beeperConfig = reader!!.Config.beeperVolume
                reader!!.Config.beeperVolume = BEEPER_VOLUME.QUIET_BEEP
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo configurar beeper: ${e.message}")
            }
 
            Log.i(TAG, "Reader configurado OK")
        } catch (e: Exception) {
            Log.e(TAG, "configureReader error", e)
        }
    }
 
    override fun eventReadNotify(e: RfidReadEvents?) {
        try {
            val tags = reader?.Actions?.getReadTags(100)
            tags?.forEach { tag ->
                val epc = tag?.tagID
                if (!epc.isNullOrBlank()) repository.onEpcReceived(epc)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "eventReadNotify error", ex)
        }
    }
 
    override fun eventStatusNotify(e: RfidStatusEvents?) {
        val type = e?.StatusEventData?.statusEventType
        Log.d(TAG, "Status: $type")
        when (type) {
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT -> {
                val handEvent = e?.StatusEventData?.HandheldTriggerEventData?.handheldEvent
                if (handEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    startInventory()
                } else if (handEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    stopInventory()
                }
            }
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                _connectionState.value = ConnectionState.Disconnected
                reader = null
                scope.launch { delay(3000); createAndConnect() }
            }
            else -> {}
        }
    }
 
    fun retry() { scope.launch { createAndConnect() } }
 
    fun startInventory(): Boolean = try {
        reader?.Actions?.Inventory?.perform(); true
    } catch (e: Exception) { Log.e(TAG, "startInventory error", e); false }
 
    fun stopInventory(): Boolean = try {
        reader?.Actions?.Inventory?.stop(); true
    } catch (e: Exception) { Log.e(TAG, "stopInventory error", e); false }
 
    fun isConnected(): Boolean = reader?.isConnected == true
 
    fun release() {
        scope.cancel()
        try {
            reader?.Events?.removeEventsListener(this)
            reader?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "release error", e)
        } finally {
            reader = null
            try { readers?.Dispose() } catch (_: Exception) {}
            readers = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}
 
