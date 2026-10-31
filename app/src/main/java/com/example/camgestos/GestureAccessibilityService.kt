package com.example.camgestos

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** Ejecuta navegación tipo control remoto, muestra avisos en pantalla y el modo standby (pantalla negra). */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: GestureAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hudView: TextView? = null
    private var blackView: View? = null
    private val hideHud = Runnable { hudView?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        blackView = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        wm.addView(blackView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT))

        val pad = (16 * resources.displayMetrics.density).toInt()
        hudView = TextView(this).apply {
            textSize = 26f; setTextColor(Color.WHITE); setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(pad * 2, pad, pad * 2, pad); visibility = View.GONE
        }
        wm.addView(hudView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = pad * 2; y = pad * 3 })
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        blackView?.let { wm.removeView(it) }
        hudView?.let { wm.removeView(it) }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- Interfaz visual ----------

    fun hud(text: String) = handler.post {
        hudView?.let {
            it.text = text; it.visibility = View.VISIBLE
            handler.removeCallbacks(hideHud); handler.postDelayed(hideHud, 1300)
        }
    }

    fun setBlack(on: Boolean) = handler.post {
        blackView?.visibility = if (on) View.VISIBLE else View.GONE
    }

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
        // Android TV < 13: mover el foco con la API de accesibilidad.
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
        while (n != null) { // sube hasta encontrar un nodo que acepte la acción
            if (n.performAction(action)) return
            n = n.parent
        }
    }

    fun global(action: Int) = performGlobalAction(action)

    private fun firstFocusable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isFocusable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            firstFocusable(c)?.let { return it }
        }
        return null
    }
}
