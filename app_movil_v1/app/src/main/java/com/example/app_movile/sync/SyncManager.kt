package com.example.app_movile.sync

import android.content.Context
import android.util.Log
import com.example.app_movile.data.db.AppDatabase
import com.example.app_movile.data.db.entities.Medicamento
import com.example.app_movile.data.remote.FirebaseMedManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.math.roundToLong

private fun DataSnapshot.asDouble(): Double? {
    return getValue(Double::class.java)
        ?: getValue(Number::class.java)?.toDouble()
        ?: getValue(String::class.java)?.toDoubleOrNull()
}

private fun DataSnapshot.toUbicacionFirebase(latKey: String = "latitud", lngKey: String = "longitud", precisionKey: String? = "precision"): UbicacionFirebase? {
    val lat = child(latKey).asDouble()
    val lng = child(lngKey).asDouble()
    if (lat == null || lng == null) return null
    val precision = precisionKey?.let { child(it).asDouble() }?.toFloat() ?: 10f
    return UbicacionFirebase(lat, lng, precision)
}

/**
 * Gestor de sincronización bidireccional Reloj-Teléfono
 *
 * El código de 3 dígitos que muestra el reloj es el ID de vinculación:
 *   /vinculacionPendiente/{uid}/{codigo3Digitos}
 *   /dispositivos/{uid}/{codigo3Digitos}          ← reloj identificado por ese código
 *   /medications/{personaKey}                     ← ruta REAL de medicamentos (reloj y móvil)
 *   /caidas/{uid}/{codigo3Digitos}/{pushId}        ← historial de caídas del reloj
 */
object SyncManager {
    private val db = FirebaseDatabase.getInstance("https://gerento-74200-default-rtdb.firebaseio.com")
    private val auth = FirebaseAuth.getInstance()
    private const val TAG = "SyncManager"

    // ─────────────────────────────────────────────
    //  VINCULACIÓN – código de 3 dígitos = ID reloj
    // ─────────────────────────────────────────────

    /** El código (100-999) es generado en el reloj; aquí sólo lo confirmamos */
    fun generarCodigoVinculacion(): String = Random.nextInt(100, 1000).toString()

    /**
     * Registra un código pendiente en Firebase.
     * Cuando el RELOJ genera su código y lo escribe, este método lo crea;
     * cuando el TELÉFONO quiere iniciar emparejamiento también puede llamarlo.
     */
    suspend fun registrarCodigoPendiente(
        context: Context,
        codigo: String,
        tipoDispositivoEsperando: String,   // "telefono" | "reloj"
        deviceId: String
    ): Boolean {
        return try {
            val uid = auth.currentUser?.uid ?: return false
            val ahora = System.currentTimeMillis()
            val expiracion = ahora + (5 * 60 * 1000L)

            db.getReference("vinculacionPendiente/$uid/$codigo").updateChildren(
                mapOf(
                    "tipoDispositivoEsperando" to tipoDispositivoEsperando,
                    "fechaExpiracion"          to expiracion,
                    "estado"                   to "pendiente",
                    "deviceId"                 to deviceId,
                    "intentosFallidos"         to 0,
                    "fechaCreacion"            to ahora,
                    "codigoReloj"              to codigo   // el código ES el id del reloj
                )
            ).await()
            Log.d(TAG, "Código pendiente registrado: $codigo")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando código: ${e.message}")
            false
        }
    }

