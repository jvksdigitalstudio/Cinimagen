package com.jvksdigitalstudio.cinimagen.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jvksdigitalstudio.cinimagen.data.LayerRepository
import com.jvksdigitalstudio.cinimagen.data.ProjectStorage
import com.jvksdigitalstudio.cinimagen.engine.AspectRatioPreset
import com.jvksdigitalstudio.cinimagen.engine.AudioClip
import com.jvksdigitalstudio.cinimagen.engine.EasingType
import com.jvksdigitalstudio.cinimagen.engine.ExportProgress
import com.jvksdigitalstudio.cinimagen.engine.ExportQuality
import com.jvksdigitalstudio.cinimagen.engine.ExportSettings
import com.jvksdigitalstudio.cinimagen.engine.FreezeFrame
import com.jvksdigitalstudio.cinimagen.engine.FreezeRuntimeState
import com.jvksdigitalstudio.cinimagen.engine.Keyframe
import com.jvksdigitalstudio.cinimagen.engine.Layer
import com.jvksdigitalstudio.cinimagen.engine.LookSettings
import com.jvksdigitalstudio.cinimagen.engine.SpeedKeyframe
import com.jvksdigitalstudio.cinimagen.engine.SpeedRampEngine
import com.jvksdigitalstudio.cinimagen.engine.VideoExporter
import com.jvksdigitalstudio.cinimagen.engine.computeExportDimensions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Duración por defecto de un proyecto nuevo. Configurable por el usuario
 * desde el panel de exportación (ver [setProjectDuration]) entre
 * [MIN_PROJECT_DURATION_MS] y [MAX_PROJECT_DURATION_MS].
 */
private const val DEFAULT_PROJECT_DURATION_MS = 8000L
const val MIN_PROJECT_DURATION_MS = 3000L
const val MAX_PROJECT_DURATION_MS = 420_000L // 7 minutos

/** FPS disponibles para elegir al crear un proyecto — valores estándar de la industria, no un rango libre. */
val AVAILABLE_PROJECT_FPS = listOf(30, 60, 90, 120)
const val DEFAULT_PROJECT_FPS = 30
private const val FRAME_TICK_MS = 16L // ~60fps para el preview en vivo

// Tiempo de "silencio" tras el último cambio antes de escribir a disco.
private const val AUTOSAVE_DEBOUNCE_MS = 900L

// Ventana de fusión para el historial de undo: ajustes continuos (arrastrar
// un slider, mover la imagen con el dedo) que ocurren dentro de esta
// ventana desde el último checkpoint se consideran "el mismo gesto" y NO
// generan un paso nuevo de undo — así diez frames de un mismo arrastre son
// un solo Ctrl+Z, no diez. Las acciones discretas (bloquear, reordenar,
// quitar keyframe) siempre fuerzan su propio checkpoint sin importar esto.
private const val UNDO_MERGE_WINDOW_MS = 600L
private const val MAX_UNDO_STEPS = 50

/** Estado del autoguardado, para mostrar un indicador discreto en la barra superior. */
sealed class SaveState {
    data object Idle : SaveState()
    data object Saving : SaveState()
    data class Saved(val atMs: Long) : SaveState()
    data class Error(val message: String) : SaveState()
}

