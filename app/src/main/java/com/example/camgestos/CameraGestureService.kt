package com.example.camgestos

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Flujo de uso:
 *  - En espera: solo escucha "mano abierta 1,2 s". Quien la haga se queda con el control.
 *  - Con control: sigue solo a esa mano (ignora manos que aparecen en otro sitio).
 *  - Sin su mano 3 s: libera el control.
 */
class CameraGestureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "stop"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        const val STABLE_FRAMES = 2
        const val LONG_MS = 900L
        const val STEP_MS = 300L
        const val ACTIVATE_MS = 1200L
        const val RELEASE_MS = 3000L
        @Volatile var running = false
    }

    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var started = false

    // Control y propietario
    private var controlling = false
    private var ox = 0f
    private var oy = 0f
    private var osize = 0.1f
    private var lastHandAt = 0L

    // Estado de gestos
    private var cand = Hand.NONE
    private var candN = 0
    private var candSince = 0L
    private var cur = Hand.NONE
    private var curSince = 0L
    private var lastSeenCur = 0L
    private var lastFire = 0L
    private var longFired = false

    // Navegación
    private var ax = 0f
    private var ay = 0f
    private var anchorSet = false
    private var lastStep = 0L

    // Ajustes en caché y FPS
    private var dead = 0.12f
    private var debug = false
    private var prefsAt = 0L
    private var frames = 0
    private var fps = 0
    private var fpsAt = 0L
    private var dbgAt = 0L

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        startInForeground()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!started) { started = true; executor.execute { prepareAndStart() } }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        GestureAccessibilityService.instance?.dbgOff()
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        landmarker?.close()
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- Modelo y cámara ----------

    private fun prepareAndStart() {
        try {
            val file = File(filesDir, "hand_landmarker.task")
            if (!file.exists()) {
                GestureAccessibilityService.instance?.hud("Descargando modelo")
                val tmp = File(filesDir, "model.tmp")
                URL(MODEL_URL).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(file)
            }
            val bytes = file.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1) // una sola mano = menos procesamiento
                .setResultListener { res, _ ->
                    try { onResult(res) } catch (e: Exception) { Log.e("CamGestos", "gesto: ${e.message}") }
                }
                .setErrorListener { Log.e("CamGestos", "MediaPipe: ${it.message}") }
                .build()
            landmarker = HandLandmarker.createFromOptions(this, options)
            ContextCompat.getMainExecutor(this).execute { startCamera() }
        } catch (e: Exception) {
            Log.e("CamGestos", "No se pudo iniciar: ${e.message}")
            GestureAccessibilityService.instance?.hud("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
          try {
            val provider = future.get()
            val selector = pickCamera(provider)
            if (selector == null) {
                GestureAccessibilityService.instance?.hud("No se detecta ninguna camara")
                stopSelf()
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val rotation = proxy.imageInfo.rotationDegrees.toFloat()
                val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
                val m = Matrix().apply { postRotate(rotation); postScale(-1f, 1f) } // gira y hace espejo
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                landmarker?.detectAsync(BitmapImageBuilder(out).build(), SystemClock.uptimeMillis())
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
            GestureAccessibilityService.instance?.hud("Reconocimiento listo")
          } catch (e: Exception) {
            Log.e("CamGestos", "camara: ${e.message}")
            GestureAccessibilityService.instance?.hud("Error de camara")
            stopSelf()
          }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pickCamera(p: ProcessCameraProvider): CameraSelector? {
        for (lens in listOf(
            CameraSelector.LENS_FACING_EXTERNAL,
            CameraSelector.LENS_FACING_FRONT,
            CameraSelector.LENS_FACING_BACK
        )) {
            val sel = CameraSelector.Builder().requireLensFacing(lens).build()
            if (try { p.hasCamera(sel) } catch (e: Exception) { false }) return sel
        }
        return null
    }

    // ---------- Procesamiento ----------

    private fun onResult(res: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        refreshPrefs(now)
        var lm = res.landmarks().firstOrNull()

        // Bloqueo de propietario: ignora manos que "saltan" de sitio o cambian mucho de tamaño.
        if (lm != null && controlling) {
            val cx = lm[9].x()
            val cy = lm[9].y()
            val size = hypot(lm[0].x() - lm[9].x(), lm[0].y() - lm[9].y()).coerceAtLeast(1e-3f)
            val allowed = 0.30f + 0.15f * ((now - lastHandAt) / 1000f)
            val ratio = size / osize
            if (hypot(cx - ox, cy - oy) > allowed || ratio < 0.6f || ratio > 1.6f) {
                lm = null
            } else {
                ox = cx; oy = cy; osize = osize * 0.8f + size * 0.2f; lastHandAt = now
            }
        }
        if (controlling && now - lastHandAt > RELEASE_MS) release()

        val g = if (lm == null) Hand.NONE else HandClassifier.classify(lm, mirrored = false)

        frames++
        if (now - fpsAt >= 1000) { fps = frames; frames = 0; fpsAt = now }
        if (debug && now - dbgAt >= 250) {
            dbgAt = now
            GestureAccessibilityService.instance?.dbg(
                "$fps fps  |  ${g.label()}  |  ${if (controlling) "control activo" else "en espera"}"
            )
        }

        // Estabilizador
        if (g == cand) candN++ else { cand = g; candN = 1; candSince = now }
        if (g == cur) lastSeenCur = now
        if (candN >= STABLE_FRAMES && cand != cur) {
            onRelease(cur, lastSeenCur - curSince)
            cur = cand; curSince = candSince; lastSeenCur = now
            lastFire = 0L; longFired = false; anchorSet = false
        }

        if (!controlling) {
            if (cur == Hand.OPEN && g == Hand.OPEN && lm != null && now - curSince >= ACTIVATE_MS)
                activate(lm[9].x(), lm[9].y(), lm[0].x(), lm[0].y(), now)
            return
        }
        if (cur == Hand.OPEN && g == Hand.OPEN && lm != null)
            navigate(lm[9].x(), lm[9].y(), now)
        onHold(cur, now)
    }

    private fun activate(mx: Float, my: Float, wx: Float, wy: Float, now: Long) {
        controlling = true
        ox = mx; oy = my
        osize = hypot(wx - mx, wy - my).coerceAtLeast(1e-3f)
        lastHandAt = now
        anchorSet = false
        GestureAccessibilityService.instance?.hud("Control activado")
    }

    private fun release() {
        controlling = false
        cur = Hand.NONE; cand = Hand.NONE; candN = 0
        anchorSet = false; longFired = false
        GestureAccessibilityService.instance?.hud("Control liberado")
    }

    private fun Hand.label() = when (this) {
        Hand.NONE -> "sin mano"
        Hand.OTHER -> "otro gesto"
        Hand.OPEN -> "mano abierta"
        Hand.FIST -> "puno"
        Hand.THUMB_UP -> "pulgar arriba"
        Hand.OK_SIGN -> "circulo (voz)"
        Hand.PINCH -> "pellizco"
        Hand.THUMB_DOWN -> "pulgar abajo"
        Hand.BACK -> "indice a la izquierda"
        Hand.ONE_UP -> "un dedo arriba"
        Hand.ONE_DOWN -> "un dedo abajo"
        Hand.TWO_UP -> "dos dedos arriba"
        Hand.TWO_DOWN -> "dos dedos abajo"
    }

    private fun refreshPrefs(now: Long) {
        if (now - prefsAt < 1000) return
        prefsAt = now
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        dead = 0.20f - 0.008f * p.getFloat("hgain", 10f)
        debug = p.getBoolean("debug", false)
        if (!debug) GestureAccessibilityService.instance?.dbgOff()
    }

    // ---------- Acciones ----------

    private fun navigate(x: Float, y: Float, now: Long) {
        if (!anchorSet) { ax = x; ay = y; anchorSet = true; return }
        val dx = x - ax
        val dy = y - ay
        if (max(abs(dx), abs(dy)) < dead || now - lastStep < STEP_MS) return
        lastStep = now
        val dir = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT)
                  else (if (dy > 0) Dir.DOWN else Dir.UP)
        post { GestureAccessibilityService.instance?.dpad(dir) }
    }

    private fun onRelease(prev: Hand, heldMs: Long) {
        if (!controlling) return
        if (prev == Hand.FIST && !longFired && heldMs in 60 until LONG_MS) {
            post { GestureAccessibilityService.instance?.apply { select(false); hud("OK") } }
        }
    }

    private fun onHold(g: Hand, now: Long) {
        val held = now - curSince
        fun repeat(first: Long, every: Long, act: () -> Unit) {
            if (held >= first && (lastFire == 0L || now - lastFire >= every)) { lastFire = now; post(act) }
        }
        val svc = GestureAccessibilityService.instance
        fun vol(dir: Int, label: String) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            svc?.hud(label)
        }
        when (g) {
            Hand.FIST -> if (held >= LONG_MS && !longFired) {
                longFired = true; post { svc?.select(true); svc?.hud("Seleccionar") }
            }
            Hand.TWO_UP -> repeat(250, 450) { vol(AudioManager.ADJUST_RAISE, "Volumen +") }
            Hand.TWO_DOWN -> repeat(250, 450) { vol(AudioManager.ADJUST_LOWER, "Volumen -") }
            Hand.ONE_UP -> repeat(300, 1200) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); svc?.hud("Canal +") }
            Hand.ONE_DOWN -> repeat(300, 1200) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); svc?.hud("Canal -") }
            Hand.PINCH -> repeat(300, 1_000_000_000) { vol(AudioManager.ADJUST_TOGGLE_MUTE, "Silenciar") }
            Hand.OK_SIGN -> repeat(400, 1_000_000_000) { svc?.hud("Comandos de voz"); svc?.voice() }
            Hand.THUMB_DOWN -> repeat(1500, 1_000_000_000) { svc?.hud("Apagando"); svc?.powerOff() }
            Hand.BACK -> repeat(250, 1_000_000_000) {
                svc?.global(AccessibilityService.GLOBAL_ACTION_BACK); svc?.hud("Atras")
            }
            else -> {}
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun post(act: () -> Unit) = ContextCompat.getMainExecutor(this).execute {
        try { act() } catch (e: Exception) { Log.e("CamGestos", "accion: ${e.message}") }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cam", "Control por camara", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraGestureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, "cam")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("CamGestos activo")
            .setContentText("Reconociendo gestos de mano")
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, n)
    }
}
