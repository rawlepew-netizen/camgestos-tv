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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import kotlin.math.max

/**
 * Gestos de mano para Android TV:
 *  ✋ mano abierta ........ mantener y empujar la mano a un lado = flechas del control remoto
 *  ✊ puño rápido ......... OK / seleccionar
 *  ✊ puño sostenido ...... pulsación larga
 *  ✌ dos dedos arriba/abajo ... volumen + / -
 *  ☝ un dedo arriba/abajo ..... canal (pista) + / -
 *  👌 mute   👈 atrás
 *  🙃 mano abierta al revés (1 s) ... standby (pantalla negra)
 *  👍 (0,8 s) ............ reanudar y encender pantalla
 */
class CameraGestureService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "stop"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        @Volatile var running = false
    }

    private enum class Mode { ACTIVE, STANDBY }

    private lateinit var executor: ExecutorService
    private var landmarker: HandLandmarker? = null
    private var started = false
    @Volatile private var mode = Mode.ACTIVE
    @Volatile private var lastFrame = 0L

    private var cand = Hand.NONE
    private var candN = 0
    private var cur = Hand.NONE
    private var curSince = 0L
    private var lastFire = 0L
    private var longFired = false

    private var ax = 0f
    private var ay = 0f
    private var anchorSet = false
    private var lastStep = 0L
    private var cpuLock: PowerManager.WakeLock? = null

    private val prefs get() = getSharedPreferences("cfg", MODE_PRIVATE)

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
        GestureAccessibilityService.instance?.setBlack(false)
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        landmarker?.close()
        cpuLock?.let { if (it.isHeld) it.release() }
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- Modelo y cámara ----------

    private fun prepareAndStart() {
        try {
            val file = File(filesDir, "hand_landmarker.task")
            if (!file.exists()) {
                GestureAccessibilityService.instance?.hud("Descargando modelo…")
                val tmp = File(filesDir, "model.tmp")
                URL(MODEL_URL).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(file)
            }
            val bytes = file.readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener { res, _ -> onResult(res) }
                .setErrorListener { Log.e("CamGestos", "MediaPipe: ${it.message}") }
                .build()
            landmarker = HandLandmarker.createFromOptions(this, options)
            ContextCompat.getMainExecutor(this).execute { startCamera() }
        } catch (e: Exception) {
            Log.e("CamGestos", "No se pudo iniciar: ${e.message}")
            GestureAccessibilityService.instance?.hud("⚠ Error: ${e.message}")
            stopSelf()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = pickCamera(provider)
            if (selector == null) {
                GestureAccessibilityService.instance?.hud("⚠ No se detecta ninguna cámara (conecta una webcam USB)")
                stopSelf()
                return@addListener
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val now = SystemClock.uptimeMillis()
                if (mode == Mode.STANDBY && now - lastFrame < 500) { proxy.close(); return@setAnalyzer }
                lastFrame = now
                val rotation = proxy.imageInfo.rotationDegrees.toFloat()
                val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                proxy.use { bmp.copyPixelsFromBuffer(it.planes[0].buffer) }
                val m = Matrix().apply { postRotate(rotation); postScale(-1f, 1f) } // espejo
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                landmarker?.detectAsync(BitmapImageBuilder(out).build(), now)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
            GestureAccessibilityService.instance?.hud("✋ Reconocimiento de gestos listo")
        }, ContextCompat.getMainExecutor(this))
    }

    /** Prioriza la cámara externa USB; si no hay, frontal; si no, trasera. */
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

    // ---------- Máquina de gestos ----------

    private fun onResult(res: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val lm = res.landmarks().firstOrNull()
        val g = if (lm == null) Hand.NONE else HandClassifier.classify(lm)

        if (g == cand) candN++ else { cand = g; candN = 1 }
        if (candN >= 3 && cand != cur) {
            onRelease(cur, now)
            cur = cand; curSince = now; lastFire = 0L; longFired = false; anchorSet = false
        }

        if (mode == Mode.ACTIVE && cur == Hand.OPEN && g == Hand.OPEN && lm != null)
            navigate(lm[9].x(), lm[9].y(), now)
        onHold(cur, now)
    }

    /** Estilo joystick: la posición al abrir la mano es el centro; empujar fuera de la zona muerta = flecha. */
    private fun navigate(x: Float, y: Float, now: Long) {
        if (!anchorSet) { ax = x; ay = y; anchorSet = true; return }
        val dead = 0.20f - 0.008f * prefs.getFloat("hgain", 10f)
        val dx = x - ax
        val dy = y - ay
        if (max(abs(dx), abs(dy)) < dead || now - lastStep < 380) return
        lastStep = now
        val dir = if (abs(dx) > abs(dy)) (if (dx > 0) Dir.RIGHT else Dir.LEFT)
                  else (if (dy > 0) Dir.DOWN else Dir.UP)
        post { GestureAccessibilityService.instance?.dpad(dir) }
    }

    private fun onRelease(prev: Hand, now: Long) {
        if (mode != Mode.ACTIVE) return
        val held = now - curSince
        if (prev == Hand.FIST && !longFired && held in 60..700) {
            post {
                GestureAccessibilityService.instance?.apply { select(false); hud("✊ OK") }
            }
        }
    }

    private fun onHold(g: Hand, now: Long) {
        val held = now - curSince
        if (mode == Mode.STANDBY) {
            if (g == Hand.THUMB_UP && held >= 800 && lastFire == 0L) { lastFire = now; post { activate() } }
            return
        }
        fun repeat(first: Long, every: Long, act: () -> Unit) {
            if (held >= first && (lastFire == 0L || now - lastFire >= every)) { lastFire = now; post(act) }
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val svc = GestureAccessibilityService.instance
        fun vol(dir: Int, label: String) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            svc?.hud(label)
        }
        when (g) {
            Hand.FIST -> if (held >= 800 && !longFired) {
                longFired = true; post { svc?.select(true); svc?.hud("✊ Seleccionar (largo)") }
            }
            Hand.TWO_UP -> repeat(250, 450) { vol(AudioManager.ADJUST_RAISE, "🔊 Volumen +") }
            Hand.TWO_DOWN -> repeat(250, 450) { vol(AudioManager.ADJUST_LOWER, "🔉 Volumen −") }
            Hand.ONE_UP -> repeat(300, 1200) { mediaKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT); svc?.hud("⏭ Canal +") }
            Hand.ONE_DOWN -> repeat(300, 1200) { mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS); svc?.hud("⏮ Canal −") }
            Hand.OK -> repeat(300, 1_000_000_000) { vol(AudioManager.ADJUST_TOGGLE_MUTE, "🔇 Mute") }
            Hand.BACK -> repeat(300, 1_000_000_000) {
                svc?.global(AccessibilityService.GLOBAL_ACTION_BACK); svc?.hud("👈 Atrás")
            }
            Hand.OPEN_DOWN -> repeat(1000, 1_000_000_000) { standby() }
            else -> {}
        }
    }

    private fun mediaKey(a: AudioManager, code: Int) {
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------- Standby / despertar ----------

    private fun standby() {
        mode = Mode.STANDBY
        val svc = GestureAccessibilityService.instance
        svc?.setBlack(true)
        if (prefs.getBoolean("deep", false) && Build.VERSION.SDK_INT >= 28)
            svc?.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (cpuLock == null) cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camgestos:cpu")
        if (cpuLock?.isHeld == false) cpuLock?.acquire()
    }

    @Suppress("DEPRECATION")
    private fun activate() {
        mode = Mode.ACTIVE
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE, "camgestos:screen"
        ).acquire(5000)
        cpuLock?.let { if (it.isHeld) it.release() }
        GestureAccessibilityService.instance?.apply { setBlack(false); hud("👍 Activo") }
    }

    private fun post(act: () -> Unit) = ContextCompat.getMainExecutor(this).execute(act)

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
            .setContentTitle("CamGestos TV activo")
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
