package com.jvksdigitalstudio.cinimagen.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.jvksdigitalstudio.cinimagen.engine.AspectRatioPreset
import com.jvksdigitalstudio.cinimagen.engine.AudioClip
import com.jvksdigitalstudio.cinimagen.engine.AudioProcessor
import com.jvksdigitalstudio.cinimagen.engine.CameraTrack
import com.jvksdigitalstudio.cinimagen.engine.FreezeFrame
import com.jvksdigitalstudio.cinimagen.engine.Layer
import com.jvksdigitalstudio.cinimagen.engine.SpeedKeyframe
import com.jvksdigitalstudio.cinimagen.engine.ThumbnailRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Resumen liviano de un proyecto guardado, para pintar "Mis proyectos" sin decodificar nada pesado. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val description: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val layerCount: Int,
    val projectDurationMs: Long,
    val aspectRatio: String,
    val fps: Int,
    val thumbnailFile: File?,
    // Portada elegida a mano por el usuario (ver [ProjectStorage.setCoverImage]).
    // La tarjeta debe preferir SIEMPRE esta imagen sobre [thumbnailFile]
    // cuando esté disponible.
    val coverImageFile: File?,
    val orderIndex: Long,
    // Tamaño total en disco de la carpeta del proyecto (json + imágenes +
    // audio + miniatura/portada), para el panel de info (ícono ⓘ). Se
    // calcula al listar, no es carísimo porque son proyectos chicos, pero
    // si en el futuro se vuelve un problema de rendimiento se puede cachear.
    val sizeBytes: Long,
    val canMoveUp: Boolean = false,
    val canMoveDown: Boolean = false
) {
    /** La imagen que la tarjeta debe mostrar: portada elegida a mano si existe, si no la miniatura auto-generada. */
    val displayImageFile: File? get() = coverImageFile ?: thumbnailFile
}

/** Dirección para reordenar manualmente un proyecto en "Mis proyectos". */
enum class MoveDirection { UP, DOWN }

/**
 * Límite de caracteres para la descripción corta editable desde "Mis
 * proyectos" (diálogo de renombrar). Se cuenta en code points (no en
 * unidades UTF-16) para que un emoji fuera del BMP (la gran mayoría de los
 * emojis modernos: 😀🎬🚀...) cuente como UN carácter y nunca se corte a la
 * mitad de su par subrogado — ver [takeCodePoints].
 */
const val DESCRIPTION_MAX_LENGTH = 200

/**
 * Recorta [this] a lo sumo a [maxCodePoints] caracteres "reales" (code
 * points), a diferencia de `String.take(n)` que cuenta unidades UTF-16 y
 * puede partir un emoji compuesto por un par subrogado (o por
 * emoji+modificador de tono de piel, banderas de dos letras, ZWJ, etc.) por
 * la mitad, dejando un carácter "roto" (�) al final del texto guardado.
 */
fun String.takeCodePoints(maxCodePoints: Int): String {
    if (this.codePointCount(0, this.length) <= maxCodePoints) return this
    val end = this.offsetByCodePoints(0, maxCodePoints)
    return this.substring(0, end)
}

/** Resultado de cargar un proyecto: metadata + capas + audio ya reconstruidos y listos para el ViewModel. */
data class LoadedProject(
    val id: String,
    val name: String,
    val projectDurationMs: Long,
    val layers: List<Layer>,
    val audioClip: AudioClip?,
    val speedKeyframes: List<SpeedKeyframe> = emptyList(),
    val freezeFrames: List<FreezeFrame> = emptyList(),
    val aspect: AspectRatioPreset = AspectRatioPreset.REELS,
    val fps: Int = 30
)

