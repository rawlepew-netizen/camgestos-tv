package com.example.camgestos

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Clasificador basado en el de AI Studio (suaviza los 21 puntos y usa dedos extendidos y distancias),
 * con estas diferencias:
 *  - Sin "atrás" por pulgar: un dedo arriba con el pulgar suelto sigue siendo "un dedo arriba".
 *  - Palma volteada (dorso de la mano) = atrás, con cualquier mano.
 *  - Mano abierta de lado hacia la derecha = inicio.
 *  - Índice apuntando a la cámara = pausa / play.
 *  - Pulgar arriba / abajo no cuentan como puño.
 */
class StudioClassifier {
    private val sm = Array(21) { FloatArray(3) }
    private var has = false
    private val alpha = 0.45f

    // Orientación de la palma aprendida con la mano abierta (para detectar cuándo se voltea).
    private var palmSign = 0
    private var palmVotes = 0f

    var fingerExt = 1.10f
    var pinchThr = 0.38f
    /** Hacia dónde apunta la mano para "Home": 1 = derecha del usuario, -1 = izquierda, 0 = ambos lados. */
    var homeSide = 1

    fun reset() { has = false }
    fun forgetPalm() { palmSign = 0; palmVotes = 0f }

    private fun d(a: FloatArray, b: FloatArray) = hypot(a[0] - b[0], a[1] - b[1])

    /**
     * [enabled] = false con el control en pausa o en standby: solo se reconocen el círculo y,
     * si [allowThumbUp], el pulgar arriba (para salir de standby).
     * [mirrored] = la imagen llega sin espejo (izquierda y derecha invertidas).
     */
    fun classify(l: List<NormalizedLandmark>, enabled: Boolean, allowThumbUp: Boolean, mirrored: Boolean): Hand {
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
        val pinkyMcp = sm[17]
        val pinkyPip = sm[18]
        val pinkyTip = sm[20]

        val handScale = max(d(wrist, middleMcp), 0.05f)
        val margin = handScale * 0.15f

        // 3. Dedos extendidos y hacia dónde apuntan
        val indexExt = d(indexTip, wrist) > d(indexPip, wrist) * fingerExt
        val middleExt = d(middleTip, wrist) > d(middlePip, wrist) * fingerExt
        val ringExt = d(ringTip, wrist) > d(ringPip, wrist) * fingerExt
        val pinkyExt = d(pinkyTip, wrist) > d(pinkyPip, wrist) * fingerExt
        val indexUp = indexExt && indexTip[1] < indexPip[1] + margin
        val indexDown = indexExt && indexTip[1] > indexPip[1] - margin
        val middleUp = middleExt && middleTip[1] < middlePip[1] + margin
        val middleDown = middleExt && middleTip[1] > middlePip[1] - margin
        val ringUp = ringExt && ringTip[1] < ringPip[1] + margin
        val pinkyUp = pinkyExt && pinkyTip[1] < pinkyPip[1] + margin

        val twoTogether = d(indexTip, middleTip) / handScale < 0.48f
        val pinch = d(thumbTip, indexTip) / handScale < pinchThr
        // En un puño el índice queda doblado hacia atrás; en un pellizco real sigue algo extendido.
        val indexCurledBack = d(indexTip, wrist) < d(indexPip, wrist) * 0.95f
        val fistLike = !indexUp && !indexDown && !middleUp && !middleDown && !ringUp && !pinkyUp

        // Pulgar claramente arriba o abajo
        val tv = thumbTip[1] - thumbMcp[1]
        val thumbIsUp = tv < -handScale * 0.5f && thumbTip[1] < indexMcp[1]
        val thumbIsDown = tv > handScale * 0.5f && thumbTip[1] > indexMcp[1]

        // Índice apuntando a la cámara: corto en 2D, por encima de las otras puntas y más cerca (z).
        val tipsAvgY = (middleTip[1] + ringTip[1]) / 2f
        val pointFront = !middleExt && !ringExt && !pinkyExt && !pinch &&
            d(indexTip, indexMcp) < handScale * 0.50f &&
            indexTip[1] < tipsAvgY - handScale * 0.15f &&
            (indexMcp[2] - indexTip[2]) > handScale * 0.15f

        // Mano abierta de lado, hacia afuera según la mano que controla (derecha: 🫱 hacia la derecha).
        val axisDx = middleMcp[0] - wrist[0]
        val axisDy = middleMcp[1] - wrist[1]
        val sideways = abs(axisDx) > abs(axisDy) * 1.2f
        val toUserRight = if (mirrored) axisDx < 0f else axisDx > 0f
        val sideOk = homeSide == 0 || (homeSide > 0 && toUserRight) || (homeSide < 0 && !toUserRight)
        val homePose = indexExt && middleExt && ringExt && pinkyExt && sideways && sideOk

        // 4. Clasificación
        var detected = when {
            pinch && (middleUp || ringUp || pinkyUp) -> Hand.OK_SIGN
            !enabled -> if (allowThumbUp && fistLike && thumbIsUp) Hand.THUMB_UP else Hand.NONE
            pinch && !indexCurledBack && !middleUp && !ringUp && !pinkyUp -> Hand.PINCH
            pointFront -> Hand.POINT_FRONT
            homePose -> Hand.HOME
            indexUp && middleUp && twoTogether && !ringUp && !pinkyUp -> Hand.TWO_UP
            indexDown && middleDown && twoTogether && !ringUp && !pinkyUp -> Hand.TWO_DOWN
            indexUp && !middleUp && !ringUp && !pinkyUp && !pinch -> Hand.ONE_UP
            indexDown && !middleDown && !ringUp && !pinkyUp -> Hand.ONE_DOWN
            indexUp && middleUp && ringUp && pinkyUp -> Hand.OPEN
            fistLike -> Hand.FIST
            else -> Hand.NONE
        }

        // 5. Un pulgar claramente arriba o abajo no cuenta como puño (evita OK por accidente).
        if (detected == Hand.FIST) {
            if (thumbIsUp) detected = Hand.THUMB_UP
            else if (thumbIsDown) detected = Hand.THUMB_DOWN
        }

        // 6. Palma volteada: el signo del producto cruzado índice-meñique cambia al voltear la mano.
        if (detected == Hand.OPEN) {
            val cross = (indexMcp[0] - wrist[0]) * (pinkyMcp[1] - wrist[1]) -
                (indexMcp[1] - wrist[1]) * (pinkyMcp[0] - wrist[0])
            val crossN = cross / (handScale * handScale)
            val facing = if (crossN > 0.12f) 1 else if (crossN < -0.12f) -1 else 0
            if (facing != 0) {
                if (palmSign == 0) {
                    palmVotes += facing
                    if (abs(palmVotes) >= 6f) palmSign = if (palmVotes > 0f) 1 else -1
                } else if (facing == -palmSign) {
                    detected = Hand.BACKHAND
                }
            }
        }
        return detected
    }
}
