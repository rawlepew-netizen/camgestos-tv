package com.example.camgestos

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

enum class Hand {
    NONE, OTHER,
    OPEN, OPEN_DOWN, FIST, THUMB_UP, OK, BACK,
    ONE_UP, ONE_DOWN, TWO_UP, TWO_DOWN
}

/** Clasifica los 21 puntos de MediaPipe. Independiente de mano izquierda/derecha. */
object HandClassifier {

    private fun d(a: NormalizedLandmark, b: NormalizedLandmark) =
        hypot(a.x() - b.x(), a.y() - b.y())

    fun classify(l: List<NormalizedLandmark>): Hand {
        if (l.size < 21) return Hand.NONE
        val size = d(l[0], l[9]).coerceAtLeast(1e-3f)

        fun ext(tip: Int, pip: Int) = d(l[tip], l[0]) > d(l[pip], l[0]) * 1.12f
        val i = ext(8, 6)
        val m = ext(12, 10)
        val r = ext(16, 14)
        val p = ext(20, 18)
        val t = d(l[4], l[17]) > d(l[3], l[17]) * 1.08f && d(l[4], l[5]) > size * 0.45f

        val px = l[9].x() - l[0].x()
        val py = l[9].y() - l[0].y()
        val palmDown = py > 0 && abs(py) > abs(px) * 0.8f

        val ix = l[8].x() - l[5].x()
        val iy = l[8].y() - l[5].y()
        val pointUp = iy < 0 && abs(iy) > abs(ix) * 1.3f
        val pointDown = iy > 0 && abs(iy) > abs(ix) * 1.3f
        val pointLeft = ix < 0 && abs(ix) > abs(iy) * 1.3f

        if (d(l[4], l[8]) < size * 0.35f && m && r && p) return Hand.OK
        if (i && m && r && p) return if (palmDown) Hand.OPEN_DOWN else Hand.OPEN

        if (!i && !m && !r && !p) {
            val thumbUp = t && l[4].y() < l[2].y() - size * 0.4f && l[4].y() < l[8].y()
            return if (thumbUp) Hand.THUMB_UP else Hand.FIST
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
