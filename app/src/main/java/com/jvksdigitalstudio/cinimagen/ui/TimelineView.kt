package com.jvksdigitalstudio.cinimagen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jvksdigitalstudio.cinimagen.R
import com.jvksdigitalstudio.cinimagen.engine.Layer
import kotlin.math.abs
import kotlin.math.roundToInt

private val LABEL_COLUMN_WIDTH = 72.dp
private val ROW_HEIGHT = 36.dp
private val RULER_HEIGHT = 20.dp
private val DIAMOND_SIZE = 14.dp
private const val TAP_SLOP_PX = 6f

// --- Master: pista fija/permanente, siempre al pie de las pistas de capas
// (nunca hace scroll con ellas) — igual que el master del playlist de FL
// Studio Mobile. Debajo va la fila para agregar pistas nuevas. ---
private val MASTER_ROW_HEIGHT = 36.dp
private val ADD_TRACK_ROW_HEIGHT = 44.dp
private val ADD_TRACK_BUTTON_SIZE = 30.dp

// Playhead estilo editor profesional (After Effects / Premiere): manija
// triangular arriba + línea vertical fina abajo, en el morado de marca de
// la app (no un color ajeno que compita visualmente). PLAYHEAD_HIT_WIDTH es
// más ancho que lo que se ve — así hay margen cómodo para agarrarlo con el
// dedo sin tener que acertarle al píxel exacto de la línea.
private val PLAYHEAD_HANDLE_WIDTH = 16.dp
private val PLAYHEAD_HANDLE_HEIGHT = 12.dp
private val PLAYHEAD_LINE_WIDTH = 2.dp
private val PLAYHEAD_HIT_WIDTH = 28.dp

/**
 * Timeline visual profesional: una pista por capa (mismo orden que el
 * panel de capas — arriba = más al frente), con un diamante por keyframe
 * que se puede arrastrar para retocar el timing sin usar los sliders, y
 * un playhead compartido que abarca todas las pistas a la vez.
 *
 * Requiere una altura fija del llamador (ver uso en [EditorScreen]) — así
 * el playhead, que se dibuja como overlay superpuesto a las pistas, sabe
 * hasta dónde estirarse sin ambigüedad de constraints.
 */
