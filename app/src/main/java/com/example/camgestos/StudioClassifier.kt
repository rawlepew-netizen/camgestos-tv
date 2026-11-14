package com.example.camgestos

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Clasificador de gestos portado de la versión de AI Studio (HandGestureClassifier.java):
 * suaviza los 21 puntos (EMA) y decide el gesto con reglas de dedos extendidos y distancias.
 * Se añaden pulgar arriba / abajo para que un pulgar no cuente como puño.
 */
class StudioClassifier {
    private val sm = Array(21) { FloatArray(3) }
    private var has = false
    private val alpha = 0.45f

    var fingerExt = 1.10f
    var pinchThr = 0.38f

    fun reset() { has = false }

    private fun d(a: FloatArray, b: FloatArray) = hypot(a[0] - b[0], a[1] - b[1])

    /** [enabled] = false cuando el control está en pausa: solo el círculo (OK de mano) sigue activo. */
    fun classify(l: List<NormalizedLandmark>, enabled: Boolean): Hand {
        if (l.size < 21) { reset(); return Hand.NONE }

        // 1. Filtro EMA para quitar el ruido de la cámara
        for (i in 0 until 21) {
            val p = l[i]
            if (!has) {
                sm[i][0] = p.x(); sm[i][1] = p.y(); sm[i][2] = p.z()
            } else {
                sm[i][0] = sm[i][0] * (1f - alpha) + p.x() * alpha
                sm[i][1] = sm[i][1] * (1f - alpha) + p.y() * alpha
                sm[i][2] = sm[i][2] * (1f - alpha) + p.z() * alpha
            }
        }
        has = true

        // 2. Puntos clave
        val wrist = sm[0]
        val thumbMcp = sm[2]
        val thumbTip = sm[4]
        val indexMcp = sm[5]
        val indexPip = sm[6]
        val indexTip = sm[8]
        val middleMcp = sm[9]
        val middlePip = sm[10]
        val middleTip = sm[12]
        val ringPip = sm[14]
        val ringTip = sm[16]
        val pinkyPip = sm[18]
        val pinkyTip = sm[20]

        val handScale = max(d(wrist, middleMcp), 0.05f)
        val margin = handScale * 0.15f

        // 3. Dedos extendidos y hacia dónde apuntan
        val indexExt = d(indexTip, wrist) > d(indexPip, wrist) * fingerExt
        val middleExt = d(middleTip, wrist) > d(middlePip, wrist) * fingerExt
        val indexUp = indexExt && indexTip[1] < indexPip[1] + margin
        val indexDown = indexExt && indexTip[1] > indexPip[1] - margin
        val middleUp = middleExt && middleTip[1] < middlePip[1] + margin
        val middleDown = middleExt && middleTip[1] > middlePip[1] - margin
        val ringUp = d(ringTip, wrist) > d(ringPip, wrist) * fingerExt && ringTip[1] < ringPip[1] + margin
        val pinkyUp = d(pinkyTip, wrist) > d(pinkyPip, wrist) * fingerExt && pinkyTip[1] < pinkyPip[1] + margin

        val twoTogether = d(indexTip, middleTip) / handScale < 0.48f

        val thumbExtended = d(thumbTip, wrist) > d(thumbMcp, wrist) * 1.10f
        val thumbDx = abs(thumbTip[0] - indexMcp[0])
        val thumbDy = abs(thumbTip[1] - indexMcp[1])
        val thumbSideways = thumbDx > handScale * 0.30f && thumbDx >= thumbDy * 0.45f

        val pinch = d(thumbTip, indexTip) / handScale < pinchThr
        // En un puño el índice queda doblado hacia atrás (la punta más cerca de la muñeca que el nudillo).
        // En un pellizco real el índice sigue algo extendido. Así un puño no se confunde con silenciar.
        val indexCurledBack = d(indexTip, wrist) < d(indexPip, wrist) * 0.95f

        // 4. Clasificación (mismo orden de reglas que AI Studio)
        var detected = when {
            pinch && (middleUp || ringUp || pinkyUp) -> Hand.OK_SIGN
            !enabled -> Hand.NONE
            pinch && !indexCurledBack && !middleUp && !ringUp && !pinkyUp -> Hand.PINCH
            indexUp && (thumbSideways || thumbExtended) && !middleUp && !ringUp && !pinkyUp && !pinch -> Hand.BACK
            indexUp && middleUp && twoTogether && !ringUp && !pinkyUp -> Hand.TWO_UP
            indexDown && middleDown && twoTogether && !ringUp && !pinkyUp -> Hand.TWO_DOWN
            indexUp && !middleUp && !ringUp && !pinkyUp && !pinch && !thumbSideways -> Hand.ONE_UP
            indexDown && !middleDown && !ringUp && !pinkyUp -> Hand.ONE_DOWN
            indexUp && middleUp && ringUp && pinkyUp -> Hand.OPEN
            !indexUp && !indexDown && !middleUp && !middleDown && !ringUp && !pinkyUp -> Hand.FIST
            else -> Hand.NONE
        }

        // 5. Extra: un pulgar claramente arriba o abajo no cuenta como puño (evita OK por accidente).
        if (detected == Hand.FIST) {
            val v = thumbTip[1] - thumbMcp[1]
            if (v < -handScale * 0.5f && thumbTip[1] < indexMcp[1]) detected = Hand.THUMB_UP
            else if (v > handScale * 0.5f && thumbTip[1] > indexMcp[1]) detected = Hand.THUMB_DOWN
        }
        return detected
    }
}
