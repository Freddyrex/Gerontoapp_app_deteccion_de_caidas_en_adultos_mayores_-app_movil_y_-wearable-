package com.example.app_movile.ui.main

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.app_movile.R
import com.example.app_movile.data.db.AppDatabase
import com.example.app_movile.notifications.NotificationManager as EventNotificationManager
import com.example.app_movile.sync.EventoCaidaFirebase
import com.example.app_movile.sync.SolicitudAyudaFirebase
import com.example.app_movile.sync.SyncManager
import com.example.app_movile.sync.UbicacionFirebase
import com.example.app_movile.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

/**
 * AlertasActivity — Pantalla de alertas con historial sincronizado.
 */
class AlertasActivity : AppCompatActivity() {

    private var caidaListener: com.example.app_movile.sync.ListenerRegistration? = null
    private var localReceiver: BroadcastReceiver? = null
    private var ayudaReceiver: BroadcastReceiver? = null

    private lateinit var caidasRecycler: RecyclerView
    private lateinit var ayudaRecycler:  RecyclerView
    private lateinit var caidasAdapter: CaidaAdapter
    private lateinit var ayudaAdapter: AyudaAdapter

    private var lastCaidasState: List<String> = emptyList()
    private var lastAyudaState: List<String> = emptyList()
    private val lastNotifiedFallTs = mutableMapOf<String, Long>()
    private val lastNotifiedHelpTs = mutableMapOf<String, Long>()
    private var fallNotificationsPrimed = false
    private var helpNotificationsPrimed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resources.getColor(R.color.background_light, theme))
        }

        val headerBar = ToolbarHelper.buildHeader(
            activity = this,
            title    = getString(R.string.alerta_caidas),
            showBack = true,
            onBack   = { finish() }
        )
        val toolbarBg = if (ThemeManager.isDark(this)) resources.getColor(R.color.toolbar_dark, theme) else resources.getColor(R.color.toolbar_light, theme)
        headerBar.setBackgroundColor(toolbarBg)
        window.statusBarColor = toolbarBg
        root.addView(headerBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(content)

        // Sección de "Última alerta del reloj" eliminada a petición del usuario.

        content.addView(sectionTitle("Historial de caídas del reloj"))
        caidasAdapter = CaidaAdapter { ev, deviceId -> eliminarCaida(ev, deviceId) }
        caidasRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AlertasActivity)
            adapter = caidasAdapter
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(24) }
        }
        content.addView(caidasRecycler)

        content.addView(sectionTitle("🆘 Llamados de ayuda"))
        ayudaAdapter = AyudaAdapter { sol, deviceId -> eliminarAyuda(sol, deviceId) }
        ayudaRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AlertasActivity)
            adapter = ayudaAdapter
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(ayudaRecycler)

        root.addView(scroll); root.addView(buildBottomBar()); setContentView(root)

        iniciarListenerCaidas(); registrarBroadcastReceivers(); cargarHistorialCompleto()
    }

    private fun cargarHistorialCompleto() { cargarHistorialCaidas(); cargarHistorialAyuda() }

    private fun cargarHistorialCaidas() {
        lifecycleScope.launch(Dispatchers.Main) {
            val dispositivos = withContext(Dispatchers.IO) {
                val vinculados = SyncManager.obtenerDispositivosVinculados(this@AlertasActivity)
                if (vinculados.any { it.tipo == "reloj" }) vinculados
                else vinculados + SyncManager.obtenerRelojesDesdeRootDispositivos(this@AlertasActivity)
            }
            val newState = mutableListOf<String>()
            val items = mutableListOf<CaidaItem>()

            dispositivos.filter { it.tipo == "reloj" }.forEach { reloj ->
                val local = withContext(Dispatchers.IO) { AppDatabase.getInstance(this@AlertasActivity).eventoCaidaDao().getByCodigoReloj(reloj.deviceId) }
                val mapa = mutableMapOf<Long, EventoCaidaFirebase>()
                local.forEach { l ->
                    val ubicacionLocal = if (l.latitud == 0.0 && l.longitud == 0.0) null
                    else UbicacionFirebase(latitud = l.latitud, longitud = l.longitud, precision = 10f)
                    mapa[l.timestamp] = EventoCaidaFirebase("local_${l.id}", l.timestamp, l.codigoReloj.ifBlank { "reloj" }, l.severidad, l.estadoRespuesta != "PENDIENTE", null, ubicacionLocal)
                }
                val remoto = withContext(Dispatchers.IO) { SyncManager.obtenerHistorialCaidas(this@AlertasActivity, reloj.deviceId) }
                remoto.forEach { mapa[it.timestamp] = it }
                val historial = mapa.values.sortedByDescending { it.timestamp }
                val label = (if (reloj.nombreDispositivo.isNotBlank()) reloj.nombreDispositivo else reloj.deviceId) + (if (reloj.nombrePersona.isNotBlank()) " (${reloj.nombrePersona})" else "")

                notificarNuevaCaidaSiCorresponde(
                    deviceId = reloj.deviceId,
                    nombreDispositivo = reloj.nombreDispositivo,
                    nombrePersona = reloj.nombrePersona,
                    historial = historial
                )

                newState.add("LABEL:$label")
                newState.add("COUNT:${historial.size}")
                historial.take(20).forEach { ev -> newState.add("CAIDA:${ev.pushId}|${ev.timestamp}|${ev.severidad}|${ev.respondida}") }

                items.add(CaidaItem.Header(label, historial.size))
                if (historial.isEmpty()) {
                    items.add(CaidaItem.Empty("Sin caídas registradas."))
                } else {
                    historial.take(20).forEach { ev -> items.add(CaidaItem.Caida(ev, reloj.deviceId)) }
                }
            }

            if (newState == lastCaidasState) return@launch
            lastCaidasState = newState
            caidasAdapter.updateItems(items)
            if (!fallNotificationsPrimed) fallNotificationsPrimed = true
        }
    }

    private fun tarjetaCaida(ev: EventoCaidaFirebase, deviceId: String, onDelete: () -> Unit): LinearLayout {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val colSev = colorSeveridad(ev.severidad)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply { text = ev.severidad.uppercase(); setTextColor(colSev); setTypeface(null, android.graphics.Typeface.BOLD) })
        card.addView(textCard("Fecha: ${sdf.format(Date(ev.timestamp))}", 12f, resources.getColor(R.color.text_primary, theme)))
        card.addView(textCard("Severidad: ${severityLabel(ev.severidad)}", 12f, colorSeveridad(ev.severidad)))
        ev.ubicacion?.let { ub ->
            card.addView(textCard("Ubicación: ${ub.latitud}, ${ub.longitud}", 12f, resources.getColor(R.color.text_secondary, theme)))
            card.addView(buildMapButton(ub.latitud, ub.longitud))
        }
        card.addView(buildDeleteButton("esta caída") { onDelete() })
        return card
    }

    private fun cargarHistorialAyuda() {
        lifecycleScope.launch(Dispatchers.Main) {
            val dispositivos = withContext(Dispatchers.IO) {
                val vinculados = SyncManager.obtenerDispositivosVinculados(this@AlertasActivity)
                if (vinculados.any { it.tipo == "reloj" }) vinculados
                else vinculados + SyncManager.obtenerRelojesDesdeRootDispositivos(this@AlertasActivity)
            }
            val newState = mutableListOf<String>()
            val items = mutableListOf<AyudaItem>()
            val db = AppDatabase.getInstance(this@AlertasActivity)
            val relojes = dispositivos.filter { it.tipo == "reloj" }

            if (relojes.isEmpty()) {
                val localAll = withContext(Dispatchers.IO) { db.solicitudAyudaDao().getAllList() }
                val historial = localAll.sortedByDescending { it.timestamp }

                notificarNuevaAyudaSiCorresponde(
                    deviceId = historial.firstOrNull()?.codigoReloj?.ifBlank { "reloj" } ?: "reloj",
                    nombreDispositivo = "",
                    nombrePersona = "",
                    historial = historial.map {
                        SolicitudAyudaFirebase(
                            pushId = "local_${it.id}",
                            timestamp = it.timestamp,
                            deviceId = it.codigoReloj.ifBlank { "reloj" },
                            mensaje = it.mensaje,
                            status = it.status,
                            ubicacion = null
                        )
                    }
                )

                newState.add("LABEL:Sin reloj vinculado")
                newState.add("COUNT:${historial.size}")
                historial.take(20).forEach { ev ->
                    newState.add("AYUDA:local_${ev.id}|${ev.timestamp}|${ev.status}|${ev.mensaje}")
                }

                items.add(AyudaItem.Header("Historial local"))
                if (historial.isEmpty()) {
                    items.add(AyudaItem.Empty("Sin llamados de ayuda."))
                } else {
                    historial.take(20).forEach { ev ->
                        items.add(
                            AyudaItem.Ayuda(
                                SolicitudAyudaFirebase(
                                    pushId = "local_${ev.id}",
                                    timestamp = ev.timestamp,
                                    deviceId = ev.codigoReloj.ifBlank { "reloj" },
                                    mensaje = ev.mensaje,
                                    status = ev.status,
                                    ubicacion = null
                                ),
                                ev.codigoReloj.ifBlank { "reloj" }
                            )
                        )
                    }
                }

                if (newState == lastAyudaState) return@launch
                lastAyudaState = newState
                ayudaAdapter.updateItems(items)
                if (!helpNotificationsPrimed) helpNotificationsPrimed = true
                return@launch
            }

            relojes.forEach { reloj ->
                val local = withContext(Dispatchers.IO) { db.solicitudAyudaDao().getByCodigoReloj(reloj.deviceId) }
                val mapa = mutableMapOf<Long, SolicitudAyudaFirebase>()
                local.forEach { l ->
                    mapa[l.timestamp] = SolicitudAyudaFirebase("local_${l.id}", l.timestamp, l.codigoReloj, l.mensaje, l.status, null)
                }
                val remoto = withContext(Dispatchers.IO) { SyncManager.obtenerHistorialAyuda(this@AlertasActivity, reloj.deviceId) }
                remoto.forEach { mapa[it.timestamp] = it }
                val historial = mapa.values.sortedByDescending { it.timestamp }
                val label = (if (reloj.nombreDispositivo.isNotBlank()) reloj.nombreDispositivo else reloj.deviceId) + (if (reloj.nombrePersona.isNotBlank()) " (${reloj.nombrePersona})" else "")

                notificarNuevaAyudaSiCorresponde(
                    deviceId = reloj.deviceId,
                    nombreDispositivo = reloj.nombreDispositivo,
                    nombrePersona = reloj.nombrePersona,
                    historial = historial
                )

                newState.add("LABEL:$label")
                newState.add("COUNT:${historial.size}")
                historial.take(20).forEach { ev -> newState.add("AYUDA:${ev.pushId}|${ev.timestamp}|${ev.status}|${ev.mensaje}") }

                items.add(AyudaItem.Header(label))
                if (historial.isEmpty()) {
                    items.add(AyudaItem.Empty("Sin llamados de ayuda."))
                } else {
                    historial.take(20).forEach { ev -> items.add(AyudaItem.Ayuda(ev, reloj.deviceId)) }
                }
            }

            if (newState == lastAyudaState) return@launch
            lastAyudaState = newState
            ayudaAdapter.updateItems(items)
            if (!helpNotificationsPrimed) helpNotificationsPrimed = true
        }
    }

    private fun notificarNuevaCaidaSiCorresponde(
        deviceId: String,
        nombreDispositivo: String,
        nombrePersona: String,
        historial: List<EventoCaidaFirebase>
    ) {
        val latest = historial.firstOrNull() ?: return
        val ultimoNotificado = lastNotifiedFallTs[deviceId] ?: 0L

        if (fallNotificationsPrimed && latest.timestamp > ultimoNotificado) {
            EventNotificationManager.showFallNotification(
                context = this,
                deviceId = deviceId,
                severidad = latest.severidad,
                timestamp = latest.timestamp,
                deviceName = nombreDispositivo,
                personaName = nombrePersona
            )
        }

        if (latest.timestamp > ultimoNotificado) {
            lastNotifiedFallTs[deviceId] = latest.timestamp
        }
    }

    private fun notificarNuevaAyudaSiCorresponde(
        deviceId: String,
        nombreDispositivo: String,
        nombrePersona: String,
        historial: List<SolicitudAyudaFirebase>
    ) {
        val latest = historial.firstOrNull() ?: return
        val ultimoNotificado = lastNotifiedHelpTs[deviceId] ?: 0L

        if (helpNotificationsPrimed && latest.timestamp > ultimoNotificado) {
            EventNotificationManager.showHelpRequestNotification(
                context = this,
                deviceId = deviceId,
                timestamp = latest.timestamp,
                deviceName = nombreDispositivo,
                personaName = nombrePersona,
                mensaje = latest.mensaje
            )
        }

        if (latest.timestamp > ultimoNotificado) {
            lastNotifiedHelpTs[deviceId] = latest.timestamp
        }
    }

    private fun tarjetaAyuda(sol: SolicitudAyudaFirebase, deviceId: String, onDelete: () -> Unit): LinearLayout {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val colorSOS = Color.parseColor("#E53935")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.bg_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) }
        }
        val filaTitulo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(4) } }
        filaTitulo.addView(android.view.View(this).apply { setBackgroundColor(colorSOS); layoutParams = LinearLayout.LayoutParams(dp(4), dp(20)).also { it.marginEnd = dp(8) } })
        filaTitulo.addView(TextView(this).apply { text = "🆘 LLAMADO DE AYUDA"; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(colorSOS) })
        card.addView(filaTitulo)
        card.addView(textCard("Fecha: ${sdf.format(Date(sol.timestamp))}", 12f, resources.getColor(R.color.text_primary, theme)))
        card.addView(textCard("Mensaje: ${sol.mensaje}", 12f, resources.getColor(R.color.text_secondary, theme)))
        card.addView(textCard("Dispositivo: ${sol.deviceId}", 11f, resources.getColor(R.color.text_secondary, theme)))
        card.addView(buildDeleteButton("este llamado") { onDelete() })
        return card
    }

    private fun iniciarListenerCaidas() {
        caidaListener = SyncManager.observarCaidas(this, { ts, orig, sev ->
            runOnUiThread {
                // Ahora solo recargamos el historial cuando llega una caída.
                cargarHistorialCompleto()
            }
        }, { Log.w("AlertasActivity", "Error listener: $it") })
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registrarBroadcastReceivers() {
        ayudaReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { cargarHistorialAyuda() } }
        registerReceiver(ayudaReceiver, IntentFilter("com.example.app_movile.ACTION_LLAMADO_AYUDA"), Context.RECEIVER_NOT_EXPORTED)

        localReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                cargarHistorialCaidas()
            }
        }
        registerReceiver(localReceiver, IntentFilter("com.example.app_movile.ACTION_LOCAL_CAIDA"), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun buildBottomBar(): LinearLayout {
        val bar = BottomBarHelper.createBottomBarContainer(this)

        val ibMeds = BottomBarHelper.createBottomBarButton(
            activity = this,
            iconRes = R.drawable.ic_medicamentos,
            contentDescription = getString(R.string.control_medicamentos)
        ) {
            startActivity(Intent(this@AlertasActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("selected_tab", "medicamentos")
            })
            finish()
        }

        val ibAlertas = BottomBarHelper.createBottomBarButton(
            activity = this,
            iconRes = R.drawable.ic_alertas,
            contentDescription = getString(R.string.alerta_caidas)
        ) {
            // Already in alertas screen
        }.apply {
            setBackgroundResource(R.drawable.bg_bottom_selected)
        }

        bar.addView(ibMeds)
        bar.addView(ibAlertas)
        return bar
    }

    private fun buildDeleteButton(name: String, onConfirm: () -> Unit) = Button(this).apply {
        text = "Eliminar"; setTextColor(Color.WHITE); val bg = android.graphics.drawable.GradientDrawable().apply { setColor(Color.RED); cornerRadius = dp(20).toFloat() }; background = bg
        setOnClickListener {
            AlertDialog.Builder(this@AlertasActivity)
                .setTitle("Eliminar")
                .setMessage("¿Deseas eliminar permanentemente $name?")
                .setPositiveButton("Sí") { _: android.content.DialogInterface, _: Int -> onConfirm() }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun buildMapButton(lat: Double, lng: Double) = Button(this).apply {
        text = "VER UBICACIÓN"
        setTextColor(Color.WHITE)
        val bg = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#388E3C")); cornerRadius = dp(20).toFloat() }
        background = bg
        setOnClickListener {
            val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Alerta)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")))
            }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
    }

    private fun severityLabel(s: String): String = when (s.lowercase()) {
        "grave", "caida_grave" -> "Caída grave"
        "media", "caida_leve" -> "Caída leve"
        "movimiento_brusco", "movimiento brusco" -> "Movimiento brusco"
        else -> s.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun eliminarCaida(ev: EventoCaidaFirebase, deviceId: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(this@AlertasActivity)
                    if (ev.pushId.startsWith("local_")) {
                        val localId = ev.pushId.removePrefix("local_").toIntOrNull() ?: return@withContext false
                        db.eventoCaidaDao().deleteById(localId)
                        true
                    } else {
                        val remotoOk = SyncManager.borrarCaida(this@AlertasActivity, deviceId, ev.pushId, ev.timestamp)
                        if (remotoOk) {
                            db.eventoCaidaDao().getByCodigoReloj(deviceId).find { it.timestamp == ev.timestamp }?.let { db.eventoCaidaDao().deleteById(it.id) }
                        }
                        remotoOk
                    }
                } catch (_: Exception) { false }
            }
            if (ok) { Toast.makeText(this@AlertasActivity, "Eliminado", Toast.LENGTH_SHORT).show(); cargarHistorialCaidas() }
            else { Toast.makeText(this@AlertasActivity, "No se pudo eliminar", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun eliminarAyuda(sol: SolicitudAyudaFirebase, deviceId: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(this@AlertasActivity)
                    val realDeviceId = sol.deviceId.ifBlank { deviceId }
                    if (sol.pushId.startsWith("local_")) {
                        val localId = sol.pushId.removePrefix("local_").toIntOrNull() ?: return@withContext false
                        db.solicitudAyudaDao().deleteById(localId)
                        true
                    } else {
                        val remotoOk = SyncManager.borrarSolicitudAyuda(this@AlertasActivity, realDeviceId, sol.pushId, sol.timestamp)
                        if (remotoOk) {
                            db.solicitudAyudaDao().getByCodigoReloj(realDeviceId).find { it.timestamp == sol.timestamp }?.let { db.solicitudAyudaDao().deleteById(it.id) }
                        }
                        remotoOk
                    }
                } catch (_: Exception) { false }
            }
            if (ok) { Toast.makeText(this@AlertasActivity, "Registro eliminado", Toast.LENGTH_SHORT).show(); cargarHistorialAyuda() }
            else { Toast.makeText(this@AlertasActivity, "No se pudo eliminar", Toast.LENGTH_SHORT).show() }
        }
    }

    private sealed class CaidaItem {
        data class Header(val label: String, val count: Int) : CaidaItem()
        data class Caida(val event: EventoCaidaFirebase, val deviceId: String) : CaidaItem()
        data class Empty(val message: String) : CaidaItem()
    }

    private sealed class AyudaItem {
        data class Header(val label: String) : AyudaItem()
        data class Ayuda(val solicitud: SolicitudAyudaFirebase, val deviceId: String) : AyudaItem()
        data class Empty(val message: String) : AyudaItem()
    }

    private inner class CaidaAdapter(
        private val onDelete: (EventoCaidaFirebase, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<CaidaItem>()

        override fun getItemViewType(position: Int) = when (items[position]) {
            is CaidaItem.Header -> 0
            is CaidaItem.Caida -> 1
            is CaidaItem.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> HeaderViewHolder(textSecondary(""))
            1 -> CaidaViewHolder(LinearLayout(this@AlertasActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> EmptyViewHolder(textSecondary(""))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is CaidaItem.Header -> (holder as HeaderViewHolder).bind(item)
                is CaidaItem.Caida -> (holder as CaidaViewHolder).bind(item)
                is CaidaItem.Empty -> (holder as EmptyViewHolder).bind(item)
            }
        }

        override fun getItemCount() = items.size

        fun updateItems(newItems: List<CaidaItem>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        private inner class HeaderViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(item: CaidaItem.Header) {
                (itemView as TextView).text = "⌚ ${item.label}  ·  ${item.count} evento(s)"
            }
        }

        private inner class EmptyViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(item: CaidaItem.Empty) { (itemView as TextView).text = item.message }
        }

        private inner class CaidaViewHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            fun bind(data: CaidaItem.Caida) {
                val container = itemView as LinearLayout
                container.removeAllViews()
                container.addView(tarjetaCaida(data.event, data.deviceId) { onDelete(data.event, data.deviceId) })
            }
        }
    }

    private inner class AyudaAdapter(
        private val onDelete: (SolicitudAyudaFirebase, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<AyudaItem>()

        override fun getItemViewType(position: Int) = when (items[position]) {
            is AyudaItem.Header -> 0
            is AyudaItem.Ayuda -> 1
            is AyudaItem.Empty -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> HeaderViewHolder(textSecondary(""))
            1 -> AyudaViewHolder(LinearLayout(this@AlertasActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            else -> EmptyViewHolder(textSecondary(""))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is AyudaItem.Header -> (holder as HeaderViewHolder).bind(item)
                is AyudaItem.Ayuda -> (holder as AyudaViewHolder).bind(item)
                is AyudaItem.Empty -> (holder as EmptyViewHolder).bind(item)
            }
        }

        override fun getItemCount() = items.size

        fun updateItems(newItems: List<AyudaItem>) {
            items.clear(); items.addAll(newItems); notifyDataSetChanged()
        }

        private inner class HeaderViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(item: AyudaItem.Header) { (itemView as TextView).text = "⌚ ${item.label}" }
        }

        private inner class EmptyViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(item: AyudaItem.Empty) { (itemView as TextView).text = item.message }
        }

        private inner class AyudaViewHolder(view: LinearLayout) : RecyclerView.ViewHolder(view) {
            fun bind(data: AyudaItem.Ayuda) {
                val container = itemView as LinearLayout
                container.removeAllViews()
                container.addView(tarjetaAyuda(data.solicitud, data.deviceId) { onDelete(data.solicitud, data.deviceId) })
            }
        }
    }

    private fun colorSeveridad(s: String) = when (s.lowercase()) { "grave", "caida_grave" -> Color.RED; "media", "caida_leve" -> Color.parseColor("#FB8C00"); else -> Color.GRAY }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; textSize = 15f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(resources.getColor(R.color.text_primary, theme)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(8) } }
    private fun textSecondary(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(resources.getColor(R.color.text_secondary, theme)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) } }
    private fun textCard(t: String, s: Float, c: Int) = TextView(this).apply { text = t; textSize = s; setTextColor(c) }

    override fun onDestroy() {
        super.onDestroy()
        caidaListener?.remover()
        try { ayudaReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { localReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
