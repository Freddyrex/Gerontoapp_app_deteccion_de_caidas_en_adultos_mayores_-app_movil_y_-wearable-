package com.example.app_movile.ui.main

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.app_movile.sync.SyncService
import com.example.app_movile.R
import com.example.app_movile.data.db.AppDatabase
import com.example.app_movile.data.db.entities.Medicamento
import com.example.app_movile.data.remote.FirebaseMedManager
import com.example.app_movile.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var db: AppDatabase
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var medicamentosRecycler: RecyclerView
    private lateinit var medicamentoAdapter: MedicamentoAdapter
    private lateinit var alertasView: LinearLayout
    private lateinit var btnMedicamentos: ImageButton
    private lateinit var btnAlertas: ImageButton

    // Intervalos disponibles (horas) — alineado con el esquema Firebase
    private val intervalOptions = listOf(4, 8, 12, 24)
    private val intervalLabels  = listOf("Cada 4 horas", "Cada 8 horas", "Cada 12 horas", "Cada 24 horas")

    // Compatibilidad con código que todavía usa frequencyOptions / frequencyLabels
    private val frequencyOptions get() = intervalOptions.map { "${it}h" }
    private val frequencyLabels  get() = intervalLabels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        db = AppDatabase.getInstance(this)
        iniciarSyncService()

        pedirPermisoNotificaciones()
        iniciarEventListenerService()

        // ── Raíz ──────────────────────────────────────────────────
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resources.getColor(R.color.background_light, theme))
        }

        // ── Header ────────────────────────────────────────────────
        val btnSettings = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (com.example.app_movile.util.ThemeManager.isDark(this@MainActivity))
                    0xFFFFFFFF.toInt() else 0xFF0D0E14.toInt()
            )
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        titleView = TextView(this).apply {
            text = getString(R.string.pantalla_principal_titulo).uppercase(Locale.getDefault())
        }
        val headerBar = ToolbarHelper.buildHeader(
            activity  = this,
            title     = getString(R.string.pantalla_principal_titulo).uppercase(Locale.getDefault()),
            showBack  = false,
            extraView = btnSettings
        )
        val toolbarBg = if (ThemeManager.isDark(this)) resources.getColor(R.color.toolbar_dark, theme) else resources.getColor(R.color.toolbar_light, theme)
        headerBar.setBackgroundColor(toolbarBg)
        window.statusBarColor = toolbarBg
        // Guardar referencia al TextView del titulo para poder actualizarlo despues
        titleView = (headerBar.getChildAt(0) as? TextView)
            ?: (headerBar.getChildAt(1) as? TextView)
            ?: titleView
        container.addView(headerBar)

        // Garantizar que el servicio de sincronización se ejecute mientras la app principal esté abierta
        iniciarSyncService()

        // ── Contenido (scroll) ────────────────────────────────────
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(inner)

        medicamentoAdapter = MedicamentoAdapter(
            onToggleActivo = { updated -> toggleMedicamentoActivo(updated) },
            onEdit = { medicamento -> showEditDialog(medicamento) },
            onDelete = { medicamento -> deleteMedicamento(medicamento) }
        )
        medicamentosRecycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = medicamentoAdapter
            isNestedScrollingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        alertasView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = LinearLayout.GONE
        }
        inner.addView(medicamentosRecycler)
        inner.addView(alertasView)
        container.addView(scroll)

        // ── FAB agregar medicamento ───────────────────────────────
        val fab = com.google.android.material.floatingactionbutton.FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            contentDescription = getString(R.string.agregar_medicamento)
            setOnClickListener { showAddDialog() }
        }
        val fabParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END
        ).also { it.setMargins(0, 0, dp(16), dp(72)) }
        val frameWrap = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        // Reemplazar scroll en container por frameWrap: primero quitar scroll del container
        container.removeView(scroll)
        frameWrap.addView(scroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frameWrap.addView(fab, fabParams)
        container.addView(frameWrap)

        // ── Bottom bar ────────────────────────────────────────────
        bottomBar = BottomBarHelper.createBottomBarContainer(this)

        fun ibBtn(iconRes: Int, desc: String, onClick: () -> Unit) = BottomBarHelper.createBottomBarButton(
            activity = this,
            iconRes = iconRes,
            contentDescription = desc,
            onClick = onClick
        )

        btnMedicamentos = ibBtn(R.drawable.ic_medicamentos, getString(R.string.control_medicamentos)) {
            medicamentosRecycler.visibility = LinearLayout.VISIBLE
            alertasView.visibility      = LinearLayout.GONE
            titleView.text = getString(R.string.pantalla_principal_titulo).uppercase(Locale.getDefault())
            highlightBottomButtonImage(btnMedicamentos)
        }

        btnAlertas = ibBtn(R.drawable.ic_alertas, getString(R.string.alerta_caidas)) {
            startActivity(Intent(this, AlertasActivity::class.java))
        }

        bottomBar.addView(btnMedicamentos)
        bottomBar.addView(btnAlertas)
        container.addView(bottomBar)

        setContentView(container)

        highlightBottomButtonImage(btnMedicamentos)
        observeMedicamentos()

        // Procesar intent con tab seleccionado
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.getStringExtra("selected_tab")) {
            "medicamentos" -> highlightBottomButtonImage(btnMedicamentos)
            "alertas"      -> highlightBottomButtonImage(btnAlertas)
        }
    }

    // ─── Observar medicamentos en tiempo real ──────────────────────

    private fun observeMedicamentos() {
        lifecycleScope.launch {
            db.medicamentoDao().getAll().collect { list ->
                renderList(list)
            }
        }
    }

    // ─── Renderizar lista de medicamentos ─────────────────────────

    private fun renderList(list: List<Medicamento>) {
        if (list.isEmpty()) {
            medicamentoAdapter.updateItems(listOf(MedicamentoItem.Empty(getString(R.string.no_hay_medicamentos))))
            return
        }
        medicamentoAdapter.updateItems(list.map { MedicamentoItem.Data(it) })
    }
    private fun toggleMedicamentoActivo(m: Medicamento) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.medicamentoDao().setActivo(m.id, !m.activo)
                val updated = db.medicamentoDao().getById(m.id) ?: return@launch
                if (updated.serverId.isNullOrBlank()) {
                    val newKey = FirebaseMedManager.pushMedicamento(this@MainActivity, updated)
                    if (!newKey.isNullOrBlank()) db.medicamentoDao().update(updated.copy(serverId = newKey))
                } else {
                    FirebaseMedManager.updateMedicamento(this@MainActivity, updated)
                }
                if (updated.activo) {
                    val next = com.example.app_movile.receiver.BootReceiver()
                        .calculateNextTriggerMillis(updated)
                    if (next > 0) com.example.app_movile.notifications.AlarmScheduler
                        .scheduleAlarm(this@MainActivity, updated.id, updated.nombre,
                            updated.dosis, next, updated.horaReferencia)
                } else {
                    com.example.app_movile.notifications.AlarmScheduler
                        .cancelAlarm(this@MainActivity, updated.id)
                }
            } catch (_: Exception) {}
        }
    }

    private fun deleteMedicamento(m: Medicamento) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete_title))
            .setMessage(getString(R.string.confirm_delete_message, m.nombre))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        db.medicamentoDao().delete(m)
                        com.example.app_movile.notifications.AlarmScheduler
                            .cancelAlarm(this@MainActivity, m.id)
                        FirebaseMedManager.deleteMedicamento(this@MainActivity, m.serverId)
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private sealed class MedicamentoItem {
        data class Data(val medicamento: Medicamento) : MedicamentoItem()
        data class Empty(val message: String) : MedicamentoItem()
    }

    private inner class MedicamentoAdapter(
        private val onToggleActivo: (Medicamento) -> Unit,
        private val onEdit: (Medicamento) -> Unit,
        private val onDelete: (Medicamento) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<MedicamentoItem>()

        override fun getItemViewType(position: Int) = when (items[position]) {
            is MedicamentoItem.Data -> 0
            is MedicamentoItem.Empty -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> MedicamentoViewHolder(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL })
            else -> EmptyViewHolder(TextView(this@MainActivity))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is MedicamentoItem.Data -> (holder as MedicamentoViewHolder).bind(item.medicamento)
                is MedicamentoItem.Empty -> (holder as EmptyViewHolder).bind(item.message)
            }
        }

        override fun getItemCount() = items.size

        fun updateItems(newItems: List<MedicamentoItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        private inner class MedicamentoViewHolder(itemLayout: LinearLayout) : RecyclerView.ViewHolder(itemLayout) {
            fun bind(m: Medicamento) {
                val item = itemView as LinearLayout
                item.removeAllViews()

                val p = dp(6)
                item.orientation = LinearLayout.HORIZONTAL
                item.setPadding(p, p, p, p)
                item.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(4) }
                item.setBackgroundResource(R.drawable.bg_card)

                val info = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }
                info.addView(tv(m.nombre, 16f, R.color.text_primary, bold = true))
                info.addView(tv(m.dosis, 14f, R.color.text_secondary))
                info.addView(tv("Cada ${m.intervalHours}h — Ref: ${m.horaReferencia ?: "-"}", 13f, R.color.text_secondary))
                info.addView(tv(
                    when {
                        m.siempreAvisar -> "Vigencia: siempre"
                        m.vigenciaDesde != null && m.vigenciaHasta != null ->
                            "Desde: ${m.vigenciaDesde}  Hasta: ${m.vigenciaHasta}"
                        m.vigenciaDesde != null -> "Desde: ${m.vigenciaDesde}"
                        m.vigenciaHasta != null -> "Hasta: ${m.vigenciaHasta}"
                        else -> "-"
                    },
                    12f, R.color.text_secondary
                ))

                val controls = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val sw = SwitchCompat(this@MainActivity).apply {
                    isChecked = m.activo
                    text = if (m.activo) getString(R.string.activo) else getString(R.string.inactivo)
                    setOnCheckedChangeListener { _, _ -> onToggleActivo(m) }
                }

                val btnEdit = ImageButton(this@MainActivity).apply {
                    setImageResource(android.R.drawable.ic_menu_edit)
                    setBackgroundColor(Color.TRANSPARENT)
                    imageTintList = ColorStateList.valueOf(resources.getColor(R.color.accent_cyan, theme))
                    contentDescription = getString(R.string.editar)
                    setOnClickListener { onEdit(m) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(4); it.gravity = Gravity.CENTER_HORIZONTAL }
                }

                val btnDelete = ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_delete)
                    setBackgroundColor(Color.TRANSPARENT)
                    imageTintList = ColorStateList.valueOf(resources.getColor(R.color.color_grave, theme))
                    contentDescription = getString(R.string.eliminar)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = dp(8); it.gravity = Gravity.CENTER_HORIZONTAL }
                    setOnClickListener { onDelete(m) }
                }

                controls.addView(sw)
                controls.addView(btnEdit)
                controls.addView(btnDelete)

                item.addView(info)
                item.addView(controls)
            }
        }

        private inner class EmptyViewHolder(view: TextView) : RecyclerView.ViewHolder(view) {
            fun bind(message: String) {
                (itemView as TextView).apply {
                    text = message
                    textSize = 16f
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                }
            }
        }
    }
    // ─── Diálogo agregar medicamento ──────────────────────────────

    private fun showAddDialog() {
        val (layout, fields) = buildMedForm(prefill = null)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.agregar_medicamento))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val (nombre, dosis, intervalHours, horaRef, vigDesde, vigHasta, siempre) = fields.read()
                if (nombre.isBlank() || dosis.isBlank()) {
                    Toast.makeText(this, getString(R.string.campos_requeridos), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val newId = db.medicamentoDao().insert(
                            Medicamento(
                                nombre        = nombre,
                                dosis         = dosis,
                                intervalHours = intervalHours,
                                horaReferencia = horaRef,
                                vigenciaDesde = if (siempre) null else vigDesde,
                                vigenciaHasta = if (siempre) null else vigHasta,
                                siempreAvisar = siempre,
                                frecuencia    = "${intervalHours}h"
                            )
                        )
                        val med = db.medicamentoDao().getById(newId.toInt())
                        med?.let {
                            // Programar alarma local
                            val next = com.example.app_movile.receiver.BootReceiver()
                                .calculateNextTriggerMillis(it)
                            if (next > 0) com.example.app_movile.notifications.AlarmScheduler
                                .scheduleAlarm(this@MainActivity, it.id, it.nombre, it.dosis,
                                    next, it.horaReferencia)
                            // Guardar en Firebase RTDB medications/{personaId}/{serverKey}
                            val key = FirebaseMedManager.pushMedicamento(this@MainActivity, it)
                            if (!key.isNullOrBlank()) {
                                // actualizar registro local con serverId
                                val updatedLocal = it.copy(serverId = key)
                                db.medicamentoDao().update(updatedLocal)
                            }
                        }
                    } catch (_: Exception) {}
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── Diálogo editar medicamento ───────────────────────────────

    private fun showEditDialog(med: Medicamento) {
        val (layout, fields) = buildMedForm(prefill = med)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editar))
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val (nombre, dosis, intervalHours, horaRef, vigDesde, vigHasta, siempre) = fields.read()
                if (nombre.isBlank() || dosis.isBlank()) {
                    Toast.makeText(this, getString(R.string.campos_requeridos), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val updated = med.copy(
                            nombre        = nombre,
                            dosis         = dosis,
                            intervalHours = intervalHours,
                            horaReferencia = horaRef,
                            vigenciaDesde = if (siempre) null else vigDesde,
                            vigenciaHasta = if (siempre) null else vigHasta,
                            siempreAvisar = siempre,
                            frecuencia    = "${intervalHours}h"
                        )
                        // 1) Actualizar en Room
                        db.medicamentoDao().update(updated)
                        // 3) Reprogramar alarma
                        val next = com.example.app_movile.receiver.BootReceiver()
                            .calculateNextTriggerMillis(updated)
                        if (next > 0) com.example.app_movile.notifications.AlarmScheduler
                            .scheduleAlarm(this@MainActivity, updated.id, updated.nombre,
                                updated.dosis, next, updated.horaReferencia)
                        // 4) Sincronizar con Firebase: si no tiene serverId primero push, si no update
                        if (updated.serverId.isNullOrBlank()) {
                            val newKey = FirebaseMedManager.pushMedicamento(this@MainActivity, updated)
                            if (!newKey.isNullOrBlank()) db.medicamentoDao().update(updated.copy(serverId = newKey))
                        } else {
                            FirebaseMedManager.updateMedicamento(this@MainActivity, updated)
                        }
                    } catch (_: Exception) {}
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── Constructor del formulario de medicamento ────────────────

    /**
     * Construye el layout del formulario y devuelve un holder con referencias
     * a todos los campos. Utilizado tanto en agregar como en editar.
     */
    private fun buildMedForm(prefill: Medicamento?): Pair<LinearLayout, MedFormFields> {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(8)
            setPadding(p, p, p, p)
        }

        // Nombre
        val inputName = EditText(this).apply {
            hint = getString(R.string.medicamento_nombre_hint)
            prefill?.let { setText(it.nombre) }
            setTextColor(resources.getColor(R.color.text_primary, theme))
        }

        // Dosis
        val inputDosis = EditText(this).apply {
            hint = getString(R.string.medicamento_dosis_hint)
            prefill?.let { setText(it.dosis) }
            setTextColor(resources.getColor(R.color.text_secondary, theme))
        }

        // Spinner: intervalo en horas 4 / 8 / 12 / 24
        layout.addView(tv("Intervalo de recordatorio:", 13f, R.color.text_secondary))
        val spinnerInterval = Spinner(this)
        ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels).also { a ->
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerInterval.adapter = a
        }
        val prefillIdx = intervalOptions.indexOf(prefill?.intervalHours ?: 8).let { if (it >= 0) it else 1 }
        spinnerInterval.setSelection(prefillIdx)

        // Hora de referencia
        val tvHoraRef = TextView(this).apply {
            text = if (prefill?.horaReferencia.isNullOrEmpty()) ""
            else "Referencia: ${prefill?.horaReferencia}"
        }
        var horaReferenciaVal: String? = prefill?.horaReferencia
        val btnHoraRef = Button(this).apply {
            text = "Seleccionar hora de referencia"
            setOnClickListener {
                val now = Calendar.getInstance()
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    horaReferenciaVal = String.format(Locale.US, "%02d:%02d", h, m)
                    tvHoraRef.text = "Referencia: $horaReferenciaVal"
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
            }
        }

        // Vigencia
        var vigDesdeVal: String? = prefill?.vigenciaDesde
        var vigHastaVal: String? = prefill?.vigenciaHasta
        val tvVigencia = TextView(this).apply {
            text = when {
                prefill == null || prefill.siempreAvisar -> "Vigencia: siempre"
                prefill.vigenciaDesde != null && prefill.vigenciaHasta != null ->
                    "Desde: ${prefill.vigenciaDesde}  Hasta: ${prefill.vigenciaHasta}"
                else -> ""
            }
        }
        val chkSiempre = CheckBox(this).apply {
            text = "Siempre avisar"
            isChecked = prefill?.siempreAvisar ?: true
        }

        fun pickDate(onPicked: (String) -> Unit) {
            val now = Calendar.getInstance()
            android.app.DatePickerDialog(this, { _, y, mo, d ->
                val cal = Calendar.getInstance().apply {
                    set(y, mo, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
                }
                onPicked(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time))
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }

        val btnDesde = Button(this).apply {
            text = "Fecha inicio"
            isEnabled = !(prefill?.siempreAvisar ?: true)
            setOnClickListener {
                pickDate { s ->
                    vigDesdeVal = s
                    tvVigencia.text = "Desde: ${vigDesdeVal ?: "-"}  Hasta: ${vigHastaVal ?: "-"}"
                }
            }
        }
        val btnHasta = Button(this).apply {
            text = "Fecha fin"
            isEnabled = !(prefill?.siempreAvisar ?: true)
            setOnClickListener {
                pickDate { s ->
                    vigHastaVal = s
                    tvVigencia.text = "Desde: ${vigDesdeVal ?: "-"}  Hasta: ${vigHastaVal ?: "-"}"
                }
            }
        }

        chkSiempre.setOnCheckedChangeListener { _, checked ->
            btnDesde.isEnabled = !checked
            btnHasta.isEnabled = !checked
            tvVigencia.text = if (checked) "Vigencia: siempre" else ""
        }

        layout.addView(inputName)
        layout.addView(inputDosis)
        layout.addView(tv("Intervalo:", 13f, R.color.text_secondary))
        layout.addView(spinnerInterval)
        layout.addView(btnHoraRef)
        layout.addView(tvHoraRef)
        layout.addView(chkSiempre)
        layout.addView(btnDesde)
        layout.addView(btnHasta)
        layout.addView(tvVigencia)

        val fields = MedFormFields(
            inputName       = inputName,
            inputDosis      = inputDosis,
            spinnerInterval = spinnerInterval,
            horaRefGetter   = { horaReferenciaVal },
            vigDesdeGetter  = { vigDesdeVal },
            vigHastaGetter  = { vigHastaVal },
            chkSiempre      = chkSiempre
        )
        return Pair(layout, fields)
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private fun tv(text: String, size: Float, colorRes: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize  = size
            setTextColor(resources.getColor(colorRes, theme))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun deselectAllImages() {
        if (!::bottomBar.isInitialized) return
        for (i in 0 until bottomBar.childCount) {
            (bottomBar.getChildAt(i) as? ImageButton)?.let {
                it.isSelected = false; it.background = null
                it.imageTintList = ContextCompat.getColorStateList(this, R.color.bottom_icon_tint)
                it.refreshDrawableState()
            }
        }
    }

    private fun highlightBottomButtonImage(selected: ImageButton) {
        if (!::bottomBar.isInitialized) return
        deselectAllImages()
        selected.isSelected = true
        selected.imageTintList = ContextCompat.getColorStateList(this, R.color.bottom_icon_tint)
        selected.setBackgroundResource(R.drawable.bg_bottom_selected)
        selected.refreshDrawableState()
    }

    private fun showMedicamentosSection() {
        titleView.text = getString(R.string.pantalla_principal_titulo).uppercase(Locale.getDefault())
    }

    private fun iniciarSyncService() {
        try {
            val intent = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar SyncService: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun iniciarEventListenerService() {
        try {
            val intent = Intent(this, com.example.app_movile.notifications.EventListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "No se pudo iniciar EventListenerService: ${e.message}")
        }
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERM_NOTIF)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::bottomBar.isInitialized) return
        if (::alertasView.isInitialized && alertasView.visibility == LinearLayout.VISIBLE) {
            highlightBottomButtonImage(btnAlertas)
        } else {
            highlightBottomButtonImage(btnMedicamentos)
            medicamentosRecycler.visibility = LinearLayout.VISIBLE
            if (::alertasView.isInitialized) alertasView.visibility = LinearLayout.GONE
            showMedicamentosSection()
        }
    }

    companion object {
        private const val REQUEST_PERM_NOTIF = 5501
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERM_NOTIF) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Mostrar diálogo explicando que sin permiso no habrá notificaciones emergentes
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_notif_title))
                    .setMessage(getString(R.string.perm_notif_message))
                    .setPositiveButton(getString(R.string.ir_a_ajustes)) { _, _ ->
                        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:" + packageName)
                        }
                        startActivity(i)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}

// ─── Holder del formulario ────────────────────────────────────

data class MedFormResult(
    val nombre: String,
    val dosis: String,
    val intervalHours: Int,
    val horaReferencia: String?,
    val vigenciaDesde: String?,
    val vigenciaHasta: String?,
    val siempreAvisar: Boolean
)

class MedFormFields(
    private val inputName: EditText,
    private val inputDosis: EditText,
    private val spinnerInterval: Spinner,
    private val horaRefGetter: () -> String?,
    private val vigDesdeGetter: () -> String?,
    private val vigHastaGetter: () -> String?,
    private val chkSiempre: CheckBox
) {
    private val intervalOptions = listOf(4, 8, 12, 24)

    fun read(): MedFormResult = MedFormResult(
        nombre        = inputName.text?.toString()?.trim() ?: "",
        dosis         = inputDosis.text?.toString()?.trim() ?: "",
        intervalHours = intervalOptions.getOrElse(spinnerInterval.selectedItemPosition) { 8 },
        horaReferencia = horaRefGetter(),
        vigenciaDesde  = vigDesdeGetter(),
        vigenciaHasta  = vigHastaGetter(),
        siempreAvisar  = chkSiempre.isChecked
    )
}
