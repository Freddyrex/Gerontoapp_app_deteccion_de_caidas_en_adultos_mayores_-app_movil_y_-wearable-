package com.example.app_movile.sync

import android.content.Context
import android.util.Log
import com.example.app_movile.data.db.entities.Medicamento
import com.example.app_movile.notifications.NotificationManager as EventNotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * WatchBridgeManager — Puente entre el esquema Firebase del teléfono y el del reloj.
 *
 * ═══════════════════════════════════════════════════════════════
 *  PRINCIPIO DE DISEÑO
 * ═══════════════════════════════════════════════════════════════
 *  • El TELÉFONO siempre opera con Firebase Auth activo (uid del usuario).
 *    Toda la información del usuario vive bajo rutas que incluyen ese uid.
 *
 *  • El RELOJ es standalone (Wear OS sin app companion obligatoria).
 *    Usa HTTP REST anónimo, identificándose sólo por su deviceId local:
 *      /dispositivos/{deviceId}/...
 *
 *  • El CÓDIGO DE 3 DÍGITOS es exclusivamente para el emparejamiento inicial.
 *    Sirve para que el usuario confirme "este reloj es el mío" y que el
 *    teléfono conozca el deviceId del reloj. Una vez vinculado, ese código
 *    ya no se usa en el flujo de datos.
 *
 * ═══════════════════════════════════════════════════════════════
 *  ESTRUCTURA FIREBASE
 * ═══════════════════════════════════════════════════════════════
 *
 *  Nodo del RELOJ (escrito por el reloj, leído por el teléfono):
 *    /dispositivos/{deviceId}/estado/codigo_vinculacion  ← código 3 dígitos
 *    /dispositivos/{deviceId}/estado/vinculado           ← false → true
 *    /dispositivos/{deviceId}/estado/uid                 ← uid del teléfono (escribe el teléfono)
 *    /dispositivos/{deviceId}/caidas/{timestamp}         ← caídas detectadas
 *    /dispositivos/{deviceId}/confirmaciones/{timestamp} ← confirmaciones de meds
 *
 *  Nodo del RELOJ (escrito por el teléfono, leído por el reloj):
 *    /dispositivos/{deviceId}/medicamentos               ← lista de meds activos
 *
 *  Nodos del TELÉFONO (con uid, rutas seguras):
 *    /medicamentos/{uid}                                 ← fuente de verdad de meds
 *    /caidas/{uid}/{deviceId}/{pushId}                   ← historial de caídas
 *    /ultimaCaida/{uid}                                  ← última caída (tiempo real)
 *    /confirmaciones_medicamentos/{uid}/{timestamp}      ← confirmaciones desde reloj
 *    /vinculacion/{uid}/deviceId                         ← deviceId del reloj vinculado
 *
 * ═══════════════════════════════════════════════════════════════
 *  FLUJO DE VINCULACIÓN
 * ═══════════════════════════════════════════════════════════════
 *  1. Reloj genera código 3 dígitos y lo escribe en /dispositivos/{deviceId}/estado
 *  2. Usuario lo escribe en VinculacionActivity del teléfono
 *  3. El teléfono (autenticado) busca ese código en /dispositivos
 *  4. Encuentra el deviceId del reloj → lo asocia a su uid:
 *       /vinculacion/{uid}/deviceId = deviceId
 *       /dispositivos/{deviceId}/estado/uid = uid
 *       /dispositivos/{deviceId}/estado/vinculado = true
 *  5. A partir de aquí el bridge puede sincronizar datos en ambas direcciones
 */
class WatchBridgeManager(private val context: Context) {

    companion object {
        private const val TAG          = "WatchBridge"
        private const val FIREBASE_URL = "https://gerento-74200-default-rtdb.firebaseio.com"
        private const val PREFS        = "siag_movile"
    }

