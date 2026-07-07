package com.example.app_movile.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.app_movile.util.Logger
import androidx.core.app.NotificationCompat
import com.example.app_movile.R
import com.example.app_movile.data.db.SiagDatabase
import com.example.app_movile.data.db.entities.EventoCaida
import com.example.app_movile.data.db.entities.SolicitudAyuda
import com.example.app_movile.sync.SyncManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio de monitoreo crítico de alta disponibilidad.
 * Compara Firebase con Room y garantiza que las alertas nuevas o recientes disparen notificaciones.
 */
class EventListenerService : Service() {

    private val db = FirebaseDatabase.getInstance("https://gerento-74200-default-rtdb.firebaseio.com")
    private val auth = FirebaseAuth.getInstance()
    private val listeners = ConcurrentHashMap<String, ChildEventListener>()
    private val valueListeners = ConcurrentHashMap<String, ValueEventListener>()
    private val siagDatabase by lazy { SiagDatabase.getInstance(applicationContext) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifiedPrefs: SharedPreferences

    companion object {
        private const val TAG = "EventListenerService"
        // Caché para no repetir notificaciones de la misma alerta en una sesión activa
        private val notifiedAlerts = ConcurrentHashMap<String, Boolean>()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifiedPrefs = applicationContext.getSharedPreferences("notified_alerts", Context.MODE_PRIVATE)
        // Cargar llaves ya notificadas para no repetir alertas tras reinicios
        notifiedPrefs.all.keys.forEach { notifiedAlerts[it] = true }
        NotificationManager.createNotificationChannels(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, ">>> SERVICIO DE MONITOREO INICIADO <<<")
        startForeground(
            NotificationManager.NOTIFICATION_ID_LISTENER_SERVICE,
            NotificationManager.buildForegroundServiceNotification(
                applicationContext,
                "Siag activo",
                "Monitoreo de alertas en segundo plano"
            )
        )

        iniciarListeners()
        return START_STICKY
    }

    private fun iniciarListeners() {
        val uid = auth.currentUser?.uid ?: return
        
        // Listener de respuesta inmediata (Nodo rápido)
        setupUltimaAlertaListener(uid)

        serviceScope.launch {
            try {
                val dispositivos = SyncManager.obtenerDispositivosVinculados(applicationContext)
                Logger.d(TAG, "Configurando escucha para ${dispositivos.size} dispositivos")

                dispositivos.forEach { dispositivo ->
                    val id = dispositivo.deviceId
                    // Escuchar todas las rutas posibles del historial del reloj
                    setupChildListener("fallEvents/$id", id, dispositivo.nombreDispositivo, dispositivo.nombrePersona, true)
                    setupChildListener("helpRequests/$id", id, dispositivo.nombreDispositivo, dispositivo.nombrePersona, false)
                    setupChildListener("dispositivos/$id/alertas_sos", id, dispositivo.nombreDispositivo, dispositivo.nombrePersona, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en iniciarListeners: ${e.message}")
            }
        }
    }

    private fun setupUltimaAlertaListener(uid: String) {
        val path = "ultimaCaida/$uid"
        if (valueListeners.containsKey(path)) return
        val ref = db.getReference(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                procesarEvento(snapshot, "RELOJ", "Dispositivo", "Persona", isFall = true)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        valueListeners[path] = listener
    }

    private fun setupChildListener(path: String, deviceId: String, deviceName: String, personaName: String, isFall: Boolean) {
        if (listeners.containsKey(path)) return
        val ref = db.getReference(path)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                procesarEvento(snapshot, deviceId, deviceName, personaName, isFall)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) { Log.e(TAG, "Error en $path: ${e.message}") }
        }
        ref.addChildEventListener(listener)
        listeners[path] = listener
    }

    private fun procesarEvento(snapshot: DataSnapshot, deviceId: String, deviceName: String, personaName: String, isFall: Boolean) {
        // Parseo robusto del timestamp
        val rawTs = snapshot.child("timestamp_ms").value ?: snapshot.child("timestamp").value ?: snapshot.key
        val timestamp = when (rawTs) {
            is Number -> rawTs.toLong()
            is String -> rawTs.toLongOrNull() ?: System.currentTimeMillis()
            else -> System.currentTimeMillis()
        }

        // Extracción de ubicación
        val lat = snapshot.child("ubicacion/latitud").getValue(Double::class.java) 
                 ?: snapshot.child("latitud").getValue(Double::class.java) ?: 0.0
        val lng = snapshot.child("ubicacion/longitud").getValue(Double::class.java) 
                 ?: snapshot.child("longitud").getValue(Double::class.java) ?: 0.0
        
        // Identificador único para evitar duplicados en la sesión y entre reinicios
        val alertKey = "${if (isFall) "F" else "H"}_${deviceId}_$timestamp"
        if (notifiedAlerts.containsKey(alertKey)) return
        if (notifiedPrefs.contains(alertKey)) {
            notifiedAlerts[alertKey] = true
            return
        }

        serviceScope.launch {
            try {
                Logger.d(TAG, "Snapshot data: ${snapshot.value}")
            } catch (t: Throwable) {
                Log.w(TAG, "No se pudo volcar snapshot: ${t.message}")
            }
            // Verificar en la BD local (SiagDatabase)
            val existeEnRoom = if (isFall) {
                val lista = siagDatabase.eventoCaidaDao().getByCodigoReloj(deviceId)
                lista.any { kotlin.math.abs(it.timestamp - timestamp) <= 5000 }
            } else {
                val lista = siagDatabase.solicitudAyudaDao().getByCodigoReloj(deviceId)
                lista.any { kotlin.math.abs(it.timestamp - timestamp) <= 5000 }
            }

            // Ventana de 10 minutos para considerar "Tiempo Real"
            val esTiempoReal = (System.currentTimeMillis() - timestamp) < 600000

            // NOTIFICAR SI: Es reciente O no está en Room
            if (esTiempoReal || !existeEnRoom) {
                notifiedAlerts[alertKey] = true
                notifiedPrefs.edit().putBoolean(alertKey, true).apply()
                Log.d(TAG, "!!! ALERTA DETECTADA: DISPARANDO NOTIFICACIÓN EMERGENTE ($alertKey) !!!")

                if (isFall) {
                    val severidad = snapshot.child("severidad").value?.toString() 
                                 ?: snapshot.child("severity").value?.toString() ?: "caida_leve"
                    
                    siagDatabase.eventoCaidaDao().insertarOActualizar(EventoCaida(
                        timestamp = timestamp, latitud = lat, longitud = lng,
                        medicamentosActivos = "Ver en historial", codigoReloj = deviceId,
                        severidad = severidad, estadoRespuesta = "PENDIENTE"
                    ))

                    withContext(Dispatchers.Main) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                                androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                NotificationManager.showFallNotification(applicationContext, deviceId, severidad, timestamp, deviceName, personaName, lat, lng)
                            } else {
                                Log.w(TAG, "Permiso POST_NOTIFICATIONS no concedido para caída")
                            }
                        } catch (e: Exception) { 
                            Log.e(TAG, "Error notif caída: ${e.message}") 
                        }
                    }
                } else {
                    val mensaje = snapshot.child("mensaje").value?.toString() 
                                ?: snapshot.child("message").value?.toString() 
                                ?: snapshot.child("descripcion").value?.toString() ?: "Necesito ayuda"

                    siagDatabase.solicitudAyudaDao().insertarOActualizar(SolicitudAyuda(
                        timestamp = timestamp, codigoReloj = deviceId,
                        status = "pendiente", mensaje = mensaje, latitud = lat, longitud = lng
                    ))

                    withContext(Dispatchers.Main) {
                        try {
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                                androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS)
                                == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                NotificationManager.showHelpRequestNotification(applicationContext, deviceId, timestamp, deviceName, personaName, mensaje, lat, lng)
                            } else {
                                Log.w(TAG, "Permiso POST_NOTIFICATIONS no concedido para ayuda")
                            }
                        } catch (e: Exception) { 
                            Log.e(TAG, "Error notif ayuda: ${e.message}") 
                        }
                    }
                }
                // Broadcast para refrescar pantallas abiertas
                sendBroadcast(Intent("com.example.app_movile.NEW_EVENT_RECEIVED"))
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