data class EditorUiState(
    val layers: List<Layer> = emptyList(),
    val selectedLayerId: String? = null,
    val playheadMs: Long = 0L,
    val projectDurationMs: Long = DEFAULT_PROJECT_DURATION_MS,
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val isCapturing: Boolean = false,
    val isImporting: Boolean = false,
    val exportProgress: ExportProgress? = null,
    // Cada mutación in-place de una capa incrementa esto, para forzar
    // recomposición aunque los objetos Layer sigan siendo los mismos.
    val revision: Int = 0,
    // --- Persistencia ---
    val projectName: String = "Proyecto sin título",
    val isLoadingProject: Boolean = true,
    val saveState: SaveState = SaveState.Idle,
    // --- Undo/Redo ---
    val undoAvailable: Boolean = false,
    val redoAvailable: Boolean = false,
    // Se incrementa SOLO en undo()/redo() (nunca en una edición normal).
    // EditorScreen lo suma a la key de `remember` de los sliders de cámara
    // para forzar que vuelvan a leer el valor real del keyframe restaurado
    // en vez de quedarse con el valor que tenían en el dedo justo antes
    // de deshacer.
    val undoRedoTick: Int = 0,
    // --- Audio de fondo (Fase 6) ---
    // El audio queda fuera del sistema de undo/redo a propósito: es una
    // sola pista a nivel de proyecto (no por capa) y sus ediciones
    // (volumen, trim, fade) son ajustes de "mezcla" más que de puesta en
    // escena — mezclarlo en el mismo historial que mueve keyframes de
    // cámara complicaría los snapshots sin un beneficio claro para el
    // usuario.
    val audioClip: AudioClip? = null,
    val isImportingAudio: Boolean = false,
    // --- Exportación configurable (Fase 6) ---
    val exportQuality: ExportQuality = ExportQuality.HD,
    val exportAspect: AspectRatioPreset = AspectRatioPreset.REELS,
    // Cuadros por segundo del video EXPORTADO — elegido al crear el
    // proyecto (ver CreateProjectDialog), no en el momento de exportar.
    // El preview en vivo del editor sigue corriendo a su propio tick
    // interno fijo (~60fps) sin importar este valor: FPS acá es
    // exclusivamente un parámetro del encoder de video final.
    val projectFps: Int = DEFAULT_PROJECT_FPS,
    // --- Velocidad variable y freeze frame (Fase 7) ---
    // Igual que el audio: queda fuera del undo/redo a propósito. Es una
    // sola línea de tiempo a nivel de proyecto (no por capa), y las
    // rampas de velocidad son más un ajuste de "ritmo de montaje" que de
    // puesta en escena — mezclarlo en el historial de undo por capa
    // complicaría los snapshots sin un beneficio claro.
    val speedKeyframes: List<SpeedKeyframe> = emptyList(),
    val freezeFrames: List<FreezeFrame> = emptyList()
)

/** Snapshot liviano de todo lo que el undo/redo puede deshacer: transform, look y orden de capas. */
private data class LayerEditState(
    val id: String,
    val zIndex: Int,
    val parallaxFactor: Float,
    val locked: Boolean,
    val visible: Boolean,
    val lookSettings: LookSettings,
    val keyframes: List<Keyframe>
)

private data class EditSnapshot(
    val layers: List<LayerEditState>,
    val projectDurationMs: Long,
    val selectedLayerId: String?,
    val playheadMs: Long
)

