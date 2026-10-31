package com.example.camgestos

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Pantalla de ajustes pensada para usarse con el control remoto (D-pad). */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        fun text(s: String, size: Float = 20f) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, pad / 3, 0, pad / 3)
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; textSize = 20f; setOnClickListener { onClick() }
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        left.addView(text("CamGestos TV", 34f))
        status = text("")
        left.addView(status)

        val b1 = button("1. Activar accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        left.addView(b1)
        left.addView(button("2. Iniciar control por cámara") { startControl() })
        left.addView(button("Detener") {
            startService(Intent(this, CameraGestureService::class.java)
                .setAction(CameraGestureService.ACTION_STOP))
            status.postDelayed({ refresh() }, 400)
        })

        left.addView(text("Sensibilidad de navegación (◀ ▶)"))
        left.addView(SeekBar(this).apply {
            max = 20
            progress = prefs.getFloat("hgain", 10f).toInt().coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.edit().putFloat("hgain", p.toFloat()).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        left.addView(CheckBox(this).apply {
            text = "Standby: intentar bloquear/apagar de verdad (experimental)"
            textSize = 18f
            isChecked = prefs.getBoolean("deep", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("deep", c).apply() }
        })

        right.addView(text("Gestos de mano", 26f))
        right.addView(text(
            "✋ Mano abierta + empujar a un lado: flechas\n" +
            "✊ Puño rápido: OK / seleccionar\n" +
            "✊ Puño sostenido: pulsación larga\n" +
            "✌ Dos dedos arriba / abajo: volumen + / −\n" +
            "☝ Un dedo arriba / abajo: canal (pista) + / −\n" +
            "👌 Mute\n" +
            "👈 Atrás\n" +
            "🙃 Mano abierta al revés (1 s): standby\n" +
            "👍 (0,8 s): reanudar y encender pantalla\n\n" +
            "Conecta una webcam USB a la TV. Inicia el control con esta pantalla abierta; " +
            "después puedes salir y sigue en segundo plano.", 19f
        ))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad * 2, pad, pad * 2, pad)
            val lp = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = pad } }
            addView(left, lp()); addView(right, lp())
        }
        setContentView(ScrollView(this).apply { addView(row) })
        b1.requestFocus()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val cams = runCatching {
            (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList.size
        }.getOrDefault(0)
        status.text = "Accesibilidad: ${if (isAccessibilityOn()) "✔ activa" else "✘ inactiva"}\n" +
            "Permiso de cámara: ${if (hasCamera()) "✔" else "✘"}\n" +
            "Cámaras detectadas: $cams ${if (cams == 0) "(conecta una webcam USB)" else ""}\n" +
            "Control: ${if (CameraGestureService.running) "▶ en ejecución" else "⏹ detenido"}"
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
        window.decorView.postDelayed({ refresh() }, 800)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (hasCamera()) startControl() else refresh()
    }
}