    /**
     * Confirma el código ingresado por el usuario en el teléfono.
     * Si es válido, vincula el reloj usando el código como deviceId del reloj.
     */
    suspend fun confirmarCodigoVinculacion(
        context: Context,
        codigo: String,
        deviceIdTelefono: String
    ): Boolean {
        return try {
            val uid = auth.currentUser?.uid ?: return false
            val codigoRef = db.getReference("vinculacionPendiente/$uid/$codigo")
            val snapshot = codigoRef.get().await()

            if (!snapshot.exists()) {
                Log.w(TAG, "Código no existe: $codigo")
                return false
            }

            val estado     = snapshot.child("estado").value as? String ?: return false
            val expiracion = snapshot.child("fechaExpiracion").value as? Long ?: return false
            val ahora      = System.currentTimeMillis()

            if (estado != "pendiente") {
                Log.w(TAG, "Código ya no está pendiente: $codigo")
                return false
            }
            if (ahora > expiracion) {
                codigoRef.child("estado").setValue("expirado").await()
                Log.w(TAG, "Código expirado: $codigo")
                return false
            }

            // 1) Marcar código como confirmado
            codigoRef.child("estado").setValue("confirmado").await()

            // 2) Registrar el RELOJ usando el código como su deviceId único
            registrarDispositivo(context, codigo, "reloj", codigo, deviceIdTelefono)

            // 3) Registrar el TELÉFONO
            registrarDispositivo(context, deviceIdTelefono, "telefono", null, null)

            Log.d(TAG, "Vinculación completada. Reloj ID=$codigo")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error confirmando código: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    //  DISPOSITIVOS
    // ─────────────────────────────────────────────

    suspend fun registrarDispositivo(
        context: Context,
        deviceId: String,
        tipo: String,
        codigoVinculacion: String? = null,
        dispositivoPrincipal: String? = null
    ): Boolean {
        return try {
            val uid   = auth.currentUser?.uid ?: return false
            val ahora = System.currentTimeMillis()
            val datos: MutableMap<String, Any?> = mutableMapOf(
                "tipo"                 to tipo,
                "fechaRegistro"        to ahora,
                "estado"               to if (dispositivoPrincipal != null) "vinculado" else "activo",
                "ultimaSincronizacion" to ahora
            )
            if (codigoVinculacion != null)  datos["codigoVinculacion"]  = codigoVinculacion
            if (dispositivoPrincipal != null) datos["dispositivoPrincipal"] = dispositivoPrincipal

            @Suppress("UNCHECKED_CAST")
            db.getReference("dispositivos/$uid/$deviceId")
                .updateChildren(datos as Map<String, Any?>).await()
            Log.d(TAG, "Dispositivo registrado: $tipo ($deviceId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando dispositivo: ${e.message}")
            false
        }
    }

    suspend fun obtenerDispositivosVinculados(context: Context): List<Dispositivo> {
        return try {
            val uid = auth.currentUser?.uid ?: return emptyList()
            val lista = linkedMapOf<String, Dispositivo>()

            // 1) Esquema canónico: /vinculacion/{uid}/deviceId + /dispositivos/{deviceId}
            val deviceIdVinculado = db.getReference("vinculacion/$uid/deviceId").get().await().getValue(String::class.java)
            if (!deviceIdVinculado.isNullOrBlank()) {
                var nodoReloj = db.getReference("dispositivos/$deviceIdVinculado").get().await()
                if (!nodoReloj.exists()) {
                    nodoReloj = db.getReference("dispositivos/$uid/$deviceIdVinculado").get().await()
                }
                if (nodoReloj.exists()) {
                    val estado = nodoReloj.child("estado")
                    val vinculado = estado.child("vinculado").getValue(Boolean::class.java)
                        ?: nodoReloj.child("vinculado").getValue(Boolean::class.java)
                        ?: false
                    val online = estado.child("online").getValue(Boolean::class.java)
                        ?: nodoReloj.child("online").getValue(Boolean::class.java)
                        ?: false
                    val ultimaSync = estado.child("ultima_sincro").getValue(Long::class.java)
                        ?: (estado.child("ultima_sincro").value as? Number)?.toLong()
                        ?: nodoReloj.child("ultima_sincro").getValue(Long::class.java)
                        ?: (nodoReloj.child("ultima_sincro").value as? Number)?.toLong()
                        ?: 0L
                    val nombrePersona = nodoReloj.child("nombrePersona").getValue(String::class.java).orEmpty()
                    val nombreDispositivo = nodoReloj.child("nombreDispositivo").getValue(String::class.java).orEmpty()
                    val estadoUi = when {
                        vinculado -> "vinculado"
                        online -> "activo"
                        else -> "offline"
                    }
                    lista[deviceIdVinculado] = Dispositivo(
                        deviceId = deviceIdVinculado,
                        tipo = "reloj",
                        estado = estadoUi,
                        ultimaSincronizacion = ultimaSync,
                        nombrePersona = nombrePersona,
                        nombreDispositivo = nombreDispositivo
                    )
                }
            }

            // 2) Respaldo canónico: buscar relojes por estado.uid cuando no exista /vinculacion/{uid}
            if (lista.isEmpty()) {
                val root = db.getReference("dispositivos").get().await()
                for (child in root.children) {
                    val deviceId = child.key ?: continue
                    val estado = child.child("estado")
                    val uidEstado = estado.child("uid").getValue(String::class.java)
                        ?: child.child("uid").getValue(String::class.java)
                    if (uidEstado != uid) continue

                    val vinculado = estado.child("vinculado").getValue(Boolean::class.java)
                        ?: child.child("vinculado").getValue(Boolean::class.java)
                        ?: false
                    val online = estado.child("online").getValue(Boolean::class.java)
                        ?: child.child("online").getValue(Boolean::class.java)
                        ?: false
                    val ultimaSync = estado.child("ultima_sincro").getValue(Long::class.java)
                        ?: (estado.child("ultima_sincro").value as? Number)?.toLong()
                        ?: child.child("ultima_sincro").getValue(Long::class.java)
                        ?: (child.child("ultima_sincro").value as? Number)?.toLong()
                        ?: 0L
                    val nombrePersona = child.child("nombrePersona").getValue(String::class.java).orEmpty()
                    val nombreDispositivo = child.child("nombreDispositivo").getValue(String::class.java).orEmpty()
                    val estadoUi = when {
                        vinculado -> "vinculado"
                        online -> "activo"
                        else -> "offline"
                    }

                    lista[deviceId] = Dispositivo(deviceId, "reloj", estadoUi, ultimaSync, nombrePersona, nombreDispositivo)
                }
            }

            // 3) Compatibilidad legado: /dispositivos/{uid}/{deviceId}
            val snapshotLegacy = db.getReference("dispositivos/$uid").get().await()
            for (child in snapshotLegacy.children) {
                val deviceId = child.key ?: continue
                if (lista.containsKey(deviceId)) continue

                val tipo = (child.child("tipo").value as? String)
                    ?: if (deviceId.startsWith("watch_") || child.hasChild("nombreDispositivo") || child.hasChild("nombrePersona")) "reloj" else continue
                val estado = child.child("estado").value as? String ?: "activo"
                val sync = child.child("ultimaSincronizacion").value as? Long ?: 0L
                val nombrePersona = child.child("nombrePersona").value as? String ?: ""
                val nombreDispositivo = child.child("nombreDispositivo").value as? String ?: ""
                lista[deviceId] = Dispositivo(deviceId, tipo, estado, sync, nombrePersona, nombreDispositivo)
            }

            // 4) Respaldo final: nodos watch_* o con eventos directos en /dispositivos/{watchId}
            obtenerRelojesDesdeRootDispositivos(context).forEach { reloj ->
                if (!lista.containsKey(reloj.deviceId)) {
                    lista[reloj.deviceId] = reloj
                }
            }

            lista.values.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo dispositivos: ${e.message}")
            emptyList()
        }
    }

    /**
     * Actualiza el nombre del dispositivo y la persona cuidada asociada en Firebase.
     */
    suspend fun actualizarNombreDispositivo(
        context: Context,
        codigoReloj: String,
        nombreDispositivo: String,
        nombrePersona: String
    ): Boolean {
        return try {
            val uid = auth.currentUser?.uid ?: return false
            val payload = mapOf(
                "nombreDispositivo" to nombreDispositivo.trim(),
                "nombrePersona" to nombrePersona.trim()
            )

            // Prioridad: vínculo canónico ya confirmado por WatchBridgeManager.
            var deviceIdObjetivo = db.getReference("vinculacion/$uid/deviceId").get().await().getValue(String::class.java)

            // Respaldo: resolver por código dentro de /dispositivos/{deviceId}/estado/codigo_vinculacion.
            if (deviceIdObjetivo.isNullOrBlank()) {
                val codigoNormalizado = normalizarCodigoVinculacion(codigoReloj)
                if (codigoNormalizado != null) {
                    val root = db.getReference("dispositivos").get().await()
                    for (child in root.children) {
                        val codigoEstado = normalizarCodigoVinculacion(child.child("estado/codigo_vinculacion").value) ?: continue
                        if (codigoEstado == codigoNormalizado) {
                            deviceIdObjetivo = child.key
                            break
                        }
                    }
                }
            }

            if (deviceIdObjetivo.isNullOrBlank()) {
                Log.w(TAG, "No se pudo resolver deviceId para actualizar nombre. codigoReloj=$codigoReloj")
                return false
            }

            db.getReference("dispositivos/$deviceIdObjetivo").updateChildren(payload).await()

            // Compatibilidad con estructura legado (si existe en ese despliegue).
            db.getReference("dispositivos/$uid/$codigoReloj").updateChildren(payload).await()

            Log.d(TAG, "Nombre dispositivo actualizado: deviceId=$deviceIdObjetivo nombre=$nombreDispositivo persona=$nombrePersona")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando nombre dispositivo: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    //  SINCRONIZACIÓN AUTOMÁTICA DE MEDICAMENTOS
    //  Ruta REAL: /medications/{personaKey}
    //  (misma ruta que lee el reloj en FirebaseSyncManager)
    // ─────────────────────────────────────────────

    /**
     * Genera la personaKey idéntica a FirebaseMedManager y al reloj:
     *   "persona_" + uid.takeLast(8)
     */
    private fun personaKey(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return "persona_${uid.takeLast(8)}"
    }

    /**
     * Escucha en tiempo real los cambios en /medications/{personaKey}.
     * Esta es la MISMA ruta que lee el reloj (FirebaseSyncManager línea 181).
     *
     * Soporta ambos formatos Firebase:
     *  - Sparse array: [null, {med1}, {med2}] → child keys "0","1","2"
     *  - Objeto: { "medId": {med}, ... }
     */
    fun observarMedicamentos(
        context: Context,
        onCambio: (List<Map<String, Any?>>) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        val pk = personaKey() ?: run {
            Log.d(TAG, "observarMedicamentos: sin sesión activa")
            return ListenerRegistration.empty()
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "onDataChange medications/$pk exists=${'$'}{snapshot.exists()} children=${'$'}{snapshot.childrenCount}")
                val lista = mutableListOf<Map<String, Any?>>()
                for (child in snapshot.children) {
                    // Ignorar índice 0 (Firebase lo crea como null en sparse arrays)
                    val key = child.key ?: continue
                    if (key == "0" && !child.hasChildren()) continue

                    val map = mutableMapOf<String, Any?>()
                    map["id"] = key
                    for (field in child.children) {
                        map[field.key ?: continue] = field.value
                    }
                    if (map.containsKey("name") || map.containsKey("nombre")) {
                        lista.add(map)
                    } else {
                        Log.w(TAG, "Medicamento ignorado en snapshot: clave=$key keys=${'$'}{map.keys}")
                    }
                }
                if (lista.isEmpty()) {
                    val keys = snapshot.children.mapNotNull { it.key }.joinToString(", ")
                    Log.w(TAG, "observarMedicamentos: lista vacía tras parseo. snapshot keys=[$keys]")
                }
                Log.d(TAG, "📦 Medicamentos observados (medications/$pk): ${lista.size}")
                onCambio(lista)
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Error escuchando medicamentos: ${error.message}")
            }
        }
        val ref = db.getReference("medications/$pk")
        ref.addValueEventListener(listener)
        Log.d(TAG, "👂 Listener medicamentos activo en medications/$pk")
        return ListenerRegistration(ref, listener)
    }

    /**
     * Descarga forzada de todos los medicamentos desde Firebase → Room DB.
     * Replica la lógica de polling del reloj (FirebaseSyncManager.sincronizarDesdFirebase).
     *
     * Usar tras login, re-autenticación o cuando se necesite garantizar
     * que Room tiene los datos más recientes.
     */
    suspend fun forzarDescargaMedicamentos(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val meds = FirebaseMedManager.fetchAllForCurrentUser(context)
            if (meds.isEmpty()) {
                Log.w(TAG, "forzarDescargaMedicamentos: 0 medicamentos en Firebase")
                return@withContext false
            }
            val dao = AppDatabase.getInstance(context).medicamentoDao()
            // Re-import: delete and insert. When inserting, Room will assign local ids
            dao.deleteAll()
            dao.insertAll(meds)
            Log.d(TAG, "✅ Descarga forzada: ${meds.size} medicamentos → Room")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en descarga forzada de medicamentos: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    //  ALARMAS
    // ─────────────────────────────────────────────

    fun observarAlarmas(
        context: Context,
        onAlarmaActualizada: (medId: String, proximaAlarma: Long, frecuencia: String) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        val uid = auth.currentUser?.uid ?: run {
            Log.d(TAG, "observarAlarmas: sin sesión activa")
            return ListenerRegistration.empty()
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (alarmaSnap in snapshot.children) {
                    val medId         = alarmaSnap.key ?: continue
                    val proximaAlarma = alarmaSnap.child("proximaAlarma").value as? Long ?: continue
                    val frecuencia    = alarmaSnap.child("frecuencia").value as? String ?: "cada 6 horas"
                    val estado        = alarmaSnap.child("estado").value as? String ?: "activa"
                    if (estado == "activa") onAlarmaActualizada(medId, proximaAlarma, frecuencia)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Error escuchando alarmas: ${error.message}")
            }
        }
        val ref = db.getReference("alarmas/$uid")
        ref.addValueEventListener(listener)
        return ListenerRegistration(ref, listener)
    }

    suspend fun registrarEjecucionAlarma(context: Context, medId: String, dispositivo: String): Boolean {
        return try {
            val uid = auth.currentUser?.uid ?: return false
            db.getReference("alarmas/$uid/$medId/dispositosQueDisparon/$dispositivo")
                .setValue(System.currentTimeMillis()).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando alarma: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    //  CAÍDAS – historial con push ID por reloj
    //  Ruta: /caidas/{uid}/{codigoReloj}/{pushId}
    // ─────────────────────────────────────────────

    /**
     * Umbrales para determinar severidad del evento de movimiento/caída:
     *   impactoPico ≥ 50  → caida_grave
     *   impactoPico ≥ 30  → caida_leve
     *   impactoPico <  30 → movimiento_brusco
     */
    fun calcularSeveridad(impactoPico: Double): String = when {
        impactoPico >= 50.0 -> "caida_grave"
        impactoPico >= 30.0 -> "caida_leve"
        else                -> "movimiento_brusco"
    }

    /**
     * Registra UN evento de caída en el historial del reloj.
     * Usa pushId de Firebase → no sobreescribe entradas anteriores.
     *
     * Severidades válidas: "movimiento_brusco" | "caida_leve" | "caida_grave"
     */
    suspend fun registrarCaida(
        context: Context,
        dispositoOrigen: String,            // "reloj" | "telefono"
        severidad: String,
        codigoReloj: String? = null,        // deviceId del reloj (código 3 dígitos / watch_xxx)
        latitud: Double? = null,
        longitud: Double? = null,
        precision: Float? = null,
        datosAcelerometro: Map<String, Any>? = null
    ): Boolean {
        return try {
            val uid       = auth.currentUser?.uid ?: return false
            val personaId = com.example.app_movile.util.PersonaManager.getPersonaId(context)
            val ahora     = System.currentTimeMillis()

            val datos: MutableMap<String, Any?> = mutableMapOf(
                "personaId"    to personaId,
                "timestamp_ms" to ahora,
                "severity"     to severidad,
                "status"       to "pendiente",
                "processed"    to false
            )
            if (latitud != null && longitud != null) {
                datos["location"] = mapOf(
                    "lat" to latitud,
                    "lng" to longitud,
                    "precision" to (precision ?: 10.0)
                )
            }
            if (datosAcelerometro != null) datos["accelData"] = datosAcelerometro

            // Nodo principal: fallEvents/{deviceId}/{pushId}
            val deviceId = codigoReloj ?: "unknown"
            db.getReference("fallEvents/$deviceId").push().setValue(datos).await()

            // Nodo de tiempo real para notificación inmediata
            db.getReference("ultimaCaida/$uid").updateChildren(
                datos + mapOf("deviceId" to deviceId)
            ).await()

            // Compatibilidad con estructura legacy
            val legacyPath = if (codigoReloj != null)
                "caidas/$uid/$codigoReloj"
            else
                "caidas/$uid/general"
            db.getReference(legacyPath).push().setValue(
                mapOf(
                    "timestamp"       to ahora,
                    "dispositoOrigen" to dispositoOrigen,
                    "severidad"       to severidad,
                    "notificada"      to false,
                    "respondida"      to false
                )
            ).await()

            Log.d(TAG, "Caída registrada: $severidad desde $dispositoOrigen (device=$deviceId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando caída: ${e.message}")
            false
        }
    }

    /**
     * Observa en tiempo real la ÚLTIMA caída (nodo ultimaCaida/{uid})
     * para notificar al cuidador de inmediato.
     */
    fun observarCaidas(
        context: Context,
        onCaidaDetectada: (timestamp: Long, dispositoOrigen: String, severidad: String) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        val uid = auth.currentUser?.uid ?: run {
            // No hay sesión activa – silencioso, no mostrar error al usuario
            Log.d(TAG, "observarCaidas: usuario no autenticado, listener no iniciado")
            return ListenerRegistration.empty()
        }
        val listener = object : ValueEventListener {
            var ultimoTimestamp = 0L
            override fun onDataChange(snapshot: DataSnapshot) {
                val timestamp      = snapshot.child("timestamp").value as? Long ?: return
                val origen         = snapshot.child("dispositoOrigen").value as? String ?: return
                val severidad      = snapshot.child("severidad").value as? String ?: "media"
                if (timestamp > ultimoTimestamp) {
                    ultimoTimestamp = timestamp
                    onCaidaDetectada(timestamp, origen, severidad)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Error escuchando caídas: ${error.message}")
            }
        }
        val ref = db.getReference("ultimaCaida/$uid")
        ref.addValueEventListener(listener)
        return ListenerRegistration(ref, listener)
    }

    /**
     * Obtiene el HISTORIAL de caídas de un reloj específico.
     * Devuelve lista ordenada de más reciente a más antiguo.
     */
    suspend fun obtenerHistorialCaidas(
        context: Context,
        codigoReloj: String
    ): List<EventoCaidaFirebase> {
        return try {
            val uid = auth.currentUser?.uid
            val mapaFinal = linkedMapOf<String, EventoCaidaFirebase>()

            if (uid != null) {
                try {
                    val snapshot = db.getReference("caidas/$uid/$codigoReloj").get().await()
                    for (child in snapshot.children) {
                        val timestamp   = child.child("timestamp").value as? Long ?: continue
                        val origen      = child.child("dispositoOrigen").value as? String ?: "reloj"
                        val severidad   = child.child("severidad").value as? String ?: "media"
                        val respondida  = child.child("respondida").value as? Boolean ?: false
                        val respuesta   = child.child("respuestaUsuario").value as? String
                        val pushId      = child.key ?: continue
                        val ubicacion = child.child("ubicacion").toUbicacionFirebase("latitud", "longitud", "precision")
                            ?: child.child("location").toUbicacionFirebase("lat", "lng")
                        mapaFinal[pushId] = EventoCaidaFirebase(
                            pushId, timestamp, origen, severidad,
                            respondida, respuesta, ubicacion
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error consultando caidas/$uid/$codigoReloj: ${e.message}")
                }
            }

            try {
                val snapshot = db.getReference("fallEvents/$codigoReloj").get().await()
                Log.d(TAG, "[CAIDAS] fallEvents/$codigoReloj exists=${snapshot.exists()} count=${snapshot.childrenCount}")
                for (child in snapshot.children) {
                    val pushId = child.key ?: continue
                    if (mapaFinal.containsKey(pushId)) continue

                    val tsRaw = child.child("timestamp_ms").value ?: child.child("timestamp").value ?: pushId.toLongOrNull()
                    val timestamp = when (tsRaw) {
                        is Long -> tsRaw
                        is Number -> tsRaw.toLong()
                        is String -> tsRaw.toLongOrNull() ?: continue
                        else -> continue
                    }
                    val severidad = child.child("severity").value as? String
                        ?: child.child("severidad").value as? String
                        ?: "caida_leve"
                    val respondida = child.child("processed").getValue(Boolean::class.java) ?: false
                    val respuesta = child.child("status").value as? String
                    val ubicacion = child.child("location").toUbicacionFirebase("lat", "lng")
                        ?: child.child("ubicacion").toUbicacionFirebase("latitud", "longitud", "precision")
                    mapaFinal[pushId] = EventoCaidaFirebase(
                        pushId, timestamp, "reloj", severidad,
                        respondida, respuesta, ubicacion
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error consultando fallEvents/$codigoReloj: ${e.message}")
            }

            // Ruta nativa del reloj: dispositivos/{deviceId}/caidas/{timestamp}
            try {
                val snapshots = mutableListOf<DataSnapshot>()
                snapshots.add(db.getReference("dispositivos/$codigoReloj/caidas").get().await())
                uid?.let { snapshots.add(db.getReference("dispositivos/$it/$codigoReloj/caidas").get().await()) }

                for (snapshot in snapshots) {
                    if (!snapshot.exists()) continue
                    Log.d(TAG, "[CAIDAS] ${snapshot.ref.path} exists=${snapshot.exists()} count=${snapshot.childrenCount}")
                    for (child in snapshot.children) {
                    val pushId = child.key ?: continue
                    if (mapaFinal.containsKey(pushId)) continue

                    val tsRaw = child.child("timestamp").value
                        ?: child.child("timestamp_ms").value
                        ?: pushId.toLongOrNull()
                    val timestamp = when (tsRaw) {
                        is Long -> tsRaw
                        is Number -> tsRaw.toLong()
                        is String -> tsRaw.toLongOrNull() ?: continue
                        else -> continue
                    }
                    val severidad = child.child("tipo").value as? String
                        ?: child.child("severidad").value as? String
                        ?: child.child("descripcion").value as? String
                        ?: "caida_leve"
                    val severidadNormalizada = when {
                        severidad.contains("MOVIMIENTO_BRUSCO", ignoreCase = true) -> "movimiento_brusco"
                        severidad.contains("GRAVE", ignoreCase = true) -> "caida_grave"
                        severidad.contains("LEVE", ignoreCase = true) -> "caida_leve"
                        else -> severidad
                    }
                    val respondida = (child.child("estado").value as? String)?.uppercase() == "USUARIO_ESTA_BIEN"
                    val respuesta = child.child("estado").value as? String
                    val ubicacion = child.child("location").toUbicacionFirebase("lat", "lng")
                        ?: child.child("ubicacion").toUbicacionFirebase("latitud", "longitud", "precision")
                        ?: run {
                            val lat = child.child("latitud").asDouble()
                            val lng = child.child("longitud").asDouble()
                            if (lat != null && lng != null) UbicacionFirebase(lat, lng, 10f) else null
                        }
                    mapaFinal[pushId] = EventoCaidaFirebase(
                        pushId, timestamp, "reloj", severidadNormalizada,
                        respondida, respuesta, ubicacion
                    )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error consultando dispositivos/$codigoReloj/caidas: ${e.message}")
            }

            mapaFinal.values.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo historial caídas: ${e.message}")
            emptyList()
        }
    }

    /**
     * Descubre relojes directamente en /dispositivos para esquemas legacy/no canónicos.
     * Ejemplo soportado: /dispositivos/watch_xxx/alertas_sos y /dispositivos/watch_xxx/caidas.
     */
    suspend fun obtenerRelojesDesdeRootDispositivos(context: Context): List<Dispositivo> {
        return try {
            val lista = linkedMapOf<String, Dispositivo>()
            val root = db.getReference("dispositivos").get().await()
            val uid = auth.currentUser?.uid

            // A) Estructura plana: /dispositivos/{watchId}
            for (child in root.children) {
                val deviceId = child.key ?: continue
                if (deviceId == uid) continue

                val tieneEventosReloj = child.child("alertas_sos").exists() || child.child("caidas").exists()
                val pareceWatch = deviceId.startsWith("watch_")
                if (!tieneEventosReloj && !pareceWatch) continue

                val nombrePersona = child.child("nombrePersona").getValue(String::class.java).orEmpty()
                val nombreDispositivo = child.child("nombreDispositivo").getValue(String::class.java).orEmpty()
                val ultimaSync = child.child("estado/ultima_sincro").getValue(Long::class.java)
                    ?: (child.child("estado/ultima_sincro").value as? Number)?.toLong()
                    ?: 0L

                lista[deviceId] = Dispositivo(
                    deviceId = deviceId,
                    tipo = "reloj",
                    estado = "activo",
                    ultimaSincronizacion = ultimaSync,
                    nombrePersona = nombrePersona,
                    nombreDispositivo = nombreDispositivo
                )
            }

            // B) Estructura anidada: /dispositivos/{uid}/{watchId}
            uid?.let { currentUid ->
                val userNode = root.child(currentUid)
                if (userNode.exists()) {
                    for (child in userNode.children) {
                        val deviceId = child.key ?: continue
                        if (lista.containsKey(deviceId)) continue
                        val tieneEventosReloj = child.child("alertas_sos").exists() || child.child("caidas").exists()
                        val pareceWatch = deviceId.startsWith("watch_")
                        if (!tieneEventosReloj && !pareceWatch) continue

                        val nombrePersona = child.child("nombrePersona").getValue(String::class.java).orEmpty()
                        val nombreDispositivo = child.child("nombreDispositivo").getValue(String::class.java).orEmpty()
                        val ultimaSync = child.child("estado/ultima_sincro").getValue(Long::class.java)
                            ?: (child.child("estado/ultima_sincro").value as? Number)?.toLong()
                            ?: 0L

                        lista[deviceId] = Dispositivo(
                            deviceId = deviceId,
                            tipo = "reloj",
                            estado = "activo",
                            ultimaSincronizacion = ultimaSync,
                            nombrePersona = nombrePersona,
                            nombreDispositivo = nombreDispositivo
                        )
                    }
                }
            }
            lista.values.toList()
        } catch (e: Exception) {
            Log.w(TAG, "Error obteniendo relojes desde /dispositivos: ${e.message}")
            emptyList()
        }
    }

    /** Responder a la última caída */
    suspend fun responderCaida(context: Context, respuesta: String): Boolean {
        return try {
            val uid   = auth.currentUser?.uid ?: return false
            val ahora = System.currentTimeMillis()
            db.getReference("ultimaCaida/$uid").updateChildren(
                mapOf("respuestaUsuario" to respuesta, "respondida" to true, "tiempoRespuesta" to ahora)
            ).await()
            Log.d(TAG, "Respuesta a caída registrada: $respuesta")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error respondiendo caída: ${e.message}")
            false
        }
    }

    /**
     * Elimina un evento de caída específico.
     * Borra del nodo fallEvents/{deviceId}/{pushId} y del nodo legacy.
     */
    suspend fun borrarCaida(context: Context, codigoReloj: String, pushId: String, timestamp: Long? = null): Boolean {
        return try {
            val uid = auth.currentUser?.uid ?: return false
            // Nodo canónico fallEvents
            db.getReference("fallEvents/$codigoReloj/$pushId").removeValue().await()
            // Nodo legacy en historial del usuario
            db.getReference("caidas/$uid/$codigoReloj/$pushId").removeValue().await()
            // Si conocemos el timestamp original, borrar también el evento en el nodo del reloj
            timestamp?.let {
                db.getReference("dispositivos/$codigoReloj/caidas/$it").removeValue().await()
                db.getReference("dispositivos/$uid/$codigoReloj/caidas/$it").removeValue().await()
            }
            // Si la alerta eliminada corresponde a la última caída, borrar el nodo de notificación rápida
            try {
                val ultimaRef = db.getReference("ultimaCaida/$uid")
                val ultimaSnap = ultimaRef.get().await()
                val ultimaTs = ultimaSnap.child("timestamp_ms").value as? Long
                    ?: ultimaSnap.child("timestamp").value as? Long
                if (ultimaTs != null && ultimaTs == timestamp) {
                    ultimaRef.removeValue().await()
                }
            } catch (_: Exception) {
                // Ignore errors while cleaning ultimaCaida
            }
            Log.d(TAG, "Caída eliminada: device=$codigoReloj pushId=$pushId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando caída: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    //  SOLICITUDES DE AYUDA (LLAMADO DE AYUDA)
    //  Ruta: helpRequests/{deviceId}/{pushId}
    // ─────────────────────────────────────────────

    /**
     * Registra una solicitud de ayuda manual enviada desde el reloj.
     * Escribe en helpRequests/{deviceId} y crea notificación al cuidador.
     */
    suspend fun registrarSolicitudAyuda(
        context: Context,
        deviceId: String,
        latitud: Double? = null,
        longitud: Double? = null,
        mensaje: String = "Necesito asistencia"
    ): Boolean {
        return try {
            val uid       = auth.currentUser?.uid ?: return false
            val personaId = com.example.app_movile.util.PersonaManager.getPersonaId(context)
            val ahora     = System.currentTimeMillis()

            val datos: MutableMap<String, Any?> = mutableMapOf(
                "personaId"    to personaId,
                "timestamp_ms" to ahora,
                "message"      to mensaje,
                "status"       to "pendiente"
            )
            if (latitud != null && longitud != null) {
                datos["location"] = mapOf("lat" to latitud, "lng" to longitud)
            }

            db.getReference("helpRequests/$deviceId").push().setValue(datos).await()

            // Notificación al cuidador
            db.getReference("notifications/$uid").push().setValue(
                mapOf(
                    "type"         to "manual_help",
                    "personaId"    to personaId,
                    "timestamp_ms" to ahora,
                    "read"         to false
                )
            ).await()

            Log.d(TAG, "Solicitud de ayuda registrada: device=$deviceId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando solicitud de ayuda: ${e.message}")
            false
        }
    }

    // Obtiene el historial de solicitudes de ayuda de un dispositivo.
    // Consulta 3 rutas: helpRequests/{deviceId}, dispositivos/{deviceId}/alertas_sos,
    // y un escaneo global de /dispositivos/* para relojes con deviceId distinto al vinculado.
    // Normaliza campos: 'descripcion' (reloj) o 'message' (telefono), clave como timestamp fallback.
    suspend fun obtenerHistorialAyuda(
        context: Context,
        deviceId: String
    ): List<SolicitudAyudaFirebase> {
        return try {
            val uid = auth.currentUser?.uid
            val mapaFinal = linkedMapOf<String, SolicitudAyudaFirebase>()

            // ── 1. Ruta del teléfono: helpRequests/{deviceId}
            try {
                val snap1 = db.getReference("helpRequests/$deviceId").get().await()
                Log.d(TAG, "[AYUDA] helpRequests/$deviceId exists=${snap1.exists()} count=${snap1.childrenCount}")
                for (child in snap1.children) {
                    val pushId = child.key ?: continue
                    val ts = (child.child("timestamp_ms").value as? Long)
                        ?: (child.child("timestamp_ms").value as? Number)?.toLong()
                        ?: pushId.toLongOrNull() ?: continue
                    val msg    = child.child("message").value as? String
                        ?: child.child("descripcion").value as? String
                        ?: "Llamado de ayuda"
                    val status = child.child("status").value as? String ?: "pendiente"
                    val loc    = child.child("location").let { l ->
                        if (l.exists()) UbicacionFirebase(
                            latitud  = l.child("lat").value as? Double ?: 0.0,
                            longitud = l.child("lng").value as? Double ?: 0.0,
                            precision = 10f
                        ) else null
                    }
                    mapaFinal[pushId] = SolicitudAyudaFirebase(pushId, ts, deviceId, msg, status, loc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[AYUDA] Error consultando helpRequests/$deviceId: ${e.message}")
            }

            // ── 2. Ruta del reloj: dispositivos/{deviceId}/alertas_sos
            try {
                val snap2 = db.getReference("dispositivos/$deviceId/alertas_sos").get().await()
                val snap2Uid = uid?.let { db.getReference("dispositivos/$it/$deviceId/alertas_sos").get().await() }
                val fuentes = listOfNotNull(snap2, snap2Uid)
                for (source in fuentes) {
                    if (!source.exists()) continue
                    Log.d(TAG, "[AYUDA] ${source.ref.path} exists=${source.exists()} count=${source.childrenCount}")
                    for (child in source.children) {
                    val reqId  = child.key ?: continue
                    if (mapaFinal.containsKey(reqId)) continue
                    val tsRaw  = child.child("timestamp").value
                        ?: child.child("timestamp_ms").value
                    val ts = when (tsRaw) {
                        is Long   -> tsRaw
                        is Number -> tsRaw.toLong()
                        else      -> reqId.toLongOrNull()
                    } ?: continue
                    val msg    = child.child("descripcion").value as? String
                        ?: child.child("message").value as? String
                        ?: "Llamado de ayuda"
                    val status = child.child("status").value as? String ?: "pendiente"
                    mapaFinal[reqId] = SolicitudAyudaFirebase(reqId, ts, deviceId, msg, status, null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[AYUDA] Error consultando dispositivos/$deviceId/alertas_sos: ${e.message}")
            }

            // ── 3. Escaneo global: /dispositivos/* para relojes con distinto deviceId
            // El reloj puede cambiar su ID entre instalaciones; esto captura esos casos.
            try {
                val root = db.getReference("dispositivos").get().await()
                Log.d(TAG, "[AYUDA] Escaneo global /dispositivos — nodos: ${root.childrenCount}")
                for (devSnap in root.children) {
                    val did = devSnap.key ?: continue
                    if (did == deviceId || did == uid) continue // ya procesado o es el nodo legado de uid

                    // Incluir si: mismo uid, o sin uid (reloj nuevo/anónimo)
                    val estadoUid = devSnap.child("estado/uid").getValue(String::class.java)?.trim()
                    if (!estadoUid.isNullOrBlank() && estadoUid != uid) continue

                    val sosSnap = devSnap.child("alertas_sos")
                    if (!sosSnap.exists()) continue

                    Log.d(TAG, "[AYUDA] Encontrado alertas_sos en /dispositivos/$did (estadoUid=$estadoUid) count=${sosSnap.childrenCount}")
                    for (reqSnap in sosSnap.children) {
                        val reqId = reqSnap.key ?: continue
                        val key   = "${did}_$reqId"
                        if (mapaFinal.containsKey(key)) continue
                        val tsRaw = reqSnap.child("timestamp").value
                            ?: reqSnap.child("timestamp_ms").value
                        val ts = when (tsRaw) {
                            is Long   -> tsRaw
                            is Number -> tsRaw.toLong()
                            else      -> reqId.toLongOrNull()
                        } ?: continue
                        val msg    = reqSnap.child("descripcion").getValue(String::class.java)
                            ?: reqSnap.child("message").getValue(String::class.java)
                            ?: "Llamado de ayuda"
                        val status = reqSnap.child("status").getValue(String::class.java) ?: "pendiente"
                        mapaFinal[key] = SolicitudAyudaFirebase(reqId, ts, did, msg, status, null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[AYUDA] Error en escaneo global: ${e.message}")
            }

            Log.d(TAG, "[AYUDA] Total alertas de ayuda encontradas: ${mapaFinal.size}")
            mapaFinal.values.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error historial ayuda: ${e.message}")
            emptyList()
        }
    }

    suspend fun borrarSolicitudAyuda(
        context: Context,
        deviceId: String,
        pushId: String,
        timestamp: Long? = null
    ): Boolean {
        return try {
            val uid = auth.currentUser?.uid
            var borrado = false
            // Ruta del telefono: helpRequests/{deviceId}/{pushId}
            try {
                db.getReference("helpRequests/$deviceId/$pushId").removeValue().await()
                borrado = true
            } catch (_: Exception) {}
            // Ruta del reloj: dispositivos/{deviceId}/alertas_sos/{pushId}
            try {
                db.getReference("dispositivos/$deviceId/alertas_sos/$pushId").removeValue().await()
                borrado = true
            } catch (_: Exception) {}
            // Ruta legado por usuario: dispositivos/{uid}/{deviceId}/alertas_sos/{pushId}
            try {
                if (!uid.isNullOrBlank()) {
                    db.getReference("dispositivos/$uid/$deviceId/alertas_sos/$pushId").removeValue().await()
                    borrado = true
                }
            } catch (_: Exception) {}

            // Fallback por timestamp para casos donde la key en Firebase no coincide con pushId.
            // Ejemplo: cuando la app recibe reqId normalizado o claves transformadas.
            if (timestamp != null) {
                try {
                    val rutas = mutableListOf("dispositivos/$deviceId/alertas_sos")
                    if (!uid.isNullOrBlank()) rutas.add("dispositivos/$uid/$deviceId/alertas_sos")
                    for (ruta in rutas) {
                        val snap = db.getReference(ruta).get().await()
                        for (child in snap.children) {
                            val ts = (child.child("timestamp").value as? Number)?.toLong()
                                ?: (child.child("timestamp_ms").value as? Number)?.toLong()
                                ?: child.key?.toLongOrNull()
                            if (ts == timestamp) {
                                child.ref.removeValue().await()
                                borrado = true
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            Log.d(TAG, "[DELETE] borrarSolicitudAyuda deviceId=$deviceId pushId=$pushId exito=$borrado")
            borrado
        } catch (_: Exception) { false }
    }

    // ─────────────────────────────────────────────
    //  SINCRONIZACIÓN AL INICIAR SESIÓN
    // ─────────────────────────────────────────────

    /**
     * Sincronización TOTAL de datos desde Firebase a Room.
     * Se ejecuta al iniciar sesión para garantizar datos frescos.
     *
     * Sincroniza:
     * ✅ Medicamentos (desde /medications/{personaKey}/)
    * ✅ Solicitudes de ayuda (desde /dispositivos/{watchId}/alertas_sos/ y helpRequests/{watchId})
     * ✅ Historial de caídas
     */
    fun forzarSincronizacionCompleta(context: Context): Boolean {
        Log.d(TAG, "════════════════════════════════════════════════════")
        Log.d(TAG, "🔄 [SYNC] *** INICIANDO SINCRONIZACIÓN COMPLETA ***")
        Log.d(TAG, "════════════════════════════════════════════════════")

        return try {
            val uid = auth.currentUser?.uid
            Log.d(TAG, "👤 [SYNC] UID usuario: $uid")

            if (uid.isNullOrEmpty()) {
                Log.w(TAG, "❌ [SYNC] ERROR: Sin UID autenticado")
                return false
            }

            val database = AppDatabase.getInstance(context)
            var totalExito = true

            // ═══════════════════════════════════════════════════
            // 1️⃣ SINCRONIZAR MEDICAMENTOS
            // ═══════════════════════════════════════════════════
            Log.d(TAG, "")
            Log.d(TAG, "1️⃣  DESCARGANDO MEDICAMENTOS...")
            try {
                val medicamentosFirebase = FirebaseMedManager.fetchAllForCurrentUser(context)
                Log.d(TAG, "   📥 Medicamentos en Firebase: ${medicamentosFirebase.size}")

                if (medicamentosFirebase.isNotEmpty()) {
                    val medDao = database.medicamentoDao()

                    // Borrar los anteriores e insertar los nuevos en un contexto de corutina bloqueante
                    runBlocking(Dispatchers.IO) {
                        medDao.deleteAll()
                        Log.d(TAG, "   🗑️  Borrados medicamentos locales")

                        var medInsertados = 0
                        for (med in medicamentosFirebase) {
                            medDao.insert(med)
                            medInsertados++
                            Log.d(TAG, "   ✅ [$medInsertados] '${med.nombre}' → Room")
                        }
                        Log.d(TAG, "   ✅ Medicamentos guardados: $medInsertados")
                    }
                } else {
                    Log.w(TAG, "   ⚠️  Sin medicamentos en Firebase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "   ❌ Error sincronizando medicamentos: ${e.message}", e)
                totalExito = false
            }

            // ═══════════════════════════════════════════════════
            // 2️⃣ SINCRONIZAR SOLICITUDES DE AYUDA (SOS)
            // ═══════════════════════════════════════════════════
            Log.d(TAG, "")
            Log.d(TAG, "2️⃣  DESCARGANDO SOLICITUDES DE AYUDA...")
            try {
                val solicitudesFirebase = obtenerHistorialAyudaPorUsuario(context, uid)
                Log.d(TAG, "   📥 Solicitudes en Firebase: ${solicitudesFirebase.size}")

                if (solicitudesFirebase.isNotEmpty()) {
                    val solDao = database.solicitudAyudaDao()

                    // Insertar las nuevas
                    runBlocking(Dispatchers.IO) {
                        var solInsertadas = 0
                        for (sol in solicitudesFirebase) {
                            val solicitud = com.example.app_movile.data.db.entities.SolicitudAyuda(
                                timestamp = sol.timestamp,
                                latitud = 0.0,  // No disponible en Firebase
                                longitud = 0.0, // No disponible en Firebase
                                mensaje = sol.mensaje,
                                codigoReloj = sol.deviceId,
                                status = sol.status
                            )
                            solDao.insert(solicitud)
                            solInsertadas++
                            Log.d(TAG, "   ✅ [$solInsertadas] '${sol.mensaje}' → Room")
                        }
                        Log.d(TAG, "   ✅ Solicitudes guardadas: $solInsertadas")
                    }
                } else {
                    Log.w(TAG, "   ⚠️  Sin solicitudes en Firebase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "   ❌ Error sincronizando solicitudes: ${e.message}", e)
                totalExito = false
            }

            // ═══════════════════════════════════════════════════
            // RESULTADO FINAL
            // ═══════════════════════════════════════════════════
            Log.d(TAG, "")
            Log.d(TAG, "✅ ✅ ✅ SINCRONIZACIÓN COMPLETADA ✅ ✅ ✅")
            Log.d(TAG, "════════════════════════════════════════════════════")

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ ❌ ❌ ERROR CRÍTICO EN SINCRONIZACIÓN ❌ ❌ ❌", e)
            Log.e(TAG, "   ${e.message}")
            Log.d(TAG, "════════════════════════════════════════════════════")
            false
        }
    }

    /**
     * Obtener historial de ayuda desde Firebase
     * Lee desde: /dispositivos/{watchId}/alertas_sos/ y /helpRequests/{watchId}
     */
    private fun obtenerHistorialAyudaPorUsuario(context: Context, uid: String): List<SolicitudAyudaFirebase> {
        Log.d(TAG, "   🔍 Buscando alertas_sos/helpRequests en Firebase...")
        return try {
            val mapa = linkedMapOf<String, SolicitudAyudaFirebase>()

            // 1) Descubrir nodos de reloj en /dispositivos/{watchId}
            val rootRef = db.getReference("dispositivos")
            val root = com.google.android.gms.tasks.Tasks.await(rootRef.get())
            Log.d(TAG, "   📡 Escaneando /dispositivos (${root.childrenCount} nodos)")

            val candidatos = mutableListOf<Pair<String, DataSnapshot>>()

            // Estructura plana /dispositivos/{watchId}
            for (devSnap in root.children) {
                val deviceId = devSnap.key ?: continue
                if (deviceId == uid) continue
                val uidEstado = devSnap.child("estado/uid").getValue(String::class.java)?.trim()
                val esCandidato = uidEstado.isNullOrBlank() || uidEstado == uid || deviceId.startsWith("watch_")
                if (!esCandidato) continue
                candidatos.add(deviceId to devSnap)
            }

            // Estructura anidada /dispositivos/{uid}/{watchId}
            val nested = root.child(uid)
            if (nested.exists()) {
                for (devSnap in nested.children) {
                    val deviceId = devSnap.key ?: continue
                    candidatos.add(deviceId to devSnap)
                }
            }

            for ((deviceId, devSnap) in candidatos.distinctBy { it.first }) {
                val sosSnap = devSnap.child("alertas_sos")
                if (sosSnap.exists()) {
                    for (child in sosSnap.children) {
                        val pushId = child.key ?: continue
                        val key = "$deviceId:$pushId"
                        val timestamp = (child.child("timestamp").value as? Number)?.toLong()
                            ?: (child.child("timestamp_ms").value as? Number)?.toLong()
                            ?: pushId.toLongOrNull()
                            ?: System.currentTimeMillis()
                        val mensaje = child.child("descripcion").value as? String
                            ?: child.child("message").value as? String
                            ?: "Llamado de ayuda"
                        val status = child.child("status").value as? String ?: "pendiente"
                        mapa[key] = SolicitudAyudaFirebase(pushId, timestamp, deviceId, mensaje, status, null)
                    }
                }

                // 2) También leer helpRequests/{watchId}
                try {
                    val helpSnap = com.google.android.gms.tasks.Tasks.await(
                        db.getReference("helpRequests/$deviceId").get()
                    )
                    for (child in helpSnap.children) {
                        val pushId = child.key ?: continue
                        val key = "$deviceId:$pushId"
                        if (mapa.containsKey(key)) continue
                        val timestamp = (child.child("timestamp_ms").value as? Number)?.toLong()
                            ?: (child.child("timestamp").value as? Number)?.toLong()
                            ?: pushId.toLongOrNull()
                            ?: System.currentTimeMillis()
                        val mensaje = child.child("message").value as? String
                            ?: child.child("descripcion").value as? String
                            ?: "Llamado de ayuda"
                        val status = child.child("status").value as? String ?: "pendiente"
                        mapa[key] = SolicitudAyudaFirebase(pushId, timestamp, deviceId, mensaje, status, null)
                    }
                } catch (_: Exception) {
                    // ruta opcional
                }
            }

            Log.d(TAG, "   ✅ Total alertas encontradas: ${mapa.size}")
            mapa.values.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error leyendo alertas_sos: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Método legacy para compatibilidad
     */
    fun forzarDescargaMedicamentosLegacy(context: Context): Boolean {
        return forzarSincronizacionCompleta(context)
    }

    // ─────────────────────────────────────────────
    //  UTILIDADES
    // ─────────────────────────────────────────────

    suspend fun actualizarUltimaSincronizacion(context: Context, deviceId: String) {
        try {
            val uid = auth.currentUser?.uid ?: return
            val ahora = System.currentTimeMillis()

            // Ruta canónica del reloj.
            db.getReference("dispositivos/$deviceId/estado/ultima_sincro")
                .setValue(ahora).await()

            // Compatibilidad con despliegues que aún usan el esquema legado.
            db.getReference("dispositivos/$uid/$deviceId/ultimaSincronizacion")
                .setValue(ahora).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando sincronización: ${e.message}")
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
}

// ─── Modelos ───────────────────────────────────

data class Dispositivo(
    val deviceId: String,
    val tipo: String,
    val estado: String,
    val ultimaSincronizacion: Long,
    val nombrePersona: String = "",
    val nombreDispositivo: String = ""
)

data class EventoCaidaFirebase(
    val pushId: String,
    val timestamp: Long,
    val dispositoOrigen: String,
    /** "movimiento_brusco" | "caida_leve" | "caida_grave" */
    val severidad: String,
    val respondida: Boolean,
    val respuestaUsuario: String?,
    val ubicacion: UbicacionFirebase?
)

data class SolicitudAyudaFirebase(
    val pushId: String,
    val timestamp: Long,
    val deviceId: String,
    val mensaje: String,
    val status: String,
    val ubicacion: UbicacionFirebase?
)

data class UbicacionFirebase(
    val latitud: Double,
    val longitud: Double,
    val precision: Float
)

class ListenerRegistration(
    private val ref: com.google.firebase.database.DatabaseReference,
    private val listener: ValueEventListener
) {
    fun remover() { ref.removeEventListener(listener) }

    companion object {
        fun empty() = ListenerRegistration(
            FirebaseDatabase.getInstance().reference,
            object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {}
                override fun onCancelled(e: DatabaseError) {}
            }
        )
    }
}
