package com.example.camgestos

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

enum class Hand {
    NONE, OTHER,
    OPEN, FIST, THUMB_UP, THUMB_DOWN,
    OK_SIGN,   // circulo con pulgar e indice y el resto extendido -> voz / buscar
    PINCH,     // pellizco (pulgar e indice juntos, resto doblado) -> silenciar
    BACK,
    ONE_UP, ONE_DOWN, TWO_UP, TWO_DOWN
}

/** Clasifica los 21 puntos de MediaPipe. Independiente de mano izquierda/derecha. */
object HandClassifier {

    private fun d(a: NormalizedLandmark, b: NormalizedLandmark) =
        hypot(a.x() - b.x(), a.y() - b.y())

    /** [mirrored] = la imagen real esta en espejo respecto a lo que ve el usuario. */
    fun classify(l: List<NormalizedLandmark>, mirrored: Boolean = true): Hand {
        if (l.size < 21) return Hand.NONE
        val size = d(l[0], l[9]).coerceAtLeast(1e-3f)

        fun ext(tip: Int, pip: Int) = d(l[tip], l[0]) > d(l[pip], l[0]) * 1.12f
        fun curled(tip: Int, pip: Int) =
            d(l[tip], l[0]) < d(l[pip], l[0]) * 1.02f || d(l[tip], l[9]) < size * 0.6f

        val i = ext(8, 6)
        val m = ext(12, 10)
        val r = ext(16, 14)
        val p = ext(20, 18)
        val t = d(l[4], l[17]) > d(l[3], l[17]) * 1.08f && d(l[4], l[5]) > size * 0.45f

        val sign = if (mirrored) -1f else 1f
        val ix = (l[8].x() - l[5].x()) * sign
        val iy = l[8].y() - l[5].y()
        val pointUp = iy < 0 && abs(iy) > abs(ix) * 1.3f
        val pointDown = iy > 0 && abs(iy) > abs(ix) * 1.3f
        // Mas tolerante que antes: basta que el indice apunte claramente hacia la izquierda.
        val pointLeft = ix < 0 && abs(ix) > abs(iy) * 0.8f

        val touching = d(l[4], l[8])
        if (touching < size * 0.35f && m && r && p) return Hand.OK_SIGN
        if (touching < size * 0.25f && !m && !r && d(l[8], l[0]) >= d(l[6], l[0]) * 0.95f)
            return Hand.PINCH

        if (i && m && r && p) return Hand.OPEN

        if (curled(8, 6) && curled(12, 10) && curled(16, 14) && curled(20, 18)) {
            val thumbUp = t && l[4].y() < l[2].y() - size * 0.4f && l[4].y() < l[8].y()
            val thumbDown = t && l[4].y() > l[2].y() + size * 0.4f && l[4].y() > l[8].y()
            return when {
                thumbUp -> Hand.THUMB_UP
                thumbDown -> Hand.THUMB_DOWN
                else -> Hand.FIST
            }
        }
        if (i && m && !r && !p) return when {
            pointUp -> Hand.TWO_UP
            pointDown -> Hand.TWO_DOWN
            else -> Hand.OTHER
        }
        if (i && !m && !r && !p) return when {
            pointLeft -> Hand.BACK
            pointUp -> Hand.ONE_UP
            pointDown -> Hand.ONE_DOWN
            else -> Hand.OTHER
        }
        return Hand.OTHER
    }
}
