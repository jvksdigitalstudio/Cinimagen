package com.jvksdigitalstudio.cinimagen.engine

import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * Tipos de interpolación entre dos keyframes. EASE_IN_OUT es la curva estándar
 * de cine (aceleración suave, desaceleración suave) y la que usa el Ken Burns
 * effect clásico de documentales.
 */
@Serializable
enum class EasingType {
    LINEAR,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    CUBIC_IN_OUT,
    CUSTOM_BEZIER
}

object Easing {
    fun apply(type: EasingType, t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return when (type) {
            EasingType.LINEAR -> clamped
            EasingType.EASE_IN -> clamped * clamped
            EasingType.EASE_OUT -> 1f - (1f - clamped) * (1f - clamped)
            EasingType.EASE_IN_OUT ->
                if (clamped < 0.5f) 2f * clamped * clamped
                else 1f - (-2f * clamped + 2f).pow(2) / 2f
            EasingType.CUBIC_IN_OUT ->
                if (clamped < 0.5f) 4f * clamped.pow(3)
                else 1f - (-2f * clamped + 2f).pow(3) / 2f
            // CUSTOM_BEZIER se resuelve aparte (ver [applyCubicBezier]), ya
            // que necesita los 4 puntos de control del keyframe, no solo t.
            EasingType.CUSTOM_BEZIER -> clamped
        }
    }

    /**
     * Curva de animación personalizada estilo After Effects / CSS
     * cubic-bezier(x1,y1,x2,y2): dos puntos de control definen la
     * aceleración/desaceleración exacta del movimiento. A diferencia de
     * los 5 easings fijos, esto le da control real a quien sabe lo que
     * está ajustando — un "ease" muy pronunciado al inicio, un rebote
     * sutil, arranques bruscos, etc.
     *
     * Resuelve x(u)=t por bisección (la curva de Bezier está parametrizada
     * en u, no en t directamente) y devuelve y(u) como el progreso real.
     */
    fun applyCubicBezier(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val target = t.coerceIn(0f, 1f)
        if (target <= 0f) return 0f
        if (target >= 1f) return 1f

        fun bezierComponent(u: Float, p1: Float, p2: Float): Float {
            val v = 1f - u
            return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
        }

        var lo = 0f
        var hi = 1f
        var u = target
        // Bisección: 20 iteraciones da precisión más que suficiente para
        // 8 segundos de proyecto a 60fps.
        repeat(20) {
            val x = bezierComponent(u, x1, x2)
            if (x < target) lo = u else hi = u
            u = (lo + hi) / 2f
        }
        return bezierComponent(u, y1, y2)
    }
}

/**
 * Un keyframe de "cámara" para una capa individual: en el instante [timeMs],
 * la capa debe tener este encuadre. Todo lo demás (pan, zoom, rotate, dolly)
 * es resultado de interpolar entre dos de estos.
 *
 * scale: 1.0 = tamaño original de la capa dentro del canvas virtual.
 * translateX/Y: en coordenadas normalizadas del canvas (-1..1), donde 0,0 es el centro.
 * rotationDeg: rotación en grados, sentido horario (giro plano, sobre el propio eje Z).
 * alpha: opacidad 0..1, útil para fundidos entre capas.
 */
@Serializable
data class Keyframe(
    val timeMs: Long,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val alpha: Float = 1f,
    // Tilt 3D real (no es solo un giro plano): inclina la capa en el eje
    // X (vertical, como una cámara mirando hacia arriba/abajo) o en el
    // eje Y (horizontal, como un paneo lateral en perspectiva). A
    // diferencia de rotationDeg (que gira sobre el propio plano de la
    // imagen), esto usa una proyección de cámara real, así que los
    // bordes se acercan/alejan con perspectiva de verdad.
    val tiltXDeg: Float = 0f,
    val tiltYDeg: Float = 0f,
    // Desenfoque de profundidad (rack focus): 0 = nítido, 1 = muy
    // desenfocado. Animable en el tiempo, igual que en cine real cuando
    // se "jala" el foco de un sujeto a otro.
    val focusBlur: Float = 0f,
    // Dolly zoom (efecto Vértigo): mueve la cámara virtual physically
    // más cerca/lejos mientras compensa el FOV para que ESTA capa
    // mantenga su tamaño exacto — el warp real ocurre en las demás capas
    // según su profundidad (derivada de su parallaxFactor). -1..1.
    val dollyZoom: Float = 0f,
    val easing: EasingType = EasingType.EASE_IN_OUT,
    // Solo se usan si easing == CUSTOM_BEZIER. Valores por defecto
    // equivalentes a un ease-in-out estándar (los mismos que usa CSS).
    val bezierX1: Float = 0.42f,
    val bezierY1: Float = 0f,
    val bezierX2: Float = 0.58f,
    val bezierY2: Float = 1f
)

/**
 * El resultado interpolado de un track en un instante dado; lo que el
 * renderer necesita para construir la matriz de transformación de la capa.
 */
data class CameraFrame(
    val translateX: Float,
    val translateY: Float,
    val scale: Float,
    val rotationDeg: Float,
    val alpha: Float,
    val tiltXDeg: Float = 0f,
    val tiltYDeg: Float = 0f,
    val focusBlur: Float = 0f,
    val dollyZoom: Float = 0f
)