class EditorViewModel(
    private val layerRepository: LayerRepository,
    private val projectStorage: ProjectStorage,
    private val projectId: String,
    private val initialName: String = "Proyecto sin título",
    private val initialAspect: AspectRatioPreset = AspectRatioPreset.REELS,
    private val initialDurationMs: Long = DEFAULT_PROJECT_DURATION_MS,
    private val initialFps: Int = DEFAULT_PROJECT_FPS
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var autosaveJob: Job? = null

    // --- Undo/Redo: pilas de snapshots livianos (sin bitmaps ni Uris) ---
    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()
    private var lastCheckpointAtMs = 0L

    init {
        viewModelScope.launch {
            val loaded = projectStorage.loadProject(projectId)
            _uiState.value = if (loaded != null) {
                _uiState.value.copy(
                    layers = loaded.layers,
                    projectName = loaded.name,
                    projectDurationMs = loaded.projectDurationMs,
                    selectedLayerId = loaded.layers.firstOrNull()?.id,
                    audioClip = loaded.audioClip,
                    speedKeyframes = loaded.speedKeyframes,
                    freezeFrames = loaded.freezeFrames,
                    // Antes de esto, el formato y el fps elegidos al crear
                    // el proyecto nunca se leían de vuelta al reabrirlo —
                    // quedaban pisados por el default (REELS/30fps) en cada
                    // apertura. Ahora se restauran desde lo guardado.
                    exportAspect = loaded.aspect,
                    projectFps = loaded.fps,
                    isLoadingProject = false,
                    revision = _uiState.value.revision + 1
                )
            } else {
                _uiState.value.copy(
                    projectName = initialName,
                    projectDurationMs = initialDurationMs,
                    exportAspect = initialAspect,
                    projectFps = initialFps,
                    isLoadingProject = false
                )
            }
        }
    }

    // ============================================================
    // Undo / Redo
    // ============================================================

    private fun captureSnapshot(): EditSnapshot {
        val state = _uiState.value
        return EditSnapshot(
            layers = state.layers.map { layer ->
                LayerEditState(
                    id = layer.id,
                    zIndex = layer.zIndex,
                    parallaxFactor = layer.parallaxFactor,
                    locked = layer.locked,
                    visible = layer.visible,
                    lookSettings = layer.lookSettings,
                    keyframes = layer.cameraTrack.keyframes.toList()
                )
            },
            projectDurationMs = state.projectDurationMs,
            selectedLayerId = state.selectedLayerId,
            playheadMs = state.playheadMs
        )
    }

    /**
     * Aplica un snapshot MUTANDO las capas existentes en su lugar (nunca
     * reemplazando los objetos [Layer]) para no perder la textura GL ya
     * subida a GPU — reemplazar el objeto forzaría un re-decode/re-upload
     * innecesario y un parpadeo visible. Si una capa del snapshot ya no
     * existe (se eliminó después de tomar el checkpoint), se la ignora sin
     * error: el undo cubre transform/look/orden, no altas ni bajas de capas.
     */
    private fun restoreSnapshot(snapshot: EditSnapshot) {
        val current = _uiState.value.layers
        snapshot.layers.forEach { edit ->
            val layer = current.find { it.id == edit.id } ?: return@forEach
            layer.zIndex = edit.zIndex
            layer.parallaxFactor = edit.parallaxFactor
            layer.locked = edit.locked
            layer.visible = edit.visible
            layer.lookSettings = edit.lookSettings
            layer.cameraTrack.replaceAll(edit.keyframes)
        }
        _uiState.value = _uiState.value.copy(
            layers = current.toList(),
            projectDurationMs = snapshot.projectDurationMs,
            selectedLayerId = snapshot.selectedLayerId,
            playheadMs = snapshot.playheadMs,
            revision = _uiState.value.revision + 1,
            undoRedoTick = _uiState.value.undoRedoTick + 1
        )
    }

    /**
     * Guarda el estado ACTUAL en la pila de undo, antes de que el llamador
     * aplique su cambio. [force] = true para acciones discretas (un tap:
     * bloquear, reordenar, quitar keyframe); false (default) para cambios
     * continuos (arrastrar un slider o la imagen), que se fusionan dentro
     * de [UNDO_MERGE_WINDOW_MS] para no llenar el historial de pasos
     * microscópicos.
     */
    private fun pushUndoCheckpoint(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckpointAtMs < UNDO_MERGE_WINDOW_MS) return
        lastCheckpointAtMs = now

        undoStack.addLast(captureSnapshot())
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()

        _uiState.value = _uiState.value.copy(
            undoAvailable = true,
            redoAvailable = false
        )
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(captureSnapshot())
        while (redoStack.size > MAX_UNDO_STEPS) redoStack.removeFirst()
        restoreSnapshot(previous)
        lastCheckpointAtMs = 0L
        _uiState.value = _uiState.value.copy(
            undoAvailable = undoStack.isNotEmpty(),
            redoAvailable = true
        )
        scheduleAutosave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(captureSnapshot())
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        restoreSnapshot(next)
        lastCheckpointAtMs = 0L
        _uiState.value = _uiState.value.copy(
            undoAvailable = true,
            redoAvailable = redoStack.isNotEmpty()
        )
        scheduleAutosave()
    }

    // ============================================================
    // Persistencia
    // ============================================================

    private fun notifyLayersChanged() {
        _uiState.value = _uiState.value.copy(
            layers = _uiState.value.layers.toList(),
            revision = _uiState.value.revision + 1
        )
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persistNow()
        }
    }

    private suspend fun persistNow() {
        val state = _uiState.value
        if (state.layers.isEmpty()) return
        _uiState.value = _uiState.value.copy(saveState = SaveState.Saving)
        try {
            projectStorage.saveProject(
                projectId = projectId,
                name = state.projectName,
                projectDurationMs = state.projectDurationMs,
                playheadMs = state.playheadMs,
                layers = state.layers,
                audioClip = state.audioClip,
                speedKeyframes = state.speedKeyframes,
                freezeFrames = state.freezeFrames,
                aspect = state.exportAspect,
                fps = state.projectFps
            )
            _uiState.value = _uiState.value.copy(saveState = SaveState.Saved(System.currentTimeMillis()))
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(saveState = SaveState.Error(t.message ?: "Error al guardar"))
        }
    }

    fun saveNow(onDone: () -> Unit = {}) {
        autosaveJob?.cancel()
        viewModelScope.launch {
            persistNow()
            onDone()
        }
    }

    fun renameProject(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = _uiState.value.copy(projectName = trimmed)
        scheduleAutosave()
    }

    /**
     * Vuelve a leer el nombre del proyecto desde disco y actualiza el
     * estado en memoria si cambió. Necesario porque este ViewModel puede
     * venir RECICLADO del ViewModelStore de la Activity (para no re-decodificar
     * imágenes cada vez que se reabre el mismo proyecto — ver comentario en
     * MainActivity junto al `viewModel(factory = ..., key = projectId)`):
     * si el nombre se cambió desde "Mis proyectos" (RenameProjectDialog)
     * MIENTRAS este ViewModel seguía vivo en caché, su copia en memoria
     * queda desactualizada, y el próximo autoguardado (incluso uno
     * disparado por `saveNow()` al simple hecho de entrar y salir sin tocar
     * nada) volvería a escribir ese nombre viejo, pisando el renombrado.
     * Por eso [MainActivity] llama a esto cada vez que se (re)entra al
     * editor, ANTES de que cualquier autoguardado pueda dispararse.
     */
    fun refreshProjectNameFromDisk() {
        viewModelScope.launch {
            val diskName = projectStorage.peekProjectName(projectId)
            if (diskName != null && diskName != _uiState.value.projectName) {
                _uiState.value = _uiState.value.copy(projectName = diskName)
            }
        }
    }

    /**
     * Duración total de la "película", en milisegundos. Si el playhead
     * quedaba más allá de la nueva duración, se reacomoda al final.
     */
    fun setProjectDuration(durationMs: Long) {
        val clamped = durationMs.coerceIn(MIN_PROJECT_DURATION_MS, MAX_PROJECT_DURATION_MS)
        _uiState.value = _uiState.value.copy(
            projectDurationMs = clamped,
            playheadMs = _uiState.value.playheadMs.coerceAtMost(clamped)
        )
        scheduleAutosave()
    }

    // ============================================================
    // Capas
    // ============================================================

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true)
            val startingZ = _uiState.value.layers.size
            val newLayers = layerRepository.importAsLayers(uris, startingZ)
            _uiState.value = _uiState.value.copy(
                layers = _uiState.value.layers + newLayers,
                selectedLayerId = _uiState.value.selectedLayerId ?: newLayers.firstOrNull()?.id,
                isImporting = false,
                revision = _uiState.value.revision + 1
            )
            scheduleAutosave()
        }
    }

    fun selectLayer(layerId: String) {
        _uiState.value = _uiState.value.copy(selectedLayerId = layerId)
    }

    fun replaceLayerImage(layerId: String, uri: Uri) {
        viewModelScope.launch {
            val decoded = layerRepository.decode(uri) ?: return@launch
            val layer = _uiState.value.layers.find { it.id == layerId } ?: return@launch
            layer.sourceUri = uri
            layer.name = decoded.displayName
            layer.pendingBitmap = decoded.bitmap
            notifyLayersChanged()
        }
    }

    fun importAsBackground(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true)
            val lowestZ = (_uiState.value.layers.minOfOrNull { it.zIndex } ?: 0) - 1
            val newLayers = layerRepository.importAsLayers(listOf(uri), lowestZ)
            val backgroundLayer = newLayers.firstOrNull()?.apply { parallaxFactor = 0.35f }
            if (backgroundLayer != null) {
                _uiState.value = _uiState.value.copy(
                    layers = _uiState.value.layers + backgroundLayer,
                    selectedLayerId = backgroundLayer.id,
                    isImporting = false,
                    revision = _uiState.value.revision + 1
                )
                scheduleAutosave()
            } else {
                _uiState.value = _uiState.value.copy(isImporting = false)
            }
        }
    }

    /** Elimina la capa por completo. Si era la seleccionada, selecciona la siguiente disponible. */
    fun removeLayer(layerId: String) {
        val remaining = _uiState.value.layers.filterNot { it.id == layerId }
        val newSelectedId = if (_uiState.value.selectedLayerId == layerId) {
            remaining.firstOrNull()?.id
        } else {
            _uiState.value.selectedLayerId
        }
        _uiState.value = _uiState.value.copy(
            layers = remaining,
            selectedLayerId = newSelectedId,
            revision = _uiState.value.revision + 1
        )
        scheduleAutosave()
    }

    fun setParallaxFactor(layerId: String, factor: Float) {
        pushUndoCheckpoint()
        _uiState.value.layers.find { it.id == layerId }?.parallaxFactor = factor
        notifyLayersChanged()
    }

    fun toggleLayerLock(layerId: String) {
        pushUndoCheckpoint(force = true)
        _uiState.value.layers.find { it.id == layerId }?.let { it.locked = !it.locked }
        notifyLayersChanged()
    }

    /** Muestra/oculta la capa del preview, la reproducción y la exportación (no la elimina). */
    fun toggleLayerVisibility(layerId: String) {
        pushUndoCheckpoint(force = true)
        _uiState.value.layers.find { it.id == layerId }?.let { it.visible = !it.visible }
        notifyLayersChanged()
    }

    /** Sube la capa una posición (queda por encima, se dibuja más al frente). */
    fun moveLayerUp(layerId: String) {
        val sorted = _uiState.value.layers.sortedBy { it.zIndex }
        val idx = sorted.indexOfFirst { it.id == layerId }
        if (idx in 0 until sorted.size - 1) {
            pushUndoCheckpoint(force = true)
            val current = sorted[idx]
            val next = sorted[idx + 1]
            val tmp = current.zIndex
            current.zIndex = next.zIndex
            next.zIndex = tmp
            notifyLayersChanged()
        }
    }

    /** Baja la capa una posición (queda por debajo, se dibuja más atrás). */
    fun moveLayerDown(layerId: String) {
        val sorted = _uiState.value.layers.sortedBy { it.zIndex }
        val idx = sorted.indexOfFirst { it.id == layerId }
        if (idx > 0) {
            pushUndoCheckpoint(force = true)
            val current = sorted[idx]
            val previous = sorted[idx - 1]
            val tmp = current.zIndex
            current.zIndex = previous.zIndex
            previous.zIndex = tmp
            notifyLayersChanged()
        }
    }

    // ============================================================
    // Audio de fondo (Fase 6)
    // ============================================================

    /**
     * Importa un archivo de audio (SAF) como pista de fondo del proyecto.
     * Reemplaza cualquier audio anterior — solo puede haber uno a la vez.
     */
    fun importAudio(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingAudio = true)
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Algunos proveedores no soportan permisos persistentes; no es fatal.
            }

            val displayName = withContext(Dispatchers.IO) { queryDisplayName(resolver, uri) }
                ?: "audio_${System.currentTimeMillis()}"
            val durationMs = withContext(Dispatchers.IO) { projectStorage.probeAudioDurationMs(uri) }

            if (durationMs <= 0L) {
                // No se pudo leer la duración: probablemente no es un archivo de
                // audio válido o el decoder no lo soporta. Se descarta sin romper
                // el proyecto.
                _uiState.value = _uiState.value.copy(isImportingAudio = false)
                return@launch
            }

            val clip = AudioClip(
                sourceUri = uri,
                displayName = displayName,
                sourceDurationMs = durationMs
            )
            _uiState.value = _uiState.value.copy(audioClip = clip, isImportingAudio = false)
            scheduleAutosave()
        }
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    fun removeAudio() {
        _uiState.value = _uiState.value.copy(audioClip = null)
        scheduleAutosave()
    }

    fun setAudioVolume(volume: Float) {
        val clip = _uiState.value.audioClip ?: return
        replaceAudioClip(clip.copy(volume = volume.coerceIn(0f, 1.5f)))
    }

    fun toggleAudioMute() {
        val clip = _uiState.value.audioClip ?: return
        replaceAudioClip(clip.copy(muted = !clip.muted))
    }

    fun setAudioTrimStart(trimStartMs: Long) {
        val clip = _uiState.value.audioClip ?: return
        val clamped = trimStartMs.coerceIn(0L, (clip.sourceDurationMs - 100L).coerceAtLeast(0L))
        replaceAudioClip(clip.copy(trimStartMs = clamped))
    }

    fun setAudioLoop(loop: Boolean) {
        val clip = _uiState.value.audioClip ?: return
        replaceAudioClip(clip.copy(loop = loop))
    }

    fun setAudioFade(fadeInMs: Long, fadeOutMs: Long) {
        val clip = _uiState.value.audioClip ?: return
        replaceAudioClip(clip.copy(fadeInMs = fadeInMs.coerceAtLeast(0L), fadeOutMs = fadeOutMs.coerceAtLeast(0L)))
    }

    /**
     * Reemplaza `audioClip` por una instancia NUEVA (no muta la existente).
     * A diferencia de las capas —donde mutar in-place evita perder la
     * textura GL ya subida a GPU—, el audio no tiene ningún recurso caro
     * atado a la identidad del objeto, así que acá conviene evitar por
     * completo la mutación in-place: reemplazar la referencia es la forma
     * más simple y a prueba de dudas de garantizar que Compose vea el
     * cambio, sin depender de ningún contador de "revision" para forzarlo.
     */
    private fun replaceAudioClip(newClip: AudioClip) {
        _uiState.value = _uiState.value.copy(audioClip = newClip, revision = _uiState.value.revision + 1)
        scheduleAutosave()
    }

    // ============================================================
    // Reproducción / timeline
    // ============================================================

    fun seekTo(timeMs: Long) {
        val clamped = timeMs.coerceIn(0L, _uiState.value.projectDurationMs)
        _uiState.value = _uiState.value.copy(playheadMs = clamped)
    }

    /**
     * Frena la reproducción (si estaba corriendo) y rebobina al inicio.
     *
     * Esta app es de una sola Activity sin NavHost: el ViewModel de cada
     * proyecto se cachea en el ViewModelStore de la Activity por su
     * projectId, así que salir al listado de proyectos NO destruye el
     * ViewModel ni cancela su corrutina de reproducción — sigue tickeando
     * en segundo plano. Si se reabre el mismo proyecto, se recibe esa
     * misma instancia todavía reproduciendo desde donde quedó. Se llama a
     * esto tanto al SALIR del editor (para parar el loop en segundo plano)
     * como al ENTRAR/reabrir un proyecto (para garantizar que siempre se
     * vea desde el principio, nunca a mitad de reproducción).
     */
    fun resetPlaybackState() {
        if (_uiState.value.isPlaying || _uiState.value.playheadMs != 0L) {
            _uiState.value = _uiState.value.copy(
                isPlaying = false,
                isRecording = false,
                isCapturing = false,
                playheadMs = 0L
            )
        }
    }

    // Recuerda si la reproducción estaba corriendo ANTES de empezar a
    // arrastrar el playhead, para poder retomarla al soltar (ver
    // beginScrub/endScrub). No es parte de EditorUiState porque es un
    // detalle interno del gesto de arrastre, no algo que la UI necesite
    // observar.
    private var wasPlayingBeforeScrub = false

    /**
     * Se llama al EMPEZAR a arrastrar el playhead. Si el proyecto estaba
     * reproduciéndose, lo pausa de inmediato — si no, mientras se arrastra
     * el loop de reproducción sigue intentando avanzar el playhead por su
     * cuenta al mismo tiempo que el dedo lo mueve, y el resultado se ve
     * trabado en vez de responder limpio al gesto.
     */
    fun beginScrub() {
        wasPlayingBeforeScrub = _uiState.value.isPlaying
        if (wasPlayingBeforeScrub) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
        }
    }

    /** Se llama al SOLTAR el playhead: si estaba reproduciendo antes de arrastrar, retoma la reproducción desde la nueva posición. */
    fun endScrub() {
        if (wasPlayingBeforeScrub) {
            wasPlayingBeforeScrub = false
            _uiState.value = _uiState.value.copy(isPlaying = true)
            startPlaybackLoop()
        }
    }

    fun togglePlayback() {
        val playing = !_uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(
            isPlaying = playing,
            isRecording = if (!playing) false else _uiState.value.isRecording,
            isCapturing = if (!playing) false else _uiState.value.isCapturing
        )
        if (playing) startPlaybackLoop()
    }

    fun toggleRecording() {
        val recording = !_uiState.value.isRecording
        if (recording) {
            _uiState.value = _uiState.value.copy(isRecording = true, isCapturing = false)
        } else {
            _uiState.value = _uiState.value.copy(isRecording = false, isCapturing = false, isPlaying = false)
        }
    }

    private fun startPlaybackLoop() {
        viewModelScope.launch {
            // Estado de freeze propio de ESTA pasada de reproducción: se
            // reinicia cada vez que se arranca a reproducir, así un freeze
            // ya "consumido" vuelve a dispararse la próxima vez que se
            // reproduce desde el principio.
            var freezeState = FreezeRuntimeState()
            while (_uiState.value.isPlaying) {
                delay(FRAME_TICK_MS)
                val state = _uiState.value
                val (next, nextFreezeState) = SpeedRampEngine.step(
                    currentBaseMs = state.playheadMs,
                    tickMs = FRAME_TICK_MS,
                    freezeState = freezeState,
                    baseDurationMs = state.projectDurationMs,
                    speedKeyframes = state.speedKeyframes,
                    freezeFrames = state.freezeFrames
                )
                freezeState = nextFreezeState
                if (next >= state.projectDurationMs) {
                    _uiState.value = _uiState.value.copy(
                        playheadMs = 0L, isPlaying = false, isRecording = false, isCapturing = false
                    )
                    freezeState = FreezeRuntimeState()
                } else {
                    _uiState.value = _uiState.value.copy(playheadMs = next)
                }
            }
        }
    }

    // ============================================================
    // Keyframes
    // ============================================================

    fun addKeyframeToSelectedLayer(
        translateX: Float,
        translateY: Float,
        scale: Float,
        rotationDeg: Float,
        alpha: Float,
        tiltXDeg: Float = 0f,
        tiltYDeg: Float = 0f,
        focusBlur: Float = 0f,
        dollyZoom: Float = 0f,
        easing: EasingType = EasingType.EASE_IN_OUT
    ) {
        pushUndoCheckpoint()
        val layer = currentSelectedLayer() ?: return
        layer.cameraTrack.addOrReplace(
            Keyframe(
                timeMs = _uiState.value.playheadMs,
                translateX = translateX,
                translateY = translateY,
                scale = scale,
                rotationDeg = rotationDeg,
                alpha = alpha,
                tiltXDeg = tiltXDeg,
                tiltYDeg = tiltYDeg,
                focusBlur = focusBlur,
                dollyZoom = dollyZoom,
                easing = easing
            )
        )

        if (_uiState.value.isRecording && !_uiState.value.isCapturing) {
            _uiState.value = _uiState.value.copy(isCapturing = true, isPlaying = true)
            startPlaybackLoop()
        }

        notifyLayersChanged()
    }

    fun removeKeyframeAtPlayhead() {
        pushUndoCheckpoint(force = true)
        val layer = currentSelectedLayer() ?: return
        layer.cameraTrack.remove(_uiState.value.playheadMs)
        notifyLayersChanged()
    }

    /**
     * Mueve un keyframe existente a un nuevo instante del timeline
     * (arrastrarlo en la pista visual). El checkpoint se fuerza porque
     * cada arrastre de un diamante ya se confirma una sola vez, al soltar
     * el dedo — no en cada frame del gesto (ver
     * [com.jvksdigitalstudio.cinimagen.ui.TimelineView]).
     */
    fun retimeKeyframe(layerId: String, oldTimeMs: Long, newTimeMs: Long) {
        val clamped = newTimeMs.coerceIn(0L, _uiState.value.projectDurationMs)
        if (clamped == oldTimeMs) return
        val layer = _uiState.value.layers.find { it.id == layerId } ?: return
        val existing = layer.cameraTrack.keyframes.find { it.timeMs == oldTimeMs } ?: return

        pushUndoCheckpoint(force = true)
        layer.cameraTrack.remove(oldTimeMs)
        layer.cameraTrack.addOrReplace(existing.copy(timeMs = clamped))
        notifyLayersChanged()
    }

    /** Actualiza el look cinematográfico (grading, viñeta, grano, glow) de UNA capa específica. */
    fun updateLookSettings(layerId: String, look: LookSettings) {
        pushUndoCheckpoint()
        _uiState.value.layers.find { it.id == layerId }?.lookSettings = look
        notifyLayersChanged()
    }

    fun currentSelectedLayer(): Layer? =
        _uiState.value.layers.find { it.id == _uiState.value.selectedLayerId }

    // ============================================================
    // Exportación
    // ============================================================

    fun setExportQuality(quality: ExportQuality) {
        _uiState.value = _uiState.value.copy(exportQuality = quality)
    }

    fun setExportAspect(aspect: AspectRatioPreset) {
        _uiState.value = _uiState.value.copy(exportAspect = aspect)
    }

    // ============================================================
    // Velocidad variable (speed ramping) y freeze frame
    // ============================================================
    // Ambas cosas operan sobre el mismo eje de tiempo BASE que los
    // keyframes de cámara (0..projectDurationMs) — no reordenan ni
    // comprimen ese eje visualmente. Lo que cambian es qué tan rápido
    // avanza ese tiempo durante la reproducción/exportación real, y por
    // lo tanto cuánto dura el video final. Ver [SpeedRampEngine] para el
    // detalle de la simulación.

    /** Duración real del video final tras aplicar rampas de velocidad y freezes. */
    fun currentOutputDurationMs(): Long {
        val state = _uiState.value
        return SpeedRampEngine.computeOutputDurationMs(
            state.projectDurationMs, state.speedKeyframes, state.freezeFrames
        )
    }

    fun addOrReplaceSpeedKeyframe(speed: Float) {
        val state = _uiState.value
        val clamped = speed.coerceIn(0.1f, 4f)
        val updated = state.speedKeyframes
            .filterNot { it.timeMs == state.playheadMs }
            .plus(SpeedKeyframe(timeMs = state.playheadMs, speed = clamped))
            .sortedBy { it.timeMs }
        _uiState.value = state.copy(speedKeyframes = updated)
        scheduleAutosave()
    }

    fun removeSpeedKeyframeAtPlayhead() {
        val state = _uiState.value
        _uiState.value = state.copy(
            speedKeyframes = state.speedKeyframes.filterNot { it.timeMs == state.playheadMs }
        )
        scheduleAutosave()
    }

    /** Congela el timeline en el instante actual del playhead durante [holdMs] de tiempo real. */
    fun addFreezeFrameAtPlayhead(holdMs: Long) {
        val state = _uiState.value
        val clamped = holdMs.coerceIn(200L, 8000L)
        // Reemplaza cualquier freeze ya existente muy cerca del mismo punto
        // (dentro de 50ms) en vez de amontonar duplicados prácticamente
        // superpuestos.
        val updated = state.freezeFrames
            .filterNot { kotlin.math.abs(it.atMs - state.playheadMs) < 50L }
            .plus(FreezeFrame(atMs = state.playheadMs, holdMs = clamped))
            .sortedBy { it.atMs }
        _uiState.value = state.copy(freezeFrames = updated)
        scheduleAutosave()
    }

    fun removeFreezeFrame(id: String) {
        val state = _uiState.value
        _uiState.value = state.copy(freezeFrames = state.freezeFrames.filterNot { it.id == id })
        scheduleAutosave()
    }

    fun exportVideo(context: Context, requestedFileName: String) {
        if (_uiState.value.layers.isEmpty()) return
        if (_uiState.value.exportProgress is ExportProgress.InProgress) return

        val appContext = context.applicationContext
        val state = _uiState.value

        // Antes esto quedaba en null hasta que exporter.export(...) llamaba a
        // onProgress por primera vez — y como el procesamiento de audio corre
        // ANTES del loop de video (y antes de cualquier onProgress), la
        // ventana de "Exportando video..." tardaba casi un minuto en
        // aparecer con audios largos, mostrando en cambio el selector de
        // calidad como si no hubiera pasado nada al tocar "Exportar". Se
        // marca "en curso" acá mismo, de forma síncrona, antes de lanzar la
        // corrutina, para que la UI cambie de inmediato.
        _uiState.value = _uiState.value.copy(exportProgress = ExportProgress.InProgress(0f))

        viewModelScope.launch(Dispatchers.IO) {
            val outputDir = File(appContext.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val outputFile = uniqueOutputFile(outputDir, sanitizeFileName(requestedFileName))

            val (widthPx, heightPx) = computeExportDimensions(state.exportQuality, state.exportAspect)
            val outputDurationMs = SpeedRampEngine.computeOutputDurationMs(
                state.projectDurationMs, state.speedKeyframes, state.freezeFrames
            )
            // El bitrate base de cada ExportQuality está calibrado para
            // 30fps; a más cuadros por segundo, el mismo presupuesto de
            // datos se reparte entre más frames y la calidad por frame cae.
            // Se escala proporcionalmente (con piso en 1x) para que 60/90/
            // 120fps mantengan una nitidez comparable a 30fps, no una
            // versión más liviana del mismo video.
            val fpsScaledBitRate = (state.exportQuality.bitRate * (state.projectFps / 30f))
                .toInt()
                .coerceAtLeast(state.exportQuality.bitRate)
            val settings = ExportSettings(
                widthPx = widthPx,
                heightPx = heightPx,
                fps = state.projectFps,
                bitRate = fpsScaledBitRate,
                durationMs = outputDurationMs
            )
            val exporter = VideoExporter(appContext)

            exporter.export(
                layers = state.layers,
                settings = settings,
                outputFile = outputFile,
                audioClip = state.audioClip,
                baseDurationMs = state.projectDurationMs,
                speedKeyframes = state.speedKeyframes,
                freezeFrames = state.freezeFrames
            ) { progress ->
                _uiState.value = _uiState.value.copy(exportProgress = progress)
            }
        }
    }

    /** Saca del nombre elegido cualquier carácter no válido para un nombre de archivo. */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "cinimagen_${System.currentTimeMillis()}" }
    }

    /** Si ya existe un archivo con ese nombre, le agrega un sufijo numérico en vez de pisarlo. */
    private fun uniqueOutputFile(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($counter).mp4")
            counter++
        }
        return candidate
    }

    fun clearExportState() {
        _uiState.value = _uiState.value.copy(exportProgress = null)
    }
}
