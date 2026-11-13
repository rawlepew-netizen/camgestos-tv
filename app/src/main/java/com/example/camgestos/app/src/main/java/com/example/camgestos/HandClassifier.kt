package com.example.camgestos

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class Hand {
    NONE, OTHER,
    OPEN, FIST, THUMB_UP, THUMB_DOWN,
    OK_SIGN,   // círculo con pulgar e índice y el resto extendido -> voz / buscar
    PINCH,     // pellizco (pulgar e índice juntos, resto doblado) -> silenciar
    BACK,
    ONE_UP, ONE_DOWN, TWO_UP, TWO_DOWN
}

/** Clasifica los 21 puntos de MediaPipe. Independiente de mano izquierda/derecha. */
object HandClassifier {

    private fun d(a: NormalizedLandmark, b: NormalizedLandmark) =
        hypot(a.x() - b.x(), a.y() - b.y())

    /**
     * Qué tan "abierta" está la mano: la punta de dedo más lejana al centro de la palma,
     * dividida entre el tamaño de la mano. Mano abierta ~1.1, puño ~0.4-0.7.
     */
    fun curl(l: List<NormalizedLandmark>): Float {
        if (l.size < 21) return 9f
        val size = d(l[0], l[9]).coerceAtLeast(1e-3f)
        val cx = (l[0].x() + l[5].x() + l[9].x() + l[13].x() + l[17].x()) / 5f
        val cy = (l[0].y() + l[5].y() + l[9].y() + l[13].y() + l[17].y()) / 5f
        fun dist(i: Int) = hypot(l[i].x() - cx, l[i].y() - cy) / size
        return max(max(dist(8), dist(12)), max(dist(16), dist(20)))
    }

    /**
     * [mirrored]: la imagen llega SIN espejo (hay que invertir izquierda/derecha).
     * [fistThr]: umbral de curl para considerar puño (calibrable).
     * [backDir]: dirección del índice para "Atrás" desde el punto de vista del usuario:
     *            -1 = izquierda, 1 = derecha, 0 = cualquiera.
     */
    fun classify(
        l: List<NormalizedLandmark>,
        mirrored: Boolean = true,
        fistThr: Float = 0.78f,
        backDir: Int = 0
    ): Hand {
        if (l.size < 21) return Hand.NONE
        val size = d(l[0], l[9]).coerceAtLeast(1e-3f)

        fun ext(tip: Int, pip: Int) = d(l[tip], l[0]) > d(l[pip], l[0]) * 1.12f
        val i = ext(8, 6)
        val m = ext(12, 10)
        val r = ext(16, 14)
        val p = ext(20, 18)
        val t = d(l[4], l[17]) > d(l[3], l[17]) * 1.08f && d(l[4], l[5]) > size * 0.45f

        val sign = if (mirrored) -1f else 1f
        val ix = (l[8].x() - l[5].x()) * sign   // positivo = hacia la derecha del usuario
        val iy = l[8].y() - l[5].y()
        val pointUp = iy < 0 && abs(iy) > abs(ix) * 1.3f
        val pointDown = iy > 0 && abs(iy) > abs(ix) * 1.3f
        val horizontal = abs(ix) > abs(iy) * 0.8f
        val pointBack = horizontal && when (backDir) {
            -1 -> ix < 0
            1 -> ix > 0
            else -> true
        }

        val touching = d(l[4], l[8])
        if (touching < size * 0.35f && m && r && p) return Hand.OK_SIGN
        if (touching < size * 0.25f && !m && !r && d(l[8], l[0]) >= d(l[6], l[0]) * 0.95f)
            return Hand.PINCH

        if (i && m && r && p) return Hand.OPEN

        if (curl(l) < fistThr) {
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
            pointBack -> Hand.BACK
            pointUp -> Hand.ONE_UP
            pointDown -> Hand.ONE_DOWN
            else -> Hand.OTHER
        }
        return Hand.OTHER
    }
}
