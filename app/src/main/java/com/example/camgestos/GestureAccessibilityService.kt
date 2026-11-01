package com.example.camgestos

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hudRoot: View? = null
    private var hudText: TextView? = null
    private var dbgRoot: View? = null
    private var dbgText: TextView? = null

    private val hideHud = Runnable {
        hudRoot?.animate()?.alpha(0f)?.setDuration(220)
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

        val (hr, ht) = pill(24f)
        hudRoot = hr; hudText = ht
        wm.addView(hr, lp(Gravity.BOTTOM or Gravity.END))

        val (dr, dt) = pill(16f)
        dbgRoot = dr; dbgText = dt
        wm.addView(dr, lp(Gravity.TOP or Gravity.START))
    }

    /** Aviso translúcido verde con un pequeño círculo verde de "en funcionamiento". */
    private fun pill(size: Float): Pair<LinearLayout, TextView> {
        val d = resources.displayMetrics.density
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
            layoutParams = LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt())
                .apply { marginEnd = (12 * d).toInt() }
        }
        val tv = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 30 * d
                setColor(Color.argb(205, 12, 92, 56))
                setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(130, 61, 220, 132))
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
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Avisos ----------

    fun hud(text: String) = handler.post {
        val r = hudRoot ?: return@post
        hudText?.text = text
        handler.removeCallbacks(hideHud)
        if (r.visibility != View.VISIBLE) { r.alpha = 0f; r.visibility = View.VISIBLE }
        r.animate().alpha(1f).setDuration(120).start()
        handler.postDelayed(hideHud, 1400)
    }

    fun dbg(text: String) = handler.post {
        dbgText?.text = text
        dbgRoot?.visibility = View.VISIBLE
    }

    fun dbgOff() = handler.post { dbgRoot?.visibility = View.GONE }

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

    fun select(long: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && !long) {
            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER); return
        }
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return
        var n: AccessibilityNodeInfo? = node
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null) {
            if (n.performAction(action)) return
            n = n.parent
        }
    }

    fun global(action: Int) = performGlobalAction(action)

    /** Abre el asistente de voz / buscador del sistema (Google Assistant en la mayoria de TVs). */
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

    /** Pone la TV en reposo (pantalla apagada). En Android < 9 abre el menu de apagado. */
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
