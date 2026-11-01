package com.example.camgestos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Pantalla principal pensada para el control remoto (D-pad). Tres pestañas: Inicio, Gestos y Calibración. */
class MainActivity : Activity() {

    private class Row(val dot: View, val value: TextView)

    private val GREEN = 0xFF3DDC84.toInt()
    private val AMBER = 0xFFFFC857.toInt()
    private val BG = 0xFF0A0E13.toInt()
    private val CARD = 0xFF131A23.toInt()
    private val LINE = 0xFF212B37.toInt()
    private val TEXT = 0xFFE8EDF2.toInt()
    private val MUTED = 0xFF8A97A6.toInt()
    private val GREY = 0xFF55606D.toInt()
    private val RED = 0xFFFF6B6B.toInt()

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    private lateinit var rA11y: Row
    private lateinit var rPerm: Row
    private lateinit var rCam: Row
    private lateinit var rMic: Row
    private lateinit var rNav: Row
    private lateinit var rRun: Row

    private val pages = mutableListOf<View>()
    private val tabViews = mutableListOf<TextView>()

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    // ---------- Utilidades de estilo ----------

    private fun shape(color: Int, radius: Int, strokeDp: Int = 0, stroke: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat(); setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun circle(color: Int) =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun caption(text: String) = label(text.uppercase(), 12f, MUTED, true).apply {
        letterSpacing = 0.14f
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun card(): LinearLayout = vbox().apply {
        background = shape(CARD, 18, 1, LINE)
        setPadding(dp(22), dp(16), dp(22), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) }
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val base = if (primary) GREEN else 0xFF1A2430.toInt()
        val edge = if (primary) GREEN else 0xFF2A3644.toInt()
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(if (primary) 0xFF6BF0A8.toInt() else 0xFF26364A.toInt(), 14, 3, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF2BB46A.toInt(), 14))
            addState(intArrayOf(), shape(base, 14, 1, edge))
        }
        return Button(this).apply {
            this.text = text; textSize = 18f; isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) 0xFF04210F.toInt() else TEXT)
            background = states; stateListAnimator = null
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
    }

    /** Pestaña / opción seleccionable. */
    private fun chip(text: String): TextView = label(text, 16f, TEXT, true).apply {
        gravity = Gravity.CENTER; isFocusable = true; isClickable = true
        setPadding(dp(22), dp(10), dp(22), dp(10))
    }

    private fun styleChip(tv: TextView, selected: Boolean) {
        val fill = if (selected) GREEN else 0xFF1A2430.toInt()
        val edge = if (selected) GREEN else 0xFF2A3644.toInt()
        tv.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(if (selected) 0xFF6BF0A8.toInt() else 0xFF26364A.toInt(), 12, 3, Color.WHITE))
            addState(intArrayOf(), shape(fill, 12, 1, edge))
        }
        tv.setTextColor(if (selected) 0xFF04210F.toInt() else TEXT)
    }

    private fun statusRow(parent: LinearLayout, name: String): Row {
        val dot = View(this).apply {
            background = circle(GREY)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) }
        }
        val title = label(name, 17f, TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = label("", 16f, MUTED)
        parent.addView(hbox().apply {
            setPadding(0, dp(8), 0, dp(8))
            addView(dot); addView(title); addView(value)
        })
        return Row(dot, value)
    }

    private fun gestureRow(parent: LinearLayout, gesture: String, action: String, last: Boolean = false) {
        parent.addView(hbox().apply {
            setPadding(0, dp(8), 0, dp(8))
            addView(label(gesture, 15f, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            })
            addView(label(action, 15f, GREEN, true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        if (!last) parent.addView(View(this).apply {
            setBackgroundColor(LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
    }

    private fun slider(
        parent: LinearLayout, title: String, key: String,
        minV: Float, maxV: Float, step: Float, def: Float, fmt: (Float) -> String
    ) {
        val steps = Math.round((maxV - minV) / step)
        val valueTv = label(fmt(prefs.getFloat(key, def)), 15f, GREEN, true)
        parent.addView(hbox().apply {
            setPadding(0, dp(10), 0, 0)
            addView(label(title, 15f, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueTv)
        })
        parent.addView(SeekBar(this).apply {
            this.max = steps
            progress = Math.round((prefs.getFloat(key, def) - minV) / step).coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(GREEN)
            thumbTintList = ColorStateList.valueOf(GREEN)
            setPadding(dp(4), dp(10), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val v = minV + p * step
                    prefs.edit().putFloat(key, v).apply()
                    valueTv.text = fmt(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun segmented(parent: LinearLayout, key: String, def: String, options: List<Pair<String, String>>) {
        val chips = mutableListOf<Pair<String, TextView>>()
        val row = hbox()
        fun refreshChips() {
            val current = prefs.getString(key, def)
            chips.forEach { (v, tv) -> styleChip(tv, v == current) }
        }
        options.forEachIndexed { idx, (value, text) ->
            val tv = chip(text)
            tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (idx > 0) marginStart = dp(8) }
            tv.setOnClickListener { prefs.edit().putString(key, value).apply(); refreshChips() }
            chips.add(value to tv)
            row.addView(tv)
        }
        refreshChips()
        parent.addView(row)
    }

    private fun toggle(parent: LinearLayout, text: String, key: String, def: Boolean) {
        parent.addView(CheckBox(this).apply {
            this.text = text
            textSize = 15f; setTextColor(TEXT)
            buttonTintList = ColorStateList.valueOf(GREEN)
            setPadding(dp(6), dp(10), 0, dp(6))
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply() }
        })
    }

    private fun twoColumns(colA: View, colB: View): View = ScrollView(this).apply {
        isFillViewport = true
        addView(hbox().apply {
            gravity = Gravity.TOP
            addView(colA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(24) })
            addView(colB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    // ---------- Páginas ----------

    private lateinit var primaryButton: Button

    private fun pageHome(): View {
        val left = vbox()
        val right = vbox()

        val status = card()
        status.addView(caption("Estado").apply { setPadding(0, 0, 0, dp(4)) })
        rA11y = statusRow(status, "Accesibilidad")
        rPerm = statusRow(status, "Permiso de cámara")
        rCam = statusRow(status, "Cámaras detectadas")
        rMic = statusRow(status, "Micrófono de la cámara")
        rNav = statusRow(status, "Navegación del sistema")
        rRun = statusRow(status, "Gestos")
        left.addView(status)

        right.addView(button("Activar accesibilidad", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        primaryButton = button("Iniciar control por cámara", true) { startControl() }
        right.addView(primaryButton)
        right.addView(button("Detener", false) {
            startService(Intent(this, CameraGestureService::class.java)
                .setAction(CameraGestureService.ACTION_STOP))
            ui.postDelayed({ refresh() }, 500)
        })
        right.addView(button("Calibrar mano y puño", false) {
            if (CameraGestureService.running) {
                startService(Intent(this, CameraGestureService::class.java)
                    .setAction(CameraGestureService.ACTION_CALIBRATE))
                Toast.makeText(this, "Sigue las indicaciones en la pantalla", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Primero inicia el control por cámara", Toast.LENGTH_LONG).show()
            }
        })
        right.addView(label(
            "Inicia el control con esta pantalla abierta. Después puedes salir de la app y sigue funcionando en segundo plano.",
            13f, MUTED
        ).apply { setPadding(0, dp(6), 0, 0) })
        return twoColumns(left, right)
    }

    private fun pageGestures(): View {
        val left = card()
        val right = card()

        left.addView(label("Control", 22f, Color.WHITE, true))
        left.addView(caption("Activación"))
        gestureRow(left, "Mano abierta sostenida", "Activar control")
        gestureRow(left, "Sin mano unos segundos", "Liberar control", last = true)
        left.addView(caption("Navegación"))
        gestureRow(left, "Mano abierta y empujar", "Flechas")
        gestureRow(left, "Puño cerrado", "Tecla OK")
        gestureRow(left, "Índice hacia un lado", "Atrás", last = true)

        right.addView(label("Multimedia y sistema", 22f, Color.WHITE, true))
        right.addView(caption("Multimedia"))
        gestureRow(right, "Dos dedos arriba / abajo", "Volumen + / -")
        gestureRow(right, "Un dedo arriba / abajo", "Canal + / -")
        gestureRow(right, "Pellizco", "Silenciar", last = true)
        right.addView(caption("Sistema"))
        gestureRow(right, "Círculo, otros dedos abiertos", "Voz / Buscar")
        gestureRow(right, "Pulgar abajo, 1,5 s", "Apagar", last = true)
        return twoColumns(left, right)
    }

    private fun pageCalib(): View {
        val left = card()
        val right = card()

        left.addView(label("Navegación y control", 22f, Color.WHITE, true))
        slider(left, "Zona de movimiento (más alto = menos recorrido)", "hgain", 0f, 20f, 1f, 10f) { "${it.toInt()}" }
        slider(left, "Velocidad máxima de las flechas", "speed", 1f, 10f, 1f, 6f) { "${it.toInt()}" }
        slider(left, "Tiempo para activar el control", "act_s", 0.6f, 2.4f, 0.1f, 1.2f) { "%.1f s".format(it) }
        slider(left, "Tiempo para liberar el control", "rel_s", 1.5f, 8f, 0.5f, 3f) { "%.1f s".format(it) }
        left.addView(caption("Mano que controla"))
        segmented(left, "hand", "any", listOf("right" to "Derecha", "left" to "Izquierda", "any" to "Cualquiera"))
        left.addView(label(
            "Con Derecha o Izquierda, solo esa mano activa el control y \"Atrás\" se hace con el índice hacia afuera. Con Cualquiera vale cualquier lado.",
            12f, MUTED
        ).apply { setPadding(0, dp(8), 0, 0) })

        right.addView(label("Detección y rendimiento", 22f, Color.WHITE, true))
        slider(right, "Sensibilidad del puño", "fist_sens", 0f, 10f, 1f, 5f) { "${it.toInt()}" }
        slider(right, "Confianza de detección (al reiniciar)", "conf", 0.30f, 0.70f, 0.05f, 0.45f) { "%.2f".format(it) }
        slider(right, "Máximo de fotogramas por segundo", "maxfps", 10f, 30f, 5f, 20f) { "${it.toInt()}" }
        right.addView(caption("Opciones"))
        toggle(right, "Aceleración por GPU (experimental, al reiniciar)", "gpu", false)
        toggle(right, "Mostrar FPS y gesto detectado", "debug", false)
        right.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })
        right.addView(button("Restablecer valores", false) {
            prefs.edit().clear().apply()
            Toast.makeText(this, "Valores restablecidos", Toast.LENGTH_SHORT).show()
            recreate()
        })
        return twoColumns(left, right)
    }

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)

        pages.add(pageHome()); pages.add(pageGestures()); pages.add(pageCalib())

        val header = hbox().apply {
            addView(View(this@MainActivity).apply {
                background = circle(GREEN)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) }
            })
            addView(vbox().apply {
                addView(label("CamGestos", 30f, Color.WHITE, true))
                addView(label("Control por gestos de mano para Android TV", 14f, MUTED))
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
        listOf("Inicio", "Gestos", "Calibración").forEachIndexed { idx, name ->
            val tv = chip(name)
            tv.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            tv.setOnClickListener { showTab(idx) }
            tv.setOnFocusChangeListener { _, has -> if (has) showTab(idx) }
            tabViews.add(tv)
            header.addView(tv)
        }

        val content = FrameLayout(this)
        pages.forEach { content.addView(it) }

        val root = vbox().apply {
            setBackgroundColor(BG)
            setPadding(dp(56), dp(30), dp(56), dp(24))
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) })
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        showTab(0)
        primaryButton.requestFocus()
    }

    private fun showTab(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { idx, tv -> styleChip(tv, idx == i) }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    // ---------- Estado ----------

    private fun set(r: Row, ok: Boolean, yes: String, no: String) {
        r.dot.background = circle(if (ok) GREEN else RED)
        r.value.text = if (ok) yes else no
        r.value.setTextColor(if (ok) TEXT else 0xFFFF8A80.toInt())
    }

    private fun refresh() {
        val cams = runCatching {
            (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList.size
        }.getOrDefault(0)
        set(rA11y, isAccessibilityOn(), "Activada", "Desactivada")
        set(rPerm, hasCamera(), "Concedido", "Pendiente")
        set(rCam, cams > 0, "$cams", "Ninguna, conecta una webcam USB")

        val mic = hasUsbMic()
        rMic.dot.background = circle(if (mic) GREEN else GREY)
        rMic.value.text = if (mic) "Detectado" else "No detectado"
        rMic.value.setTextColor(if (mic) TEXT else MUTED)

        val direct = Build.VERSION.SDK_INT >= 33
        rNav.dot.background = circle(if (direct) GREEN else AMBER)
        rNav.value.text = if (direct) "Directa (Android ${Build.VERSION.RELEASE})" else "Por foco (Android ${Build.VERSION.RELEASE})"
        rNav.value.setTextColor(TEXT)

        val on = CameraGestureService.running
        rRun.dot.background = circle(if (on) GREEN else GREY)
        rRun.value.text = if (on) "En funcionamiento" else "Detenido"
        rRun.value.setTextColor(if (on) GREEN else MUTED)
    }

    private fun hasUsbMic(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun hasCamera() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun startControl() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (!hasCamera()) { requestPermissions(perms.toTypedArray(), 1); return }
        if (!isAccessibilityOn()) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        ContextCompat.startForegroundService(this, Intent(this, CameraGestureService::class.java))
        ui.postDelayed({ refresh() }, 800)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (hasCamera()) startControl() else refresh()
    }
}