@Composable
fun TimelineView(
    layers: List<Layer>,
    selectedLayerId: String?,
    playheadMs: Long,
    projectDurationMs: Long,
    onSeek: (Long) -> Unit,
    onSelectLayer: (String) -> Unit,
    onRetimeKeyframe: (layerId: String, oldTimeMs: Long, newTimeMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubStart: () -> Unit = {},
    onScrubEnd: () -> Unit = {},
    // Se dispara al tocar el "+" de la fila vacía debajo del master —
    // por ahora abre una ventana vacía (ver EditorScreen), a futuro será
    // el punto de entrada para agregar pistas nuevas al playlist.
    onAddTrackClick: () -> Unit = {}
) {
    val sortedLayers = remember(layers) { layers.sortedByDescending { it.zIndex } }
    val density = LocalDensity.current
    val safeDuration = projectDurationMs.coerceAtLeast(1L)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val labelWidthPx = with(density) { LABEL_COLUMN_WIDTH.toPx() }
        val trackWidthPx = (totalWidthPx - labelWidthPx).coerceAtLeast(1f)

        Column(modifier = Modifier.fillMaxWidth()) {
            // --- Regla de tiempo: toca en cualquier punto para mover el playhead ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RULER_HEIGHT)
                    .pointerInput(safeDuration, trackWidthPx) {
                        detectTapGestures { offset ->
                            val localX = (offset.x - labelWidthPx).coerceIn(0f, trackWidthPx)
                            onSeek(((localX / trackWidthPx) * safeDuration).toLong())
                        }
                    }
            ) {
                Text(
                    // Antes decía "0:00" fijo; ahora muestra el playhead
                    // real, actualizándose en vivo mientras avanza la
                    // línea de tiempo (reproducción, grabación o scrubbing).
                    formatTimelineTimecode(playheadMs),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(labelWidthPx.roundToInt(), 0) }
                        .padding(start = 2.dp)
                )
                Text(
                    formatTimelineTimecode(projectDurationMs),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
                )
            }

            if (sortedLayers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Importá una imagen para ver su pista acá",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    sortedLayers.forEach { layer ->
                        key(layer.id) {
                            TimelineRow(
                                layer = layer,
                                isSelected = layer.id == selectedLayerId,
                                trackWidthPx = trackWidthPx,
                                projectDurationMs = safeDuration,
                                onSelect = { onSelectLayer(layer.id) },
                                onSeek = onSeek,
                                onRetimeKeyframe = { old, new -> onRetimeKeyframe(layer.id, old, new) }
                            )
                        }
                    }
                }
            }

            // --- Master: fija al pie de las pistas de capas, nunca
            // hace scroll con ellas — un solo master por proyecto. ---
            MasterTrackRow()

            // --- Agregar pista: área vacía con un "+" al centro; a futuro
            // acá se elige qué tipo de pista sumar al playlist. ---
            AddTrackRow(onClick = onAddTrackClick)
        }

        // --- Playhead: manija triangular + línea vertical, arrastrable con el
        // dedo desde cualquier punto (estilo After Effects/Premiere), en el
        // morado de marca en vez de un color que compita visualmente. ---
        val playheadColor = MaterialTheme.colorScheme.primary
        val hitWidthPx = with(density) { PLAYHEAD_HIT_WIDTH.toPx() }
        val currentPlayheadLocalX = (playheadMs.toFloat() / safeDuration) * trackWidthPx

        // Mientras se arrastra, la posición "de verdad" (dragLocalX) manda por
        // sobre la que viene del estado (currentPlayheadLocalX) — así el dedo
        // nunca se desincroniza esperando a que el ViewModel confirme cada
        // frame. Al soltar, vuelve a null y se apoya de nuevo en el estado.
        var dragLocalX by remember { mutableStateOf<Float?>(null) }
        val displayLocalX = dragLocalX ?: currentPlayheadLocalX
        val dragState = rememberDraggableState { delta ->
            val base = dragLocalX ?: currentPlayheadLocalX
            val next = (base + delta).coerceIn(0f, trackWidthPx)
            dragLocalX = next
            onSeek(((next / trackWidthPx) * safeDuration).toLong())
        }

        Box(
            modifier = Modifier
                .offset { IntOffset((labelWidthPx + displayLocalX - hitWidthPx / 2f).roundToInt(), 0) }
                .width(PLAYHEAD_HIT_WIDTH)
                .fillMaxHeight()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragLocalX = currentPlayheadLocalX
                        // Pausa la reproducción ANTES de que el primer delta
                        // mueva un solo píxel — si no, el frame que arrastra
                        // el dedo compite con el loop de reproducción tratando
                        // de avanzar por su cuenta al mismo tiempo (visible
                        // como un tironeo/bug raro mientras se sostiene el
                        // playhead sin soltarlo).
                        onScrubStart()
                    },
                    onDragStopped = {
                        dragLocalX = null
                        // Si estaba reproduciendo antes de arrastrar, retoma
                        // la reproducción desde donde se soltó el dedo; si no
                        // estaba reproduciendo, se queda pausado ahí — nunca
                        // arranca solo porque sí.
                        onScrubEnd()
                    }
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                drawLine(
                    color = playheadColor,
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = PLAYHEAD_LINE_WIDTH.toPx()
                )
                val handleHalfW = PLAYHEAD_HANDLE_WIDTH.toPx() / 2f
                val handleH = PLAYHEAD_HANDLE_HEIGHT.toPx()
                val handlePath = Path().apply {
                    moveTo(centerX - handleHalfW, 0f)
                    lineTo(centerX + handleHalfW, 0f)
                    lineTo(centerX, handleH)
                    close()
                }
                drawPath(handlePath, color = playheadColor)
            }
        }
    }
}

/**
 * Fila del "master": permanente, fija y predeterminada — siempre está
 * presente sin importar cuántas capas tenga el proyecto, siempre al pie
 * de las pistas de capas, y nunca hace scroll junto con ellas (por eso
 * vive fuera del Column con verticalScroll, como hermano suyo). Es una
 * fila de referencia visual por ahora — sin keyframes propios — pensada
 * como anclaje para lo que se sume después (volumen/mezcla global, etc.).
 */