    private val db    = FirebaseDatabase.getInstance(FIREBASE_URL)
    private val auth  = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Listeners activos
    private var caidasListener:         ValueEventListener? = null
    private var confirmacionesListener: ValueEventListener? = null
    private var sosListener:            ValueEventListener? = null
    private var vinculacionesListener:  ValueEventListener? = null

    // deviceId del reloj vinculado (persistido en prefs del teléfono)
    private val watchDeviceIdPref: String?
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("watch_device_id", null)

    // Callbacks para SyncService / UI
    var onCaidaDesdeReloj:   ((timestamp: Long, severidad: String) -> Unit)? = null
    var onConfirmacionMed:   ((nombre: String, tomado: Boolean, timestamp: Long) -> Unit)? = null
    var onRelojVinculado:    ((deviceId: String) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────
    //  INICIO / PARADA
    // ─────────────────────────────────────────────────────────────

    fun iniciar() {
        val uid = auth.currentUser?.uid ?: run {
            Log.w(TAG, "iniciar: sin sesión activa — esperar login antes de iniciar bridge")
            return
        }
        Log.d(TAG, "WatchBridgeManager iniciado uid=$uid")

        val did = watchDeviceIdPref
        if (did != null) {
            // Reloj ya vinculado: activar listeners de datos
            Log.d(TAG, "Reloj ya vinculado deviceId=$did")
            escucharCaidasDelReloj(uid, did)
            escucharConfirmacionesDelReloj(uid, did)
            escucharAlertasSOSDelReloj(uid, did)
        } else {
            // Sin reloj vinculado: solo escuchar si hay un reloj esperando
            Log.d(TAG, "Sin reloj vinculado aún — esperando vinculación")
        }
    }

    fun detener() {
        val did = watchDeviceIdPref
        did?.let { deviceId ->
            caidasListener?.let {
                db.getReference("dispositivos/$deviceId/caidas").removeEventListener(it)
                caidasListener = null
            }
            confirmacionesListener?.let {
                db.getReference("dispositivos/$deviceId/confirmaciones").removeEventListener(it)
                confirmacionesListener = null
            }
            sosListener?.let {
                db.getReference("dispositivos/$deviceId/alertas_sos").removeEventListener(it)
                sosListener = null
            }
        }
        vinculacionesListener?.let {
            db.getReference("dispositivos").removeEventListener(it)
            vinculacionesListener = null
        }
        Log.d(TAG, "WatchBridgeManager detenido")
    }

    // ─────────────────────────────────────────────────────────────
    //  VINCULACIÓN
    //  El código de 3 dígitos es solo para identificar el reloj correcto.
    //  Una vez encontrado el deviceId, la auth del teléfono (uid) se
    //  asocia al reloj y el código ya no se necesita.
    // ─────────────────────────────────────────────────────────────

    /**
     * El usuario escribió el código del reloj → buscamos en /dispositivos
     * el nodo que tenga ese codigo_vinculacion.
     *
     * @return true si el emparejamiento fue exitoso
     */
    suspend fun confirmarVinculacionConReloj(codigoIngresado: String): Boolean {
        val uid = auth.currentUser?.uid ?: run {
            Log.e(TAG, "confirmarVinculacion: teléfono sin sesión activa")
            return false
        }
        val codigoNormalizado = normalizarCodigoVinculacion(codigoIngresado) ?: run {
            Log.w(TAG, "confirmarVinculacion: código inválido recibido=$codigoIngresado")
            return false
        }

        return try {
            val snapshot = db.getReference("dispositivos").get().await()

            var candidatoDeviceId: String? = null
            var encontradosConCodigo = 0
            var bloqueadosPorOtroUid = 0

            for (child in snapshot.children) {
                val deviceId = child.key ?: continue
                val estado = child.child("estado")

                // Puede venir como String o Number según cómo lo haya escrito el reloj.
                val codigoReloj = normalizarCodigoVinculacion(
                    estado.child("codigo_vinculacion").value
                        ?: child.child("codigo_vinculacion").value
                ) ?: continue
                if (codigoReloj != codigoNormalizado) continue

                encontradosConCodigo++
                val uidExistente = (
                    estado.child("uid").value?.toString()
                        ?: child.child("uid").value?.toString()
                    )?.trim().orEmpty().ifBlank { null }
                val vinculado = estado.child("vinculado").getValue(Boolean::class.java)
                    ?: child.child("vinculado").getValue(Boolean::class.java)
                    ?: false

                // Solo bloqueamos si el reloj está realmente vinculado a otro uid.
                if (vinculado && uidExistente != null && uidExistente != uid) {
                    bloqueadosPorOtroUid++
                    Log.w(TAG, "Código coincidente pero bloqueado: deviceId=$deviceId uid=$uidExistente vinculado=$vinculado")
                    continue
                }

                candidatoDeviceId = deviceId
                break
            }

            val deviceId = candidatoDeviceId ?: run {
                when {
                    encontradosConCodigo == 0 -> Log.w(TAG, "No se encontró reloj con código: $codigoNormalizado")
                    bloqueadosPorOtroUid > 0 -> Log.w(TAG, "Código $codigoNormalizado encontrado, pero todos los relojes están vinculados a otro usuario")
                    else -> Log.w(TAG, "No se encontró reloj disponible para código: $codigoNormalizado")
                }
                return false
            }

            Log.d(TAG, "Reloj identificado: deviceId=$deviceId código=$codigoNormalizado")

            db.getReference("dispositivos/$deviceId").updateChildren(
                mapOf(
                    // Escribimos en ambos formatos para compatibilidad reloj/app.
                    "estado/vinculado" to true,
                    "estado/uid" to uid,
                    "estado/ultima_sincro" to System.currentTimeMillis(),
                    "vinculado" to true,
                    "uid" to uid,
                    "ultima_sincro" to System.currentTimeMillis()
                )
            ).await()

            db.getReference("vinculacion/$uid").updateChildren(
                mapOf(
                    "deviceId" to deviceId,
                    "fechaVinculo" to System.currentTimeMillis(),
                    "codigoUsado" to codigoNormalizado
                )
            ).await()

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("watch_device_id", deviceId)
                .apply()

            // Limpiar listeners antiguos si existen
            val oldDid = watchDeviceIdPref
            if (oldDid != null && oldDid != deviceId) {
                caidasListener?.let {
                    db.getReference("dispositivos/$oldDid/caidas").removeEventListener(it)
                    caidasListener = null
                }
                confirmacionesListener?.let {
                    db.getReference("dispositivos/$oldDid/confirmaciones").removeEventListener(it)
                    confirmacionesListener = null
                }
                sosListener?.let {
                    db.getReference("dispositivos/$oldDid/alertas_sos").removeEventListener(it)
                    sosListener = null
                }
            }

            // Iniciar nuevos listeners
            escucharCaidasDelReloj(uid, deviceId)
            escucharConfirmacionesDelReloj(uid, deviceId)
            escucharAlertasSOSDelReloj(uid, deviceId)

            onRelojVinculado?.invoke(deviceId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error confirmando vinculación", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  MEDICAMENTOS  teléfono → reloj
    //  Escribe en /dispositivos/{deviceId}/medicamentos
    //  con el formato que parsea el reloj: {nombre, dosis, horario, esAltoRiesgo}
    // ─────────────────────────────────────────────────────────────

    suspend fun sincronizarMedicamentosAlReloj(medicamentos: List<Medicamento>): Boolean {
        // La sesión del teléfono siempre debe estar activa para llegar aquí
        if (auth.currentUser == null) {
            Log.w(TAG, "sincronizarMeds: sin sesión activa")
            return false
        }
        val deviceId = watchDeviceIdPref ?: run {
            Log.d(TAG, "sincronizarMeds: sin reloj vinculado aún")
            return false
        }
        return try {
            // Formato exacto que parsea FirebaseSyncManager.parseMedicamentos() en el reloj
            val medList = medicamentos.filter { it.activo }.map { med ->
                mapOf(
                    "nombre"       to med.nombre,
                    "dosis"        to med.dosis,
                    // El reloj muestra un solo horario; usamos horaReferencia o primer horario
                    "horario"      to (med.horaReferencia ?: med.horarios.firstOrNull() ?: ""),
                    "esAltoRiesgo" to med.esAltoRiesgo,
                    "vigenciaDesde" to (med.vigenciaDesde ?: ""),
                    "vigenciaHasta" to (med.vigenciaHasta ?: ""),
                    "siempreAvisar" to med.siempreAvisar
                )
            }
            Log.d(TAG, "WatchBridge sincronizando al dispositivo $deviceId: total=${medicamentos.size}, activos=${medList.size}")
            db.getReference("dispositivos/$deviceId").updateChildren(
                mapOf(
                    "medicamentos"  to medList,
                    "ultima_sincro" to System.currentTimeMillis()
                )
            ).await()
            Log.d(TAG, "Meds → reloj $deviceId: ${medList.size} activos")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sincronizando meds al reloj: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  CAÍDAS  reloj → teléfono
    //  El reloj escribe en /dispositivos/{deviceId}/caidas/{timestamp}
    //  El bridge espeja en /caidas/{uid}/{deviceId}/{pushId}
    //                   y en /ultimaCaida/{uid}  (para notificación inmediata)
    // ─────────────────────────────────────────────────────────────

    private fun escucharCaidasDelReloj(uid: String, deviceId: String) {
        val listener = object : ValueEventListener {
            private val procesados = mutableSetOf<Long>()

            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    // El reloj guarda la caída con la clave = timestamp numérico
                    val timestamp = child.key?.toLongOrNull()
                        ?: child.child("timestamp").value as? Long
                        ?: continue
                    if (timestamp in procesados) continue

                    // Ignorar caídas ya respondidas
                    val estadoCaida = child.child("estado").value as? String ?: "PENDIENTE"
                    if (estadoCaida == "USUARIO_ESTA_BIEN") {
                        procesados.add(timestamp)
                        continue
                    }

                    procesados.add(timestamp)
                    val magnitud = medirMagnitud(child)
                    val severidad = parsearSeveridad(child, magnitud)

                    Log.d(TAG, "Caída desde reloj: ts=$timestamp sev=$severidad mag=$magnitud")

                    // Espejo en rutas del teléfono (con uid) para historial y notificación
                    scope.launch {
                        espejearCaida(uid, deviceId, timestamp, severidad, child)
                        // Persistir en Room para que UI muestre historial sin recargar
                        try {
                            val dbRoom = com.example.app_movile.data.db.AppDatabase.getInstance(context)
                            val evento = com.example.app_movile.data.db.entities.EventoCaida(
                                timestamp = timestamp,
                                latitud = 0.0,
                                longitud = 0.0,
                                medicamentosActivos = "",
                                codigoReloj = deviceId,
                                severidad = severidad,
                                estadoRespuesta = "PENDIENTE"
                            )
                            dbRoom.eventoCaidaDao().insert(evento)
                            // Notificar UI local para refrescar inmediatamente
                            val intent = android.content.Intent("com.example.app_movile.ACTION_LOCAL_CAIDA")
                            intent.putExtra("timestamp", timestamp)
                            intent.putExtra("severidad", severidad)
                            intent.putExtra("codigoReloj", deviceId)
                            context.sendBroadcast(intent)

                            val nombres = obtenerNombresReloj(deviceId)
                            EventNotificationManager.showFallNotification(
                                context = context,
                                deviceId = deviceId,
                                severidad = severidad,
                                timestamp = timestamp,
                                deviceName = nombres.first,
                                personaName = nombres.second
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo persistir caida localmente: ${e.message}")
                        }
                    }
                    onCaidaDesdeReloj?.invoke(timestamp, severidad)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error escuchando caídas reloj: ${error.message}")
            }
        }
        db.getReference("dispositivos/$deviceId/caidas").addValueEventListener(listener)
        caidasListener = listener
        Log.d(TAG, "Escuchando caídas del reloj $deviceId")
    }

    private fun medirMagnitud(snapshot: DataSnapshot): Double {
        val magnitudDirecta = snapshot.child("magnitudMaxima").getValue(Number::class.java)?.toDouble()
        if (magnitudDirecta != null) return magnitudDirecta
        snapshot.child("detalle/svm").getValue(Number::class.java)?.let { return it.toDouble() }

        val ax = snapshot.child("detalle/ax").getValue(Number::class.java)?.toDouble() ?: 0.0
        val ay = snapshot.child("detalle/ay").getValue(Number::class.java)?.toDouble() ?: 0.0
        val az = snapshot.child("detalle/az").getValue(Number::class.java)?.toDouble() ?: 0.0
        return kotlin.math.sqrt(ax * ax + ay * ay + az * az)
    }

    private fun parsearSeveridad(snapshot: DataSnapshot, magnitud: Double): String {
        val tipoRaw = snapshot.child("tipo").getValue(String::class.java)
            ?.lowercase()?.replace("_", " ")?.trim()
        return when {
            tipoRaw?.contains("grave") == true -> "grave"
            tipoRaw?.contains("leve") == true -> "media"
            tipoRaw?.contains("movimiento brusc") == true -> "movimiento_brusco"
            magnitud >= 50.0 -> "grave"
            magnitud >= 30.0 -> "media"
            else -> "leve"
        }
    }

    private suspend fun espejearCaida(
        uid: String,
        deviceId: String,
        timestamp: Long,
        severidad: String,
        snap: DataSnapshot
    ) {
        try {
            val datos: MutableMap<String, Any?> = mutableMapOf(
                "timestamp"       to timestamp,
                "severidad"       to severidad,
                "dispositoOrigen" to "reloj",
                "notificada"      to false,
                "respondida"      to false,
                "canal"           to "firebase"
            )
            val accelData = mutableMapOf<String, Any?>()
            snap.child("accelX").getValue(Number::class.java)?.let { accelData["accelX"] = it.toDouble() }
            snap.child("accelY").getValue(Number::class.java)?.let { accelData["accelY"] = it.toDouble() }
            snap.child("accelZ").getValue(Number::class.java)?.let { accelData["accelZ"] = it.toDouble() }
            snap.child("magnitudMaxima").getValue(Number::class.java)?.let { accelData["magnitudMaxima"] = it.toDouble() }
            snap.child("detalle/svm").getValue(Number::class.java)?.let { accelData["svm"] = it.toDouble() }
            snap.child("detalle/ax").getValue(Number::class.java)?.let { accelData["ax"] = it.toDouble() }
            snap.child("detalle/ay").getValue(Number::class.java)?.let { accelData["ay"] = it.toDouble() }
            snap.child("detalle/az").getValue(Number::class.java)?.let { accelData["az"] = it.toDouble() }
            if (accelData.isNotEmpty()) datos["datosAcelerometro"] = accelData

            // Historial bajo uid (ruta segura del teléfono)
            db.getReference("caidas/$uid/$deviceId").push().setValue(datos).await()
            // Nodo tiempo real para notificación inmediata
            db.getReference("ultimaCaida/$uid").updateChildren(
                datos + mapOf("codigoReloj" to deviceId)
            ).await()
            // También persistir en Room (historial centralizado)
            try {
                val dbRoom = com.example.app_movile.data.db.AppDatabase.getInstance(context)
                val eventoLocal = com.example.app_movile.data.db.entities.EventoCaida(
                    timestamp = timestamp,
                    latitud = 0.0,
                    longitud = 0.0,
                    medicamentosActivos = "",
                    codigoReloj = deviceId,
                    severidad = severidad,
                    estadoRespuesta = "PENDIENTE"
                )
                val id = dbRoom.eventoCaidaDao().insert(eventoLocal)
                val intent = android.content.Intent("com.example.app_movile.ACTION_LOCAL_CAIDA")
                intent.putExtra("timestamp", timestamp)
                intent.putExtra("severidad", severidad)
                intent.putExtra("codigoReloj", deviceId)
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error guardando caida local en espejearCaida: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error espejeando caída: ${e.message}")
        }
    }

    private fun escucharAlertasSOSDelReloj(uid: String, deviceId: String) {
        val listener = object : ValueEventListener {
            private val procesadas = mutableSetOf<String>()

            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    try {
                        val pushId = child.key ?: continue
                        if (pushId in procesadas) continue
                        procesadas.add(pushId)

                        val ts = (child.child("timestamp").value as? Long)
                            ?: (child.child("timestamp_ms").value as? Number)?.toLong()
                            ?: System.currentTimeMillis()
                        val msg = child.child("descripcion").getValue(String::class.java)
                            ?: child.child("message").getValue(String::class.java)
                            ?: "Necesito asistencia"
                        // Persistir en Room
                        try {
                            val dbRoom = com.example.app_movile.data.db.AppDatabase.getInstance(context)
                            val lat = child.child("location/lat").getValue(Double::class.java) ?: 0.0
                            val lng = child.child("location/lng").getValue(Double::class.java) ?: 0.0
                            val solicitud = com.example.app_movile.data.db.entities.SolicitudAyuda(
                                timestamp = ts,
                                latitud = lat,
                                longitud = lng,
                                mensaje = msg,
                                codigoReloj = deviceId,
                                status = child.child("status").getValue(String::class.java) ?: "pendiente"
                            )
                            // insert on IO
                            scope.launch {
                                val newId = dbRoom.solicitudAyudaDao().insert(solicitud)
                                // Notificar UI local
                                val intent = android.content.Intent("com.example.app_movile.ACTION_LLAMADO_AYUDA")
                                context.sendBroadcast(intent)

                                val nombres = obtenerNombresReloj(deviceId)
                                EventNotificationManager.showHelpRequestNotification(
                                    context = context,
                                    deviceId = deviceId,
                                    timestamp = ts,
                                    deviceName = nombres.first,
                                    personaName = nombres.second,
                                    mensaje = msg
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo persistir solicitud ayuda: ${e.message}")
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Error escuchando alertas_sos en reloj $deviceId: ${error.message}")
            }
        }
        db.getReference("dispositivos/$deviceId/alertas_sos").addValueEventListener(listener)
        sosListener = listener
        Log.d(TAG, "Escuchando alertas_sos del reloj $deviceId")
    }

    // ─────────────────────────────────────────────────────────────
    //  CONFIRMACIONES DE MEDICAMENTOS  reloj → teléfono
    //  El reloj escribe en /dispositivos/{deviceId}/confirmaciones/{timestamp}
    //  El bridge espeja en /confirmaciones_medicamentos/{uid}/{timestamp}
    // ─────────────────────────────────────────────────────────────

    private fun escucharConfirmacionesDelReloj(uid: String, deviceId: String) {
        val listener = object : ValueEventListener {
            private val procesadas = mutableSetOf<Long>()

            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val timestamp = child.key?.toLongOrNull() ?: continue
                    if (timestamp in procesadas) continue
                    procesadas.add(timestamp)

                    val nombre = child.child("nombre").value as? String ?: continue
                    val tomado = child.child("tomado").value as? Boolean ?: false

                    Log.d(TAG, "Confirmación desde reloj: $nombre tomado=$tomado")
                    onConfirmacionMed?.invoke(nombre, tomado, timestamp)

                    scope.launch {
                        try {
                            // Espejo bajo uid del teléfono
                            db.getReference("confirmaciones_medicamentos/$uid/$timestamp")
                                .setValue(mapOf(
                                    "nombre"    to nombre,
                                    "tomado"    to tomado,
                                    "timestamp" to timestamp,
                                    "deviceId"  to deviceId,
                                    "fuente"    to "reloj_firebase"
                                )).await()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error espejeando confirmación: ${e.message}")
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error escuchando confirmaciones: ${error.message}")
            }
        }
        db.getReference("dispositivos/$deviceId/confirmaciones").addValueEventListener(listener)
        confirmacionesListener = listener
        Log.d(TAG, "Escuchando confirmaciones del reloj $deviceId")
    }

    // ─────────────────────────────────────────────────────────────
    //  RESPUESTA A CAÍDA  teléfono → reloj
    //  El cuidador responde desde el teléfono → se escribe en el nodo del reloj
    // ─────────────────────────────────────────────────────────────

    suspend fun responderCaidaEnReloj(timestamp: Long, respuesta: String): Boolean {
        if (auth.currentUser == null) return false
        val deviceId = watchDeviceIdPref ?: return false
        return try {
            db.getReference("dispositivos/$deviceId/caidas/$timestamp").updateChildren(
                mapOf(
                    "estado"              to respuesta,
                    "timestamp_respuesta" to System.currentTimeMillis()
                )
            ).await()
            Log.d(TAG, "Respuesta a caída enviada al reloj: $respuesta ts=$timestamp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error respondiendo caída al reloj: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILIDADES
    // ─────────────────────────────────────────────────────────────

    // Renombrada para evitar colisión de firma JVM con un getter generado.
    // Usar `watchDeviceId()` en vez de `getWatchDeviceId()` desde Kotlin/Java.
    fun watchDeviceId(): String? = watchDeviceIdPref

    private suspend fun obtenerNombresReloj(deviceId: String): Pair<String, String> {
        return try {
            val reloj = SyncManager.obtenerDispositivosVinculados(context)
                .firstOrNull { it.deviceId == deviceId }
            Pair(
                reloj?.nombreDispositivo.orEmpty(),
                reloj?.nombrePersona.orEmpty()
            )
        } catch (_: Exception) {
            Pair("", "")
        }
    }

    private fun normalizarCodigoVinculacion(raw: Any?): String? {
        return when (raw) {
            is Number -> {
                val n = raw.toLong()
                if (n in 0..999) n.toString().padStart(3, '0') else null
            }
            null -> null
            else -> {
                val texto = raw.toString().trim()
                if (texto.isEmpty()) return null
                val digitos = texto.filter { it.isDigit() }
                if (digitos.isEmpty() || digitos.length > 3) null else digitos.padStart(3, '0')
            }
        }
    }

    /** Devuelve true si hay un reloj vinculado Y hay sesión activa */
    fun estaListo(): Boolean = auth.currentUser != null && watchDeviceIdPref != null

    /** Para desvincular el reloj actual */
    suspend fun desvincularReloj(): Boolean {
        val uid      = auth.currentUser?.uid ?: return false
        val deviceId = watchDeviceIdPref ?: return false
        return try {
            // Limpiar el vínculo en el nodo del teléfono
            db.getReference("vinculacion/$uid/deviceId").removeValue().await()
            // Limpiar el uid y vinculado en el nodo del reloj
            db.getReference("dispositivos/$deviceId").updateChildren(
                mapOf(
                    "estado/vinculado" to false,
                    "estado/uid" to null,
                    "vinculado" to false,
                    "uid" to null
                )
            ).await()
            // Limpiar prefs del teléfono
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("watch_device_id").apply()
            detener()
            Log.d(TAG, "Reloj desvinculado: $deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error desvinculando: ${e.message}")
            false
        }
    }
}
