package com.example.app_movile.data.wear

import android.content.Context
import android.util.Log
import com.example.app_movile.data.db.entities.Medicamento
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WearSyncRepository — Capa de abstracción de sincronización Teléfono → Reloj
 *
 * Canal primario:  Wearable Data Layer API (Bluetooth directo, baja latencia)
 * Canal fallback:  Firebase Realtime Database  (via [FirebaseFallbackSync])
 *
 * Al llamar a [sincronizarMedicamentos]:
 *   1. Siempre escribe en Firebase (persistencia garantizada).
 *   2. Además intenta enviar por Wearable si el reloj está en rango BT.
 *
 * Así el reloj recibe los datos por el canal más rápido disponible y,
 * si vuelve al rango Bluetooth más tarde, Firebase ya tiene los datos actualizados.
 */
class WearSyncRepository(private val context: Context) {

    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val gson = Gson()

    companion object {
        private const val TAG = "WearSyncRepository"
    }

    /**
     * Sincroniza medicamentos usando el mejor canal disponible.
     *
     * - Firebase se escribe SIEMPRE (canal fallback garantizado).
     * - Wearable Data Layer se intenta adicionalmente si el reloj está conectado.
     *
     * No lanza excepción si solo falla Wearable (se registra el error y se continúa).
     * Solo lanza [WearSyncException] si ambos canales fallan completamente.
     */
    suspend fun sincronizarMedicamentos(medicamentos: List<Medicamento>) {
        withContext(Dispatchers.IO) {
            var wearableOk = false
            var firebaseOk = false

            // ── Canal 1: Wearable Data Layer (intento rápido) ──────────────
            try {
                val request = PutDataMapRequest.create("/siag/medicamentos").apply {
                    dataMap.putString("medicamentos_json", gson.toJson(medicamentos))
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }
                // setUrgent() asegura entrega inmediata cuando el reloj está en rango
                Tasks.await(
                    dataClient.putDataItem(request.asPutDataRequest().setUrgent())
                )
                wearableOk = true
                Log.d(TAG, "Wearable Data Layer: ${medicamentos.size} medicamentos enviados")
            } catch (e: Exception) {
                Log.w(TAG, "Wearable no disponible, usando Firebase fallback: ${e.message}")
            }

            // ── Canal 2: Firebase RTDB (siempre, como fuente de verdad) ────
            try {
                val result = FirebaseFallbackSync.sincronizarMedicamentos(context, medicamentos)
                firebaseOk = result.exitoso
                if (firebaseOk) {
                    Log.d(TAG, "Firebase RTDB: medicamentos escritos (canal=${result.canal})")
                } else {
                    Log.e(TAG, "Firebase RTDB falló: ${result.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en Firebase fallback: ${e.message}")
            }

            // ── Resultado final ─────────────────────────────────────────────
            if (!wearableOk && !firebaseOk) {
                throw WearSyncException(
                    "Sin conectividad: no se pudo sincronizar por Wearable ni por Firebase"
                )
            }
        }
    }

    /**
     * Envía un mensaje directo al reloj por Wearable (para comandos urgentes).
     * Si el reloj no está en rango, el mensaje se descarta (no se guarda en Firebase).
     * Para datos persistentes usa [sincronizarMedicamentos].
     */
    suspend fun enviarMensajeWearable(path: String, payload: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                if (nodes.isEmpty()) {
                    Log.d(TAG, "enviarMensaje: ningún nodo Wearable conectado")
                    return@withContext false
                }
                for (node in nodes) {
                    Tasks.await(
                        Wearable.getMessageClient(context).sendMessage(node.id, path, payload)
                    )
                }
                Log.d(TAG, "Mensaje enviado a ${nodes.size} nodo(s): $path")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Error enviando mensaje Wearable: ${e.message}")
                false
            }
        }
    }
}

class WearSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
