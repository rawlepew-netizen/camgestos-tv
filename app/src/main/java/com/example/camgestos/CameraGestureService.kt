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
import com.google.mediapipe.tasks.core.Delegate
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
 *  - En espera: solo escucha "mano abierta sostenida". Quien la haga se queda con el control.
 *  - Con control: sigue solo a esa mano (ignora manos que aparecen en otro sitio).
 *  - Sin su mano unos segundos: libera el control.
 * Todos los tiempos y umbrales se leen de los ajustes (pantalla Calibración).
 */
class CameraGestureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_CALIBRATE = "calibrate"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        const val STABLE_FRAMES = 2
        const val SLOW_MS = 380L
        @Volatile var running = false
    }

    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var started = false
    private val prefs get() = getSharedPreferences("cfg", MODE_PRIVATE)

    // Cámara
    @Volatile private var flipByCoords = true
    @Volatile private var minGapMs = 50L
    private var lastAnalyzed = 0L
    private val ring = arrayOfNulls<Bitmap>(3)
    private var ringIdx = 0
    private var firstResult = false

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
    private var lastFire = 0L
    private var lastOk = 0L

    // Navegación
    private var ax = 0f
    private var ay = 0f
    private var sx = 0f
    private var sy = 0f
    private var anchorSet = false
    private var armed = true
    private var lastStep = 0L

    // Ajustes en caché (se releen cada segundo)
    private var deadZone = 0.12f
    private var fastIv = 180L
    private var actMs = 1200L
    private var relMs = 3000L
    private var fistEnter = 0.70f
    private var fistExit = 0.87f
    private var handPref = "any"
    private var backDir = 0
    private var debug = false
    private var prefsAt = 0L

    // FPS / depuración
    private var frames = 0
    private var fps = 0
    private var fpsAt = 0L
    private var dbgAt = 0L

    // Calibración
    @Volatile private var calRequested = false
    private var calPhase = 0
    private var calStart = 0L
    private var calShown = 0
    private val calOpen = ArrayList<Float>()
    private val calFist = ArrayList<Float>()

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor()
        startInForeground()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_CALIBRATE -> { calRequested = true; return START_STICKY }
        }
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
            val p = prefs
            var useGpu = p.getBoolean("gpu", false)
            if (p.getBoolean("gpu_trying", false)) {
                // La vez anterior con GPU la app se cerró de golpe: se desactiva sola.
                p.edit().putBoolean("gpu", false).putBoolean("gpu_trying", false).commit()
                useGpu = false
                GestureAccessibilityService.instance?.hud("GPU desactivada por un error anterior")
            }
            val file = File(filesDir, "hand_landmarker.task")
            if (!file.exists()) {
                GestureAccessibilityService.instance?.hud("Descargando modelo")
                val tmp = File(filesDir, "model.tmp")
                URL(MODEL_URL).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(file)
            }
            val bytes = file.readBytes()
            val conf = p.getFloat("conf", 0.45f)

            fun make(gpu: Boolean): HandLandmarker {
                val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
                val bb = BaseOptions.builder().setModelAssetBuffer(buf)
                if (gpu) bb.setDelegate(Delegate.GPU)
                val options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(bb.build())
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(conf)
                    .setMinHandPresenceConfidence(conf - 0.05f)
                    .setMinTrackingConfidence(conf - 0.05f)
                    .setResultListener { res, _ ->
                        try { onResult(res) } catch (e: Exception) { Log.e("CamGestos", "gesto: ${e.message}") }
                    }
                    .setErrorListener { Log.e("CamGestos", "MediaPipe: ${it.message}") }
                    .build()
                return HandLandmarker.createFromOptions(this, options)
            }

            landmarker = if (useGpu) {
                p.edit().putBoolean("gpu_trying", true).commit()
                try {
                    make(true)
                } catch (e: Exception) {
                    p.edit().putBoolean("gpu", false).putBoolean("gpu_trying", false).commit()
                    GestureAccessibilityService.instance?.hud("GPU no disponible, usando CPU")
                    make(false)
                }
            } else make(false)
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
                    GestureAccessibilityService.instance?.hud("No se detecta ninguna cámara")
                    stopSelf()
                    return@addListener
                }
                // Resolución pequeña = mucho menos trabajo por fotograma.
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
                    val now = SystemClock.uptimeMillis()
                    // Límite de fotogramas por segundo (ajustable): menos carga = menos retraso.
                    if (now - lastAnalyzed < minGapMs) { proxy.close(); return@setAnalyzer }
                    lastAnalyzed = now
                    val rotation = proxy.imageInfo.rotationDegrees
                    val w = proxy.width
                    val h = proxy.height
                    if (rotation == 0) {
                        // Camino rápido: sin girar ni copiar; el espejo se aplica a las coordenadas.
                        flipByCoords = true
                        val cached = ring[ringIdx]
                        val bmp: Bitmap =
                            if (cached != null && cached.width == w && cached.height == h) cached
                            else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { ring[ringIdx] = it }
                        ringIdx = (ringIdx + 1) % ring.size
                        proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
                        landmarker?.detectAsync(BitmapImageBuilder(bmp).build(), now)
                    } else {
                        // Camino de respaldo: gira y hace espejo en el bitmap.
                        flipByCoords = false
                        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        proxy.use { src.copyPixelsFromBuffer(it.planes[0].buffer) }
                        val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(-1f, 1f) }
                        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                        landmarker?.detectAsync(BitmapImageBuilder(out).build(), now)
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
                GestureAccessibilityService.instance?.hud("Reconocimiento listo")
            } catch (e: Exception) {
                Log.e("CamGestos", "cámara: ${e.message}")
                GestureAccessibilityService.instance?.hud("Error de cámara")
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
        if (!firstResult) { firstResult = true; prefs.edit().putBoolean("gpu_trying", false).apply() }
        refreshPrefs(now)
        val flip = flipByCoords
        var lm = res.landmarks().firstOrNull()
        val curlV = if (lm != null) HandClassifier.curl(lm) else -1f

        // Mano derecha o izquierda (MediaPipe supone imagen en espejo; con imagen cruda se invierte).
        var userRight: Boolean? = null
        if (lm != null) {
            userRight = when (res.handedness().firstOrNull()?.firstOrNull()?.categoryName()) {
                "Right" -> !flip
                "Left" -> flip
                else -> null
            }
        }

        // Calibración guiada: pausa el control normal.
        if (calPhase != 0 || calRequested) { calibrate(curlV, now); return }

        // La preferencia de mano solo se aplica al activar el control.
        if (!controlling && lm != null && handPref != "any" && userRight != null &&
            userRight != (handPref == "right")) lm = null

        // Bloqueo de propietario: ignora manos que "saltan" de sitio o cambian mucho de tamaño.
        if (lm != null && controlling) {
            val cx = lm[9].x()
            val cy = lm[9].y()
            val size = hypot(lm[0].x() - lm[9].x(), lm[0].y() - lm[9].y()).coerceAtLeast(1e-3f)
            val allowed = 0.35f + 0.15f * ((now - lastHandAt) / 1000f)
            val ratio = size / osize
            if (hypot(cx - ox, cy - oy) > allowed || ratio < 0.5f || ratio > 2.0f) {
                lm = null
            } else {
                ox = cx; oy = cy; osize = osize * 0.8f + size * 0.2f; lastHandAt = now
            }
        }
        if (controlling && now - lastHandAt > relMs) release()

        val thr = if (cur == Hand.FIST) fistExit else fistEnter
        val g = if (lm == null) Hand.NONE else HandClassifier.classify(lm, flip, thr, backDir)

        frames++
        if (now - fpsAt >= 1000) { fps = frames; frames = 0; fpsAt = now }
        if (debug && now - dbgAt >= 250) {
            dbgAt = now
            val c = "%.2f".format(curlV)
            val t = "%.2f".format(thr)
            val hand = when (userRight) { true -> "mano der."; false -> "mano izq."; null -> "-" }
            GestureAccessibilityService.instance?.dbg(
                "$fps fps  |  ${g.label()}  |  curl $c / $t  |  $hand  |  ${if (controlling) "control activo" else "en espera"}"
            )
        }

        // Estabilizador
        if (g == cand) candN++ else { cand = g; candN = 1; candSince = now }
        if (candN >= STABLE_FRAMES && cand != cur) {
            cur = cand; curSince = candSince
            lastFire = 0L; anchorSet = false; armed = true
            // Puño cerrado = tecla OK (una vez por puño, con pausa de seguridad).
            if (controlling && cur == Hand.FIST && now - lastOk > 600) {
                lastOk = now
                post { GestureAccessibilityService.instance?.apply { select(); hud("OK") } }
            }
        }

        if (!controlling) {
            if (cur == Hand.OPEN && g == Hand.OPEN && lm != null && now - curSince >= actMs)
                activate(lm[9].x(), lm[9].y(), lm[0].x(), lm[0].y(), now)
            return
        }
        if (cur == Hand.OPEN && g == Hand.OPEN && lm != null) {
            val nx = if (flip) 1f - lm[9].x() else lm[9].x()
            navigate(nx, lm[9].y(), now)
        }
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
        anchorSet = false
        GestureAccessibilityService.instance?.hud("Control liberado")
    }

    // ---------- Calibración del puño ----------

    private fun calibrate(curlV: Float, now: Long) {
        val svc = GestureAccessibilityService.instance
        if (calRequested) {
            calRequested = false
            calPhase = 1; calStart = now; calShown = 0
            calOpen.clear(); calFist.clear()
            controlling = false
            cur = Hand.NONE; cand = Hand.NONE; candN = 0
        }
        val t = now - calStart
        when {
            t < 2500 -> {
                if (calShown < 1) { calShown = 1; svc?.hud("Calibración: abre la mano") }
                if (t >= 1000 && curlV >= 0f) calOpen.add(curlV)
            }
            t < 5000 -> {
                if (calShown < 2) { calShown = 2; svc?.hud("Ahora cierra el puño") }
                if (t >= 3500 && curlV >= 0f) calFist.add(curlV)
            }
            else -> { calPhase = 0; finishCalibration() }
        }
    }

    private fun finishCalibration() {
        val svc = GestureAccessibilityService.instance
        val o = calOpen.sorted()
        val f = calFist.sorted()
        if (o.size < 4 || f.size < 4) {
            svc?.hud("Calibración fallida: no se vio la mano"); return
        }
        val openLow = o[(o.size * 0.1f).toInt()]
        val fistHigh = f[((f.size - 1) * 0.9f).toInt()]
        if (openLow - fistHigh < 0.15f) {
            svc?.hud("Calibración fallida: gestos muy parecidos"); return
        }
        val base = fistHigh + 0.4f * (openLow - fistHigh)
        prefs.edit().putFloat("fist_base", base).putFloat("fist_sens", 5f).apply()
        prefsAt = 0L
        svc?.hud("Calibración lista")
    }

    private fun Hand.label() = when (this) {
        Hand.NONE -> "sin mano"
        Hand.OTHER -> "otro gesto"
        Hand.OPEN -> "mano abierta"
        Hand.FIST -> "puño"
        Hand.THUMB_UP -> "pulgar arriba"
        Hand.THUMB_DOWN -> "pulgar abajo"
        Hand.OK_SIGN -> "círculo (voz)"
        Hand.PINCH -> "pellizco"
        Hand.BACK -> "índice al lado"
        Hand.ONE_UP -> "un dedo arriba"
        Hand.ONE_DOWN -> "un dedo abajo"
        Hand.TWO_UP -> "dos dedos arriba"
        Hand.TWO_DOWN -> "dos dedos abajo"
    }

    private fun refreshPrefs(now: Long) {
        if (now - prefsAt < 1000) return
        prefsAt = now
        val p = prefs
        deadZone = 0.20f - 0.008f * p.getFloat("hgain", 10f)
        fastIv = (330f - 25f * p.getFloat("speed", 6f)).toLong().coerceAtLeast(60L)
        actMs = (p.getFloat("act_s", 1.2f) * 1000f).toLong()
        relMs = (p.getFloat("rel_s", 3f) * 1000f).toLong()
        val enter = p.getFloat("fist_base", 0.70f) + (p.getFloat("fist_sens", 5f) - 5f) * 0.04f
        fistEnter = enter.coerceIn(0.45f, 1.05f)
        fistExit = fistEnter + 0.17f
        handPref = p.getString("hand", "any") ?: "any"
        backDir = when (handPref) { "left" -> -1; "right" -> 1; else -> 0 }
        minGapMs = (1000f / p.getFloat("maxfps", 20f)).toLong()
        debug = p.getBoolean("debug", false)
        if (!debug) GestureAccessibilityService.instance?.dbgOff()
    }

    // ---------- Acciones ----------

    /**
     * Estilo joystick: la posición al abrir la mano es el centro. Al empujar fuera de la zona muerta
     * sale una flecha al instante; cuanto más lejos, más rápido se repite.
     */
    private fun navigate(x: Float, y: Float, now: Long) {
        if (!anchorSet) { ax = x; ay = y; sx = x; sy = y; anchorSet = true; armed = true; return }
        sx += (x - sx) * 0.6f
        sy += (y - sy) * 0.6f
        val dx = sx - ax
        val dy = sy - ay
        val dist = max(abs(dx), abs(dy))
        if (dist < deadZone) {
            ax += dx * 0.03f; ay += dy * 0.03f   // recentrado lento si la mano se queda quieta
            armed = true
            return
        }
        val over = ((dist - deadZone) / 0.20f).coerceIn(0f, 1f)
        val interval = (SLOW_MS - (SLOW_MS - fastIv) * over).toLong()
        val ready = armed || now - lastStep >= interval
        if (!ready || now - lastStep < 90) return
        armed = false
        lastStep = now
        val dir = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT)
                  else (if (dy > 0) Dir.DOWN else Dir.UP)
        post { GestureAccessibilityService.instance?.dpad(dir) }
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
            Hand.TWO_UP -> repeat(250, 450) { vol(AudioManager.ADJUST_RAISE, "Volumen +") }
            Hand.TWO_DOWN -> repeat(250, 450) { vol(AudioManager.ADJUST_LOWER, "Volumen -") }
            Hand.ONE_UP -> repeat(300, 1200) { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); svc?.hud("Canal +") }
            Hand.ONE_DOWN -> repeat(300, 1200) { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); svc?.hud("Canal -") }
            Hand.PINCH -> repeat(300, 1_000_000_000) { vol(AudioManager.ADJUST_TOGGLE_MUTE, "Silenciar") }
            Hand.OK_SIGN -> repeat(400, 1_000_000_000) { svc?.hud("Comandos de voz"); svc?.voice() }
            Hand.THUMB_DOWN -> repeat(1500, 1_000_000_000) { svc?.hud("Apagando"); svc?.powerOff() }
            Hand.BACK -> repeat(250, 1_000_000_000) {
                svc?.global(AccessibilityService.GLOBAL_ACTION_BACK); svc?.hud("Atrás")
            }
            else -> {}
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun post(act: () -> Unit) = ContextCompat.getMainExecutor(this).execute {
        try { act() } catch (e: Exception) { Log.e("CamGestos", "acción: ${e.message}") }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cam", "Control por cámara", NotificationManager.IMPORTANCE_LOW)
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
