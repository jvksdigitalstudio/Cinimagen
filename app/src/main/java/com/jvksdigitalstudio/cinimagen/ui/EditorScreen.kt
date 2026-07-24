package com.jvksdigitalstudio.cinimagen.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.jvksdigitalstudio.cinimagen.ui.theme.ChromaKeyGreen
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.jvksdigitalstudio.cinimagen.R
import com.jvksdigitalstudio.cinimagen.engine.AudioPreviewPlayer
import com.jvksdigitalstudio.cinimagen.engine.CameraFrame
import com.jvksdigitalstudio.cinimagen.engine.EasingType
import com.jvksdigitalstudio.cinimagen.engine.Layer
import com.jvksdigitalstudio.cinimagen.engine.LookSettings
import com.jvksdigitalstudio.cinimagen.viewmodel.EditorViewModel
import com.jvksdigitalstudio.cinimagen.viewmodel.SaveState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBackToProjects: () -> Unit,
    onImportClick: () -> Unit,
    onImportBackgroundClick: () -> Unit,
    onReplaceImageClick: (String) -> Unit,
    onImportAudioClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var layersPanelExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    // Ventana que abre el "+" debajo del master en el timeline — todavía
    // vacía a propósito, es el punto de entrada para agregar pistas nuevas
    // al playlist una vez que se defina qué tipos va a soportar.
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var layerPendingDelete by remember { mutableStateOf<Layer?>(null) }

    val selectedLayer = viewModel.currentSelectedLayer()
    val currentFrame = selectedLayer?.cameraTrack?.frameAt(state.playheadMs)
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedPanel by remember(state.selectedLayerId) { mutableStateOf(0) }

    // --- Preview en vivo del audio de fondo (independiente del pipeline de export) ---
    val audioPreviewPlayer = remember { AudioPreviewPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { audioPreviewPlayer.release() }
    }
    // Arranca/pausa el preview cuando cambia isPlaying, el audio importado,
    // o su estado de mute — NO se dispara en cada tick del playhead (eso
    // generaría un tartamudeo audible); una vez arrancado, el audio corre
    // con su propio reloj en paralelo al loop visual del preview.
    LaunchedEffect(state.isPlaying, state.audioClip?.sourceUri, state.audioClip?.muted) {
        val clip = state.audioClip
        if (clip == null || clip.muted || !state.isPlaying) {
            audioPreviewPlayer.pause()
        } else {
            audioPreviewPlayer.playFrom(clip, state.playheadMs)
        }
    }
    // El volumen sí se aplica en caliente sin reiniciar la reproducción.
    LaunchedEffect(state.audioClip?.volume) {
        state.audioClip?.let { audioPreviewPlayer.updateVolume(it.volume) }
    }

    // Atrás del sistema: si está en pantalla completa, sale de ahí primero;
    // si no, guarda inmediatamente y vuelve a "Mis proyectos".
    BackHandler(enabled = true) {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            viewModel.saveNow { onBackToProjects() }
        }
    }

    // Parpadeo del ícono de grabar: solo se anima de verdad mientras
    // isCapturing es true (grabando en serio); en los otros dos estados
    // (apagado / armado en rojo fijo) el alpha se queda en 1f.
    val recordBlink by rememberInfiniteTransition(label = "recordBlink").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(animation = tween(450), repeatMode = RepeatMode.Reverse),
        label = "recordBlinkAlpha"
    )
    // Además del ícono, el FONDO del botón también pulsa en rojo — mucho
    // más notorio que solo el ícono parpadeando, para que sea inconfundible.
    val recordGlow by rememberInfiniteTransition(label = "recordGlow").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(animation = tween(450), repeatMode = RepeatMode.Reverse),
        label = "recordGlowAlpha"
    )

    // Valores del "keyframe en edición" para la capa seleccionada. Al
    // tocar cualquier capa (en el panel de arriba) esto cambia de
    // inmediato: cada capa tiene sus propios valores de cámara Y de look
    // cinematográfico, completamente independientes entre sí.
    //
    // Mientras se está GRABANDO, la key NO incluye el playhead (que
    // avanza solo) para que un gesto en curso no se reinicie a mitad de
    // camino cada 16ms; solo se reinicia al cambiar de capa o al
    // arrancar/parar la grabación. Fuera de grabación, sí se reinicia al
    // mover el playhead manualmente (comportamiento normal de edición).
    // "Ancla" de sincronización con el modelo: se recalcula EXPLÍCITAMENTE
    // solo en los momentos en que los sliders/el gesto SÍ deben "saltar" a
    // leer el keyframe actual — cambiar de capa seleccionada, un undo/redo,
    // o mover el playhead a mano estando FUERA de grabación. Activar o
    // desactivar el modo Grabar NO la toca: así la pose que se dejó
    // ajustada con los sliders (o arrastrando la imagen) antes de grabar
    // se conserva intacta al presionar el botón rojo, en vez de saltar de
    // vuelta al valor del keyframe existente en ese punto — o al neutro,
    // si todavía no había ninguno — que es justo lo que pasaba antes.
    var syncTick by remember { mutableStateOf(0) }

    var translateX by remember { mutableStateOf(currentFrame?.translateX ?: 0f) }
    var translateY by remember { mutableStateOf(currentFrame?.translateY ?: 0f) }
    var scale by remember { mutableStateOf(currentFrame?.scale ?: 1f) }
    var rotation by remember { mutableStateOf(currentFrame?.rotationDeg ?: 0f) }
    var alpha by remember { mutableStateOf(currentFrame?.alpha ?: 1f) }
    var tiltX by remember { mutableStateOf(currentFrame?.tiltXDeg ?: 0f) }
    var tiltY by remember { mutableStateOf(currentFrame?.tiltYDeg ?: 0f) }
    var focusBlur by remember { mutableStateOf(currentFrame?.focusBlur ?: 0f) }
    var dollyZoom by remember { mutableStateOf(currentFrame?.dollyZoom ?: 0f) }

    // Relee el keyframe real del modelo y lo vuelca a los sliders; también
    // avanza syncTick, que el pointerInput del preview usa para saber
    // cuándo debe "olvidarse" del gesto en curso (mismo criterio, un solo
    // lugar de verdad).
    fun syncSlidersFromModel() {
        val frame = viewModel.currentSelectedLayer()?.cameraTrack?.frameAt(state.playheadMs)
        translateX = frame?.translateX ?: 0f
        translateY = frame?.translateY ?: 0f
        scale = frame?.scale ?: 1f
        rotation = frame?.rotationDeg ?: 0f
        alpha = frame?.alpha ?: 1f
        tiltX = frame?.tiltXDeg ?: 0f
        tiltY = frame?.tiltYDeg ?: 0f
        focusBlur = frame?.focusBlur ?: 0f
        dollyZoom = frame?.dollyZoom ?: 0f
        syncTick++
    }

    // Cambiar de capa o deshacer/rehacer siempre resincroniza, incluso si
    // en ese instante se está grabando (son ediciones explícitas del
    // usuario, no el mero paso del tiempo).
    LaunchedEffect(state.selectedLayerId, state.undoRedoTick) {
        syncSlidersFromModel()
    }
    // El scrubbing manual del playhead (arrastrar el scrubber o tocar el
    // timeline) resincroniza los sliders SOLO cuando no se está grabando
    // — durante la grabación el playhead avanza solo, 16ms en 16ms, y
    // resincronizar en cada tick reiniciaría el gesto en curso.
    LaunchedEffect(state.playheadMs) {
        if (!state.isRecording) syncSlidersFromModel()
    }
    var showGuides by remember { mutableStateOf(false) }

    // Guarda un keyframe en el instante actual SOLO mientras el modo
    // Grabar está activo (state.isRecording) — igual que cualquier editor
    // profesional de cámara: fuera de grabación, mover la imagen o los
    // sliders actualiza el PREVIEW en vivo (las variables locales de más
    // arriba) para poder ensayar el encuadre con total libertad, pero no
    // escribe nada en el timeline hasta que se presiona el botón rojo.
    fun commitLiveFrame() {
        if (!state.isRecording) return
        val layer = selectedLayer ?: return
        if (layer.locked) return
        viewModel.addKeyframeToSelectedLayer(
            translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, EasingType.EASE_IN_OUT
        )
    }

    // NOTA importante sobre el modo grabar: NO hay un temporizador que
    // capture keyframes cada cierto tiempo. El botón de grabar solo
    // "arma" el estado (círculo rojo) y hace avanzar el playhead solo;
    // el keyframe real únicamente se escribe cuando el usuario provoca
    // un cambio de verdad — un gesto sobre el preview (ver
    // pointerInput -> commitLiveFrame() más abajo) o mover un slider de
    // cámara (ver LabeledSlider -> commitLiveFrame() en la sección de
    // Cámara) — Y SOLO si el modo Grabar está activo. Con Grabar apagado,
    // esos mismos gestos y sliders siguen moviendo el preview con total
    // libertad (para ensayar), pero no tocan ningún keyframe.

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = Color.White,
        topBar = {
            // Barra superior CUSTOM (no el TopAppBar estándar de Material3):
            // se necesitan tres zonas alineadas contra el ANCHO TOTAL de la
            // pantalla — nombre a la izquierda, Grabar+Play en el centro
            // real de toda la barra (no solo del espacio libre entre
            // navigationIcon y actions, que es donde un TopAppBar de
            // Material3 centraría su title), y undo/redo/exportar a la
            // derecha. Un Box con fillMaxWidth() + Modifier.align() por
            // zona es la única forma de lograr ese centrado verdadero.
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    // --- Izquierda: atrás + nombre del proyecto ---
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        IconButton(onClick = { viewModel.saveNow { onBackToProjects() } }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_back),
                                contentDescription = "Volver a Mis proyectos"
                            )
                        }
                        Column(
                            modifier = Modifier.clickable { showRenameDialog = true }
                        ) {
                            Text(
                                state.projectName,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            SaveStatusLabel(state.saveState)
                        }
                    }

                    // --- Centro real de la barra: Grabar + Play/Pausa ---
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleRecording() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isCapturing) {
                                        Color(0xFFFF3B30).copy(alpha = recordGlow)
                                    } else {
                                        Color.White.copy(alpha = 0.12f)
                                    }
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (state.isRecording) R.drawable.ic_record_active else R.drawable.ic_record_idle
                                ),
                                contentDescription = when {
                                    state.isCapturing -> "Detener grabación (grabando)"
                                    state.isRecording -> "Detener grabación (en espera)"
                                    else -> "Grabar movimiento de cámara"
                                },
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(20.dp)
                                    .alpha(if (state.isCapturing) recordBlink else 1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { viewModel.resetPlaybackState() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_skip_to_start),
                                contentDescription = "Volver al principio",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { viewModel.togglePlayback() },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                painter = painterResource(id = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // --- Derecha: deshacer / rehacer / exportar ---
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        IconButton(onClick = { viewModel.undo() }, enabled = state.undoAvailable) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_undo),
                                contentDescription = "Deshacer",
                                tint = if (state.undoAvailable) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(onClick = { viewModel.redo() }, enabled = state.redoAvailable) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_redo),
                                contentDescription = "Rehacer",
                                tint = if (state.redoAvailable) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                        }
                        IconButton(
                            onClick = { showExportDialog = true },
                            enabled = state.layers.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_export),
                                contentDescription = "Exportar video",
                                tint = if (state.layers.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = onImportBackgroundClick) {
                    Text("F")
                }
                Spacer(modifier = Modifier.height(8.dp))
                FloatingActionButton(onClick = onImportClick) { Text("+") }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // --- Preview FIJO: siempre visible, no se va con el scroll de los controles ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (isFullscreen) 1f else 0.46f)
                    .background(ChromaKeyGreen)
                    .pointerInput(selectedLayer?.id, selectedLayer?.locked, syncTick) {
                        if (selectedLayer == null || selectedLayer.locked) return@pointerInput
                        detectTransformGestures { _, pan, zoom, rotationDelta ->
                            val boxWidth = size.width.toFloat().coerceAtLeast(1f)
                            val boxHeight = size.height.toFloat().coerceAtLeast(1f)
                            translateX = (translateX + (pan.x / boxWidth) * 2f).coerceIn(-2f, 2f)
                            translateY = (translateY - (pan.y / boxHeight) * 2f).coerceIn(-2f, 2f)
                            scale = (scale * zoom).coerceIn(0.2f, 5f)
                            rotation = (rotation + rotationDelta).coerceIn(-180f, 180f)
                            commitLiveFrame()
                        }
                    }
            ) {
                GLPreview(
                    getLayers = { viewModel.uiState.value.layers },
                    getPlayheadMs = { viewModel.uiState.value.playheadMs },
                    getLiveOverride = {
                        selectedLayer?.let {
                            it.id to CameraFrame(translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom)
                        }
                    }
                )
                if (state.isImporting) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                if (state.isLoadingProject) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(ChromaKeyGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                // --- Guías de composición: solo overlay del editor, NUNCA se exportan al video ---
                if (showGuides) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val guideColor = Color.White.copy(alpha = 0.5f)
                        val strokeW = 1.dp.toPx()
                        val thirdW = size.width / 3f
                        val thirdH = size.height / 3f
                        drawLine(guideColor, androidx.compose.ui.geometry.Offset(thirdW, 0f), androidx.compose.ui.geometry.Offset(thirdW, size.height), strokeW)
                        drawLine(guideColor, androidx.compose.ui.geometry.Offset(thirdW * 2, 0f), androidx.compose.ui.geometry.Offset(thirdW * 2, size.height), strokeW)
                        drawLine(guideColor, androidx.compose.ui.geometry.Offset(0f, thirdH), androidx.compose.ui.geometry.Offset(size.width, thirdH), strokeW)
                        drawLine(guideColor, androidx.compose.ui.geometry.Offset(0f, thirdH * 2), androidx.compose.ui.geometry.Offset(size.width, thirdH * 2), strokeW)
                        val centerGuideColor = Color.White.copy(alpha = 0.25f)
                        drawLine(centerGuideColor, androidx.compose.ui.geometry.Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Offset(size.width / 2f, size.height), strokeW)
                        drawLine(centerGuideColor, androidx.compose.ui.geometry.Offset(0f, size.height / 2f), androidx.compose.ui.geometry.Offset(size.width, size.height / 2f), strokeW)
                    }
                }

                // --- Botones de guías y capas: arriba a la derecha del preview ---
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Surface(
                        modifier = Modifier.clickable { showGuides = !showGuides },
                        color = if (showGuides) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_grid),
                            contentDescription = if (showGuides) "Ocultar guías de composición" else "Mostrar guías de composición",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box {
                    Surface(
                        modifier = Modifier.clickable { layersPanelExpanded = !layersPanelExpanded },
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_layers),
                            contentDescription = "Capas",
                            tint = Color.White,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    if (layersPanelExpanded) {
                        Surface(
                            modifier = Modifier
                                .padding(top = 48.dp)
                                .widthIn(min = 260.dp, max = 320.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 8.dp
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Capas (arriba = más al frente)",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(bottom = 4.dp).weight(1f)
                                    )
                                    IconButton(
                                        onClick = { layersPanelExpanded = false },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_close),
                                            contentDescription = "Cerrar panel de capas",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                val orderedLayers = state.layers.sortedByDescending { it.zIndex }
                                orderedLayers.forEach { layer ->
                                    val isSelected = layer.id == state.selectedLayerId
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectLayer(layer.id) }
                                            .background(
                                                if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent
                                            )
                                            .padding(vertical = 4.dp, horizontal = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AsyncImage(
                                                model = layer.sourceUri,
                                                contentDescription = layer.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .alpha(if (layer.visible) 1f else 0.35f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                layer.name,
                                                color = if (layer.visible) Color.White else Color.White.copy(alpha = 0.5f),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            IconButton(onClick = { viewModel.moveLayerUp(layer.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_chevron_up),
                                                    contentDescription = "Subir capa",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(onClick = { viewModel.moveLayerDown(layer.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_chevron_down),
                                                    contentDescription = "Bajar capa",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(onClick = { viewModel.toggleLayerVisibility(layer.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    painter = painterResource(
                                                        id = if (layer.visible) R.drawable.ic_eye else R.drawable.ic_eye_off
                                                    ),
                                                    contentDescription = if (layer.visible) "Ocultar capa" else "Mostrar capa",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(onClick = { viewModel.toggleLayerLock(layer.id) }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    painter = painterResource(
                                                        id = if (layer.locked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
                                                    ),
                                                    contentDescription = if (layer.locked) "Desbloquear capa" else "Bloquear capa",
                                                    tint = if (layer.locked) Color(0xFFFFC107) else Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(onClick = { layerPendingDelete = layer }, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_delete),
                                                    contentDescription = "Eliminar capa",
                                                    tint = Color(0xFFFF6B6B),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                // El hint "Arrastra · pellizca · gira con 2 dedos" se quitó
                // a pedido — quedaba redundante una vez que el usuario ya
                // conoce el gesto. Se conserva el aviso de "Capa bloqueada",
                // que sí es información que cambia y vale la pena mostrar.
                if (selectedLayer != null && !layersPanelExpanded && selectedLayer.locked) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "Capa bloqueada",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                /*
                // --- Barra de reproducción tipo video player, integrada al preview ---
                // Comentado a pedido: esta línea de tiempo (scrubber con
                // timecodes) y el botón de pantalla completa se van a
                // reusar más adelante, reubicados en la pantalla de
                // preview de exportación — no en la pantalla de edición en
                // tiempo real, que ahora queda más limpia. Grabar y
                // Play/Pausa ya se movieron arriba, fijos sobre el preview
                // (ver el Row nuevo antes de este Box).
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    // Línea de tiempo fina con el timecode en cada extremo
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTimecode(state.playheadMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Slider(
                            value = state.playheadMs.toFloat(),
                            onValueChange = { viewModel.seekTo(it.toLong()) },
                            valueRange = 0f..state.projectDurationMs.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Text(
                            formatTimecode(state.projectDurationMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Grabar — Play grande — Pantalla completa
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = { viewModel.toggleRecording() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.isCapturing) {
                                        Color(0xFFFF3B30).copy(alpha = recordGlow)
                                    } else {
                                        Color.White.copy(alpha = 0.12f)
                                    }
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (state.isRecording) R.drawable.ic_record_active else R.drawable.ic_record_idle
                                ),
                                contentDescription = when {
                                    state.isCapturing -> "Detener grabación (grabando)"
                                    state.isRecording -> "Detener grabación (en espera)"
                                    else -> "Grabar movimiento de cámara"
                                },
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(22.dp)
                                    .alpha(if (state.isCapturing) recordBlink else 1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        IconButton(
                            onClick = { viewModel.resetPlaybackState() },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_skip_to_start),
                                contentDescription = "Volver al principio",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        IconButton(
                            onClick = { viewModel.togglePlayback() },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                painter = painterResource(id = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = if (state.isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = { isFullscreen = !isFullscreen },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_fullscreen),
                                contentDescription = if (isFullscreen) "Salir de pantalla completa" else "Pantalla completa",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                */
            }

            if (!isFullscreen) {

            // --- Timeline visual: una pista por capa con keyframes arrastrables ---
            TimelineView(
                layers = state.layers,
                selectedLayerId = state.selectedLayerId,
                playheadMs = state.playheadMs,
                projectDurationMs = state.projectDurationMs,
                onSeek = { viewModel.seekTo(it) },
                onSelectLayer = { viewModel.selectLayer(it) },
                onRetimeKeyframe = { layerId, oldMs, newMs -> viewModel.retimeKeyframe(layerId, oldMs, newMs) },
                onScrubStart = { viewModel.beginScrub() },
                onScrubEnd = { viewModel.endScrub() },
                onAddTrackClick = { showAddTrackDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp, max = 220.dp)
            )

            // --- Panel de controles ---
            // COMENTADO A PROPÓSITO (no borrar): Cámara / Look
            // cinematográfico / Audio / Tiempo van a pasar a ser módulos
            // cargables independientes más adelante — por ahora se deja
            // todo el bloque original intacto pero apagado, para no
            // perder nada del comportamiento cuando se ordene.
            /*
            // La cabecera (línea divisoria + pestañas Cámara/Look) queda
            // FIJA; solo el contenido de la pestaña activa hace scroll
            // debajo, como en cualquier app de edición profesional.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.54f)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (state.layers.isNotEmpty()) {
                    TabRow(
                        selectedTabIndex = selectedPanel,
                        modifier = Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = selectedPanel == 0,
                            onClick = { selectedPanel = 0 },
                            text = { Text("Cámara") }
                        )
                        Tab(
                            selected = selectedPanel == 1,
                            onClick = { selectedPanel = 1 },
                            text = { Text("Look cinematográfico") }
                        )
                        Tab(
                            selected = selectedPanel == 2,
                            onClick = { selectedPanel = 2 },
                            text = { Text("Audio") }
                        )
                        Tab(
                            selected = selectedPanel == 3,
                            onClick = { selectedPanel = 3 },
                            text = { Text("Tiempo") }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {

                if (selectedPanel == 3) {
                    // --- Tiempo: velocidad variable y freeze frame, a nivel de proyecto ---
                    TimeRampPanel(
                        playheadMs = state.playheadMs,
                        projectDurationMs = state.projectDurationMs,
                        speedKeyframes = state.speedKeyframes,
                        freezeFrames = state.freezeFrames,
                        outputDurationMs = viewModel.currentOutputDurationMs(),
                        onSetSpeedHere = { viewModel.addOrReplaceSpeedKeyframe(it) },
                        onRemoveSpeedHere = { viewModel.removeSpeedKeyframeAtPlayhead() },
                        onAddFreezeHere = { viewModel.addFreezeFrameAtPlayhead(it) },
                        onRemoveFreeze = { viewModel.removeFreezeFrame(it) },
                        onSeekTo = { viewModel.seekTo(it) }
                    )
                } else if (selectedPanel == 2) {
                    // --- Audio: a nivel de proyecto, no depende de la capa seleccionada ---
                    AudioPanel(
                        audioClip = state.audioClip,
                        isImporting = state.isImportingAudio,
                        onImportClick = onImportAudioClick,
                        onRemove = { viewModel.removeAudio() },
                        onVolumeChange = { viewModel.setAudioVolume(it) },
                        onToggleMute = { viewModel.toggleAudioMute() },
                        onTrimStartChange = { viewModel.setAudioTrimStart(it) },
                        onLoopChange = { viewModel.setAudioLoop(it) },
                        onFadeChange = { fadeIn, fadeOut -> viewModel.setAudioFade(fadeIn, fadeOut) }
                    )
                } else if (selectedLayer != null) {
                    if (selectedPanel == 0) {
                    // --- Cámara: independiente por capa ---
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cámara — ${selectedLayer.name}", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onReplaceImageClick(selectedLayer.id) }, enabled = !selectedLayer.locked) {
                                    Text("Reemplazar imagen")
                                }
                                IconButton(
                                    onClick = {
                                        translateX = 0f; translateY = 0f; scale = 1f; rotation = 0f; alpha = 1f
                                        tiltX = 0f; tiltY = 0f; focusBlur = 0f; dollyZoom = 0f
                                        commitLiveFrame()
                                    },
                                    enabled = !selectedLayer.locked
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Resetear encuadre")
                                }
                            }
                        }

                        LabeledSlider("Pan X", translateX, -2f..2f, enabled = !selectedLayer.locked) { translateX = it; commitLiveFrame() }
                        LabeledSlider("Pan Y", translateY, -2f..2f, enabled = !selectedLayer.locked) { translateY = it; commitLiveFrame() }
                        LabeledSlider("Zoom", scale, 0.2f..5f, enabled = !selectedLayer.locked) { scale = it; commitLiveFrame() }
                        LabeledSlider("Rotación (giro plano)", rotation, -180f..180f, enabled = !selectedLayer.locked) { rotation = it; commitLiveFrame() }

                        Text(
                            "Tilt 3D (cámara real, no giro plano)",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        LabeledSlider("Tilt vertical (arriba/abajo)", tiltX, -45f..45f, enabled = !selectedLayer.locked) { tiltX = it; commitLiveFrame() }
                        LabeledSlider("Tilt horizontal (paneo lateral)", tiltY, -45f..45f, enabled = !selectedLayer.locked) { tiltY = it; commitLiveFrame() }
                        LabeledSlider("Enfoque (rack focus)", focusBlur, 0f..1f, enabled = !selectedLayer.locked) { focusBlur = it; commitLiveFrame() }
                        LabeledSlider("Dolly zoom (efecto Vértigo)", dollyZoom, -1f..1f, enabled = !selectedLayer.locked) { dollyZoom = it; commitLiveFrame() }

                        LabeledSlider("Opacidad", alpha, 0f..1f, enabled = !selectedLayer.locked) { alpha = it; commitLiveFrame() }
                        LabeledSlider(
                            "Parallax (fondo=bajo, sujeto=1.0)",
                            selectedLayer.parallaxFactor,
                            0f..1f,
                            enabled = !selectedLayer.locked
                        ) { viewModel.setParallaxFactor(selectedLayer.id, it) }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.addKeyframeToSelectedLayer(
                                        translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, EasingType.EASE_IN_OUT
                                    )
                                },
                                enabled = !selectedLayer.locked
                            ) {
                                Text("Fijar keyframe aquí")
                            }
                            OutlinedButton(
                                onClick = { viewModel.removeKeyframeAtPlayhead() },
                                enabled = !selectedLayer.locked
                            ) {
                                Text("Quitar keyframe")
                            }
                        }

                        Text(
                            "Keyframes: ${selectedLayer.cameraTrack.keyframes.joinToString { "${it.timeMs}ms" }}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    } else {
                    // --- Look cinematográfico: independiente por capa ---
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Look cinematográfico — ${selectedLayer.name}", style = MaterialTheme.typography.titleSmall)

                        val look = selectedLayer.lookSettings
                        val lockedNow = selectedLayer.locked

                        Text("Exposición y color", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        LabeledSlider("Exposición", look.exposure, -1f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(exposure = it))
                        }
                        LabeledSlider("Saturación", look.saturation, 0f..2f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(saturation = it))
                        }
                        LabeledSlider("Contraste", look.contrast, 0.5f..1.8f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(contrast = it))
                        }
                        LabeledSlider("Temperatura (frío/cálido)", look.warmth, -1f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(warmth = it))
                        }
                        LabeledSlider("Tinte (verde/magenta)", look.tint, -1f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(tint = it))
                        }

                        Text("Sombras y luces", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                        LabeledSlider("Levantar sombras", look.shadowsLift, 0f..0.3f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(shadowsLift = it))
                        }
                        LabeledSlider("Suavizar luces altas", look.highlightsRolloff, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(highlightsRolloff = it))
                        }
                        LabeledSlider("Split-tone cine (teal/naranja)", look.splitToneIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(splitToneIntensity = it))
                        }

                        Text("Efectos de lente y film", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                        LabeledSlider("Viñeta", look.vignetteIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(vignetteIntensity = it))
                        }
                        LabeledSlider("Grano de película", look.grainIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(grainIntensity = it))
                        }
                        LabeledSlider("Glow (brillo energía)", look.glowIntensity, 0f..1.5f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(glowIntensity = it))
                        }
                        LabeledSlider("Umbral del glow", look.glowThreshold, 0.3f..0.95f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(glowThreshold = it))
                        }
                        LabeledSlider("Vibración de cámara (handheld)", look.cameraShakeIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(cameraShakeIntensity = it))
                        }

                        Text("Óptica de lente (nivel estudio)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                        LabeledSlider("Distorsión de lente (cojín/barril)", look.lensDistortion, -1f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(lensDistortion = it))
                        }
                        LabeledSlider("Aberración cromática", look.chromaticAberration, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(chromaticAberration = it))
                        }
                        LabeledSlider("Lens flare anamórfico", look.lensFlareIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(lensFlareIntensity = it))
                        }
                        LabeledSlider("Bokeh anamórfico (estira el enfoque)", look.anamorphicBokeh, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(anamorphicBokeh = it))
                        }
                        LabeledSlider("Motion blur (según velocidad de cámara)", look.motionBlurIntensity, 0f..1f, enabled = !lockedNow) {
                            viewModel.updateLookSettings(selectedLayer.id, look.copy(motionBlurIntensity = it))
                        }

                        Text("Presets de estudio", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.updateLookSettings(selectedLayer.id, LookSettings()) },
                                enabled = !lockedNow
                            ) {
                                Text("Resetear")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateLookSettings(
                                        selectedLayer.id,
                                        LookSettings(
                                            saturation = 1.15f, contrast = 1.15f, warmth = -0.35f,
                                            vignetteIntensity = 0.55f, grainIntensity = 0.2f,
                                            glowIntensity = 0.8f, glowThreshold = 0.6f
                                        )
                                    )
                                },
                                enabled = !lockedNow
                            ) {
                                Text("Sci-Fi oscuro")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateLookSettings(
                                        selectedLayer.id,
                                        LookSettings(
                                            saturation = 1.1f, contrast = 1.2f,
                                            splitToneIntensity = 0.6f, shadowsLift = 0.03f,
                                            highlightsRolloff = 0.2f, vignetteIntensity = 0.3f
                                        )
                                    )
                                },
                                enabled = !lockedNow
                            ) {
                                Text("Teal & Naranja")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateLookSettings(
                                        selectedLayer.id,
                                        LookSettings(
                                            saturation = 1.3f, contrast = 1.25f, warmth = -0.2f,
                                            glowIntensity = 1.1f, glowThreshold = 0.55f,
                                            vignetteIntensity = 0.4f, splitToneIntensity = 0.3f
                                        )
                                    )
                                },
                                enabled = !lockedNow
                            ) {
                                Text("Neón Cyberpunk")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.updateLookSettings(
                                        selectedLayer.id,
                                        LookSettings(
                                            saturation = 0.75f, contrast = 0.9f, warmth = 0.3f,
                                            shadowsLift = 0.12f, highlightsRolloff = 0.35f,
                                            grainIntensity = 0.45f, vignetteIntensity = 0.35f
                                        )
                                    )
                                },
                                enabled = !lockedNow
                            ) {
                                Text("Película vintage")
                            }
                        }
                    }
                    }
                } else {
                    Text("Importa imágenes con el botón + para empezar", modifier = Modifier.padding(16.dp))
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
            }
            */

            } // fin if (!isFullscreen)
        }
    }

    if (showRenameDialog) {
        RenameProjectDialog(
            initialName = state.projectName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                viewModel.renameProject(newName)
            }
        )
    }

    // El diálogo queda abierto mientras el usuario lo pidió explícitamente
    // O mientras haya una exportación en curso / un resultado pendiente de
    // ver — así, si se cierra por error mientras exporta, el progreso
    // sigue siendo accesible tocando el ícono de exportar de nuevo.
    if (showExportDialog || state.exportProgress != null) {
        ExportDialog(
            projectName = state.projectName,
            aspect = state.exportAspect,
            quality = state.exportQuality,
            onQualityChange = { viewModel.setExportQuality(it) },
            exportProgress = state.exportProgress,
            onStartExport = { fileName -> viewModel.exportVideo(context, fileName) },
            onShare = { outputFile ->
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", outputFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir video"))
            },
            onDismiss = {
                showExportDialog = false
                viewModel.clearExportState()
            }
        )
    }

    if (showAddTrackDialog) {
        AddTrackDialog(onDismiss = { showAddTrackDialog = false })
    }

    layerPendingDelete?.let { layer ->
        AlertDialog(
            onDismissRequest = { layerPendingDelete = null },
            title = { Text("¿Eliminar esta capa?") },
            text = {
                Text("\"${layer.name}\" se va a borrar junto con todos sus keyframes y ajustes de look. Esta acción no se puede deshacer.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeLayer(layer.id)
                    layerPendingDelete = null
                }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { layerPendingDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Slider reutilizable con etiqueta y valor formateado arriba. `valueLabel`
 * permite mostrar el valor con un formato distinto al decimal por defecto
 * (p. ej. como mm:ss para duraciones, en los paneles de Audio y Export).
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    valueLabel: (Float) -> String = { "%.2f".format(it) },
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ${valueLabel(value)}", style = MaterialTheme.typography.labelSmall)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled)
    }
}

/** Formatea milisegundos como mm:ss, al estilo de cualquier reproductor de video. */
private fun formatTimecode(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Indicador discreto de autoguardado bajo el nombre del proyecto en la
 * barra superior — el mismo lenguaje visual que Google Docs, Notion o
 * cualquier editor "premium": nunca hay que acordarse de guardar, y
 * siempre es visible que el trabajo está a salvo.
 */
@Composable
private fun SaveStatusLabel(saveState: SaveState) {
    val (text, color) = when (saveState) {
        is SaveState.Idle -> "" to Color.Transparent
        is SaveState.Saving -> "Guardando…" to MaterialTheme.colorScheme.onSurfaceVariant
        is SaveState.Saved -> "Guardado" to MaterialTheme.colorScheme.primary
        is SaveState.Error -> "No se pudo guardar" to MaterialTheme.colorScheme.error
    }
    if (text.isNotEmpty()) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * Ventana que abre el "+" de la fila de agregar pista, debajo del master
 * en el timeline. A PROPÓSITO queda vacía por ahora — es solo el
 * contenedor/andamiaje; el contenido (elegir tipo de pista, etc.) se
 * define más adelante.
 */
@Composable
private fun AddTrackDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Agregar pista", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Contenido intencionalmente vacío por ahora.
                Box(modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Diálogo compacto para renombrar el proyecto actual desde dentro del editor. */
@Composable
private fun RenameProjectDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Renombrar proyecto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Guardar") }
                }
            }
        }
    }
}
