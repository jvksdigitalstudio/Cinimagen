package com.jvksdigitalstudio.cinimagen.engine

import android.graphics.Bitmap
import android.net.Uri
import java.util.UUID

/**
 * Una capa independiente dentro del proyecto: una imagen PNG (con o sin
 * transparencia) más su propia pista de cámara. El orden en [zIndex]
 * determina qué capa se dibuja encima de cuál (para el efecto parallax:
 * el fondo tiene zIndex menor y se mueve más lento que el personaje).
 *
 * El bitmap se mantiene en memoria de forma perezosa; el motor GL sube
 * la textura una sola vez y libera el bitmap de CPU tras subirla, para
 * no duplicar memoria en imágenes grandes.
 */

data class Layer(
    val id: String = UUID.randomUUID().toString(),
    var sourceUri: Uri,
    var name: String,
    var zIndex: Int,
    var parallaxFactor: Float = 1f, // 1 = movimiento normal, <1 = se mueve más lento (fondo)
    var locked: Boolean = false,    // true = no se puede mover/editar desde el preview ni sliders
    var visible: Boolean = true,    // false = oculta del preview, la reproducción y la exportación
    var lookSettings: LookSettings = LookSettings(), // grading, viñeta, grano, glow — independiente por capa
    val cameraTrack: CameraTrack = CameraTrack(),
    var widthPx: Int = 0,
    var heightPx: Int = 0
) {
    // Textura GL asignada en tiempo de render; -1 = no subida todavía.
    @Transient
    var glTextureId: Int = -1

    // Referencia temporal al bitmap decodificado, solo hasta que se sube a GL.
    @Transient
    var pendingBitmap: Bitmap? = null
}
