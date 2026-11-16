package com.example.camgestos

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** Ejecuta la navegación tipo control remoto y muestra avisos discretos (texto + punto verde). */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
        val GREEN = 0xFF3DDC84.toInt()
        /** Mientras haya standby, cualquier tecla del control remoto llama a esta función para salir. */
        @Volatile var keyWake: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hudRoot: View? = null
    private var hudText: TextView? = null
    private var dbgRoot: View? = null
    private var dbgText: TextView? = null
    private var previewView: HandPreview? = null
    private var blackView: View? = null

    private val hideHud = Runnable {
        hudRoot?.animate()?.alpha(0f)?.setDuration(260)
            ?.withEndAction { hudRoot?.visibility = View.GONE }?.start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        fun lp(g: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = g; x = (48 * d).toInt(); y = (40 * d).toInt() }

        val bv = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        blackView = bv
        wm.addView(bv, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ))

        val (hr, ht) = pill(24f)
        hudRoot = hr; hudText = ht
        wm.addView(hr, lp(Gravity.BOTTOM or Gravity.END))

        val (dr, dt) = pill(15f)
        dbgRoot = dr; dbgText = dt
        wm.addView(dr, lp(Gravity.TOP or Gravity.START))

        val pv = HandPreview(this).apply { visibility = View.GONE }
        previewView = pv
        val plp = WindowManager.LayoutParams(
            (300 * d).toInt(), (225 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (48 * d).toInt(); y = (40 * d).toInt() }
        wm.addView(pv, plp)
    }

    /** Aviso muy translúcido, verde, con un pequeño círculo verde de "en funcionamiento". */
    private fun pill(size: Float): Pair<LinearLayout, TextView> {
        val d = resources.displayMetrics.density
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREEN)
                setStroke((3 * d).toInt(), Color.argb(90, 61, 220, 132)) // halo
            }
            layoutParams = LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt())
                .apply { marginEnd = (12 * d).toInt() }
        }
        val tv = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, Color.argb(170, 0, 0, 0))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), (11 * d).toInt(), (22 * d).toInt(), (11 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 30 * d
                setColor(Color.argb(120, 6, 46, 30))
                setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(90, 61, 220, 132))
            }
            visibility = View.GONE
            addView(dot); addView(tv)
        }
        return root to tv
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hudRoot?.let { runCatching { wm.removeView(it) } }
        dbgRoot?.let { runCatching { wm.removeView(it) } }
        previewView?.let { runCatching { wm.removeView(it) } }
        blackView?.let { runCatching { wm.removeView(it) } }
        return super.onUnbind(intent)
    }

    /** En standby cualquier tecla del control remoto lo desactiva (y no llega a la app que está debajo). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val w = keyWake ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) handler.post { w() }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Avisos ----------

    fun hud(text: String) = handler.post {
        val r = hudRoot ?: return@post
        hudText?.text = text
        handler.removeCallbacks(hideHud)
        val d = resources.displayMetrics.density
        if (r.visibility != View.VISIBLE) { r.alpha = 0f; r.translationY = 14 * d; r.visibility = View.VISIBLE }
        r.animate().alpha(1f).translationY(0f).setDuration(150).start()
        handler.postDelayed(hideHud, 1500)
    }

    fun dbg(text: String) = handler.post {
        dbgText?.text = text
        dbgRoot?.visibility = View.VISIBLE
    }

    fun dbgOff() = handler.post { dbgRoot?.visibility = View.GONE }

    /** Muestra el fotograma de la cámara con el esqueleto de la mano (para comprobar qué ve la TV). */
    fun preview(b: Bitmap?, p: FloatArray?, t: String, mirror: Boolean) = handler.post {
        previewView?.let { it.visibility = View.VISIBLE; it.set(b, p, t, mirror) }
    }

    fun previewOff() = handler.post { previewView?.visibility = View.GONE }

    /** Pantalla negra del modo standby. */
    fun setBlack(on: Boolean) = handler.post { blackView?.visibility = if (on) View.VISIBLE else View.GONE }

    // ---------- Navegación tipo D-pad ----------

    fun dpad(dir: Dir) {
        if (Build.VERSION.SDK_INT >= 33) {
            performGlobalAction(when (dir) {
                Dir.UP -> GLOBAL_ACTION_DPAD_UP
                Dir.DOWN -> GLOBAL_ACTION_DPAD_DOWN
                Dir.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                Dir.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
            })
            return
        }
        val root = rootInActiveWindow ?: return
        val cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (cur == null) { firstFocusable(root)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS); return }
        val next = cur.focusSearch(when (dir) {
            Dir.UP -> View.FOCUS_UP
            Dir.DOWN -> View.FOCUS_DOWN
            Dir.LEFT -> View.FOCUS_LEFT
            Dir.RIGHT -> View.FOCUS_RIGHT
        }) ?: return
        if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    /** Tecla OK / seleccionar. */
    fun select() {
        if (Build.VERSION.SDK_INT >= 33) {
            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER); return
        }
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
        }
    }

    fun global(action: Int) = performGlobalAction(action)

    /** Abre el asistente de voz / buscador del sistema (Google Assistant en la mayoría de TVs). */
    fun voice() {
        val intents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
            Intent("android.intent.action.VOICE_ASSIST"),
            Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Exception) { /* probar el siguiente */ }
        }
        hud("Asistente de voz no disponible")
    }

    /** Pone la TV en reposo (pantalla apagada). En Android < 9 abre el menú de apagado. */
    fun powerOff() {
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    private fun firstFocusable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            firstFocusable(c)?.let { return it }
        }
        return null
    }
}

/** Vista pequeña con la imagen de la cámara y el esqueleto de la mano detectada. */
class HandPreview(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var pts: FloatArray? = null
    private var text = ""
    private var mirror = true
    private val dens = ctx.resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 220, 132) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 1.6f * dens
    }
    private val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * dens
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val dst = RectF()
    private val links = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17
    )

    fun set(b: Bitmap?, p: FloatArray?, t: String, m: Boolean) {
        bmp = b; pts = p; text = t; mirror = m
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        var left = 0f
        var top = 0f
        var dw = w
        var dh = h
        val b = bmp
        if (b != null) {
            val sc = minOf(w / b.width, h / b.height)
            dw = b.width * sc
            dh = b.height * sc
            left = (w - dw) / 2f
            top = (h - dh) / 2f
            dst.set(left, top, left + dw, top + dh)
            c.save()
            if (mirror) c.scale(-1f, 1f, w / 2f, h / 2f)   // espejo solo de la imagen
            c.drawBitmap(b, null, dst, null)
            c.restore()
        }
        val p = pts
        if (p != null && p.size >= 42) {
            var k = 0
            while (k < links.size) {
                val a = links[k]
                val e = links[k + 1]
                c.drawLine(left + p[2 * a] * dw, top + p[2 * a + 1] * dh,
                    left + p[2 * e] * dw, top + p[2 * e + 1] * dh, line)
                k += 2
            }
            for (i in 0 until 21) {
                c.drawCircle(left + p[2 * i] * dw, top + p[2 * i + 1] * dh, 2.6f * dens, dot)
            }
        }
        c.drawText(text, 10f * dens, h - 10f * dens, tx)
    }
}