@Composable
private fun MasterTrackRow() {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MASTER_ROW_HEIGHT)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        ) {
            Text(
                "Master",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(LABEL_COLUMN_WIDTH)
                    .fillMaxHeight()
                    .wrapContentHeight(Alignment.CenterVertically)
                    .padding(start = 8.dp, end = 4.dp)
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Fila vacía debajo del master con un ícono "+" al centro (referencia:
 * playlist de FL Studio Mobile). Al tocarlo dispara [onClick] — hoy abre
 * una ventana todavía vacía (ver [EditorScreen]), lista para definirse
 * más adelante.
 */
@Composable
private fun AddTrackRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ADD_TRACK_ROW_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(ADD_TRACK_BUTTON_SIZE)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = "Agregar pista",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun TimelineRow(
    layer: Layer,
    isSelected: Boolean,
    trackWidthPx: Float,
    projectDurationMs: Long,
    onSelect: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetimeKeyframe: (oldTimeMs: Long, newTimeMs: Long) -> Unit
) {
    // Se lee directo de cameraTrack.keyframes: EditorScreen ya fuerza
    // recomposición vía `revision` cada vez que cambian, así que esta fila
    // siempre ve la lista al día sin necesitar su propio estado.
    val keyframes = layer.cameraTrack.keyframes

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
    ) {
        Text(
            layer.name,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(LABEL_COLUMN_WIDTH)
                .fillMaxHeight()
                .clickable { onSelect() }
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(start = 8.dp, end = 4.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(if (isSelected) Color.White.copy(alpha = 0.06f) else Color.Transparent)
                .pointerInput(layer.id, trackWidthPx, projectDurationMs) {
                    detectTapGestures {
                        onSelect()
                        val localX = it.x.coerceIn(0f, trackWidthPx)
                        onSeek(((localX / trackWidthPx) * projectDurationMs).toLong())
                    }
                }
        ) {
            keyframes.forEach { kf ->
                key(kf.timeMs) {
                    KeyframeDiamond(
                        timeMs = kf.timeMs,
                        trackWidthPx = trackWidthPx,
                        projectDurationMs = projectDurationMs,
                        isSelected = isSelected,
                        onTap = {
                            onSelect()
                            onSeek(kf.timeMs)
                        },
                        onRetime = { newTimeMs -> onRetimeKeyframe(kf.timeMs, newTimeMs) }
                    )
                }
            }
        }
    }
}

/**
 * Un keyframe individual en la pista: diamante que se puede tocar (selecciona
 * la capa y salta a ese instante) o arrastrar horizontalmente (retoca el
 * timing). Se distingue tap de arrastre por distancia recorrida, no por dos
 * detectores de gestos separados — evitar que compitan por los mismos
 * eventos de puntero es justamente lo que se evita haciéndolo así.
 */
@Composable
private fun KeyframeDiamond(
    timeMs: Long,
    trackWidthPx: Float,
    projectDurationMs: Long,
    isSelected: Boolean,
    onTap: () -> Unit,
    onRetime: (newTimeMs: Long) -> Unit
) {
    var dragOffsetPx by remember(timeMs) { mutableStateOf(0f) }
    val baseX = (timeMs.toFloat() / projectDurationMs) * trackWidthPx
    val sizePx = with(LocalDensity.current) { DIAMOND_SIZE.toPx() }
    val markerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White

    Box(
        modifier = Modifier
            .offset { IntOffset((baseX + dragOffsetPx - sizePx / 2f).roundToInt(), 0) }
            .size(DIAMOND_SIZE)
            .pointerInput(timeMs, trackWidthPx, projectDurationMs) {
                var totalMovement = 0f
                detectDragGestures(
                    onDragStart = { totalMovement = 0f },
                    onDragEnd = {
                        if (totalMovement < TAP_SLOP_PX) {
                            onTap()
                        } else {
                            val newTimeMs = (((baseX + dragOffsetPx) / trackWidthPx) * projectDurationMs)
                                .toLong()
                                .coerceIn(0L, projectDurationMs)
                            if (newTimeMs != timeMs) onRetime(newTimeMs)
                        }
                        dragOffsetPx = 0f
                    },
                    onDragCancel = { dragOffsetPx = 0f }
                ) { change, dragAmount ->
                    change.consume()
                    totalMovement += abs(dragAmount.x)
                    dragOffsetPx = (dragOffsetPx + dragAmount.x).coerceIn(-baseX, trackWidthPx - baseX)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val color = markerColor
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width / 2f, size.height)
                lineTo(0f, size.height / 2f)
                close()
            }
            drawPath(path, color = color)
            drawPath(path, color = Color.Black.copy(alpha = 0.35f), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** Formato mm:ss local, sin depender de EditorScreen (esa función es privada de ese archivo). */
private fun formatTimelineTimecode(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
