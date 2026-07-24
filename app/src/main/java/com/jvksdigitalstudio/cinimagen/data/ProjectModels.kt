package com.jvksdigitalstudio.cinimagen.data

import com.jvksdigitalstudio.cinimagen.engine.FreezeFrame
import com.jvksdigitalstudio.cinimagen.engine.Keyframe
import com.jvksdigitalstudio.cinimagen.engine.LookSettings
import com.jvksdigitalstudio.cinimagen.engine.SpeedKeyframe
import kotlinx.serialization.Serializable

/**
 * Representación serializable de una capa dentro de `project.json`.
 *
 * No usa [com.jvksdigitalstudio.cinimagen.engine.Layer] directamente porque
 * esa clase tiene un `Uri` (no serializable sin un serializer custom) y
 * campos `@Transient` de GPU (`glTextureId`, `pendingBitmap`) que no tiene
 * sentido persistir — son estado de render en memoria, no datos del
 * proyecto. `imageFileName` reemplaza al Uri original: es el nombre del
 * archivo dentro de `images/`, la copia local de la imagen que hace que el
 * proyecto sea autocontenido y no dependa de que el Uri de SAF original
 * siga siendo válido.
 */
@Serializable
data class LayerData(
    val id: String,
    val imageFileName: String,
    val name: String,
    val zIndex: Int,
    val parallaxFactor: Float = 1f,
    val locked: Boolean = false,
    val visible: Boolean = true,
    val lookSettings: LookSettings = LookSettings(),
    val keyframes: List<Keyframe> = emptyList(),
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

/**
 * Representación serializable del clip de audio de fondo del proyecto
 * (análogo a [LayerData] pero a nivel de proyecto entero — solo hay uno).
 * `audioFileName` es la copia local dentro de `audio/`, mismo patrón que
 * `imageFileName` en [LayerData].
 */
@Serializable
data class AudioTrackData(
    val audioFileName: String,
    val displayName: String,
    val sourceDurationMs: Long,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val trimStartMs: Long = 0L,
    val loop: Boolean = true,
    val fadeInMs: Long = 400L,
    val fadeOutMs: Long = 600L
)

/** Proyecto completo tal como se guarda en `project.json`. */
@Serializable
data class ProjectData(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val projectDurationMs: Long = 8000L,
    val layers: List<LayerData> = emptyList(),
    val audioTrack: AudioTrackData? = null,
    // Velocidad variable y freeze frame (opcional; listas vacías = sin
    // rampas, comportamiento idéntico a como era antes de esta función).
    val speedKeyframes: List<SpeedKeyframe> = emptyList(),
    val freezeFrames: List<FreezeFrame> = emptyList(),
    // Propiedades elegidas al crear el proyecto (ver CreateProjectDialog).
    // aspectRatio se guarda como el nombre del enum AspectRatioPreset
    // (String) en vez del tipo directamente: kotlinx.serialization SÍ
    // soporta enums nativamente, pero guardarlo como String hace que un
    // valor desconocido de una versión futura/pasada del formato no rompa
    // la carga entera del proyecto — se puede resolver con un fallback
    // seguro en vez de que decodeFromString explote.
    val aspectRatio: String = "REELS",
    val fps: Int = 30,
    // Descripción corta opcional (portada/tarjeta de "Mis proyectos"). Si
    // queda vacía, la UI ofrece un resumen auto-calculado (capas · duración
    // · formato) como sugerencia, pero nunca se persiste nada hasta que el
    // usuario confirma el diálogo de renombrar/descripción.
    val description: String = "",
    // Nombre del archivo de portada personalizada dentro de la carpeta del
    // proyecto (p. ej. "cover.jpg"), o null si no se eligió ninguna y la
    // tarjeta debe mostrar la miniatura auto-generada de siempre
    // (thumbnail.jpg, con el look ya aplicado).
    val coverImageFileName: String? = null,
    // Posición manual dentro de "Mis proyectos": convención = valor negativo
    // de un timestamp en ms, así ordenar ASCENDENTE por este campo deja lo
    // más reciente (o lo último movido) primero — igual que el orden viejo
    // por updatedAtMs descendente, pero ahora reordenable a mano con
    // "Mover arriba"/"Mover abajo". 0L (el default de kotlinx.serialization
    // para proyectos guardados ANTES de esta función) se interpreta en
    // ProjectStorage como "sin posición manual todavía" y cae de nuevo a
    // ordenar por updatedAtMs para no romper el orden de proyectos viejos.
    val orderIndex: Long = 0L
)

