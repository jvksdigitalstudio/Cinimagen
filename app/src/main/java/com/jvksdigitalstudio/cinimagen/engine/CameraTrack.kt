package com.jvksdigitalstudio.cinimagen.engine

/**
 * Una pista de cámara: lista ordenada de keyframes que definen cómo se
 * mueve el "encuadre virtual" sobre una capa a lo largo del tiempo.
 *
 * Esto es el equivalente directo a lo que hace After Effects con las
 * propiedades de posición/escala/rotación de una capa, pero simplificado
 * a lo que necesitamos para efectos tipo documental (pan, zoom, tilt, dolly).
 */
class CameraTrack(initialKeyframes: List<Keyframe> = emptyList()) {

    private val _keyframes = initialKeyframes.sortedBy { it.timeMs }.toMutableList()
    val keyframes: List<Keyframe> get() = _keyframes

    fun addOrReplace(keyframe: Keyframe) {
        _keyframes.removeAll { it.timeMs == keyframe.timeMs }
        _keyframes.add(keyframe)
        _keyframes.sortBy { it.timeMs }
    }

    fun remove(timeMs: Long) {
        _keyframes.removeAll { it.timeMs == timeMs }
    }

    /**
     * Reemplaza TODOS los keyframes de golpe, preservando la identidad de
     * este objeto CameraTrack (y por lo tanto de la [Layer] que lo contiene).
     * Se usa al restaurar un snapshot de undo/redo: como [Layer.cameraTrack]
     * es un `val`, no se puede reasignar un CameraTrack nuevo sin perder la
     * textura GL ya subida — en cambio, se vacía y se rellena esta misma
     * instancia.
     */
    fun replaceAll(newKeyframes: List<Keyframe>) {
        _keyframes.clear()
        _keyframes.addAll(newKeyframes.sortedBy { it.timeMs })
    }

    fun durationMs(): Long = _keyframes.maxOfOrNull { it.timeMs } ?: 0L

    /**
     * Devuelve el encuadre interpolado en [timeMs]. Si no hay keyframes,
     * devuelve un encuadre "identidad" (sin transformación).
     * Si hay uno solo, se mantiene fijo (estático) en ese valor.
     * Si el tiempo está antes del primero o después del último, se sostiene
     * el valor del extremo más cercano (comportamiento estándar de cine).
     */
    fun frameAt(timeMs: Long): CameraFrame {
        if (_keyframes.isEmpty()) {
            return CameraFrame(0f, 0f, 1f, 0f, 1f)
        }
        if (_keyframes.size == 1) {
            val k = _keyframes.first()
            return CameraFrame(k.translateX, k.translateY, k.scale, k.rotationDeg, k.alpha, k.tiltXDeg, k.tiltYDeg, k.focusBlur, k.dollyZoom)
        }

        val first = _keyframes.first()
        val last = _keyframes.last()
        if (timeMs <= first.timeMs) {
            return CameraFrame(first.translateX, first.translateY, first.scale, first.rotationDeg, first.alpha, first.tiltXDeg, first.tiltYDeg, first.focusBlur, first.dollyZoom)
        }
        if (timeMs >= last.timeMs) {
            return CameraFrame(last.translateX, last.translateY, last.scale, last.rotationDeg, last.alpha, last.tiltXDeg, last.tiltYDeg, last.focusBlur, last.dollyZoom)
        }

        // Encontrar el par de keyframes que rodea a timeMs
        var lower = first
        var upper = last
        for (i in 0 until _keyframes.size - 1) {
            val a = _keyframes[i]
            val b = _keyframes[i + 1]
            if (timeMs >= a.timeMs && timeMs <= b.timeMs) {
                lower = a
                upper = b
                break
            }
        }

        val span = (upper.timeMs - lower.timeMs).coerceAtLeast(1)
        val rawT = (timeMs - lower.timeMs).toFloat() / span.toFloat()
        val t = if (upper.easing == EasingType.CUSTOM_BEZIER) {
            Easing.applyCubicBezier(rawT, upper.bezierX1, upper.bezierY1, upper.bezierX2, upper.bezierY2)
        } else {
            Easing.apply(upper.easing, rawT)
        }

        return CameraFrame(
            translateX = lerp(lower.translateX, upper.translateX, t),
            translateY = lerp(lower.translateY, upper.translateY, t),
            scale = lerp(lower.scale, upper.scale, t),
            rotationDeg = lerp(lower.rotationDeg, upper.rotationDeg, t),
            alpha = lerp(lower.alpha, upper.alpha, t),
            tiltXDeg = lerp(lower.tiltXDeg, upper.tiltXDeg, t),
            tiltYDeg = lerp(lower.tiltYDeg, upper.tiltYDeg, t),
            focusBlur = lerp(lower.focusBlur, upper.focusBlur, t),
            dollyZoom = lerp(lower.dollyZoom, upper.dollyZoom, t)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