/**
 * Persistencia de proyectos. Cada proyecto vive en su propia carpeta dentro
 * del almacenamiento privado de la app —
 * `/data/data/<pkg>/files/projects/<projectId>/` — con cuatro cosas:
 *
 * - `project.json`: metadata del proyecto + todas las capas (transform,
 *   keyframes, look cinematográfico por capa) + el clip de audio si hay uno
 * - `images/<layerId>.png`: copia LOCAL de cada imagen de capa
 * - `audio/<archivo>`: copia LOCAL del audio de fondo, si el proyecto tiene uno
 * - `thumbnail.jpg`: miniatura ya "graduada" (con el look aplicado),
 *   generada con [ThumbnailRenderer]
 *
 * ## Por qué se copia cada imagen/audio en vez de guardar solo su Uri original
 * Un Uri de Storage Access Framework puede dejar de ser válido si el
 * usuario mueve, borra o desinstala la app que compartió el archivo
 * originalmente, o si Android revoca el permiso persistente — y ahí el
 * proyecto quedaría con capas o audio "rotos" e irrecuperables. Copiando el
 * archivo dentro de la carpeta del propio proyecto, éste queda 100%
 * autocontenido: sobrevive reinicios, cambios en la galería del usuario, e
 * incluso se podría exportar la carpeta entera a otro dispositivo.
 *
 * Todas las operaciones son suspend y corren en [Dispatchers.IO].
 */
