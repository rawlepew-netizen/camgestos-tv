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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Pantalla principal pensada para el control remoto (D-pad). */
class MainActivity : Activity() {

    private class Row(val dot: View, val value: TextView)

    private val GREEN = 0xFF3DDC84.toInt()
    private val BG = 0xFF0B0F14.toInt()
    private val CARD = 0xFF141B24.toInt()
    private val LINE = 0xFF222C38.toInt()
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
    private lateinit var rRun: Row

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(CARD, 18)
        setPadding(dp(22), dp(16), dp(22), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun button(text: String, primary: Boolean, onClick: () -> Unit): Button {
        val base = if (primary) GREEN else 0xFF1B2531.toInt()
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
            ).apply { topMargin = dp(10) }
        }
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
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(dot); addView(title); addView(value)
        })
        return Row(dot, value)
    }

    private fun gestureRow(parent: LinearLayout, gesture: String, action: String, last: Boolean = false) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
            addView(label(gesture, 15f, TEXT).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(BG)

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ----- Columna izquierda: título, estado, acciones, ajustes -----
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = circle(GREEN)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(14) }
            })
            addView(label("CamGestos", 34f, Color.WHITE, true))
        })
        left.addView(label("Control por gestos de mano para Android TV", 16f, MUTED)
            .apply { setPadding(0, dp(4), 0, dp(6)) })

        val status = card()
        status.addView(caption("Estado").apply { setPadding(0, 0, 0, dp(4)) })
        rA11y = statusRow(status, "Accesibilidad")
        rPerm = statusRow(status, "Permiso de camara")
        rCam = statusRow(status, "Camaras detectadas")
        rMic = statusRow(status, "Microfono de la camara")
        rRun = statusRow(status, "Gestos")
        left.addView(status)

        val first = button("Activar accesibilidad", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        left.addView(first)
        left.addView(button("Iniciar control por camara", true) { startControl() })
        left.addView(button("Detener", false) {
            startService(Intent(this, CameraGestureService::class.java)
                .setAction(CameraGestureService.ACTION_STOP))
            ui.postDelayed({ refresh() }, 500)
        })

        val settings = card()
        settings.addView(caption("Ajustes").apply { setPadding(0, 0, 0, dp(4)) })
        settings.addView(label("Sensibilidad de navegacion", 16f, TEXT))
        settings.addView(SeekBar(this).apply {
            max = 20
            progress = prefs.getFloat("hgain", 10f).toInt().coerceIn(0, 20)
            progressTintList = ColorStateList.valueOf(GREEN)
            thumbTintList = ColorStateList.valueOf(GREEN)
            setPadding(dp(4), dp(14), dp(4), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.edit().putFloat("hgain", p.toFloat()).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        settings.addView(CheckBox(this).apply {
            text = "Mostrar FPS y gesto detectado"
            textSize = 16f; setTextColor(TEXT)
            buttonTintList = ColorStateList.valueOf(GREEN)
            isChecked = prefs.getBoolean("debug", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("debug", c).apply() }
        })
        left.addView(settings)

        // ----- Columna derecha: guía de gestos -----
        val guide = card().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 }
        guide.addView(label("Gestos", 24f, Color.WHITE, true).apply { setPadding(0, 0, 0, dp(2)) })

        guide.addView(caption("Activacion"))
        gestureRow(guide, "Mano abierta, 1,2 s", "Activar control")
        gestureRow(guide, "Sin mano, 3 s", "Liberar control", last = true)

        guide.addView(caption("Navegacion"))
        gestureRow(guide, "Mano abierta y empujar", "Flechas")
        gestureRow(guide, "Puno rapido", "OK")
        gestureRow(guide, "Puno sostenido, 1 s", "Pulsacion larga")
        gestureRow(guide, "Indice a la izquierda", "Atras", last = true)

        guide.addView(caption("Multimedia"))
        gestureRow(guide, "Dos dedos arriba / abajo", "Volumen + / -")
        gestureRow(guide, "Un dedo arriba / abajo", "Canal + / -")
        gestureRow(guide, "Pellizco, pulgar e indice", "Silenciar")
        gestureRow(guide, "Circulo, otros dedos abiertos", "Voz / Buscar")
        gestureRow(guide, "Pulgar abajo, 1,5 s", "Apagar", last = true)

        guide.addView(label(
            "Solo responde a la mano que activo el control. Inicia el servicio con esta pantalla abierta; despues puedes salir.",
            13f, MUTED
        ).apply { setPadding(0, dp(14), 0, 0) })
        right.addView(guide)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(28) })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG); isFillViewport = true; addView(row)
        })
        first.requestFocus()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

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
