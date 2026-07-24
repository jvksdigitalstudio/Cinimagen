package com.jvksdigitalstudio.cinimagen.engine

import android.net.Uri

/**
 * Clip de audio de fondo del proyecto, en memoria (análogo a [Layer] pero
 * a nivel de proyecto entero: solo puede haber uno). [sourceUri] apunta al
 * archivo ya copiado localmente por `ProjectStorage` una vez guardado, o al
 * Uri de SAF recién elegido antes del primer guardado.
 */
class AudioClip(
    var sourceUri: Uri,
    var displayName: String,
    /** Duración total del ARCHIVO de audio original (no del proyecto). */
    var sourceDurationMs: Long,
    /** 0f = silencio, 1f = volumen original, hasta 1.5f para dar algo de boost. */
    var volume: Float = 1f,
    var muted: Boolean = false,
    /** Punto del archivo original donde arranca a sonar (permite recortar el inicio). */
    var trimStartMs: Long = 0L,
    /** Si el audio es más corto que la duración del proyecto, lo repite en loop. */
    var loop: Boolean = true,
    var fadeInMs: Long = 400L,
    var fadeOutMs: Long = 600L
) {
    /**
     * Crea una copia con los campos indicados reemplazados. Se usa en el
     * ViewModel en vez de mutar esta instancia in-place: reemplazar la
     * REFERENCIA (no solo el contenido) es lo que garantiza, sin ninguna
     * ambigüedad, que Compose y el `equals()` de [EditorUiState] vean el
     * cambio como un estado genuinamente nuevo y disparen recomposición —
     * mutar un campo de una instancia ya existente puede quedar "invisible"
     * para código que compara por referencia.
     */
    fun copy(
        volume: Float = this.volume,
        muted: Boolean = this.muted,
        trimStartMs: Long = this.trimStartMs,
        loop: Boolean = this.loop,
        fadeInMs: Long = this.fadeInMs,
        fadeOutMs: Long = this.fadeOutMs
    ): AudioClip = AudioClip(
        sourceUri = sourceUri,
        displayName = displayName,
        sourceDurationMs = sourceDurationMs,
        volume = volume,
        muted = muted,
        trimStartMs = trimStartMs,
        loop = loop,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs
    )
}

/**
 * Preset de calidad de exportación: define bitrate y el lado corto del
 * video en píxeles. Los bitrates apuntan a calidad de "master" profesional
 * (bastante por encima del mínimo que pide YouTube para subir, más cerca
 * de lo que graba nativamente una cámara flagship en H.264), no al mínimo
 * aceptable — para exportar con el mejor detalle que el hardware permita,
 * no solo "que se vea bien en la compresión de una plataforma". Se
 * re-escalan a más fps en tiempo de export (ver EditorViewModel.exportVideo).
 *
 * FULL_HD conserva su nombre de constante por compatibilidad con el único
 * lugar que la referencia por nombre (el default en EditorUiState), pero
 * representa 2K/QHD (1440p) — la resolución que YA usaba antes, solo que
 * estaba mal etiquetada como "Full HD+" (eso es 1080p).
 */
enum class ExportQuality(val label: String, val shortSidePx: Int, val bitRate: Int) {
    DRAFT("Borrador", 720, 8_000_000),
    HD("Full HD", 1080, 20_000_000),
    FULL_HD("2K (QHD)", 1440, 35_000_000),
    UHD_4K("4K (UHD)", 2160, 80_000_000)
}

/** Preset de formato/aspecto de salida, pensado para las plataformas donde se sube el video. */
enum class AspectRatioPreset(val label: String, val subtitle: String) {
    REELS("9:16", "Reels · TikTok · Stories"),
    SQUARE("1:1", "Feed cuadrado"),
    WIDESCREEN("16:9", "YouTube · horizontal")
}

/** Calcula (widthPx, heightPx) a partir de calidad + aspecto elegidos. */
fun computeExportDimensions(quality: ExportQuality, aspect: AspectRatioPreset): Pair<Int, Int> {
    val shortSide = quality.shortSidePx
    // Redondeado a múltiplo de 2 (requisito común de encoders AVC).
    val longSide = (shortSide * 16 / 9) and 1.inv()
    return when (aspect) {
        AspectRatioPreset.REELS -> shortSide to longSide       // vertical: angosto x alto
        AspectRatioPreset.SQUARE -> shortSide to shortSide
        AspectRatioPreset.WIDESCREEN -> longSide to shortSide  // horizontal: ancho x bajo
    }
}