class ProjectStorage(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true // tolerante a versiones futuras del formato
        prettyPrint = true
        encodeDefaults = true
    }

    private val projectsRoot: File
        get() = File(appContext.filesDir, "projects").apply { mkdirs() }

    // Un Mutex por projectId: evita que dos escrituras concurrentes al MISMO
    // proyecto (p. ej. un autoguardado con debounce que dispara justo cuando
    // el usuario navega hacia atrás y fuerza un guardado inmediato) se pisen
    // entre sí y corrompan project.json. Proyectos distintos no se bloquean
    // entre sí — cada uno vive en su propia carpeta, no hay nada que proteger
    // ahí.
    private val projectMutexes = mutableMapOf<String, Mutex>()

    private fun mutexFor(projectId: String): Mutex = synchronized(projectMutexes) {
        projectMutexes.getOrPut(projectId) { Mutex() }
    }

    private fun projectDir(projectId: String) = File(projectsRoot, projectId).apply { mkdirs() }
    private fun imagesDir(projectId: String) = File(projectDir(projectId), "images").apply { mkdirs() }
    private fun audioDir(projectId: String) = File(projectDir(projectId), "audio").apply { mkdirs() }
    private fun projectFile(projectId: String) = File(projectDir(projectId), "project.json")
    private fun thumbnailFile(projectId: String) = File(projectDir(projectId), "thumbnail.jpg")
    private fun coverFile(projectId: String) = File(projectDir(projectId), "cover.jpg")

    fun newProjectId(): String = UUID.randomUUID().toString()

    /**
     * Clave de orden efectiva de un proyecto: usa la posición manual
     * ([ProjectData.orderIndex]) si ya se asignó una (distinta de 0L), y si
     * no cae de nuevo a `-updatedAtMs` — misma escala (negativo de un
     * timestamp en ms), así un proyecto recién movido y uno viejo sin mover
     * todavía conviven en el mismo orden sin saltos raros. Ordenar
     * ASCENDENTE por esta clave da el resultado deseado: más reciente (o lo
     * último reordenado a mano) primero.
     */
    private fun orderKey(data: ProjectData): Long = if (data.orderIndex != 0L) data.orderIndex else -data.updatedAtMs

    /** Carga y ordena todos los [ProjectData] crudos — base compartida por [listProjects] y [moveProject]. */
    private fun loadAllProjectDataSorted(): List<Pair<File, ProjectData>> =
        projectsRoot.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir ->
                val file = File(dir, "project.json")
                if (!file.exists()) return@mapNotNull null
                runCatching { dir to json.decodeFromString<ProjectData>(file.readText()) }.getOrNull()
            }
            ?.sortedBy { (_, data) -> orderKey(data) }
            ?: emptyList()

    /** Suma recursiva del tamaño de todos los archivos dentro de una carpeta. */
    private fun File.totalSizeBytes(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Lista todos los proyectos guardados, en el orden manual del usuario (o el más reciente primero por defecto). */
    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val ordered = loadAllProjectDataSorted()
        ordered.mapIndexed { index, (dir, data) ->
            ProjectSummary(
                id = data.id,
                name = data.name,
                description = data.description,
                createdAtMs = data.createdAtMs,
                updatedAtMs = data.updatedAtMs,
                layerCount = data.layers.size,
                projectDurationMs = data.projectDurationMs,
                aspectRatio = data.aspectRatio,
                fps = data.fps,
                thumbnailFile = File(dir, "thumbnail.jpg").takeIf { it.exists() },
                coverImageFile = data.coverImageFileName?.let { name -> File(dir, name).takeIf { it.exists() } },
                orderIndex = data.orderIndex,
                sizeBytes = dir.totalSizeBytes(),
                canMoveUp = index > 0,
                canMoveDown = index < ordered.size - 1
            )
        }
    }

    /**
     * Guarda el proyecto completo: JSON con todas las capas y sus
     * keyframes, el clip de audio si lo hay, copia local de cualquier
     * imagen/audio nuevo o reemplazado, y una miniatura fiel (con grading
     * real aplicado) para "Mis proyectos". No hace nada si el proyecto
     * todavía no tiene ninguna capa — evita crear proyectos vacíos en
     * disco antes de que el usuario importe algo.
     */
    suspend fun saveProject(
        projectId: String,
        name: String,
        projectDurationMs: Long,
        playheadMs: Long,
        layers: List<Layer>,
        audioClip: AudioClip? = null,
        speedKeyframes: List<SpeedKeyframe> = emptyList(),
        freezeFrames: List<FreezeFrame> = emptyList(),
        aspect: AspectRatioPreset = AspectRatioPreset.REELS,
        fps: Int = 30
    ): Unit = withContext(Dispatchers.IO) {
        if (layers.isEmpty()) return@withContext
        mutexFor(projectId).withLock {

        val imgDir = imagesDir(projectId)
        val existing = runCatching { json.decodeFromString<ProjectData>(projectFile(projectId).readText()) }.getOrNull()

        val layerDataList = layers.map { layer ->
            val fileName = ensureLocalImage(layer, imgDir)
            LayerData(
                id = layer.id,
                imageFileName = fileName,
                name = layer.name,
                zIndex = layer.zIndex,
                parallaxFactor = layer.parallaxFactor,
                locked = layer.locked,
                visible = layer.visible,
                lookSettings = layer.lookSettings,
                keyframes = layer.cameraTrack.keyframes,
                widthPx = layer.widthPx,
                heightPx = layer.heightPx
            )
        }

        // Limpieza: borra copias locales de capas que ya no están en el proyecto
        // (p. ej. tras eliminar una capa), para no acumular basura en disco.
        val validNames = layerDataList.map { it.imageFileName }.toSet()
        imgDir.listFiles()?.forEach { f -> if (f.name !in validNames) f.delete() }

        val audioDataResult = audioClip?.let { clip ->
            val fileName = ensureLocalAudio(clip, audioDir(projectId))
            AudioTrackData(
                audioFileName = fileName,
                displayName = clip.displayName,
                sourceDurationMs = clip.sourceDurationMs,
                volume = clip.volume,
                muted = clip.muted,
                trimStartMs = clip.trimStartMs,
                loop = clip.loop,
                fadeInMs = clip.fadeInMs,
                fadeOutMs = clip.fadeOutMs
            )
        }
        // Si se quitó el audio del proyecto (audioClip == null pero antes había
        // uno), se limpia también su copia local para no dejar basura huérfana.
        if (audioDataResult == null) {
            audioDir(projectId).listFiles()?.forEach { it.delete() }
        }

        val data = ProjectData(
            id = projectId,
            name = name.ifBlank { existing?.name ?: "Proyecto sin título" },
            createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            projectDurationMs = projectDurationMs,
            layers = layerDataList,
            audioTrack = audioDataResult,
            speedKeyframes = speedKeyframes,
            freezeFrames = freezeFrames,
            aspectRatio = aspect.name,
            fps = fps,
            // El autoguardado del editor no toca descripción/portada/orden
            // manual — esos campos sólo cambian desde los diálogos de "Mis
            // proyectos" (renombrar, portada, mover arriba/abajo).
            description = existing?.description ?: "",
            coverImageFileName = existing?.coverImageFileName,
            // Proyecto nuevo (sin `existing`): se le asigna una posición al
            // tope de la lista, igual que "más reciente primero" de antes.
            orderIndex = existing?.orderIndex ?: -System.currentTimeMillis()
        )
        projectFile(projectId).writeText(json.encodeToString(data))

        val thumbnail = ThumbnailRenderer.render(appContext, layers, timeMs = playheadMs)
        if (thumbnail != null) {
            runCatching {
                thumbnailFile(projectId).outputStream().use { out ->
                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            thumbnail.recycle()
            }
        }
    }

    /**
     * Asegura que la capa tenga su copia local dentro del proyecto. Si el
     * `sourceUri` ya apunta a esa copia (proyecto recién cargado, imagen sin
     * cambios), no vuelve a copiar nada. Si apunta a un Uri de SAF fresco
     * (importación nueva o "reemplazar imagen"), copia el contenido una vez.
     */
    private fun ensureLocalImage(layer: Layer, imgDir: File): String {
        val fileName = "${layer.id}.png"
        val destFile = File(imgDir, fileName)
        val alreadyLocalCopy = layer.sourceUri.scheme == "file" && layer.sourceUri.path == destFile.absolutePath
        if (!alreadyLocalCopy || !destFile.exists()) {
            runCatching {
                appContext.contentResolver.openInputStream(layer.sourceUri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                // A partir de ahora esta capa "vive" localmente: los próximos
                // autoguardados no necesitan volver a copiar desde el Uri
                // original de SAF en cada slider que se mueve — y de paso la
                // capa queda protegida ante ese Uri si se invalida más tarde.
                layer.sourceUri = Uri.fromFile(destFile)
            }
        }
        return fileName
    }

    /**
     * Mismo patrón que [ensureLocalImage] pero para el audio de fondo.
     * El nombre de archivo local conserva la extensión original (mp3, m4a,
     * wav...) porque algunos decodificadores del sistema la usan como pista
     * adicional para elegir el demuxer correcto.
     */
    private fun ensureLocalAudio(clip: AudioClip, dir: File): String {
        val extension = guessAudioExtension(clip.displayName)
        val fileName = "audio.$extension"
        val destFile = File(dir, fileName)
        val alreadyLocalCopy = clip.sourceUri.scheme == "file" && clip.sourceUri.path == destFile.absolutePath
        if (!alreadyLocalCopy || !destFile.exists()) {
            // Antes de copiar, se limpia cualquier audio anterior con otro nombre
            // (p. ej. si el usuario reemplazó el audio de fondo por uno con
            // distinta extensión).
            dir.listFiles()?.forEach { it.delete() }
            runCatching {
                appContext.contentResolver.openInputStream(clip.sourceUri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                clip.sourceUri = Uri.fromFile(destFile)
            }
        }
        return fileName
    }

    private fun guessAudioExtension(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return if (ext.isNotBlank() && ext.length <= 4) ext else "m4a"
    }

    /** Carga un proyecto guardado y reconstruye sus capas + audio (con bitmap/uri ya listos), para el ViewModel. */
    /**
     * Lectura liviana: solo el `name` guardado en `project.json`, SIN
     * decodificar ninguna imagen de capa (a diferencia de [loadProject],
     * que decodifica el proyecto entero). Pensada para poder resincronizar
     * el nombre en memoria de un ViewModel reciclado sin pagar el costo de
     * volver a cargar todas las capas — ver
     * [com.jvksdigitalstudio.cinimagen.viewmodel.EditorViewModel.refreshProjectNameFromDisk].
     */
    suspend fun peekProjectName(projectId: String): String? = withContext(Dispatchers.IO) {
        val file = projectFile(projectId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<ProjectData>(file.readText()).name }.getOrNull()
    }

    suspend fun loadProject(projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val file = projectFile(projectId)
        if (!file.exists()) return@withContext null
        val data = runCatching { json.decodeFromString<ProjectData>(file.readText()) }.getOrNull() ?: return@withContext null
        val imgDir = imagesDir(projectId)

        val layers = data.layers.mapNotNull { layerData ->
            val imageFile = File(imgDir, layerData.imageFileName)
            if (!imageFile.exists()) return@mapNotNull null
            val decoded = runCatching { ImageDecoding.decodeSampledFromFile(imageFile) }.getOrNull()
                ?: return@mapNotNull null

            Layer(
                id = layerData.id,
                sourceUri = Uri.fromFile(imageFile),
                name = layerData.name,
                zIndex = layerData.zIndex,
                parallaxFactor = layerData.parallaxFactor,
                locked = layerData.locked,
                visible = layerData.visible,
                lookSettings = layerData.lookSettings,
                cameraTrack = CameraTrack(layerData.keyframes),
                widthPx = layerData.widthPx.takeIf { it > 0 } ?: decoded.width,
                heightPx = layerData.heightPx.takeIf { it > 0 } ?: decoded.height
            ).apply {
                // El motor de preview sube esto a GPU una sola vez y lo libera (ver GLRenderer);
                // hay que dejarlo listo acá igual que hace LayerRepository al importar.
                pendingBitmap = decoded
            }
        }

        val audioClip = data.audioTrack?.let { audioData ->
            val audioFile = File(audioDir(projectId), audioData.audioFileName)
            if (!audioFile.exists()) return@let null
            AudioClip(
                sourceUri = Uri.fromFile(audioFile),
                displayName = audioData.displayName,
                sourceDurationMs = audioData.sourceDurationMs,
                volume = audioData.volume,
                muted = audioData.muted,
                trimStartMs = audioData.trimStartMs,
                loop = audioData.loop,
                fadeInMs = audioData.fadeInMs,
                fadeOutMs = audioData.fadeOutMs
            )
        }

        LoadedProject(
            id = data.id,
            name = data.name,
            projectDurationMs = data.projectDurationMs,
            layers = layers,
            audioClip = audioClip,
            speedKeyframes = data.speedKeyframes,
            freezeFrames = data.freezeFrames,
            // Fallback seguro: si el valor guardado no coincide con ningún
            // preset conocido (formato viejo, o un valor de una versión
            // futura), se usa REELS en vez de que decodeFromString explote
            // y deje el proyecto entero inaccesible.
            aspect = runCatching { AspectRatioPreset.valueOf(data.aspectRatio) }.getOrDefault(AspectRatioPreset.REELS),
            fps = data.fps
        )
    }

    suspend fun deleteProject(projectId: String): Unit = withContext(Dispatchers.IO) {
        mutexFor(projectId).withLock {
            projectDir(projectId).deleteRecursively()
            Unit
        }
    }

    /** Duplica un proyecto entero (json + imágenes + audio + miniatura) bajo un id nuevo, como "Copia de...". */
    suspend fun duplicateProject(projectId: String, newName: String): String? = withContext(Dispatchers.IO) {
        mutexFor(projectId).withLock {
            val srcDir = projectDir(projectId)
            if (!File(srcDir, "project.json").exists()) return@withLock null
            val newId = newProjectId()
            val dstDir = projectDir(newId)
            srcDir.copyRecursively(dstDir, overwrite = true)

            val srcData = runCatching { json.decodeFromString<ProjectData>(File(dstDir, "project.json").readText()) }.getOrNull()
                ?: return@withLock null
            val updated = srcData.copy(
                id = newId,
                name = newName,
                updatedAtMs = System.currentTimeMillis(),
                orderIndex = -System.currentTimeMillis()
            )
            File(dstDir, "project.json").writeText(json.encodeToString(updated))
            newId
        }
    }

    /**
     * Actualiza nombre y/o descripción del proyecto en un solo guardado
     * (mismo diálogo en la UI: "Renombrar" ahora también edita la
     * descripción corta). [newDescription] ya debe venir recortada a
     * [DESCRIPTION_MAX_LENGTH] — la UI se encarga de eso mientras el
     * usuario tipea, acá simplemente se persiste tal cual llega.
     */
    suspend fun renameProject(projectId: String, newName: String, newDescription: String? = null): Unit =
        withContext(Dispatchers.IO) {
            mutexFor(projectId).withLock {
                val file = projectFile(projectId)
                if (!file.exists()) return@withLock
                runCatching {
                    val data = json.decodeFromString<ProjectData>(file.readText())
                    file.writeText(
                        json.encodeToString(
                            data.copy(
                                name = newName,
                                description = newDescription ?: data.description,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                    )
                }
            }
        }

    /**
     * Copia [uri] como portada personalizada del proyecto (`cover.jpg`,
     * reemplazando cualquier portada anterior) y la deja activa. A
     * diferencia de la miniatura auto-generada (que siempre refleja el
     * look real del proyecto en el momento de guardar), esta es una
     * elección manual del usuario y el autoguardado del editor nunca la
     * pisa — ver [saveProject].
     */
    suspend fun setCoverImage(projectId: String, uri: Uri): Unit = withContext(Dispatchers.IO) {
        mutexFor(projectId).withLock {
            val file = projectFile(projectId)
            if (!file.exists()) return@withLock
            val dest = coverFile(projectId)
            val copied = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied || !dest.exists()) return@withLock
            runCatching {
                val data = json.decodeFromString<ProjectData>(file.readText())
                file.writeText(
                    json.encodeToString(
                        data.copy(coverImageFileName = dest.name, updatedAtMs = System.currentTimeMillis())
                    )
                )
            }
        }
    }

    /**
     * Guarda [bitmap] YA recortado/encuadrado (salida del editor "Ajustar
     * portada" — ver [com.jvksdigitalstudio.cinimagen.ui.CoverAdjustDialog])
     * como portada personalizada del proyecto. A diferencia de
     * [setCoverImage] (que copia el archivo elegido tal cual, a ciegas),
     * este método persiste exactamente el encuadre que el usuario centró a
     * mano, con la misma relación de aspecto que la tarjeta de "Mis
     * proyectos".
     */
    suspend fun setCoverImageBitmap(projectId: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        mutexFor(projectId).withLock {
            val file = projectFile(projectId)
            if (!file.exists()) return@withLock
            val dest = coverFile(projectId)
            val saved = runCatching {
                dest.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            }.isSuccess
            if (!saved || !dest.exists()) return@withLock
            runCatching {
                val data = json.decodeFromString<ProjectData>(file.readText())
                file.writeText(
                    json.encodeToString(
                        data.copy(coverImageFileName = dest.name, updatedAtMs = System.currentTimeMillis())
                    )
                )
            }
        }
    }

    /** Quita la portada personalizada y vuelve a mostrar la miniatura auto-generada de siempre. */
    suspend fun removeCoverImage(projectId: String): Unit = withContext(Dispatchers.IO) {
        mutexFor(projectId).withLock {
            val file = projectFile(projectId)
            if (!file.exists()) return@withLock
            coverFile(projectId).delete()
            runCatching {
                val data = json.decodeFromString<ProjectData>(file.readText())
                file.writeText(
                    json.encodeToString(
                        data.copy(coverImageFileName = null, updatedAtMs = System.currentTimeMillis())
                    )
                )
            }
        }
    }

    /**
     * Reordena manualmente un proyecto un lugar hacia arriba o abajo en
     * "Mis proyectos", intercambiando su [ProjectData.orderIndex] con el del
     * vecino inmediato en ese sentido. No hace nada si el proyecto ya está
     * en la punta correspondiente (primero para UP, último para DOWN).
     * Bloquea ambos proyectos afectados, siempre en el mismo orden (por id)
     * para no arriesgar un deadlock si dos movimientos se disparan a la vez.
     */
    suspend fun moveProject(projectId: String, direction: MoveDirection): Unit = withContext(Dispatchers.IO) {
        val ordered = loadAllProjectDataSorted()
        val index = ordered.indexOfFirst { (_, data) -> data.id == projectId }
        if (index < 0) return@withContext
        val neighborIndex = if (direction == MoveDirection.UP) index - 1 else index + 1
        if (neighborIndex !in ordered.indices) return@withContext

        val (currentDir, currentData) = ordered[index]
        val (neighborDir, neighborData) = ordered[neighborIndex]
        val currentKey = orderKey(currentData)
        val neighborKey = orderKey(neighborData)

        // Se bloquean los dos proyectos afectados siempre en el mismo orden
        // (comparando id como String) para que dos movimientos disparados a
        // la vez no puedan llegar a tomar los mutexes al revés y deadlockear
        // entre sí.
        val (firstId, secondId) = if (currentData.id < neighborData.id) {
            currentData.id to neighborData.id
        } else {
            neighborData.id to currentData.id
        }

        mutexFor(firstId).withLock {
            mutexFor(secondId).withLock {
                runCatching {
                    File(currentDir, "project.json")
                        .writeText(json.encodeToString(currentData.copy(orderIndex = neighborKey)))
                }
                runCatching {
                    File(neighborDir, "project.json")
                        .writeText(json.encodeToString(neighborData.copy(orderIndex = currentKey)))
                }
            }
        }
    }

    /** Duración total del archivo de audio, usada al importar para poblar [AudioClip.sourceDurationMs]. */
    fun probeAudioDurationMs(uri: Uri): Long = AudioProcessor.probeDurationMs(appContext, uri)

    // ------------------------------------------------------------------
    // Compartir / colaborar: exportar un proyecto entero como un único
    // archivo ".olyze" (un zip renombrado) que otra persona puede
    // abrir con Cinimagen en SU propio teléfono — mismas capas,
    // keyframes, look, audio e imágenes, listo para seguir editando. Vive
    // en `getExternalFilesDir()/exports/`, la carpeta que ya declara
    // [file_paths.xml] para el FileProvider (necesario para poder
    // compartirlo con cualquier otra app vía Intent.ACTION_SEND).
    // ------------------------------------------------------------------

    private fun exportsDir(): File =
        File(appContext.getExternalFilesDir(null), "exports").apply { mkdirs() }

    /** Nombre de archivo seguro a partir del nombre del proyecto (sin caracteres que rompan rutas). */
    private fun safeFileName(name: String): String {
        val cleaned = name.trim().ifBlank { "proyecto" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
        return cleaned.ifBlank { "proyecto" }
    }

    /**
     * Empaqueta la carpeta completa del proyecto (`project.json` + `images/`
     * + `audio/` + `thumbnail.jpg` + `cover.jpg`) en un único archivo
     * `<nombre>.olyze` dentro de `exports/`, listo para compartir. La
     * extensión es solo cosmética — internamente es un `.zip` estándar, así
     * que si alguien lo abre con cualquier descompresor común igual va a
     * poder ver su contenido.
     */
    suspend fun exportProjectZip(projectId: String): File? = withContext(Dispatchers.IO) {
        val dir = projectDir(projectId)
        val jsonFile = File(dir, "project.json")
        if (!jsonFile.exists()) return@withContext null

        val data = runCatching { json.decodeFromString<ProjectData>(jsonFile.readText()) }.getOrNull()
        val outFile = File(exportsDir(), "${safeFileName(data?.name ?: projectId)}.olyze")
        runCatching { outFile.delete() }

        runCatching {
            ZipOutputStream(FileOutputStream(outFile).buffered()).use { zipOut ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val relativePath = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                    zipOut.putNextEntry(ZipEntry(relativePath))
                    f.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }.onFailure { return@withContext null }

        outFile.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Contraparte de [exportProjectZip]: importa un `.olyze` recibido de
     * otra persona (o de otro dispositivo propio) como un proyecto NUEVO e
     * independiente — nunca pisa un proyecto existente, incluso si viene del
     * mismo `id` original, para evitar que abrir por error el mismo archivo
     * dos veces borre trabajo propio. Devuelve el id del proyecto ya
     * importado y listo para abrir, o `null` si el archivo no es un
     * `.olyze` válido.
     */
    suspend fun importProjectZip(uri: Uri): String? = withContext(Dispatchers.IO) {
        val newId = newProjectId()
        val dstDir = projectDir(newId)

        val extracted = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var any = false
                    while (entry != null) {
                        // Se ignora cualquier entrada con ".." en la ruta (zip-slip):
                        // protege de que un archivo malicioso escriba fuera de
                        // `dstDir` reescribiendo rutas del sistema de archivos.
                        val safeName = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && !safeName.contains("..")) {
                            val outFile = File(dstDir, safeName)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> zipIn.copyTo(out) }
                            any = true
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                    any
                }
            } ?: false
        }.getOrDefault(false)

        if (!extracted || !File(dstDir, "project.json").exists()) {
            dstDir.deleteRecursively()
            return@withContext null
        }

        // Se reescribe el id interno para que coincida con la carpeta nueva
        // (`newId`) y se reubica al tope de "Mis proyectos", como cualquier
        // proyecto recién creado.
        runCatching {
            val jsonFile = File(dstDir, "project.json")
            val data = json.decodeFromString<ProjectData>(jsonFile.readText())
            jsonFile.writeText(
                json.encodeToString(
                    data.copy(
                        id = newId,
                        updatedAtMs = System.currentTimeMillis(),
                        orderIndex = -System.currentTimeMillis()
                    )
                )
            )
        }.onFailure {
            dstDir.deleteRecursively()
            return@withContext null
        }

        newId
    }
}
