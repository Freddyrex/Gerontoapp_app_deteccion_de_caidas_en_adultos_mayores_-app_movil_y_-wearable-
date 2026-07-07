package com.example.app_movile.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.example.app_movile.data.db.AppDatabase
import com.example.app_movile.data.db.entities.EventoCaida
import com.example.app_movile.notifications.NotificationManager
import com.example.app_movile.util.PersonaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var caidasListener: ListenerRegistration? = null
    private var ayudaListeners: MutableList<com.google.firebase.database.ValueEventListener> = mutableListOf()
    private lateinit var watchBridge: WatchBridgeManager

    override fun onCreate() {
        super.onCreate()
        NotificationManager.createNotificationChannels(applicationContext)
        startForeground(
            NotificationManager.NOTIFICATION_ID_SYNC_SERVICE,
            NotificationManager.buildForegroundServiceNotification(
                applicationContext,
                "Siag en segundo plano",
                "Sincronizando datos y escuchando actualizaciones"
            )
        )
        Log.d(TAG, "SyncService iniciado")
        watchBridge = WatchBridgeManager(this)
        watchBridge.iniciar()
        iniciarListeners()
        iniciarPollerEmergencias()
    }

    private fun iniciarPollerEmergencias() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val dispositivos = SyncManager.obtenerDispositivosVinculados(this@SyncService)
                    val dbRoom = AppDatabase.getInstance(this@SyncService)
                    
                    dispositivos.filter { it.tipo == "reloj" }.forEach { reloj ->
                        // 1. POLLER DE AYUDA
                        val historialAyuda = SyncManager.obtenerHistorialAyuda(this@SyncService, reloj.deviceId)
                        val localAyudaMax = dbRoom.solicitudAyudaDao().getByCodigoReloj(reloj.deviceId)
                            .maxOfOrNull { it.timestamp } ?: 0L
                        
                        historialAyuda.filter { it.timestamp > localAyudaMax }.forEach { ev ->
                            try {
                                val lat = ev.ubicacion?.latitud ?: 0.0
                                val lng = ev.ubicacion?.longitud ?: 0.0
                                dbRoom.solicitudAyudaDao().insert(com.example.app_movile.data.db.entities.SolicitudAyuda(
                                    timestamp = ev.timestamp, latitud = lat, longitud = lng,
                                    mensaje = ev.mensaje, codigoReloj = reloj.deviceId, status = ev.status
                                ))
                                withContext(Dispatchers.Main) {
                                    NotificationManager.showHelpRequestNotification(
                                        this@SyncService, reloj.deviceId, ev.timestamp,
                                        reloj.nombreDispositivo, reloj.nombrePersona, ev.mensaje
                                    )
                                }
                            } catch (e: Exception) { Log.w(TAG, "Error poller ayuda: ${e.message}") }
                        }

                        // 2. POLLER DE CAÍDAS
                        val historialCaidas = SyncManager.obtenerHistorialCaidas(this@SyncService, reloj.deviceId)
                        val localCaidasMax = dbRoom.eventoCaidaDao().getByCodigoReloj(reloj.deviceId)
                            .maxOfOrNull { it.timestamp } ?: 0L

                        historialCaidas.filter { it.timestamp > localCaidasMax }.forEach { ev ->
                            try {
                                val lat = ev.ubicacion?.latitud ?: 0.0
                                val lng = ev.ubicacion?.longitud ?: 0.0
                                dbRoom.eventoCaidaDao().insert(EventoCaida(
                                    timestamp = ev.timestamp, latitud = lat, longitud = lng,
                                    medicamentosActivos = "Ver historial", codigoReloj = reloj.deviceId,
                                    severidad = ev.severidad, estadoRespuesta = "PENDIENTE"
                                ))
                                withContext(Dispatchers.Main) {
                                    NotificationManager.showFallNotification(
                                        this@SyncService, reloj.deviceId, ev.severidad,
                                        ev.timestamp, reloj.nombreDispositivo, reloj.nombrePersona, lat, lng
                                    )
                                }
                            } catch (e: Exception) { Log.w(TAG, "Error poller caídas: ${e.message}") }
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Error general poller: ${e.message}") }
                delay(3000L) 
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun iniciarListeners() {
        caidasListener = SyncManager.observarCaidas(this,
            onCaidaDetectada = { timestamp, origen, severidad ->
                handleCaida(timestamp, origen, severidad)
            },
            onError = { Log.e(TAG, "Error caídas: $it") }
        )
        iniciarListenersHelpRequests()
    }

    private fun handleCaida(timestamp: Long, deviceId: String, severidad: String) {
        scope.launch {
            val nombrePersona = PersonaManager.getNombre(applicationContext)
            NotificationManager.showFallNotification(
                this@SyncService, deviceId, severidad, timestamp, "Reloj", nombrePersona, 0.0, 0.0
            )
        }
    }

    private fun iniciarListenersHelpRequests() {
        scope.launch(Dispatchers.IO) {
            val dispositivos = SyncManager.obtenerDispositivosVinculados(this@SyncService)
            dispositivos.filter { it.tipo == "reloj" }.forEach { reloj ->
                val ref = com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("helpRequests/${reloj.deviceId}")
                val listener = object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        scope.launch(Dispatchers.IO) {
                            val dbRoom = AppDatabase.getInstance(this@SyncService)
                            for (child in snapshot.children) {
                                val ts = (child.child("timestamp_ms").value as? Number)?.toLong() ?: 0L
                                val exists = dbRoom.solicitudAyudaDao().getByCodigoReloj(reloj.deviceId)
                                    .any { it.timestamp == ts }
                                if (!exists) {
                                    val msg = child.child("message").getValue(String::class.java) ?: "Ayuda"
                                    withContext(Dispatchers.Main) {
                                        NotificationManager.showHelpRequestNotification(
                                            this@SyncService, reloj.deviceId, ts,
                                            reloj.nombreDispositivo, reloj.nombrePersona, msg
                                        )
                                    }
                                }
                            }
                        }
                    }
                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
                }
                ref.addValueEventListener(listener)
                ayudaListeners.add(listener)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        caidasListener?.remover()
        watchBridge.detener()
    }

    companion object {
        private const val TAG = "SyncService"
    }
}
