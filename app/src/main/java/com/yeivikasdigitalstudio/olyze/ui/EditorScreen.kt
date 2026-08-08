package com.yeivikasdigitalstudio.olyze.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Rect
import com.yeivikasdigitalstudio.olyze.ui.theme.ChromaKeyGreen
import com.yeivikasdigitalstudio.olyze.ui.theme.BrandPurpleDeep
import com.yeivikasdigitalstudio.olyze.ui.theme.BrandPurpleLight
import com.yeivikasdigitalstudio.olyze.ui.theme.SurfaceTintedElevated
import com.yeivikasdigitalstudio.olyze.ui.theme.effectiveLayerColorStrong
import com.yeivikasdigitalstudio.olyze.ui.theme.layerTrackColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.yeivikasdigitalstudio.olyze.R
import com.yeivikasdigitalstudio.olyze.engine.animation.EasingType
import com.yeivikasdigitalstudio.olyze.engine.camera.CameraFrame
import com.yeivikasdigitalstudio.olyze.engine.effects.LookSettings
import com.yeivikasdigitalstudio.olyze.engine.core.PixelColorSource
import com.yeivikasdigitalstudio.olyze.engine.scene.Layer
import com.yeivikasdigitalstudio.olyze.viewmodel.EditorViewModel
import com.yeivikasdigitalstudio.olyze.viewmodel.SaveState
import com.yeivikasdigitalstudio.olyze.timeline.TimelineEvent
import android.widget.Toast
import com.yeivikasdigitalstudio.olyze.data.ColorExtraction
import com.yeivikasdigitalstudio.olyze.data.Extrude3D
import com.yeivikasdigitalstudio.olyze.data.ImageDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.atan2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBackToProjects: () -> Unit,
    onImportClick: () -> Unit,
    onImportBackgroundClick: () -> Unit,
    onReplaceImageClick: (String) -> Unit,
    onImportAudioClick: () -> Unit,
    // Elegir una foto para una casilla de elenco/personajes (0..3) del
    // panel "Información del proyecto" — mismo patrón que
    // onReplaceImageClick, pero con índice de casilla en vez de layerId.
    onPickCastPhotoClick: (Int) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Aviso elegante y puntual cuando la línea de tiempo deja de poder
    // expandirse sola (llegó a las 3 horas) — se dispara UNA vez por cada
    // vez que se entra en ese estado (ver TimelineDurationManager), nunca
    // en bucle mientras el playhead siga pegado al final.
    LaunchedEffect(viewModel) {
        viewModel.timelineEvents.collect { event ->
            when (event) {
                TimelineEvent.MaxDurationReached -> {
                    Toast.makeText(
                        context,
                        "Llegaste a la duración máxima del proyecto (3 horas)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    // Ventana que abre el "+" debajo del master en el timeline — todavía
    // vacía a propósito, es el punto de entrada para agregar pistas nuevas
    // al playlist una vez que se defina qué tipos va a soportar.
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var layerPendingDelete by remember { mutableStateOf<Layer?>(null) }
    // --- Mismo reemplazo de flechas subir/bajar que ya se hizo en el
    // panel de acciones del timeline (TimelineView.kt): acá abajo, en el
    // panel legado "Capas", conviven las MISMAS dos acciones nuevas
    // (renombrar / cambiar color) para que ambos paneles se comporten
    // igual — nada de que un panel tenga las flechas viejas y el otro no. ---
    var layerPendingRename by remember { mutableStateOf<Layer?>(null) }
    // --- Cuentagotas (ver LayerDialogs.kt → LayerColorPickerDialog): el
    // color que se "chupa" vive en lo que dibuja el renderer del preview,
    // así que el pedido/resultado tiene que pasar por acá — pero la UI
    // solo conoce el contrato [PixelColorSource] (engine/core), no la
    // clase concreta que lo implementa (GLRenderer). ---
    var pixelColorSource by remember { mutableStateOf<PixelColorSource?>(null) }
    // Qué capa está esperando un color del cuentagotas ahora mismo (null = nadie).
    var eyedropperActiveForLayerId by remember { mutableStateOf<String?>(null) }
    // Resultado listo para que la fila de esa capa lo recoja y reabra su
    // diálogo de color con este color ya cargado. Se limpia apenas la fila
    // lo consume (ver onConsumeEyedropperResult más abajo).
    var eyedropperPickedColor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // --- Cuentagotas en vivo (arrastrar para previsualizar, ver overlay
    // más abajo): posición actual del dedo sobre el preview (en px de
    // vista, coordenadas locales del Box del canvas) y el color que se
    // está leyendo AHORA MISMO en esa posición, mientras el dedo sigue
    // apoyado — todavía no es el color elegido, solo lo que se ve dentro
    // de la lupa en tiempo real. null = no hay dedo apoyado. ---
    var eyedropperTouchPos by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var eyedropperLiveArgb by remember { mutableStateOf<Int?>(null) }
    var layerPendingColorChange by remember { mutableStateOf<Layer?>(null) }

    val selectedLayer = viewModel.currentSelectedLayer()
    val currentFrame = selectedLayer?.let { viewModel.frameAt(it, state.playheadMs) }
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedPanel by remember(state.selectedLayerId) { mutableStateOf(0) }
    // --- Qué panel de la barra Keyframes/Control/Rack está abierto ahora
    // mismo (null = ninguno, se ve el timeline normal). Tocar la pestaña ya
    // abierta la cierra; tocar otra distinta cambia a esa. ---
    var expandedBottomSection by remember { mutableStateOf<BottomBarSection?>(null) }

    // --- Panel de "Información del proyecto" (título, sinopsis, créditos):
    // reemplaza TODA la zona de abajo (regla + capas + barra
    // Keyframes/Control/Rack), no se superpone parcial como
    // expandedBottomSection — por eso es un booleano aparte y no un cuarto
    // valor de BottomBarSection. Ver el ícono en la barra de arriba
    // (ic_project_info, a la izquierda de la cuadrícula) y ProjectInfoPanel
    // en EditorBottomBar.kt.
    var showProjectInfoPanel by remember { mutableStateOf(false) }

    // --- Preview en vivo del audio de fondo (independiente del pipeline de export).
    // La UI ya no posee el AudioPreviewPlayer ni decide play/pause/seek —
    // solo avisa al ViewModel que uno de estos tres valores cambió; quién
    // reproduce y cómo es responsabilidad del motor (ver
    // EditorViewModel.syncAudioPreview / .updateAudioPreviewVolume).
    LaunchedEffect(state.isPlaying, state.audioClip?.sourceUri, state.audioClip?.muted) {
        viewModel.syncAudioPreview(context)
    }
    // El volumen sí se aplica en caliente sin reiniciar la reproducción.
    LaunchedEffect(state.audioClip?.volume) {
        viewModel.updateAudioPreviewVolume(context)
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
    // Estirado independiente de ancho/alto — manijas "estirar ancho"
    // (lateral derecha) y "estirar alto" (inferior central) del modo
    // "Edición > Imagen". Ver el comentario completo en CameraFrame.kt.
    var scaleX by remember { mutableStateOf(currentFrame?.scaleX ?: 1f) }
    var scaleY by remember { mutableStateOf(currentFrame?.scaleY ?: 1f) }

    // Relee el keyframe real del modelo y lo vuelca a los sliders; también
    // avanza syncTick, que el pointerInput del preview usa para saber
    // cuándo debe "olvidarse" del gesto en curso (mismo criterio, un solo
    // lugar de verdad).
    fun syncSlidersFromModel() {
        val frame = viewModel.currentSelectedLayer()?.let { viewModel.frameAt(it, state.playheadMs) }
        translateX = frame?.translateX ?: 0f
        translateY = frame?.translateY ?: 0f
        scale = frame?.scale ?: 1f
        rotation = frame?.rotationDeg ?: 0f
        alpha = frame?.alpha ?: 1f
        tiltX = frame?.tiltXDeg ?: 0f
        tiltY = frame?.tiltYDeg ?: 0f
        focusBlur = frame?.focusBlur ?: 0f
        dollyZoom = frame?.dollyZoom ?: 0f
        scaleX = frame?.scaleX ?: 1f
        scaleY = frame?.scaleY ?: 1f
        syncTick++
    }

    // Mini-menú vacío de la manija "esquina superior izquierda" de cada
    // capa (ver las 6 manijas del modo "Edición > Imagen" más abajo, en
    // el Canvas del preview). Por ahora no tiene contenido — solo el
    // panel vacío que se pidió, listo para que una futura actualización
    // le agregue opciones.
    // Declarada ACÁ arriba (y no más abajo, junto a showGridMenu /
    // showEdicionMenu) porque el LaunchedEffect de abajo ya la usa para
    // cerrar el mini-menú al cambiar de capa — en Kotlin no hay
    // "hoisting": usar una variable antes de su declaración es un error
    // de compilación real (Unresolved reference), no solo de estilo.
    var showLayerCornerMenu by remember { mutableStateOf(false) }

    // --- Modo edición dedicado (por-capa) ---
    // Id de la capa que está en "modo edición" aislado: se activa desde la
    // primera opción del mini-menú de la manija "≡" ("Editar"). Mientras
    // esté seteado (no-null) y coincida con la capa seleccionada:
    //  - el GLPreview solo dibuja ESA capa (el resto desaparece del
    //    canvas, ver `getLayers` de GLPreview más abajo).
    //  - esa capa se centra en el canvas (translateX/Y se llevan a 0,0;
    //    se guarda la posición original en editModeOriginalTranslate para
    //    devolverla tal cual estaba al salir).
    //  - aparece el panel inferior de edición (por ahora vacío — "cáscara"
    //    lista para que una futura actualización le agregue los ajustes y
    //    parámetros de edición de imagen profesional pedidos).
    var editModeLayerId by remember { mutableStateOf<String?>(null) }
    var editModeOriginalTranslate by remember { mutableStateOf<Offset?>(null) }

    // Sale del modo edición y devuelve la capa a su posición original.
    fun exitEditMode() {
        val original = editModeOriginalTranslate
        if (original != null) {
            translateX = original.x
            translateY = original.y
        }
        editModeLayerId = null
        editModeOriginalTranslate = null
    }

    // Entra en modo edición para la capa actualmente seleccionada:
    // guarda su posición real, la centra en el canvas (0,0) y activa el
    // aislamiento (solo esa capa visible).
    fun enterEditModeForSelectedLayer() {
        val sel = selectedLayer ?: return
        editModeOriginalTranslate = Offset(translateX, translateY)
        translateX = 0f
        translateY = 0f
        editModeLayerId = sel.id
        showLayerCornerMenu = false
    }

    // Cambiar de capa o deshacer/rehacer siempre resincroniza, incluso si
    // en ese instante se está grabando (son ediciones explícitas del
    // usuario, no el mero paso del tiempo).
    LaunchedEffect(state.selectedLayerId, state.undoRedoTick) {
        syncSlidersFromModel()
        // El mini-menú de la manija "esquina sup. izquierda" es por-capa;
        // si cambió la selección (o un undo/redo la movió), se cierra en
        // vez de quedar abierto apuntando a una capa que ya no es la
        // seleccionada.
        showLayerCornerMenu = false
        // Mismo criterio para el modo edición dedicado: es por-capa, así
        // que cambiar de selección (desde el timeline, undo/redo, etc.)
        // lo cierra en vez de dejarlo aislando una capa que ya no está
        // seleccionada.
        if (editModeLayerId != null && editModeLayerId != state.selectedLayerId) {
            editModeLayerId = null
            editModeOriginalTranslate = null
        }
    }

    // --- ARREGLADO: "selecciono una capa y de inmediato otra, por una
    // fracción de segundo se ve algo raro (un resto de la capa anterior)
    // que desaparece al terminar de cargar la capa seleccionada" — la
    // causa era que seleccionar desde el timeline o el panel de capas
    // solo llamaba a viewModel.selectLayer(id) y dejaba que el
    // LaunchedEffect de arriba resincronizara translateX/Y/scale/etc.
    // LaunchedEffect corre en una corrutina que arranca DESPUÉS de que
    // esta composición ya se dibujó — así que había SIEMPRE un frame de
    // por medio donde selectedLayer ya era la capa NUEVA pero
    // translateX/Y/scale/rotation todavía tenían los valores de la capa
    // VIEJA: el marco de selección y el override en vivo del GLPreview
    // (que combina el id de la capa ya seleccionada con esos valores
    // viejos) dibujaban la capa nueva en la posición/escala de la
    // anterior durante ese único frame — el "flash" reportado. Este
    // wrapper hace exactamente lo mismo que ya hacía el tap directo sobre
    // el preview (más arriba en este archivo): selecciona Y resincroniza
    // en el mismo tramo síncrono, sin esperar a la corrutina.
    fun selectLayerAndSync(layerId: String) {
        viewModel.selectLayer(layerId)
        syncSlidersFromModel()
    }
    // El scrubbing manual del playhead (arrastrar el scrubber o tocar el
    // timeline) resincroniza los sliders SOLO cuando no se está grabando
    // — durante la grabación el playhead avanza solo, 16ms en 16ms, y
    // resincronizar en cada tick reiniciaría el gesto en curso.
    LaunchedEffect(state.playheadMs) {
        if (!state.isRecording) syncSlidersFromModel()
    }
    // --- Guías de composición. Modelo GridShape (qué FORMA: rectángulo,
    // diagonal ↗, diagonal ↖, diagonal cruzada, redondo) + GridSpec
    // (columnas/filas, independientes entre sí). Ambos se guardan
    // SIEMPRE el último ajuste elegido — aunque el usuario apague la
    // cuadrícula, la forma y la densidad personalizadas quedan
    // recordadas para la próxima vez que la prenda, igual que Photoshop
    // o Figma recuerdan el espaciado de grilla. `gridEnabled` es el
    // on/off real, separado del valor — así el botón de arriba solo
    // abre/cierra el menú, y activar la cuadrícula pasa por elegir una
    // forma, tocar el switch, o mover cualquiera de los steppers.
    // BUG REAL corregido acá: estos 5 ajustes vivían solo como
    // `remember { mutableStateOf(...) }` — estado puro de composición,
    // nunca pasaba por el ViewModel ni se guardaba en project.json. Por
    // eso, al salir del proyecto (esta composable se descarta) y volver a
    // entrar (se crea una instancia nueva), la cuadrícula volvía siempre
    // a los defaults aunque el usuario la hubiera activado y ajustado.
    // Ahora se leen de `state` (restaurado desde disco al abrir el
    // proyecto, ver EditorViewModel) y cualquier cambio se manda de
    // vuelta con `viewModel.updateGridSettings(...)`, que ya dispara el
    // autoguardado — igual que el resto de los ajustes persistentes del
    // panel "Información del proyecto".
    val gridShape = remember(state.gridShapeName) {
        runCatching { GridShape.valueOf(state.gridShapeName) }.getOrDefault(GridShape.RECTANGLE)
    }
    val gridSpec = GridSpec(state.gridColumns, state.gridRows)
    val gridEnabled = state.gridEnabled
    val gridLineColorEnabled = state.gridLineColorEnabled
    val gridLineHue = state.gridLineHue
    val gridLineThicknessDp = state.gridLineThicknessDp
    val gridLineOpacity = state.gridLineOpacity
    var showGridMenu by remember { mutableStateOf(false) }
    // Instante (epoch ms) en que el Popup se cerró por última vez SOLO.
    // Existe por un problema real de Compose: cuando el usuario toca
    // afuera del Popup para cerrarlo, y ese "afuera" es justo el mismo
    // ícono que lo abrió, el toque dispara DOS cosas en la misma pasada:
    // el auto-dismiss del Popup (onDismissRequest) Y el onClick del
    // IconButton que está debajo — el resultado, sin este guard, es que
    // el menú se cierra y se vuelve a abrir en el mismo instante, y se ve
    // como si "no cerrara nunca" tocando el ícono. El guard: si el
    // Popup se acaba de auto-cerrar hace menos de 200ms, un click del
    // ícono que intente REABRIR se ignora esa única vez — pero cerrar
    // (cuando ya está abierto) nunca se bloquea, así que el ícono
    // siempre funciona como toggle real, comportamiento estándar en
    // cualquier app premium.
    var gridMenuAutoDismissedAtMs by remember { mutableStateOf(0L) }

    // --- Menú "Edición" (al lado del ícono Grabar, barra superior) ---
    // Mismo patrón toggle + guard de auto-dismiss que showGridMenu de
    // arriba: sin el guard, tocar el texto "Edición" para CERRAR el
    // Popup dispara también su propio onClick en la misma pasada (el
    // toque "afuera" que el Popup detecta es justo ese mismo texto) y
    // el menú parece no cerrarse nunca.
    var showEdicionMenu by remember { mutableStateOf(false) }
    var edicionMenuAutoDismissedAtMs by remember { mutableStateOf(0L) }
    // Estado de la única opción del menú por ahora ("Imagen"). Vive acá
    // (no en el ViewModel) porque todavía no dispara ningún efecto real
    // sobre el proyecto — es la casilla en sí, lista para que una
    // próxima actualización la conecte a lo que deba activar.
    var edicionImagenChecked by remember { mutableStateOf(false) }
    // Guarda un keyframe en el instante actual SOLO mientras el modo
    // Grabar está activo (state.isRecording) — igual que cualquier editor
    // profesional de cámara: fuera de grabación, mover la imagen o los
    // sliders NUNCA crea ni toca un keyframe. En cambio, actualiza la pose
    // ESTÁTICA de la capa (CameraTrack.baseFrame) — un concepto totalmente
    // aparte de la animación, que no aparece en la pista de keyframes y
    // que CameraTrack.frameAt() solo usa cuando la capa no tiene ninguna
    // animación armada. Así:
    //  - Capa sin animación: mover/ajustar queda guardado de verdad (no se
    //    pierde al cambiar de capa ni al cerrar el proyecto), sin que
    //    exista NINGÚN keyframe.
    //  - Capa YA animada con Grabar: seguís pudiendo "ensayar" el encuadre
    //    libremente sin grabar — como baseFrame se ignora en cuanto hay
    //    keyframes, tocarlo no tiene ningún efecto visual una vez que
    //    volvés a esa capa, y la animación existente queda intacta.
    fun commitLiveFrame() {
        // OJO: NO usar el `selectedLayer` de más arriba acá — ese es un
        // val "congelado" en el momento en que se compuso esta función,
        // y como el pointerInput(Unit) del preview lanza su corrutina UNA
        // sola vez y la mantiene corriendo para siempre (Compose nunca la
        // reinicia porque su key nunca cambia), la clausura que llega a
        // ejecutar commitLiveFrame() queda anclada a la capa que estaba
        // seleccionada la PRIMERA vez que se compuso la pantalla — sin
        // importar cuántas capas distintas selecciones después. Ese era
        // el bug real: mover una capa, seleccionar otra y arrastrarla en
        // realidad seguía escribiendo la posición sobre la primera capa
        // (que no se veía moverse porque estaba fuera de donde mirabas).
        // viewModel.currentSelectedLayer() lee el estado ACTUAL directo
        // del ViewModel en cada llamada, así que siempre apunta a la capa
        // realmente seleccionada en ese instante.
        val layer = viewModel.currentSelectedLayer() ?: return
        if (layer.locked) return
        if (state.isRecording) {
            viewModel.addKeyframeToSelectedLayer(
                translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, scaleX, scaleY, EasingType.EASE_IN_OUT
            )
        } else {
            viewModel.updateBaseFrameForSelectedLayer(
                translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, scaleX, scaleY
            )
        }
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
                        // --- "Edición": texto ancla del menú premium con
                        // la opción "Imagen" + su casilla. Envuelto en Box
                        // por la misma razón que el ícono de cuadrícula
                        // más abajo — el Popup necesita un ancla cuyas
                        // coordenadas en pantalla usar para aparecer justo
                        // debajo, centrado. Va ANTES del ícono Grabar (a
                        // su izquierda), tal como en la referencia.
                        Box {
                            Text(
                                "Edición",
                                color = if (showEdicionMenu) BrandPurpleLight else Color.White,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (showEdicionMenu) {
                                            showEdicionMenu = false
                                        } else {
                                            val now = System.currentTimeMillis()
                                            if (now - edicionMenuAutoDismissedAtMs > 200) {
                                                showEdicionMenu = true
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                            if (showEdicionMenu) {
                                EdicionMenu(
                                    imagenChecked = edicionImagenChecked,
                                    onImagenToggle = { edicionImagenChecked = !edicionImagenChecked },
                                    onDismiss = {
                                        showEdicionMenu = false
                                        edicionMenuAutoDismissedAtMs = System.currentTimeMillis()
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

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

                        // ARREGLADO: se veía más pegado a "Volver al
                        // principio" que este a "Reproducir" — ambos
                        // Spacers medían 16dp en código, pero el círculo
                        // de Grabar/Retroceder es translúcido (apenas se
                        // nota su borde) mientras que el de Play es
                        // sólido y bien visible, así que a simple vista el
                        // hueco Grabar↔Retroceder se sentía más chico.
                        // 4dp extra acá empareja la sensación visual con
                        // el otro hueco, que ya estaba bien.
                        Spacer(modifier = Modifier.width(20.dp))

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

                        Spacer(modifier = Modifier.width(16.dp))

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
                        // --- Información del proyecto: título, sinopsis,
                        // créditos, etc. — como la descripción de un video
                        // de YouTube, pero del proyecto entero. Por ahora
                        // solo abre el panel vacío (ver ProjectInfoPanel en
                        // EditorBottomBar.kt); los campos del formulario se
                        // van armando en próximas actualizaciones. A
                        // propósito a la IZQUIERDA de la cuadrícula, como
                        // pediste.
                        IconButton(onClick = { showProjectInfoPanel = !showProjectInfoPanel }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_project_info),
                                contentDescription = if (showProjectInfoPanel) "Cerrar información del proyecto" else "Información del proyecto",
                                tint = if (showProjectInfoPanel) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                        // --- Guías de composición (cuadrícula): al lado del
                        // ícono de deshacer (la flecha "regresar" del
                        // historial), como pediste — no junto a la flecha
                        // de volver a Mis proyectos.
                        //
                        // El ícono solo abre/cierra este menú (GridMenu,
                        // más abajo) — nunca prende ni apaga la cuadrícula
                        // directo al tocarlo. Envuelto en un Box porque el
                        // Popup necesita un "ancla" — un elemento cuyas
                        // coordenadas en pantalla use como referencia para
                        // aparecer justo debajo, centrado.
                        Box {
                            IconButton(onClick = {
                                if (showGridMenu) {
                                    // Ya está abierto: cerrar SIEMPRE
                                    // funciona, sin excepción — es la
                                    // mitad del toggle que nunca hay que
                                    // bloquear.
                                    showGridMenu = false
                                } else {
                                    // Va a abrir: solo se ignora si el
                                    // Popup se auto-cerró hace instantes
                                    // por este mismo toque "afuera" (ver
                                    // comentario de gridMenuAutoDismissedAtMs
                                    // más arriba).
                                    val now = System.currentTimeMillis()
                                    if (now - gridMenuAutoDismissedAtMs > 200) {
                                        showGridMenu = true
                                    }
                                }
                            }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_grid),
                                    contentDescription = if (gridEnabled) "Cambiar cuadrícula de composición" else "Mostrar cuadrícula de composición",
                                    tint = if (gridEnabled) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                            if (showGridMenu) {
                                GridMenu(
                                    enabled = gridEnabled,
                                    shape = gridShape,
                                    spec = gridSpec,
                                    lineColorEnabled = gridLineColorEnabled,
                                    lineHue = gridLineHue,
                                    lineThicknessDp = gridLineThicknessDp,
                                    lineOpacity = gridLineOpacity,
                                    onShapeSelect = { newShape ->
                                        // Elegir una forma NO cierra el
                                        // menú (a diferencia de los viejos
                                        // presets de densidad): después de
                                        // elegir forma, lo más probable es
                                        // que el usuario quiera seguir
                                        // ajustando Columnas/Filas para
                                        // esa forma — cerrar de una lo
                                        // obligaría a reabrir el menú para
                                        // seguir afinando.
                                        viewModel.updateGridSettings(shapeName = newShape.name, enabled = true)
                                    },
                                    onAxisChange = { newSpec ->
                                        // Los steppers +/- son ajuste
                                        // fino: el menú se queda abierto
                                        // para poder seguir tocando sin
                                        // que se cierre en cada toque.
                                        viewModel.updateGridSettings(
                                            columns = newSpec.columns,
                                            rows = newSpec.rows,
                                            enabled = true
                                        )
                                    },
                                    onToggle = { viewModel.updateGridSettings(enabled = !gridEnabled) },
                                    onLineColorToggle = { viewModel.updateGridSettings(lineColorEnabled = !gridLineColorEnabled) },
                                    onLineHueChange = { newHue -> viewModel.updateGridSettings(lineHue = newHue) },
                                    onThicknessChange = { newThickness -> viewModel.updateGridSettings(lineThicknessDp = newThickness) },
                                    onOpacityChange = { newOpacity -> viewModel.updateGridSettings(lineOpacity = newOpacity) },
                                    onDismiss = {
                                        showGridMenu = false
                                        gridMenuAutoDismissedAtMs = System.currentTimeMillis()
                                    }
                                )
                            }
                        }
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
        }
        // --- floatingActionButton eliminado: el "+" (importar imagen) y la
        // "F" (importar fondo) que vivían acá abajo a la derecha ahora están
        // dentro del diálogo "Agregar pista" (opción "Imagen"), para no
        // duplicar puntos de entrada — antes de esto había DOS "+": uno acá
        // y otro en la línea de tiempo, y eso confundía. ---
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // --- Preview FIJO: siempre visible, no se va con el scroll de los controles ---
            // --- Sincronización canvas -> timeline: tocar una imagen en el
            // preview selecciona su capa correspondiente abajo (antes solo
            // funcionaba al revés, tocando la fila del timeline). Va en un
            // pointerInput SEPARADO del de arrastrar/pellizcar de más
            // arriba — ambos conviven sobre el mismo Box sin pisarse,
            // mismo patrón ya usado en el resto de la app (ver
            // ColorWheelPicker en LayerDialogs.kt). Usa rememberUpdatedState
            // para leer siempre la lista de capas y el playhead MÁS
            // RECIENTES: la lambda de detectTapGestures se arma una sola
            // vez (key = Unit) y queda viva mientras dure la pantalla, así
            // que sin esto el hit-test terminaría comparando el toque
            // contra datos viejos — el mismo bug de closure obsoleto que
            // se corrigió antes en la lupa del selector de color.
            //
            // Selección + arrastre en un solo toque continuo, como
            // cualquier editor mobile profesional (CapCut, Canva): tocar
            // una capa DISTINTA la selecciona Y la deja arrastrar/
            // pellizcar/rotar de inmediato, sin soltar el dedo. La caja
            // de hit-test es el margen COMPLETO de la capa (ver
            // hitTestLayerAt), no su contenido pintado — así la selección
            // es siempre predecible sin importar la forma o el tamaño de
            // lo que se ve dentro del PNG. El pan/zoom/rotación de varios
            // dedos se calcula a mano
            // (centroide, distancia promedio, ángulo entre los primeros
            // dos dedos) en vez de reusar detectTransformGestures, porque
            // esa función arma su PROPIO ciclo de "primer toque" por
            // dentro — no se puede encadenar a mitad de un gesto que ya
            // empezamos a procesar nosotros mismos más arriba.
            val latestLayersForHitTest = rememberUpdatedState(state.layers)
            val latestPlayheadForHitTest = rememberUpdatedState(state.playheadMs)
            val latestSelectedLayerId = rememberUpdatedState(selectedLayer?.id)
            val latestSelectedLayerForDrag = rememberUpdatedState(selectedLayer)
            val hitTestBoxSize = remember { mutableStateOf(IntSize.Zero) }
            // Bandera siempre-actualizada de si el cuentagotas está
            // activo AHORA — necesaria para el guard de más abajo. Va en
            // rememberUpdatedState (no se lee `eyedropperActiveForLayerId`
            // directo) por el mismo motivo de siempre: este pointerInput
            // vive en una corrutina de larga vida (key = Unit) que no se
            // reinicia en cada recomposición, así que leerla directo
            // adentro daría un valor viejo "congelado" del momento en que
            // arrancó la corrutina.
            val latestEyedropperActive = rememberUpdatedState(eyedropperActiveForLayerId != null)
            // Bandera siempre-actualizada de si "Edición > Imagen" está
            // activada (ver menú EdicionMenu, arriba del ícono Grabar).
            // Mismo motivo que latestEyedropperActive: este pointerInput
            // vive en una corrutina de larga vida (key = Unit), así que
            // leer edicionImagenChecked directo adentro daría el valor
            // "congelado" del momento en que arrancó el gesto. Con esto
            // apagado, la capa sigue pudiéndose ARRASTRAR con un dedo
            // (mover de lugar), pero el pellizco de dos dedos NO
            // escala ni rota — queda "estática" salvo por la posición,
            // tal como pediste.
            val latestEdicionImagenEnabled = rememberUpdatedState(edicionImagenChecked)
            // Callback para "reemplazar imagen" del doble-tap — ver más
            // abajo, cerca de tapSlopPx. rememberUpdatedState por el mismo
            // motivo de siempre: esta lambda vive dentro del pointerInput
            // de larga vida (key = Unit).
            val latestOnReplaceImageClick = rememberUpdatedState(onReplaceImageClick)
            // Igual criterio: GLPreview.getLayers corre en el hilo de GL,
            // así que el filtro de "modo edición" (aislar una sola capa)
            // necesita leer el valor MÁS RECIENTE, no el que tenía la
            // composición cuando se creó el lambda.
            val latestEditModeLayerId = rememberUpdatedState(editModeLayerId)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isFullscreen) Modifier.weight(1f)
                        else Modifier.fillMaxHeight(0.46f)
                    )
                    .background(ChromaKeyGreen)
                    .onSizeChanged { hitTestBoxSize.value = it }
                    .pointerInput(Unit) {
                        // Doble-tap para reemplazar imagen: estas dos
                        // variables viven ACÁ afuera (no adentro del
                        // bloque de awaitEachGesture, que se reinicia en
                        // cada ciclo de gesto) para poder comparar el
                        // toque de un gesto contra el toque del gesto
                        // ANTERIOR y así detectar el segundo tap. Funciona
                        // con "Edición > Imagen" activada o apagada, tal
                        // como se pidió.
                        var lastImageTapAtMs = 0L
                        var lastImageTapLayerId: String? = null
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // --- ARREGLADO: con el cuentagotas activo, este
                            // mismo toque es del overlay de arriba (ver
                            // "Cuentagotas: overlay..." más abajo en el
                            // árbol), NO de seleccionar/arrastrar una capa.
                            // Antes, como este Box usa
                            // `requireUnconsumed = false` a propósito (para
                            // no pelearse con otros gestos), terminaba
                            // procesando el toque IGUAL aunque el overlay ya
                            // lo hubiera consumido — por eso, al usar el
                            // cuentagotas sobre una imagen, esa imagen
                            // quedaba seleccionada y se arrastraba en vez de
                            // solo tomar el color. Ahora se sale de una,
                            // sin tocar nada del gesto, y se lo deja
                            // enteramente al overlay del cuentagotas.
                            if (latestEyedropperActive.value) return@awaitEachGesture
                            val boxSize = hitTestBoxSize.value
                            val hitLayerId = hitTestLayerAt(
                                tapOffset = down.position,
                                boxWidthPx = boxSize.width.toFloat(),
                                boxHeightPx = boxSize.height.toFloat(),
                                layers = latestLayersForHitTest.value,
                                playheadMs = latestPlayheadForHitTest.value,
                                preferredLayerId = latestSelectedLayerId.value
                            )

                            // --- Manijas de "Edición > Imagen" (6 por capa,
                            // ver EdicionMenu arriba del ícono Grabar): se
                            // prueban ANTES que la selección/arrastre normal
                            // de más abajo, y SOLO contra la capa YA
                            // seleccionada — tocar una manija nunca cambia
                            // de capa, siempre actúa sobre la que se estaba
                            // editando. Si el modo está apagado no hay
                            // manijas dibujadas (ver el Canvas más abajo),
                            // así que tampoco deben responder a toques acá.
                            if (latestEdicionImagenEnabled.value) {
                                val sel = latestSelectedLayerForDrag.value
                                if (sel != null && !sel.locked) {
                                    val handleCorners = layerBoundingQuadPx(
                                        translateX = translateX,
                                        translateY = translateY,
                                        scaleVal = scale,
                                        rotationDeg = rotation,
                                        parallaxFactor = sel.parallaxFactor,
                                        layerWidthPx = sel.widthPx,
                                        layerHeightPx = sel.heightPx,
                                        boxWidthPx = boxSize.width.toFloat(),
                                        boxHeightPx = boxSize.height.toFloat(),
                                        scaleXVal = scaleX,
                                        scaleYVal = scaleY
                                    )
                                    if (handleCorners != null && handleCorners.size == 4) {
                                        val topLeft = handleCorners[0]
                                        val topRight = handleCorners[1]
                                        val bottomRight = handleCorners[2]
                                        val bottomLeft = handleCorners[3]
                                        val rightMid = Offset((topRight.x + bottomRight.x) / 2f, (topRight.y + bottomRight.y) / 2f)
                                        val bottomMid = Offset((bottomRight.x + bottomLeft.x) / 2f, (bottomRight.y + bottomLeft.y) / 2f)
                                        val centerPx = Offset((topLeft.x + bottomRight.x) / 2f, (topLeft.y + bottomRight.y) / 2f)
                                        val touchRadiusPx = 20.dp.toPx()
                                        fun hits(p: Offset) = (down.position - p).getDistance() <= touchRadiusPx

                                        when {
                                            hits(topRight) -> {
                                                // Esquina sup. derecha: eliminar la capa. ARREGLADO:
                                                // antes borraba directo con viewModel.removeLayer(sel.id),
                                                // sin avisar — la ÚNICA vía de borrado que no pedía
                                                // confirmación en toda la app, justo la más fácil de tocar
                                                // sin querer (una esquina del marco, en pleno gesto de
                                                // edición). Ahora dispara el mismo diálogo "¿Eliminar esta
                                                // capa?" (Cancelar/Eliminar) que ya usa el ícono de borrar
                                                // de la barra de cada capa, más abajo en el timeline —
                                                // mismo criterio en los dos lugares donde se puede borrar
                                                // una capa.
                                                down.consume()
                                                layerPendingDelete = sel
                                                return@awaitEachGesture
                                            }
                                            hits(topLeft) -> {
                                                // Esquina sup. izquierda: mini-menú desplegable (por ahora vacío).
                                                down.consume()
                                                showLayerCornerMenu = !showLayerCornerMenu
                                                return@awaitEachGesture
                                            }
                                            hits(bottomLeft) -> {
                                                // Esquina inf. izquierda: girar arrastrando alrededor del centro de la capa.
                                                //
                                                // ARREGLADO: la dirección estaba invertida — arrastrar
                                                // hacia un lado giraba la capa hacia el lado contrario.
                                                // Causa real: `angle`/`deltaAngle` se calculan con
                                                // atan2 sobre coordenadas de PANTALLA (Y crece hacia
                                                // abajo), lo que da un ángulo que crece en sentido
                                                // HORARIO al arrastrar el dedo en sentido horario. Pero
                                                // `rotation` se renderiza (LayerDrawer/GLRenderer y el
                                                // propio cálculo del marco de selección más abajo, en
                                                // NDC con Y hacia arriba) con la convención contraria:
                                                // un `rotation` positivo gira la capa en sentido
                                                // ANTIHORARIO en pantalla. Sumar `deltaAngle` directo a
                                                // `rotation` mezclaba ambas convenciones sin
                                                // compensar, y el resultado era un giro en espejo del
                                                // gesto del dedo. La resta (en vez de la suma) es
                                                // exactamente esa compensación: ahora arrastrar en
                                                // sentido horario gira la capa en sentido horario, y
                                                // viceversa — el dedo y la imagen giran para el mismo
                                                // lado, como en cualquier editor (CapCut, Canva, etc.).
                                                down.consume()
                                                var prevAngle = Math.toDegrees(
                                                    atan2((down.position.y - centerPx.y).toDouble(), (down.position.x - centerPx.x).toDouble())
                                                ).toFloat()
                                                var lastCommitAtMs = 0L
                                                var pendingCommit = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val pr = ev.changes.filter { it.pressed }
                                                    if (pr.isEmpty()) break
                                                    val p = pr[0].position
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    val angle = Math.toDegrees(
                                                        atan2((p.y - centerPx.y).toDouble(), (p.x - centerPx.x).toDouble())
                                                    ).toFloat()
                                                    var deltaAngle = angle - prevAngle
                                                    if (deltaAngle > 180f) deltaAngle -= 360f
                                                    if (deltaAngle < -180f) deltaAngle += 360f
                                                    rotation = normalizeRotationDeg(rotation - deltaAngle)
                                                    prevAngle = angle
                                                    pendingCommit = true
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastCommitAtMs >= 120L) {
                                                        commitLiveFrame(); lastCommitAtMs = now; pendingCommit = false
                                                    }
                                                }
                                                if (pendingCommit) commitLiveFrame()
                                                return@awaitEachGesture
                                            }
                                            hits(bottomRight) -> {
                                                // Esquina inf. derecha: agrandar/achicar arrastrando (alternativa al
                                                // pellizco de dos dedos, que sigue funcionando exactamente igual que antes).
                                                down.consume()
                                                var prevDist = (down.position - centerPx).getDistance().coerceAtLeast(1f)
                                                var lastCommitAtMs = 0L
                                                var pendingCommit = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val pr = ev.changes.filter { it.pressed }
                                                    if (pr.isEmpty()) break
                                                    val p = pr[0].position
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    val dist = (p - centerPx).getDistance().coerceAtLeast(1f)
                                                    scale = (scale * (dist / prevDist)).coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
                                                    prevDist = dist
                                                    pendingCommit = true
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastCommitAtMs >= 120L) {
                                                        commitLiveFrame(); lastCommitAtMs = now; pendingCommit = false
                                                    }
                                                }
                                                if (pendingCommit) commitLiveFrame()
                                                return@awaitEachGesture
                                            }
                                            hits(rightMid) -> {
                                                // Lateral derecha, medio: estirar/apretar solo el ANCHO.
                                                //
                                                // ARREGLADO: antes se trabajaba siempre con
                                                // valores ABSOLUTOS (kotlin.math.abs) de la
                                                // distancia al centro, y el resultado se
                                                // recortaba con .coerceIn(MIN_LAYER_SCALE,
                                                // MAX_LAYER_SCALE) — ambos SIEMPRE positivos.
                                                // Eso significaba que, por más que se arrastrara
                                                // la manija hacia (y más allá) del centro, scaleX
                                                // nunca podía cruzar 0 ni volverse negativo: se
                                                // quedaba pegado en MIN_LAYER_SCALE (una capa
                                                // angosta) sin nunca voltear la imagen. Pedido
                                                // explícito: al ACHICAR (arrastrar la manija hacia
                                                // y más allá del centro), la capa tiene que
                                                // VOLTEARSE horizontalmente (flip), como en
                                                // cualquier editor (CapCut, Canva, etc.) — el motor
                                                // de render ya soporta esto de forma nativa, un
                                                // scaleX negativo en la matriz de escala GL
                                                // produce exactamente un flip horizontal
                                                // (LayerDrawer.kt, Matrix.scaleM).
                                                //
                                                // La solución: en vez de una razón INCREMENTAL
                                                // cuadro-a-cuadro sobre valores absolutos, se usa
                                                // una razón sobre la posición X con signo,
                                                // relativa al punto donde se agarró la manija
                                                // (baseDx, siempre del lado derecho > 0 al
                                                // empezar). Mientras el dedo sigue del lado
                                                // derecho del centro, la razón es positiva
                                                // (estira/achica normal, igual que antes). Al
                                                // cruzar el centro hacia la izquierda, la razón se
                                                // vuelve negativa y scaleX pasa a negativo — ahí
                                                // ocurre el flip. La MAGNITUD sigue recortada
                                                // entre MIN_LAYER_SCALE y MAX_LAYER_SCALE (para
                                                // que el motor nunca reciba una escala 0), solo
                                                // que ahora el SIGNO queda libre.
                                                down.consume()
                                                val scaleXAtGrab = scaleX
                                                val rawBaseDx = down.position.x - centerPx.x
                                                val baseDx = if (kotlin.math.abs(rawBaseDx) < 1f) {
                                                    if (rawBaseDx >= 0f) 1f else -1f
                                                } else rawBaseDx
                                                var lastCommitAtMs = 0L
                                                var pendingCommit = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val pr = ev.changes.filter { it.pressed }
                                                    if (pr.isEmpty()) break
                                                    val p = pr[0].position
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    val currentDx = p.x - centerPx.x
                                                    val ratioSigned = currentDx / baseDx
                                                    val rawScaleX = scaleXAtGrab * ratioSigned
                                                    val mag = kotlin.math.abs(rawScaleX).coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
                                                    scaleX = if (rawScaleX >= 0f) mag else -mag
                                                    pendingCommit = true
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastCommitAtMs >= 120L) {
                                                        commitLiveFrame(); lastCommitAtMs = now; pendingCommit = false
                                                    }
                                                }
                                                if (pendingCommit) commitLiveFrame()
                                                return@awaitEachGesture
                                            }
                                            hits(bottomMid) -> {
                                                // Inferior, medio: estirar/apretar solo el ALTO.
                                                // Mismo criterio y mismo motivo que en `rightMid`
                                                // arriba (ver comentario completo ahí): ahora
                                                // ACHICAR más allá del centro voltea la capa
                                                // verticalmente (scaleY negativo = flip vertical
                                                // en el motor GL), en vez de quedar pegado en el
                                                // piso de escala sin voltear nunca.
                                                down.consume()
                                                val scaleYAtGrab = scaleY
                                                val rawBaseDy = down.position.y - centerPx.y
                                                val baseDy = if (kotlin.math.abs(rawBaseDy) < 1f) {
                                                    if (rawBaseDy >= 0f) 1f else -1f
                                                } else rawBaseDy
                                                var lastCommitAtMs = 0L
                                                var pendingCommit = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val pr = ev.changes.filter { it.pressed }
                                                    if (pr.isEmpty()) break
                                                    val p = pr[0].position
                                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                                    val currentDy = p.y - centerPx.y
                                                    val ratioSigned = currentDy / baseDy
                                                    val rawScaleY = scaleYAtGrab * ratioSigned
                                                    val mag = kotlin.math.abs(rawScaleY).coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
                                                    scaleY = if (rawScaleY >= 0f) mag else -mag
                                                    pendingCommit = true
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastCommitAtMs >= 120L) {
                                                        commitLiveFrame(); lastCommitAtMs = now; pendingCommit = false
                                                    }
                                                }
                                                if (pendingCommit) commitLiveFrame()
                                                return@awaitEachGesture
                                            }
                                        }
                                    }
                                }
                            }

                            // --- ARREGLADO: antes, tocar una capa DISTINTA
                            // a la seleccionada solo la seleccionaba y
                            // "drenaba" (consumía sin hacer nada) el resto
                            // de ese mismo toque continuo — había que
                            // LEVANTAR el dedo y volver a tocar para recién
                            // ahí poder moverla. En la práctica, un
                            // toque-y-arrastre normal (sin levantar el
                            // dedo) sobre una capa distinta no hacía
                            // NADA visible: ni parecía seleccionarla ni
                            // moverla — exactamente el bug reportado. Ahora
                            // seleccionar y arrastrar viven en el MISMO
                            // toque continuo, como cualquier editor mobile
                            // (CapCut, Canva, etc.): se selecciona la capa
                            // nueva Y ADEMÁS se sincronizan translateX/Y/
                            // scale/rotation/etc. leyendo su keyframe
                            // ACTUAL del modelo ahí mismo (sin esperar al
                            // LaunchedEffect(state.selectedLayerId), que es
                            // asíncrono y llegaría un frame tarde), para
                            // que el arrastre que sigue abajo ya opere
                            // sobre los valores correctos de la capa recién
                            // elegida desde el primer movimiento del dedo.
                            var layer = latestSelectedLayerForDrag.value
                            if (hitLayerId != null && hitLayerId != latestSelectedLayerId.value) {
                                viewModel.selectLayer(hitLayerId)
                                val newLayer = latestLayersForHitTest.value.firstOrNull { it.id == hitLayerId }
                                val newFrame = newLayer?.let { viewModel.frameAt(it, latestPlayheadForHitTest.value) }
                                translateX = newFrame?.translateX ?: 0f
                                translateY = newFrame?.translateY ?: 0f
                                scale = newFrame?.scale ?: 1f
                                rotation = newFrame?.rotationDeg ?: 0f
                                alpha = newFrame?.alpha ?: 1f
                                tiltX = newFrame?.tiltXDeg ?: 0f
                                tiltY = newFrame?.tiltYDeg ?: 0f
                                focusBlur = newFrame?.focusBlur ?: 0f
                                dollyZoom = newFrame?.dollyZoom ?: 0f
                                scaleX = newFrame?.scaleX ?: 1f
                                scaleY = newFrame?.scaleY ?: 1f
                                syncTick++
                                layer = newLayer
                            }

                            // El toque cayó sobre la capa YA seleccionada,
                            // sobre la que se acaba de seleccionar recién
                            // arriba, o no tocó ninguna capa (para poder
                            // pellizcar aunque el dedo arranque en un hueco
                            // vacío del canvas): mover/pellizcar/rotar con
                            // libertad.
                            if (layer != null && layer.locked) return@awaitEachGesture

                            var previousCentroid: Offset? = null
                            var previousSpan: Float? = null
                            var previousAngle: Float? = null
                            var previousPressedCount = 0
                            // --- Distancia total recorrida por el dedo desde
                            // que bajó, para poder distinguir un TOQUE real
                            // (deselecciona si cayó en un hueco vacío) de un
                            // pellizco/paneo que arrancó en ese mismo hueco
                            // vacío (no debe deseleccionar nada, solo mover
                            // la capa ya seleccionada). Ver uso más abajo.
                            var totalMovementPx = 0f
                            // --- Menos lag durante el arrastre: escribir
                            // en el ViewModel (que dispara recomposición
                            // de TODA la lista de capas + programa
                            // autoguardado) en CADA evento de movimiento
                            // del dedo es innecesario — el feedback visual
                            // durante el arrastre ya viene de las
                            // variables locales (translateX, etc.) vía
                            // getLiveOverride, no de esta escritura. Se
                            // persiste como mucho cada ~50ms mientras se
                            // mueve, y siempre una vez más al soltar el
                            // dedo, para no perder la posición final.
                            var lastCommitAtMs = 0L
                            var pendingCommit = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                var sumX = 0f
                                var sumY = 0f
                                for (c in pressed) {
                                    sumX += c.position.x
                                    sumY += c.position.y
                                }
                                val centroid = Offset(sumX / pressed.size, sumY / pressed.size)

                                val prevCentroid = previousCentroid
                                // --- ARREGLADO: "al hacer zoom (pellizco) y
                                // soltar, la capa se sube/baja un poco". Al
                                // soltar un pellizco los dos dedos casi
                                // nunca se levantan en el MISMO evento — uno
                                // se levanta un instante antes que el otro.
                                // En ese evento intermedio la cantidad de
                                // dedos apoyados cambia de 2 a 1, y el
                                // centroide se recalcula con un solo punto:
                                // salta de golpe a la posición de ESE dedo
                                // suelto, aunque ningún dedo se haya movido
                                // realmente. Ese salto de centroide se
                                // traducía directo en un salto de
                                // translateX/Y — justo el bug reportado.
                                // Ahora, si la cantidad de dedos cambió
                                // desde el evento anterior, este frame SOLO
                                // actualiza la base (centroide/separación/
                                // ángulo) sin aplicar ningún delta; el
                                // seguimiento normal retoma recién en el
                                // próximo evento, ya con una base
                                // consistente para esa nueva cantidad de
                                // dedos.
                                val fingerCountChanged = pressed.size != previousPressedCount
                                if (prevCentroid != null && !fingerCountChanged) {
                                    val boxWidth = size.width.toFloat().coerceAtLeast(1f)
                                    val boxHeight = size.height.toFloat().coerceAtLeast(1f)
                                    val panDx = centroid.x - prevCentroid.x
                                    val panDy = centroid.y - prevCentroid.y
                                    totalMovementPx += hypot(panDx, panDy)
                                    translateX = (translateX + (panDx / boxWidth) * 2f).coerceIn(-2f, 2f)
                                    translateY = (translateY - (panDy / boxHeight) * 2f).coerceIn(-2f, 2f)

                                    if (pressed.size >= 2) {
                                        // --- Zoom con dos dedos: pedido
                                        // explícito de que funcione SIEMPRE,
                                        // esté o no activo "Edición >
                                        // Imagen" — antes todo este bloque
                                        // (escala Y rotación) estaba atado a
                                        // `latestEdicionImagenEnabled.value`,
                                        // así que con el modo apagado el
                                        // pellizco no hacía nada más que
                                        // panear. Ahora el ACERCAR/ALEJAR
                                        // con los dedos queda desacoplado de
                                        // ese modo — funciona siempre que
                                        // haya 2+ dedos — mientras que la
                                        // ROTACIÓN con dos dedos sigue
                                        // exclusivamente detrás de "Edición
                                        // > Imagen" activado, tal como se
                                        // pidió ("nada de que jire, solo
                                        // zoom" con el modo apagado). Ningún
                                        // otro gesto (manijas, iconos) se ve
                                        // afectado: siguen dependiendo del
                                        // modo igual que antes.
                                        var sumDist = 0f
                                        for (c in pressed) {
                                            sumDist += hypot(c.position.x - centroid.x, c.position.y - centroid.y)
                                        }
                                        val span = sumDist / pressed.size
                                        val prevSpan = previousSpan
                                        if (prevSpan != null && prevSpan > 1f) {
                                            scale = (scale * (span / prevSpan)).coerceIn(MIN_LAYER_SCALE, MAX_LAYER_SCALE)
                                        }
                                        previousSpan = span

                                        if (latestEdicionImagenEnabled.value) {
                                            val a = pressed[0].position
                                            val b = pressed[1].position
                                            val angle = Math.toDegrees(
                                                atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                                            ).toFloat()
                                            val prevAngle = previousAngle
                                            if (prevAngle != null) {
                                                var deltaAngle = angle - prevAngle
                                                if (deltaAngle > 180f) deltaAngle -= 360f
                                                if (deltaAngle < -180f) deltaAngle += 360f
                                                rotation = normalizeRotationDeg(rotation + deltaAngle)
                                            }
                                            previousAngle = angle
                                        } else {
                                            // "Edición > Imagen" apagada:
                                            // se limpia la base de ángulo
                                            // para que, si se activa a
                                            // mitad de gesto, no salte con
                                            // un delta viejo — el zoom de
                                            // arriba sigue funcionando
                                            // igual, esto solo afecta a la
                                            // rotación.
                                            previousAngle = null
                                        }
                                    } else {
                                        // Un solo dedo: no se toca escala
                                        // ni rotación, solo se deja avanzar
                                        // el paneo de más arriba. Se limpia
                                        // la base de span/ángulo para que,
                                        // si se agrega un segundo dedo, no
                                        // salte con un delta viejo.
                                        previousSpan = null
                                        previousAngle = null
                                    }

                                    pendingCommit = true
                                    val now = System.currentTimeMillis()
                                    if (now - lastCommitAtMs >= 120L) {
                                        commitLiveFrame()
                                        lastCommitAtMs = now
                                        pendingCommit = false
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (fingerCountChanged) {
                                    // Cambió la cantidad de dedos: solo
                                    // resetear la base de pellizco (span/
                                    // ángulo), nunca aplicar delta acá.
                                    previousSpan = null
                                    previousAngle = null
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                previousCentroid = centroid
                                previousPressedCount = pressed.size
                            }
                            // Se soltó el dedo: garantizar que la posición
                            // final quede guardada aunque haya caído
                            // dentro de la "ventana" sin persistir.
                            if (pendingCommit) commitLiveFrame()

                            // --- ARREGLADO: tocar un espacio vacío del
                            // canvas (sin ninguna capa debajo del dedo) no
                            // hacía NADA — el marco de selección se quedaba
                            // ahí pegado hasta elegir otra capa desde el
                            // timeline. Pedido explícito: un toque simple
                            // (no un pellizco/paneo, que sigue funcionando
                            // igual que antes) sobre un hueco vacío tiene
                            // que quitar el marco. Umbral de 8dp de
                            // movimiento total para diferenciar "toque" de
                            // "arrastre que arrancó en un hueco vacío".
                            val tapSlopPx = 8.dp.toPx()
                            if (hitLayerId == null && totalMovementPx < tapSlopPx) {
                                viewModel.clearSelection()
                            }

                            // --- Doble-tap para reemplazar imagen: un toque
                            // genuino (no arrastre — mismo umbral tapSlopPx
                            // de arriba) sobre una capa, dos veces seguidas
                            // en menos de 300ms, abre el selector de
                            // imágenes para esa capa. Funciona con
                            // "Edición > Imagen" prendida o apagada — se
                            // pidió explícitamente que no dependa de ese
                            // modo. Reusa el mismo selector nativo
                            // (ActivityResultContracts.OpenDocument, ver
                            // MainActivity.onReplaceImageClick) que ya usa
                            // el botón "Reemplazar imagen" del panel de
                            // propiedades — en Android 13+ eso ya abre el
                            // Selector de Fotos moderno de Google (mismo
                            // look que la galería nativa), así que no hace
                            // falta construir una ventana propia para esto.
                            if (hitLayerId != null && totalMovementPx < tapSlopPx) {
                                val tappedLayer = latestLayersForHitTest.value.firstOrNull { it.id == hitLayerId }
                                if (tappedLayer != null && !tappedLayer.locked) {
                                    val nowMs = System.currentTimeMillis()
                                    val isDoubleTap = hitLayerId == lastImageTapLayerId &&
                                        (nowMs - lastImageTapAtMs) < 300L
                                    if (isDoubleTap) {
                                        lastImageTapAtMs = 0L
                                        lastImageTapLayerId = null
                                        latestOnReplaceImageClick.value(hitLayerId)
                                    } else {
                                        lastImageTapAtMs = nowMs
                                        lastImageTapLayerId = hitLayerId
                                    }
                                }
                            }
                        }
                    }
            ) {
                // --- Cuadrícula de composición rasterizada a bitmap (ver
                // comentario completo en [rasterizeGridBitmap]): se
                // recalcula SOLO cuando algo de la cuadrícula (forma,
                // columnas/filas, color, grosor) o el tamaño del lienzo
                // cambian de verdad — las keys de `remember` NO incluyen
                // nada que cambie en cada frame de arrastre de una capa
                // (translateX/Y/scale no son keys acá), así que mover o
                // escalar una capa normal no re-rasteriza nada.
                val gridDensity = androidx.compose.ui.platform.LocalDensity.current
                val gridBitmap = remember(
                    gridEnabled,
                    gridShape,
                    gridSpec,
                    gridLineColorEnabled,
                    gridLineHue,
                    gridLineThicknessDp,
                    gridLineOpacity,
                    hitTestBoxSize.value
                ) {
                    if (!gridEnabled) {
                        null
                    } else {
                        rasterizeGridBitmap(
                            widthPx = hitTestBoxSize.value.width,
                            heightPx = hitTestBoxSize.value.height,
                            shape = gridShape,
                            spec = gridSpec,
                            color = gridLineDrawColor(gridLineColorEnabled, gridLineHue, gridLineOpacity),
                            strokeWidthPx = with(gridDensity) { gridLineThicknessDp.dp.toPx() }
                        )
                    }
                }
                GLPreview(
                    getLayers = {
                        val allLayers = viewModel.uiState.value.layers
                        // Modo edición dedicado: solo se dibuja la capa
                        // aislada, el resto "desaparece" del canvas — tal
                        // cual se pidió ("que se centre... y que todas
                        // desaparezca que solo quede esa imagen").
                        val editId = latestEditModeLayerId.value
                        if (editId != null) {
                            allLayers.filter { it.id == editId }
                        } else {
                            allLayers
                        }
                    },
                    getPlayheadMs = { viewModel.uiState.value.playheadMs },
                    // La cuadrícula viaja como una textura de fondo más —
                    // GLRenderer la dibuja PRIMERO, antes que las capas
                    // reales, así queda detrás de cualquier imagen (ver
                    // comentario en rasterizeGridBitmap y en
                    // GLRenderer.onDrawFrame).
                    getGridBitmap = { gridBitmap },
                    getLiveOverride = {
                        // ARREGLADO: acá antes se capturaba `selectedLayer`
                        // directo (un `val` normal, congelado en los valores
                        // que tenía la ÚLTIMA composición). GLSurfaceView
                        // corre en su propio hilo de render, en su propio
                        // reloj, completamente desacoplado del hilo de UI/
                        // Compose — puede llamar a este lambda en cualquier
                        // instante, incluso a mitad de un cambio de capa.
                        // Seleccionar una capa nueva actualiza
                        // translateX/Y/scale/... de inmediato (son
                        // MutableState, el cambio es visible al toque), pero
                        // `selectedLayer` (el id de la capa "vieja") solo se
                        // refresca cuando Compose recompone — eso pasa
                        // DESPUÉS, en el próximo frame. Resultado: por uno o
                        // más frames, el hilo de render veía el id de la
                        // capa VIEJA combinado con la transformación de la
                        // capa NUEVA, y dibujaba la capa vieja saltando a la
                        // posición/escala de la nueva — el "flash" de la
                        // otra capa reportado. `latestSelectedLayerForDrag`
                        // (definido más arriba, ya usado para el hit-test de
                        // drag por esta misma razón) es un State cuyo
                        // `.value` se lee en el momento exacto de la
                        // llamada, no al crear el lambda — así el id y la
                        // transformación quedan siempre sincronizados sin
                        // importar en qué instante exacto dispare el hilo
                        // de GL.
                        latestSelectedLayerForDrag.value?.let {
                            it.id to CameraFrame(translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, scaleX, scaleY)
                        }
                    },
                    onRendererReady = { pixelColorSource = it }
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

                // --- Marco de la capa seleccionada: el rectángulo COMPLETO
                // que ocupa (mismo criterio que hitTestLayerAt), sin
                // importar cuánto de eso se vea transparente. Pedido
                // explícito: que quede claro hasta dónde "abarca" la
                // imagen para tocar/arrastrar, no solo lo pintado. Usa los
                // valores EN VIVO (translateX, scale, etc., no el frame
                // guardado del modelo) para que el marco siga el dedo
                // durante el arrastre sin quedar un frame atrás. ---
                if (selectedLayer != null && !selectedLayer.locked) {
                    val boxSize = hitTestBoxSize.value
                    val corners = layerBoundingQuadPx(
                        translateX = translateX,
                        translateY = translateY,
                        scaleVal = scale,
                        rotationDeg = rotation,
                        parallaxFactor = selectedLayer.parallaxFactor,
                        layerWidthPx = selectedLayer.widthPx,
                        layerHeightPx = selectedLayer.heightPx,
                        boxWidthPx = boxSize.width.toFloat(),
                        boxHeightPx = boxSize.height.toFloat(),
                        scaleXVal = scaleX,
                        scaleYVal = scaleY
                    )
                    if (corners != null && corners.size == 4) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val frameColor = BrandPurpleLight
                            val strokeW = 2.dp.toPx()
                            for (i in corners.indices) {
                                val start = corners[i]
                                val end = corners[(i + 1) % corners.size]
                                drawLine(frameColor, start, end, strokeW)
                            }
                            // --- 6 manijas premium, una función específica
                            // por esquina/punto medio — SOLO con "Edición >
                            // Imagen" activada (ver menú EdicionMenu, arriba
                            // del ícono Grabar). Con la opción apagada, la
                            // capa sigue mostrando su marco (para saber
                            // hasta dónde abarca y poder arrastrarla), pero
                            // sin manijas — la imagen queda "estática" salvo
                            // por la posición, tal como se pidió. Mismo
                            // criterio de esquinas que layerBoundingQuadPx:
                            // [0]=arriba-izq [1]=arriba-der [2]=abajo-der
                            // [3]=abajo-izq.
                            if (edicionImagenChecked) {
                                val topLeft = corners[0]
                                val topRight = corners[1]
                                val bottomRight = corners[2]
                                val bottomLeft = corners[3]
                                val rightMid = Offset((topRight.x + bottomRight.x) / 2f, (topRight.y + bottomRight.y) / 2f)
                                val bottomMid = Offset((bottomRight.x + bottomLeft.x) / 2f, (bottomRight.y + bottomLeft.y) / 2f)

                                val badgeRadius = 11.dp.toPx()
                                val badgeRing = 1.6.dp.toPx()
                                val glyphStroke = 1.6.dp.toPx()

                                fun DrawScope.drawBadge(center: Offset, glyph: DrawScope.(Offset, Float, Color, Float) -> Unit) {
                                    drawCircle(Color.White, badgeRadius, center)
                                    drawCircle(frameColor, badgeRadius, center, style = Stroke(width = badgeRing))
                                    glyph(center, badgeRadius, frameColor, glyphStroke)
                                }

                                // 1: esquina inferior derecha — agrandar/achicar (reescalar uniforme).
                                drawBadge(bottomRight) { c, r, col, sw -> drawDoubleArrowGlyph(c, r, Math.toRadians(45.0), col, sw) }
                                // 2: lateral derecha, medio — estirar/apretar ANCHO.
                                drawBadge(rightMid) { c, r, col, sw -> drawDoubleArrowGlyph(c, r, 0.0, col, sw) }
                                // 3: inferior, medio — estirar/apretar ALTO.
                                drawBadge(bottomMid) { c, r, col, sw -> drawDoubleArrowGlyph(c, r, Math.PI / 2.0, col, sw) }
                                // Esquina inferior izquierda — girar.
                                drawBadge(bottomLeft) { c, r, col, sw -> drawRotateGlyph(c, r, col, sw) }
                                // Esquina superior izquierda — mini-menú (vacío por ahora).
                                drawBadge(topLeft) { c, r, col, sw -> drawMenuGlyph(c, r, col, sw) }
                                // Esquina superior derecha — eliminar capa.
                                drawBadge(topRight) { c, r, col, sw -> drawDeleteGlyph(c, r, col, sw) }
                            }
                        }

                        // --- Panel del mini-menú (manija esquina sup.
                        // izquierda): mismo look premium (Surface elevada
                        // + borde sutil) que EdicionMenu/GridMenu, para
                        // que se sienta de la misma familia visual que el
                        // resto de la app. Anclada en px absolutos a la
                        // esquina sup. izquierda del marco (corners[0]),
                        // no a un composable — porque la manija que la
                        // abre vive dentro del Canvas de arriba, no en el
                        // árbol de Compose.
                        //
                        // ARREGLADO (3): las dos vueltas anteriores
                        // dejaban el panel prácticamente a la misma altura
                        // que el ícono (offset vertical de +14dp, mientras
                        // que el ícono mismo — círculo de radio
                        // `menuBadgeRadius` = 11dp + su anillo de borde de
                        // 1.6dp — ya ocupa hasta unos ~12dp desde el
                        // centro), así que el panel arrancaba pegado
                        // ARRIBA, tapando al propio ícono y montándose
                        // sobre la imagen en vez de colgar por debajo.
                        // Ahora el offset vertical se calcula a partir del
                        // radio REAL del ícono (`menuBadgeRadius`,
                        // compartido con el dibujado del badge más arriba
                        // para que nunca se puedan desincronizar) más un
                        // margen de 10dp — el panel arranca recién donde
                        // termina el círculo del ícono, con un hueco
                        // visible pero chico, tal como un menú colgante
                        // normal. En X se achicó el ancho a
                        // wrapContentWidth (ya no 112dp fijos: "Editar" no
                        // necesita tanto) y el offset pasó de +6dp a
                        // -4dp, así el panel arranca CASI en el mismo eje
                        // vertical que el ícono en vez de nacer varios dp
                        // a su derecha.
                        if (edicionImagenChecked && showLayerCornerMenu) {
                            val anchor = corners[0]
                            val menuBadgeRadius = 11.dp
                            val menuBadgeRing = 1.6.dp
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (anchor.x - 4.dp.toPx()).roundToInt(),
                                            (anchor.y + (menuBadgeRadius + menuBadgeRing).toPx() + 10.dp.toPx()).roundToInt()
                                        )
                                    }
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .wrapContentWidth()
                                        .wrapContentHeight()
                                        .shadow(elevation = 10.dp, shape = RoundedCornerShape(14.dp)),
                                    color = SurfaceTintedElevated,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { enterEditModeForSelectedLayer() }
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_edit),
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "Editar",
                                                color = Color.White,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Chip "Editando imagen X" del modo edición dedicado ---
                // Aparece solo mientras `editModeLayerId` apunta a la capa
                // seleccionada (ver enterEditModeForSelectedLayer /
                // exitEditMode más arriba). El panel de ajustes/parámetros
                // de edición YA NO va acá adentro del canvas — eso no se
                // pidió nunca; ese panel vacío va abajo, reemplazando la
                // zona del timeline (ver el Box con weight(1f) más abajo,
                // donde vive TimelineView) — es esa zona la que estaba
                // marcada con líneas amarillas en la referencia original.
                if (editModeLayerId != null && editModeLayerId == selectedLayer?.id) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(SurfaceTintedElevated)
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "Editando imagen",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .clickable { exitEditMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = "Salir del modo edición",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // --- Guías de composición: ahora se dibujan DETRÁS de las
                // capas (ver `gridBitmap`/`getGridBitmap` más arriba y
                // GLRenderer.onDrawFrame) en vez de como overlay de
                // Compose por encima de todo — por eso ya no hay un
                // Canvas acá. Sigue siendo solo del editor, NUNCA se
                // exporta al video (GLRenderer del exportador no recibe
                // getGridBitmap).

                // --- El botón de capas se movió a la esquina inferior
                // izquierda, junto a la barra de tiempo del timeline (ver
                // más abajo, donde está TimelineView) — antes vivía acá
                // arriba tapando parte del preview. ---

                // El hint "Arrastra · pellizca · gira con 2 dedos" se quitó
                // a pedido — quedaba redundante una vez que el usuario ya
                // conoce el gesto. Se conserva el aviso de "Capa bloqueada",
                // que sí es información que cambia y vale la pena mostrar.
                if (selectedLayer != null && selectedLayer.locked) {
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

                        // Mismo ajuste que en la barra superior: 4dp extra
                        // para emparejar la sensación visual con el hueco
                        // Retroceder↔Play (ver comentario completo arriba).
                        Spacer(modifier = Modifier.width(20.dp))

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

                // --- Cuentagotas: overlay que aparece ENCIMA de todo el
                // preview mientras se espera el toque. ANTES: un solo tap
                // ya elegía el color, sin poder ver antes qué se estaba
                // tocando ni corregir el dedo si apuntaba mal ("solo hay
                // una oportunidad para seleccionar el color dando un
                // click"). AHORA: al apoyar el dedo aparece una lupa
                // circular pegada arriba del punto tocado que muestra EN
                // VIVO el color de esa posición (se actualiza en cada
                // movimiento, sin soltar); recién al levantar el dedo se
                // confirma ese último color como elegido. Si el dedo se
                // levanta fuera del canvas (offset inválido) o se cancela
                // el gesto, no se confirma nada — solo se sale del modo
                // cuentagotas. ---
                if (eyedropperActiveForLayerId != null) {
                    val targetLayerId = eyedropperActiveForLayerId
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                            .pointerInput(targetLayerId) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    var lastPos = down.position
                                    eyedropperTouchPos = lastPos
                                    pixelColorSource?.requestPixelColor(
                                        lastPos.x.roundToInt(),
                                        lastPos.y.roundToInt()
                                    ) { argb -> eyedropperLiveArgb = argb }

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (change.pressed) {
                                            change.consume()
                                            lastPos = change.position
                                            eyedropperTouchPos = lastPos
                                            pixelColorSource?.requestPixelColor(
                                                lastPos.x.roundToInt(),
                                                lastPos.y.roundToInt()
                                            ) { argb -> eyedropperLiveArgb = argb }
                                        } else {
                                            // Dedo levantado: confirma el ÚLTIMO color
                                            // visto en la lupa como el elegido.
                                            val layerId = targetLayerId
                                            if (layerId != null) {
                                                pixelColorSource?.requestPixelColor(
                                                    lastPos.x.roundToInt(),
                                                    lastPos.y.roundToInt()
                                                ) { argb ->
                                                    eyedropperPickedColor = layerId to argb
                                                    eyedropperActiveForLayerId = null
                                                    eyedropperTouchPos = null
                                                    eyedropperLiveArgb = null
                                                }
                                            } else {
                                                eyedropperActiveForLayerId = null
                                                eyedropperTouchPos = null
                                                eyedropperLiveArgb = null
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                    ) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp),
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    if (eyedropperTouchPos == null)
                                        "Tocá y arrastrá sobre la imagen para elegir el color"
                                    else
                                        "Soltá para elegir este color",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Cancelar",
                                    color = BrandPurpleLight,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.clickable {
                                        eyedropperActiveForLayerId = null
                                        eyedropperTouchPos = null
                                        eyedropperLiveArgb = null
                                    }
                                )
                            }
                        }

                        // --- Lupa flotante: sigue al dedo en tiempo real,
                        // desplazada hacia arriba para no quedar tapada por
                        // el dedo/mano. Muestra el color leído AHORA en un
                        // círculo grande + su código hex, con una línea guía
                        // que baja hasta el punto exacto que se está
                        // tocando (para no perder precisión aunque la lupa
                        // esté offset). ---
                        val touchPos = eyedropperTouchPos
                        if (touchPos != null) {
                            val liveColor = eyedropperLiveArgb?.let { Color(it) } ?: Color.Gray
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val loupeOffsetPx = with(density) { 96.dp.toPx() }
                            val loupeCenterY = (touchPos.y - loupeOffsetPx).coerceAtLeast(
                                with(density) { 90.dp.toPx() }
                            )
                            // Línea guía entre la lupa y el punto tocado.
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawLine(
                                    color = Color.White.copy(alpha = 0.8f),
                                    start = androidx.compose.ui.geometry.Offset(touchPos.x, loupeCenterY),
                                    end = touchPos,
                                    strokeWidth = with(density) { 1.5.dp.toPx() }
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .offset {
                                        androidx.compose.ui.unit.IntOffset(
                                            (touchPos.x - with(density) { 44.dp.toPx() }).roundToInt(),
                                            (loupeCenterY - with(density) { 44.dp.toPx() }).roundToInt()
                                        )
                                    }
                                    .size(88.dp)
                                    .clip(CircleShape)
                                    .background(liveColor)
                                    .border(3.dp, Color.White, CircleShape)
                            )
                            Surface(
                                modifier = Modifier
                                    .offset {
                                        androidx.compose.ui.unit.IntOffset(
                                            (touchPos.x - with(density) { 50.dp.toPx() }).roundToInt(),
                                            (loupeCenterY + with(density) { 50.dp.toPx() }).roundToInt()
                                        )
                                    },
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "#" + String.format("%06X", (eyedropperLiveArgb ?: 0) and 0xFFFFFF),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!isFullscreen) {

            // --- Timeline visual: una pista por capa con keyframes arrastrables ---
            // ANTES: este Box (y el TimelineView de adentro) tenían un tope
            // FIJO de 220dp (heightIn max=220dp), mientras que el Box
            // "relleno" de más abajo (línea ~807, placeholder del panel
            // Cámara/Look/Audio/Tiempo comentado) usaba weight(1f) — se
            // quedaba con TODO el espacio sobrante de la pantalla sin
            // importar cuánto necesitara realmente el timeline. Resultado:
            // apenas había 3-4 capas, el timeline llegaba a su tope de
            // 220dp y necesitaba scroll interno para llegar al "+", pero
            // como el relleno de abajo es EXACTAMENTE el mismo morado
            // sólido (BrandPurpleDeep), todo ese scroll pendiente se veía
            // como una sola pared uniforme tapando las capas y el "+" —
            // no había ningún panel invisible tapando nada, era solo mal
            // reparto de espacio entre estos dos hermanos.
            //
            // Ahora el timeline usa weight(1f) — se lleva el espacio
            // sobrante real de la pantalla (compartido con el relleno de
            // abajo, ver su propio comentario), así que en la gran mayoría
            // de los casos entran ruler + master + varias capas + el "+"
            // sin necesitar scroll para nada; solo con MUCHAS capas entra a
            // tallar el scroll interno de TimelineView, y en ese caso sí
            // hay contenido real de sobra (no una pared vacía).
            //
            // --- Envoltorio nuevo para el panel "Información del
            // proyecto": antes el Box de acá abajo (con weight(1f)) y
            // EditorBottomBar eran hermanos sueltos dentro de la Column de
            // toda la pantalla — cada uno con su propio pedazo de alto,
            // pero SIN un padre en común que abarcara los dos juntos, así
            // que no había forma de poner un panel que tapara am
            // BOS a la vez de punta a punta (el pedido: "desde la barra de
            // playhead hasta el borde de abajo de la pantalla", ruler +
            // capas + la barra Keyframes/Control/Rack, todo junto). Ahora
            // ese Box exterior es el padre común: adentro, una Column
            // nueva reproduce EXACTAMENTE el mismo reparto de alto que
            // había antes (timeline con weight(1f) + EditorBottomBar con
            // su alto fijo) — mismo resultado visual cuando el panel está
            // cerrado — y al lado, como hermano de esa Column dentro del
            // mismo Box, va el panel nuevo, que al ocupar fillMaxSize()
            // cubre justo ese alto combinado.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                TimelineView(
                    layers = state.layers,
                    // Ver comentario en TimelineView.kt: sin esto, el
                    // reordenamiento por arrastre "regresaba" a su lugar
                    // anterior al soltar el dedo, porque moveLayerUp/Down
                    // mutan el zIndex de los mismos objetos Layer sin
                    // cambiar la referencia de forma que Compose lo note.
                    revision = state.revision,
                    selectedLayerId = state.selectedLayerId,
                    playheadMs = state.playheadMs,
                    projectDurationMs = state.projectDurationMs,
                    onSeek = { viewModel.seekTo(it) },
                    onSelectLayer = { selectLayerAndSync(it) },
                    onRetimeKeyframe = { layerId, oldMs, newMs -> viewModel.retimeKeyframe(layerId, oldMs, newMs) },
                    onToggleLayerVisibility = { viewModel.toggleLayerVisibility(it) },
                    onToggleLayerLock = { viewModel.toggleLayerLock(it) },
                    onToggleLayerOrderLock = { viewModel.toggleLayerOrderLock(it) },
                    onRenameLayer = { layerId, newName -> viewModel.renameLayer(layerId, newName) },
                    onChangeLayerColor = { layerId, colorArgb, useBW -> viewModel.setLayerCustomColor(layerId, colorArgb, useBW) },
                    onChangeLayerGradient = { layerId, startArgb, endArgb, angleDegrees, isRadial, useBW -> viewModel.setLayerGradient(layerId, startArgb, endArgb, angleDegrees, isRadial, useBW) },
                    onResetLayerColor = { layerId -> viewModel.resetLayerColor(layerId) },
                    // "Multicolor": un solo checkpoint de undo para el grupo
                    // entero + degradado repartido entre las capas marcadas
                    // (ver EditorViewModel.setLayersGradient), en vez de
                    // pintar el mismo degradado completo en cada capa por
                    // separado.
                    onChangeMultipleLayersColor = { layerIds, colorArgb, useBW -> viewModel.setLayersCustomColor(layerIds, colorArgb, useBW) },
                    onChangeMultipleLayersGradient = { layerIds, startArgb, endArgb, angleDegrees, isRadial, useBW -> viewModel.setLayersGradient(layerIds, startArgb, endArgb, angleDegrees, isRadial, useBW) },
                    onResetMultipleLayersColor = { layerIds -> viewModel.resetLayersColor(layerIds) },
                    onRequestEyedropper = { layerId -> eyedropperActiveForLayerId = layerId },
                    eyedropperResult = eyedropperPickedColor,
                    onConsumeEyedropperResult = { eyedropperPickedColor = null },
                    onReorderLayer = { layerId, steps -> viewModel.reorderLayer(layerId, steps) },
                    onDeleteLayerRequest = { layer -> layerPendingDelete = layer },
                    onScrubStart = { viewModel.beginScrub() },
                    onScrubEnd = { viewModel.endScrub() },
                    onAddTrackClick = { showAddTrackDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                )

                // --- El ícono de capas + flecha (y el menú "Multicolor" que
                // se abre al tocarlo) ya NO vive acá: se movió DENTRO de
                // TimelineView (ver TimelineView.kt), porque esa función
                // necesita leer y modificar el color de TODAS las capas a
                // la vez — algo que TimelineView ya podía hacer con sus
                // propios parámetros (onChangeLayerColor, onChangeLayerGradient,
                // onResetLayerColor), sin tener que subir ese estado hasta
                // acá y volver a bajarlo. Antes vivía en este archivo porque
                // el viejo panel emergente de capas era más simple y no
                // necesitaba nada de eso.

                // --- Panel vacío de Keyframes / Control / Rack: se
                // superpone al timeline (master + capas) cuando una de las
                // tres pestañas de abajo está activa, PERO nunca tapa la
                // regla de tiempo de arriba ni la barra de pestañas de
                // abajo — el padding(top = RULER_HEIGHT, start =
                // LABEL_COLUMN_WIDTH) recorta exactamente ese hueco, mismos
                // valores que ya usan TimelineView y EditorBottomBar, así
                // los tres quedan perfectamente alineados sin duplicar
                // números a mano. Vive DENTRO de este mismo Box (el que ya
                // tiene el alto real del timeline vía weight(1f)), así su
                // borde inferior cae justo, sin espacio de más, donde
                // empieza EditorBottomBar.
                expandedBottomSection?.let { section ->
                    SectionPlaceholderPanel(
                        section = section,
                        onClose = { expandedBottomSection = null },
                        // --- Ver comentario completo en
                        // SectionPlaceholderPanel/ControlImageOptionsPanel
                        // (EditorBottomBar.kt): por ahora TODAS las capas
                        // son de imagen, así que esto es simplemente "hay
                        // una capa seleccionada" — se deja explícito acá
                        // (en vez de un `true` fijo adentro del panel)
                        // para que el día que existan capas de otro tipo
                        // esto se pueda filtrar por tipo real sin tocar
                        // EditorBottomBar.kt.
                        hasImageLayerSelected = selectedLayer != null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = LABEL_COLUMN_WIDTH, top = RULER_HEIGHT)
                            .zIndex(5f)
                    )
                }
            }

            // --- Cabecera de secciones Keyframes / Control / Rack ---
            // Antes acá solo había un relleno morado sólido de 16dp para
            // cerrar el borde inferior del timeline. Ahora ese espacio pasa
            // a ser esta barra de navegación entre paneles: arranca justo
            // donde termina la columna de miniaturas de las capas (nunca
            // desde el borde izquierdo de la pantalla — el hueco de la
            // izquierda deja ver el mismo relleno morado de fondo), y ofrece
            // tres secciones — Rack a la derecha, Control al medio,
            // Keyframes a la izquierda — por ahora puramente visuales,
            // cada una recibe su función más adelante.
            EditorBottomBar(
                modifier = Modifier.fillMaxWidth(),
                selectedSection = expandedBottomSection,
                onKeyframesClick = {
                    expandedBottomSection = if (expandedBottomSection == BottomBarSection.KEYFRAMES) null
                        else BottomBarSection.KEYFRAMES
                },
                onControlClick = {
                    expandedBottomSection = if (expandedBottomSection == BottomBarSection.CONTROL) null
                        else BottomBarSection.CONTROL
                },
                onRackClick = {
                    expandedBottomSection = if (expandedBottomSection == BottomBarSection.RACK) null
                        else BottomBarSection.RACK
                }
            )
            } // fin de la Column interna (timeline + EditorBottomBar)

            // --- Panel "Editando imagen": tapa TODO lo de abajo (ruler +
            // capas + la barra Keyframes/Control/Rack) de punta a punta,
            // mismo patrón que ProjectInfoPanel arriba — Box hermano de la
            // Column (timeline+bottombar) dentro de este mismo Box
            // exterior con weight(1f), así su fillMaxSize() cubre
            // exactamente ese alto combinado completo, sin dejar nada
            // asomado abajo ni arriba. Sin padding, sin borde amarillo
            // (eso era tu marcador en la referencia, no un color real
            // pedido) — mismo morado oscuro sólido que el resto de
            // ventanas de la app (SurfaceTintedElevated).
            // Chequeo explícito con `!= null` (no `selectedLayer?.id`) a
            // propósito acá: LayerColorEditPanel de abajo necesita un
            // Layer no-nulo, y Kotlin solo puede "smart cast" selectedLayer
            // como no-nulo dentro de este bloque si la condición lo
            // verifica de forma DIRECTA — comparar `selectedLayer?.id` no
            // alcanza para que el compilador lo infiera, aunque en la
            // práctica sea imposible que este bloque corra con
            // selectedLayer null (editModeLayerId ya no sería igual a
            // null.id). Este fue justo el error real que rompió el build
            // (`Argument type mismatch: actual type is 'Layer?', but
            // 'Layer' was expected`).
            if (editModeLayerId != null && selectedLayer != null && editModeLayerId == selectedLayer.id) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f),
                    color = SurfaceTintedElevated
                ) {
                    LayerColorEditPanel(
                        layer = selectedLayer,
                        context = context,
                        viewModel = viewModel
                    )
                }
            }

            // --- Panel "Información del proyecto": tapa TODO lo de abajo
            // (regla, capas, barra Keyframes/Control/Rack) de punta a
            // punta, con animación de acordeón — se despliega desde arriba
            // hacia abajo al abrir (expandVertically, anclado arriba) y se
            // retrae hacia arriba al cerrar (shrinkVertically, mismo
            // ancla). Dos formas de cerrar, como pediste: la X de adentro
            // del panel (ver ProjectInfoPanel) y tocar de nuevo este mismo
            // ícono en la barra de arriba (ic_project_info) — ambas
            // terminan en lo mismo, showProjectInfoPanel = false, así que
            // ninguna de las dos necesita lógica extra acá.
            // BUG REAL DE COMPILACIÓN corregido (esto es lo que rompió el
            // build en GitHub Actions): `AnimatedVisibility` no es una sola
            // función — Compose declara varias versiones con el mismo
            // nombre (la genérica, y otras que son extensión de
            // ColumnScope/RowScope). Acá este Box está anidado DENTRO de la
            // Column general de toda la pantalla (más arriba en este mismo
            // archivo), así que esa ColumnScope sigue "alcanzable" como
            // receptor implícito aunque el Box esté en el medio. El
            // compilador encontró esa versión de ColumnScope como
            // candidata y no supo decidir automáticamente por la genérica,
            // así que pedía un receptor explícito ("cannot be called in
            // this context with an implicit receiver"). La solución real
            // es nombrar el paquete completo acá, así no hay ninguna
            // ambigüedad posible: SIEMPRE la versión genérica, sea cual
            // sea el anidado de Column/Box a su alrededor.
            androidx.compose.animation.AnimatedVisibility(
                visible = showProjectInfoPanel,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(durationMillis = 320)
                ) + fadeIn(animationSpec = tween(durationMillis = 220)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(durationMillis = 280)
                ) + fadeOut(animationSpec = tween(durationMillis = 160)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(10f)
            ) {
                ProjectInfoPanel(
                    onClose = { showProjectInfoPanel = false },
                    title = state.projectName,
                    onTitleChange = { viewModel.renameProject(it) },
                    releaseYear = state.releaseYear,
                    onReleaseYearChange = { viewModel.updateReleaseYear(it) },
                    genre = state.genre,
                    onGenreChange = { viewModel.updateGenre(it) },
                    durationMinutes = state.infoDurationMinutes,
                    onDurationMinutesChange = { viewModel.updateInfoDurationMinutes(it) },
                    castPhotoFiles = state.castPhotoFiles,
                    onPickCastPhoto = onPickCastPhotoClick,
                    onRemoveCastPhoto = { viewModel.removeCastPhoto(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            } // fin del Box exterior (timeline+bottombar normal, o panel de info encima)

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
                        speedAtPlayhead = viewModel.speedAtPlayhead(),
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
                                        scaleX = 1f; scaleY = 1f
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
                                        translateX, translateY, scale, rotation, alpha, tiltX, tiltY, focusBlur, dollyZoom, scaleX, scaleY, EasingType.EASE_IN_OUT
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
            dimensionsPx = viewModel.currentExportDimensions(),
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
        AddTrackDialog(
            onDismiss = { showAddTrackDialog = false },
            onImportImageClick = {
                showAddTrackDialog = false
                onImportClick()
            }
        )
    }

    layerPendingRename?.let { layer ->
        RenameLayerDialog(
            initialName = layer.name,
            accentColor = effectiveLayerColorStrong(layer),
            onDismiss = { layerPendingRename = null },
            onConfirm = { newName ->
                layerPendingRename = null
                viewModel.renameLayer(layer.id, newName)
            }
        )
    }

    layerPendingColorChange?.let { layer ->
        LayerColorPickerDialog(
            initialColorArgb = layer.customColorArgb,
            initialGradientStartArgb = layer.customGradientStartArgb,
            initialGradientEndArgb = layer.customGradientEndArgb,
            initialUseGradient = layer.useGradientColor,
            initialGradientAngleDegrees = layer.gradientAngleDegrees,
            initialGradientIsRadial = layer.gradientIsRadial,
            initialBlackAndWhiteMode = layer.useBlackAndWhiteMode,
            fallbackColorArgb = layerTrackColor(layer.colorIndex).toArgb(),
            onDismiss = { layerPendingColorChange = null },
            onSelectColor = { colorArgb, useBW ->
                layerPendingColorChange = null
                viewModel.setLayerCustomColor(layer.id, colorArgb, useBW)
            },
            onSelectGradient = { startArgb, endArgb, angleDegrees, isRadial, useBW ->
                layerPendingColorChange = null
                viewModel.setLayerGradient(layer.id, startArgb, endArgb, angleDegrees, isRadial, useBW)
            },
            onReset = { viewModel.resetLayerColor(layer.id) }
        )
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
    // BUG REAL corregido acá: esta Column no tenía `fillMaxWidth()`, así
    // que en cualquier panel donde el ancho no viniera ya forzado por un
    // padre con weight/fill (como pasa en el panel "Recolor", cuyo
    // contenedor usa CenterHorizontally sin fillMaxWidth explícito) el
    // Slider caía a su ancho mínimo de "wrap content" — se veía cortado
    // a la mitad o menos, en vez de ocupar todo el panel como antes.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ${valueLabel(value)}", style = MaterialTheme.typography.labelSmall)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
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
 * Calcula las 4 esquinas (en píxeles de pantalla, mismo Box que el
 * preview) del rectángulo COMPLETO que ocupa una capa — el mismo que usa
 * [hitTestLayerAt] para decidir si un toque la alcanza — sin importar
 * cuánto de ese rectángulo se vea realmente opaco. Es la geometría
 * inversa de esa función: en vez de "toque de pantalla -> ¿adentro de la
 * capa?", es "esquinas de la capa en su espacio local -> pantalla".
 *
 * Para qué sirve: mostrar un marco/guía sobre la capa seleccionada, para
 * que quede claro hasta dónde llega el "lienzo" real de esa imagen (con
 * su margen transparente y todo) — no solo lo que se ve pintado. Así el
 * usuario entiende de un vistazo por qué un toque cae "dentro" o "fuera"
 * de una capa, en vez de que sea una caja invisible.
 */
/**
 * Dibuja un par de trazos en forma de "cabeza de flecha" en [tip],
 * apuntando hacia afuera en la dirección [outwardDirRad] (radianes,
 * mismo sistema que atan2: 0 = derecha, PI/2 = abajo en pantalla).
 * Pieza compartida de [drawDoubleArrowGlyph] y [drawRotateGlyph] — así
 * las 3 manijas de flecha doble (reescalar, estirar ancho, estirar
 * alto) y la de girar usan exactamente el mismo trazo, sin duplicar la
 * trigonometría en cada una.
 */
private fun DrawScope.drawArrowheadAt(
    tip: Offset,
    outwardDirRad: Double,
    size: Float,
    color: Color,
    strokeWidthPx: Float
) {
    val spread = Math.toRadians(28.0)
    val backDir1 = outwardDirRad + Math.PI - spread
    val backDir2 = outwardDirRad + Math.PI + spread
    drawLine(
        color, tip,
        Offset(tip.x + size * cos(backDir1).toFloat(), tip.y + size * sin(backDir1).toFloat()),
        strokeWidthPx, cap = StrokeCap.Round
    )
    drawLine(
        color, tip,
        Offset(tip.x + size * cos(backDir2).toFloat(), tip.y + size * sin(backDir2).toFloat()),
        strokeWidthPx, cap = StrokeCap.Round
    )
}

/**
 * Ícono de flecha doble (dos puntas, una a cada lado del centro) sobre
 * el eje [angleRad] — 45° para "reescalar" (esquina inf. derecha), 0°
 * (horizontal) para "estirar ancho" (lateral derecha, medio) y 90°
 * (vertical) para "estirar alto" (inferior, medio). Delgado (grosor
 * mediano, no grueso) a propósito, para que se vea premium/profesional
 * y no como un ícono de sistema genérico.
 */
private fun DrawScope.drawDoubleArrowGlyph(
    center: Offset,
    r: Float,
    angleRad: Double,
    color: Color,
    strokeWidthPx: Float
) {
    val len = r * 0.55f
    val dx = (len * cos(angleRad)).toFloat()
    val dy = (len * sin(angleRad)).toFloat()
    val p1 = Offset(center.x - dx, center.y - dy)
    val p2 = Offset(center.x + dx, center.y + dy)
    drawLine(color, p1, p2, strokeWidthPx, cap = StrokeCap.Round)
    val headLen = r * 0.34f
    drawArrowheadAt(p1, angleRad + Math.PI, headLen, color, strokeWidthPx)
    drawArrowheadAt(p2, angleRad, headLen, color, strokeWidthPx)
}

/** Ícono de girar (esquina inf. izquierda): arco + una sola cabeza de flecha en la punta, en el sentido en que arrastrar realmente rota la capa. */
private fun DrawScope.drawRotateGlyph(center: Offset, r: Float, color: Color, strokeWidthPx: Float) {
    val radius = r * 0.5f
    val startDeg = -50f
    val sweepDeg = 280f
    val rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
    drawArc(
        color = color,
        startAngle = startDeg,
        sweepAngle = sweepDeg,
        useCenter = false,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    )
    val endRad = Math.toRadians((startDeg + sweepDeg).toDouble())
    val tip = Offset(center.x + radius * cos(endRad).toFloat(), center.y + radius * sin(endRad).toFloat())
    val tangentOutward = endRad + Math.PI / 2.0
    drawArrowheadAt(tip, tangentOutward, radius * 0.65f, color, strokeWidthPx)
}

/** Ícono de eliminar (esquina sup. derecha): una "X" simple y delgada. */
private fun DrawScope.drawDeleteGlyph(center: Offset, r: Float, color: Color, strokeWidthPx: Float) {
    val d = r * 0.42f
    drawLine(color, Offset(center.x - d, center.y - d), Offset(center.x + d, center.y + d), strokeWidthPx, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x - d, center.y + d), Offset(center.x + d, center.y - d), strokeWidthPx, cap = StrokeCap.Round)
}

/** Ícono de menú (esquina sup. izquierda): tres líneas horizontales delgadas ("hamburguesa"), abre el panel vacío. */
private fun DrawScope.drawMenuGlyph(center: Offset, r: Float, color: Color, strokeWidthPx: Float) {
    val halfW = r * 0.42f
    val gap = r * 0.34f
    for (i in -1..1) {
        val y = center.y + i * gap
        drawLine(color, Offset(center.x - halfW, y), Offset(center.x + halfW, y), strokeWidthPx, cap = StrokeCap.Round)
    }
}

/**
 * Distancia (al cuadrado, no hace falta la raíz para comparar) entre dos
 * colores ARGB en el espacio RGB — usada por [LayerColorEditPanel] para
 * encontrar, dentro de una paleta recién extraída, el swatch más
 * parecido a un color que se vio en pantalla antes del recargue. No
 * pesa el canal alpha: acá siempre se comparan colores ya opacos
 * (swatches de paleta).
 */
private fun colorDistanceSquared(a: Int, b: Int): Int {
    val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
    val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
    val db = (a and 0xFF) - (b and 0xFF)
    return dr * dr + dg * dg + db * db
}

/**
 * Panel de "Color" del modo edición dedicado (ver EditorScreen: overlay
 * "Editando imagen"): a la izquierda, un cuadrito por cada color
 * distinto extraído de la imagen de la capa (ColorExtraction.
 * extractPalette); a la derecha, la rueda de color profesional
 * (reutiliza ColorWheelPicker, la misma de "Color de la capa" en
 * LayerDialogs.kt). Tocar un cuadrito lo selecciona (la rueda salta a
 * su matiz/saturación); arrastrar la rueda recolorea EN VIVO solo ese
 * color en el canvas (ColorExtraction.recolor, por cercanía de color,
 * no igualdad exacta de píxel — así agarra también el sombreado/
 * antialiasing de ese color, no un único tono puro).
 *
 * Dos resoluciones de trabajo a propósito:
 *  - [liveBitmap] (chica, ~220px de lado) para que la extracción de
 *    paleta sea instantánea y cada frame de arrastre de la rueda se
 *    recoloree y suba a GL sin lag notable.
 *  - [fullBitmap] (hasta 1024px) que se decodifica aparte, en paralelo,
 *    y es la que de verdad se recolorea y persiste a disco (ver
 *    EditorViewModel.commitLayerRecolor) 500ms después del último
 *    cambio — así arrastrar rápido no dispara decenas de escrituras de
 *    archivo por segundo, pero el resultado final que se guarda es de
 *    mejor calidad que la vista previa liviana del arrastre.
 */
@Composable
private fun LayerColorEditPanel(
    layer: Layer,
    context: android.content.Context,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    var liveBitmap by remember(layer.id) { mutableStateOf<Bitmap?>(null) }
    var fullBitmap by remember(layer.id) { mutableStateOf<Bitmap?>(null) }
    var palette by remember(layer.id) { mutableStateOf<List<Int>>(emptyList()) }
    var isLoadingPalette by remember(layer.id) { mutableStateOf(true) }
    var selectedOriginal by remember(layer.id) { mutableStateOf<Int?>(null) }
    val remaps = remember(layer.id) { mutableStateMapOf<Int, Int>() }
    var wheelHue by remember(layer.id) { mutableStateOf(0f) }
    var wheelSat by remember(layer.id) { mutableStateOf(0f) }
    var wheelVal by remember(layer.id) { mutableStateOf(1f) }
    // "Opacidad" del panel: cuánto pisa el color recoloreado al color
    // original de la imagen (1 = recolor a pleno, 0 = imagen intacta) —
    // ver el parámetro `intensity` de ColorExtraction.recolor. Empieza en
    // 1 (comportamiento de siempre) para no sorprender con un resultado
    // "apagado" apenas se entra al panel.
    var recolorOpacity by remember(layer.id) { mutableStateOf(1f) }
    var commitJob by remember(layer.id) { mutableStateOf<Job?>(null) }

    // Qué pestaña del header ("Recolor" / "3D") está activa — ver
    // EditImageToolsHeader. Por capa: si cambiás de capa seleccionada,
    // vuelve a "Recolor" en vez de arrastrar la pestaña de la capa
    // anterior.
    var selectedTab by remember(layer.id) { mutableStateOf(0) }

    fun selectSwatch(originalColor: Int) {
        selectedOriginal = originalColor
        val effective = remaps[originalColor] ?: originalColor
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(effective, hsv)
        wheelHue = hsv[0]
        wheelSat = hsv[1]
        wheelVal = hsv[2]
    }

    LaunchedEffect(layer.id, layer.sourceUri) {
        // BUG REAL corregido acá: `remaps` vive en remember(layer.id) — NO
        // se reinicia cuando `layer.sourceUri` cambia. Y sí cambia: apenas
        // pasan los 500ms de debounce, commitLayerRecolor guarda el
        // resultado en un ARCHIVO NUEVO y actualiza sourceUri a esa nueva
        // ruta (ver comentario de commitLayerRecolor en EditorViewModel).
        // Ese archivo nuevo YA tiene el cambio anterior horneado en los
        // píxeles — pero la entrada vieja de `remaps` (color original de
        // ANTES → el color que se eligió) seguía viva en memoria. Si el
        // usuario volvía a tocar la rueda para elegir OTRO color, el mapa
        // de recolor terminaba con DOS entradas compitiendo por los mismos
        // píxeles: la vieja (que en muchos casos seguía siendo "válida"
        // porque su color de origen todavía estaba cerca del color actual)
        // y la nueva recién elegida — y la mezcla ponderada de recolor()
        // terminaba tirando el resultado de vuelta hacia el color viejo,
        // por más que se arrastrara la rueda a un tono distinto. Con una
        // imagen de un solo color plano esto era especialmente notorio:
        // la entrada vieja competía cabeza a cabeza contra la nueva en
        // CASI todos los píxeles a la vez, así que el cambio nuevo casi no
        // se notaba — "se queda pegado en el mismo verde".
        //
        // Ahora, cada vez que se carga una imagen nueva desde disco (osea,
        // cada vez que el commit anterior YA quedó guardado), se limpia
        // `remaps` entero: el color base que se acaba de cargar YA ES el
        // resultado final del cambio anterior, así que cualquier delta
        // viejo relativo a un color-de-origen que ya no existe en el
        // archivo debe descartarse, no acumularse para siempre.
        // BUG REAL #2 corregido acá: antes de limpiar el estado, guardamos
        // qué color se veía en pantalla para el swatch que el usuario
        // tenía elegido (su remap si lo tenía, si no el original). Este
        // LaunchedEffect no corre solo una vez al entrar al panel: corre
        // CADA VEZ que cambia `layer.sourceUri`, y sourceUri cambia solo
        // 500ms después de CADA pausa al arrastrar un slider o la rueda
        // (ver commitLayerRecolor). O sea que este bloque se re-ejecutaba
        // en medio de una sesión de edición normal, no solo al abrir el
        // panel.
        val previousEffectiveColor = selectedOriginal?.let { remaps[it] ?: it }

        remaps.clear()
        selectedOriginal = null

        isLoadingPalette = true
        val small = withContext(Dispatchers.IO) {
            ImageDecoding.decodeSampledFromUri(context.contentResolver, layer.sourceUri, maxDimension = 220)
        }
        liveBitmap = small
        val extracted = small?.let { ColorExtraction.extractPalette(it) } ?: emptyList()
        palette = extracted
        isLoadingPalette = false
        // BUG REAL #2, la parte que de verdad se sentía como "se
        // restablece solo": acá SIEMPRE se auto-seleccionaba
        // `extracted.firstOrNull()`, sin importar qué color tenía
        // elegido el usuario. Como este efecto se repite en cada pausa
        // de edición (no solo al abrir el panel), si el usuario estaba
        // ajustando el 2do o 3er color de la paleta, la selección saltaba
        // de vuelta al primero cada medio segundo — el recolor SÍ se
        // aplicaba y se guardaba bien, pero el panel visualmente
        // "olvidaba" en qué color estabas parado, así que mover un
        // slider parecía no hacer nada o quedar limitado.
        //
        // Ahora, si había un color seleccionado antes, se busca en la
        // paleta nueva el más parecido (por distancia RGB) a como se
        // veía ese color en pantalla, y se re-selecciona ESE — la
        // selección "sigue" al mismo color a través de los recargues
        // automáticos. Solo si no había nada elegido todavía (primera
        // vez que se abre el panel para esta capa) se cae al
        // comportamiento original de elegir el más presente.
        val toReselect = previousEffectiveColor
            ?.let { target -> extracted.minByOrNull { candidate -> colorDistanceSquared(candidate, target) } }
            ?: extracted.firstOrNull()
        toReselect?.let { selectSwatch(it) }
        fullBitmap = withContext(Dispatchers.IO) {
            ImageDecoding.decodeSampledFromUri(context.contentResolver, layer.sourceUri, maxDimension = 1024)
        }
    }

    fun applyLivePreviewAndScheduleCommit() {
        liveBitmap?.let { small ->
            val recoloredSmall = ColorExtraction.recolor(small, remaps.toMap(), intensity = recolorOpacity)
            viewModel.previewLayerRecolor(layer.id, recoloredSmall)
        }
        commitJob?.cancel()
        commitJob = coroutineScope.launch {
            delay(500)
            val source = fullBitmap ?: liveBitmap ?: return@launch
            val remapsSnapshot = remaps.toMap()
            val opacitySnapshot = recolorOpacity
            val recoloredFull = withContext(Dispatchers.Default) {
                ColorExtraction.recolor(source, remapsSnapshot, intensity = opacitySnapshot)
            }
            viewModel.commitLayerRecolor(layer.id, recoloredFull)
        }
    }

    // Compartida por la rueda Y por los sliders de Brillo/Saturación: sea
    // cual sea el control que se mueva, todos terminan en el mismo lugar
    // — arman el color HSV completo, lo guardan como remap del color
    // seleccionado, y disparan la vista previa en vivo + el guardado con
    // debounce. Sin esto, cada control tendría que repetir esa misma
    // secuencia de 3 pasos por separado.
    fun applyCurrentWheelColor() {
        val newColor = android.graphics.Color.HSVToColor(floatArrayOf(wheelHue, wheelSat, wheelVal))
        selectedOriginal?.let { remaps[it] = newColor }
        applyLivePreviewAndScheduleCommit()
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // --- Header de pestañas del modo "Editando imagen": "Recolor"
        // (el panel de esta función, más abajo) y "3D" (Extrude3DPanel)
        // ya están activas/funcionales. Las últimas dos quedan como
        // cuadros vacíos reservados para próximas herramientas.
        EditImageToolsHeader(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        )

        if (selectedTab == 1) {
            Extrude3DPanel(
                layer = layer,
                context = context,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
        // --- Columna izquierda: un cuadrito por color extraído ---
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Color",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(10.dp))
            when {
                isLoadingPalette -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(top = 4.dp),
                            color = Color.White.copy(alpha = 0.5f),
                            strokeWidth = 2.dp
                        )
                    }
                }
                palette.isEmpty() -> {
                    Text(
                        "Sin colores",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                else -> {
                    // Cada cuadrito se achica automáticamente si hay
                    // muchos colores, para que TODOS entren en la fila
                    // vertical sin scroll — como se pidió ("mientras más
                    // colores el cuadro más se va ajustando para que
                    // entre en la pantalla").
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        val spacing = 6.dp
                        val count = palette.size
                        val idealSize = if (count > 0) {
                            (maxHeight - spacing * (count - 1).coerceAtLeast(0)) / count
                        } else maxHeight
                        val swatchSize = idealSize.coerceIn(14.dp, 40.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                            palette.forEach { originalColor ->
                                val effectiveColor = remaps[originalColor] ?: originalColor
                                val isSelected = selectedOriginal == originalColor
                                Box(
                                    modifier = Modifier
                                        .size(swatchSize)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(effectiveColor))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { selectSwatch(originalColor) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(28.dp))

        // --- Columna derecha: sliders profesionales arriba, rueda abajo ---
        Column(
            modifier = Modifier.fillMaxHeight().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedOriginal == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Sin colores para editar",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                // Brillo y Saturación son la MISMA información que ya
                // controla la posición del dedo en la rueda (radio =
                // saturación, y el brillo pinta el anillo) — tenerlos
                // también como sliders no es redundante en un panel
                // profesional: permite un ajuste fino de precisión
                // (0.01 en vez de depender del pulso del dedo en una
                // pantalla chica) sin perder la rueda como forma rápida
                // de elegir el matiz. Los tres controles escriben al
                // mismo estado (wheelHue/wheelSat/wheelVal), así que
                // mover un slider también mueve el punto de la rueda, y
                // viceversa — quedan siempre sincronizados.
                LabeledSlider(
                    label = "Brillo",
                    value = wheelVal,
                    range = 0f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { v ->
                    wheelVal = v
                    applyCurrentWheelColor()
                }
                LabeledSlider(
                    label = "Saturación",
                    value = wheelSat,
                    range = 0f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { s ->
                    wheelSat = s
                    applyCurrentWheelColor()
                }
                // "Opacidad" en vez de un slider de "suavizado": es el
                // control que de verdad tiene sentido para el usuario acá
                // — cuánto pisa el color nuevo al original — en vez de
                // exponer un parámetro técnico interno (radio de mezcla)
                // que no se entiende sin leer el código. Ver el
                // parámetro `intensity` de ColorExtraction.recolor.
                LabeledSlider(
                    label = "Opacidad",
                    value = recolorOpacity,
                    range = 0f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { o ->
                    recolorOpacity = o
                    applyLivePreviewAndScheduleCommit()
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    ColorWheelPicker(
                        hue = wheelHue,
                        saturation = wheelSat,
                        brightness = wheelVal,
                        onColorChange = { h, s ->
                            wheelHue = h
                            wheelSat = s
                            applyCurrentWheelColor()
                        },
                        modifier = Modifier.size(200.dp)
                    )
                }
            }
        }
        }
    }
}

/**
 * Panel de la pestaña "3D": extrusión real (ver Extrude3D) de la capa
 * completa — sirve para foto, PNG recortado, forma o texto ya
 * rasterizado por igual, no hace falta que sea un "sticker". Mismo
 * patrón de vista previa en vivo + guardado con debounce de 500ms que
 * [LayerColorEditPanel] usa para "Recolor": cada movimiento de slider
 * recalcula sobre una copia chica (liviano, sin lag) y sube esa vista
 * previa; medio segundo después del último movimiento, se recalcula
 * sobre la copia grande y se persiste como archivo nuevo (reusa
 * EditorViewModel.previewLayerRecolor/commitLayerRecolor tal cual —
 * son genéricos, no hacen nada específico de "recolorear").
 *
 * Nota honesta: como el cuerpo extruido puede sobresalir del cuadro
 * original de la imagen (por la rotación y la profundidad), el bitmap
 * resultante es más grande que el original con un margen alrededor —
 * evita que el efecto se vea recortado, a costa de que el tamaño en
 * píxeles de la capa cambie al aplicar el efecto.
 */
@Composable
private fun Extrude3DPanel(
    layer: Layer,
    context: android.content.Context,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    var liveBitmap by remember(layer.id) { mutableStateOf<Bitmap?>(null) }
    var fullBitmap by remember(layer.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(layer.id) { mutableStateOf(true) }

    var rotationX by remember(layer.id) { mutableStateOf(0f) }
    var rotationY by remember(layer.id) { mutableStateOf(0f) }
    var rotationZ by remember(layer.id) { mutableStateOf(0f) }
    var depth by remember(layer.id) { mutableStateOf(0.35f) }
    var bevel by remember(layer.id) { mutableStateOf(0.5f) }
    var materialOpacity by remember(layer.id) { mutableStateOf(1f) }
    var commitJob by remember(layer.id) { mutableStateOf<Job?>(null) }

    LaunchedEffect(layer.id, layer.sourceUri) {
        isLoading = true
        val small = withContext(Dispatchers.IO) {
            ImageDecoding.decodeSampledFromUri(context.contentResolver, layer.sourceUri, maxDimension = 260)
        }
        liveBitmap = small
        isLoading = false
        fullBitmap = withContext(Dispatchers.IO) {
            ImageDecoding.decodeSampledFromUri(context.contentResolver, layer.sourceUri, maxDimension = 1024)
        }
    }

    fun currentParams() = Extrude3D.Params(
        rotationXDeg = rotationX,
        rotationYDeg = rotationY,
        rotationZDeg = rotationZ,
        depth = depth,
        bevel = bevel,
        opacity = materialOpacity
    )

    fun applyLivePreviewAndScheduleCommit() {
        liveBitmap?.let { small ->
            viewModel.previewLayerRecolor(layer.id, Extrude3D.render(small, currentParams()))
        }
        commitJob?.cancel()
        commitJob = coroutineScope.launch {
            delay(500)
            val source = fullBitmap ?: liveBitmap ?: return@launch
            val params = currentParams()
            val rendered = withContext(Dispatchers.Default) { Extrude3D.render(source, params) }
            viewModel.commitLayerRecolor(layer.id, rendered)
        }
    }

    Column(modifier = modifier) {
        if (isLoading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = 2.dp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                LabeledSlider(
                    label = "Rotación X (arriba/abajo)",
                    value = rotationX,
                    range = -60f..60f,
                    valueLabel = { "${it.roundToInt()}°" }
                ) { rotationX = it; applyLivePreviewAndScheduleCommit() }

                LabeledSlider(
                    label = "Rotación Y (izquierda/derecha)",
                    value = rotationY,
                    range = -60f..60f,
                    valueLabel = { "${it.roundToInt()}°" }
                ) { rotationY = it; applyLivePreviewAndScheduleCommit() }

                LabeledSlider(
                    label = "Rotación Z (giro plano)",
                    value = rotationZ,
                    range = -180f..180f,
                    valueLabel = { "${it.roundToInt()}°" }
                ) { rotationZ = it; applyLivePreviewAndScheduleCommit() }

                LabeledSlider(
                    label = "Profundidad",
                    value = depth,
                    range = 0.05f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { depth = it; applyLivePreviewAndScheduleCommit() }

                LabeledSlider(
                    label = "Bisel (borde redondeado)",
                    value = bevel,
                    range = 0f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { bevel = it; applyLivePreviewAndScheduleCommit() }

                LabeledSlider(
                    label = "Opacidad del material",
                    value = materialOpacity,
                    range = 0.2f..1f,
                    valueLabel = { "${(it * 100).roundToInt()}%" }
                ) { materialOpacity = it; applyLivePreviewAndScheduleCommit() }
            }
        }
    }
}

/**
 * Header de 4 pestañas del modo "Editando imagen" (overlay sobre el
 * lienzo). "Recolor" y "3D" ya están implementadas y responden al
 * toque; las dos últimas siguen como cuadros reservados para próximas
 * herramientas (deshabilitados, sin acción al tocarlos).
 */
@Composable
private fun EditImageToolsHeader(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Recolor", "3D", "Próximamente", "Próximamente")
    val enabledCount = 2
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            val isEnabled = index < enabledCount
            val isActive = isEnabled && index == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isActive) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isActive) Color.White.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .then(
                        // Los cuadros todavía sin implementar quedan sin
                        // `clickable` para que no den feedback de "tocado"
                        // prometiendo algo que aún no hace nada.
                        if (isEnabled) Modifier.clickable { onTabSelected(index) } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.35f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

private fun layerBoundingQuadPx(
    translateX: Float,
    translateY: Float,
    scaleVal: Float,
    rotationDeg: Float,
    parallaxFactor: Float,
    layerWidthPx: Int,
    layerHeightPx: Int,
    boxWidthPx: Float,
    boxHeightPx: Float,
    // Estirado independiente de ancho/alto (manijas "estirar ancho" /
    // "estirar alto" del modo "Edición > Imagen") — 1f = sin estirar,
    // idéntico al comportamiento de antes de que existieran. Ver el
    // comentario completo en CameraFrame.kt.
    scaleXVal: Float = 1f,
    scaleYVal: Float = 1f
): List<Offset>? {
    if (boxWidthPx <= 0f || boxHeightPx <= 0f || layerWidthPx <= 0 || layerHeightPx <= 0) return null
    val viewportAspect = boxWidthPx / boxHeightPx
    val imageAspect = layerWidthPx.toFloat() / layerHeightPx.toFloat()

    val fitScaleX: Float
    val fitScaleY: Float
    if (imageAspect > viewportAspect) {
        fitScaleX = 2f
        fitScaleY = 2f * viewportAspect / imageAspect
    } else {
        fitScaleY = 2f
        fitScaleX = 2f * imageAspect / viewportAspect
    }
    // ARREGLADO: mismo motivo que en `hitTestLayerAt` — scaleXVal/scaleYVal
    // ahora pueden ser negativos (capa volteada). Si no se toma el
    // valor absoluto acá, un flip invertía qué esquina del marco de
    // selección caía "a la izquierda" o "a la derecha" en pantalla,
    // haciendo que el botón de eliminar/menú/manijas de estirar
    // saltaran de lado cada vez que la capa se voltea — confuso y nada
    // profesional. El marco de selección (y sus manijas) tiene que
    // quedarse SIEMPRE en el mismo lugar relativo a la pantalla,
    // volteada o no la imagen de adentro.
    val halfWidth = kotlin.math.abs(0.5f * fitScaleX * scaleVal * scaleXVal)
    val halfHeight = kotlin.math.abs(0.5f * fitScaleY * scaleVal * scaleYVal)
    val centerX = translateX * parallaxFactor
    val centerY = translateY * parallaxFactor
    val angleRad = Math.toRadians(rotationDeg.toDouble())
    val cosA = cos(angleRad).toFloat()
    val sinA = sin(angleRad).toFloat()

    // Esquinas en espacio local (sin rotar), orden: arriba-izq, arriba-der,
    // abajo-der, abajo-izq — para poder dibujar el contorno en un solo trazo.
    val localCorners = listOf(
        Offset(-halfWidth, halfHeight),
        Offset(halfWidth, halfHeight),
        Offset(halfWidth, -halfHeight),
        Offset(-halfWidth, -halfHeight)
    )
    return localCorners.map { local ->
        // Rotar (espacio local -> NDC ya rotado) y trasladar al centro real.
        val ndcX = local.x * cosA - local.y * sinA + centerX
        val ndcY = local.x * sinA + local.y * cosA + centerY
        // NDC (centro, -1..1, Y arriba) -> píxeles de pantalla (origen
        // arriba-izquierda) — el inverso exacto de lo que hace hitTestLayerAt.
        Offset(
            x = (ndcX + 1f) / 2f * boxWidthPx,
            y = (1f - ndcY) / 2f * boxHeightPx
        )
    }
}

/**
 * Determina qué capa está "debajo" de un toque en el preview, para poder
 * seleccionarla tocándola directamente en el canvas — no solo desde su
 * fila en el timeline.
 *
 * Prueba la CAJA COMPLETA del sprite ya transformada (posición, escala y
 * rotación en el plano) — el mismo margen/rectángulo que dibuja el marco
 * violeta de selección (ver [layerBoundingQuadPx]), replicando la misma
 * geometría que usa el motor GL para el plano z=0. A propósito NO se
 * prueba contra el contenido real (alfa) del PNG: la capa se selecciona
 * por su "lienzo" completo, sin importar el tamaño, forma o cuánto margen
 * transparente tenga la imagen — así el criterio de selección es siempre
 * el mismo rectángulo que el usuario VE como marco, sin sorpresas, y sin
 * el costo (y la demora) de decodificar cada PNG para leer su canal alfa.
 *
 * [preferredLayerId] (la capa ya seleccionada) se revisa PRIMERO: si el
 * toque cae dentro de su caja, se respeta esa selección y se puede
 * mover, sin importar qué otra capa esté encima en ese mismo punto —
 * igual que "arrastrar por el marco" en cualquier editor. Solo si el
 * toque NO alcanza esa caja se hace el barrido normal de más arriba
 * (zIndex mayor) a más abajo, devolviendo la primera capa cuya caja
 * llega hasta ahí.
 *
 * *Nota de arquitectura (Etapa 7):* esta función SÍ llama
 * `layer.cameraTrack.frameAt(...)` directo, a diferencia de los demás
 * lugares de este archivo (que piden `viewModel.frameAt(...)`). Es
 * intencional: `hitTestLayerAt` es una función pura de nivel de archivo,
 * sin `viewModel` en su firma a propósito — recibe `layers` como
 * parámetro y no depende de nada más, lo que la hace fácil de testear
 * sola y barata de llamar en cada movimiento del dedo. Pedirle un
 * `EditorViewModel` solo para interpolar un frame sería acoplarla a algo
 * que no necesita — el objetivo de sacar `engine.*` de la UI es evitar
 * que la UI POSEA o DECIDA sobre objetos del motor (ver Etapa 3), no
 * prohibir que una función pura reciba modelos de dominio inmutables
 * (`Layer`) y calcule con ellos. Esta función no posee nada: recibe,
 * calcula, devuelve.
 */
private fun hitTestLayerAt(
    tapOffset: Offset,
    boxWidthPx: Float,
    boxHeightPx: Float,
    layers: List<Layer>,
    playheadMs: Long,
    preferredLayerId: String? = null
): String? {
    if (boxWidthPx <= 0f || boxHeightPx <= 0f) return null
    val viewportAspect = boxWidthPx / boxHeightPx

    // Del punto tocado (píxeles de pantalla, origen arriba-izquierda) a
    // coordenadas NDC (origen centro, rango -1..1, eje Y hacia arriba) —
    // el mismo espacio en el que vive la geometría que arma el GL.
    val ndcX = (tapOffset.x / boxWidthPx) * 2f - 1f
    val ndcY = 1f - (tapOffset.y / boxHeightPx) * 2f

    fun isHit(layer: Layer): Boolean {
        if (!layer.visible || layer.locked || layer.widthPx <= 0 || layer.heightPx <= 0) return false
        val frame = layer.cameraTrack.frameAt(playheadMs)
        val imageAspect = layer.widthPx.toFloat() / layer.heightPx.toFloat()

        val fitScaleX: Float
        val fitScaleY: Float
        if (imageAspect > viewportAspect) {
            fitScaleX = 2f
            fitScaleY = 2f * viewportAspect / imageAspect
        } else {
            fitScaleY = 2f
            fitScaleX = 2f * imageAspect / viewportAspect
        }
        // El quad base del GL mide 1x1 (-0.5..0.5) antes de escalar, así
        // que el semi-ancho/alto real en NDC es la mitad de
        // fitScale*scale*scaleX/scaleY (scaleX/scaleY = estirado
        // independiente de las manijas "estirar ancho"/"estirar alto",
        // 1f si no se tocaron — ver CameraFrame.kt).
        //
        // ARREGLADO: `frame.scaleX`/`frame.scaleY` ahora pueden ser
        // NEGATIVOS (capa volteada/flip horizontal o vertical — ver
        // manijas "rightMid"/"bottomMid" más arriba). Sin el abs() de
        // acá, un halfWidth/halfHeight negativo hacía que
        // `abs(localX) <= halfWidth` fuera SIEMPRE falso (un valor
        // absoluto nunca es ≤ a un número negativo), es decir: una capa
        // volteada quedaba imposible de tocar/seleccionar en el lienzo.
        // El signo de scaleX/scaleY solo importa para el flip visual
        // (motor GL); para el test de "¿el toque cae dentro de la
        // caja?" siempre hace falta la MAGNITUD.
        val halfWidth = kotlin.math.abs(0.5f * fitScaleX * frame.scale * frame.scaleX)
        val halfHeight = kotlin.math.abs(0.5f * fitScaleY * frame.scale * frame.scaleY)

        val centerX = frame.translateX * layer.parallaxFactor
        val centerY = frame.translateY * layer.parallaxFactor

        // Deshacer la rotación de la capa para probar el punto en su
        // espacio local (sin rotar): rota el vector (toque - centro) por
        // el ángulo OPUESTO al de la capa.
        val dx = ndcX - centerX
        val dy = ndcY - centerY
        val angleRad = Math.toRadians(-frame.rotationDeg.toDouble())
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()
        val localX = dx * cosA - dy * sinA
        val localY = dx * sinA + dy * cosA

        return kotlin.math.abs(localX) <= halfWidth && kotlin.math.abs(localY) <= halfHeight
    }

    if (preferredLayerId != null) {
        val preferred = layers.firstOrNull { it.id == preferredLayerId }
        if (preferred != null && isHit(preferred)) return preferred.id
    }

    for (layer in layers.sortedByDescending { it.zIndex }) {
        if (layer.id == preferredLayerId) continue // ya se probó arriba
        if (isHit(layer)) return layer.id
    }
    return null
}

/**
 * Especificación numérica de la cuadrícula de composición: columnas y
 * filas totalmente INDEPENDIENTES entre sí. Se comparte entre TODAS las
 * formas de [GridShape] — cambiar de forma no resetea estos números, así
 * que pasar de "Rectángulo" a "Redondo" mantiene la misma densidad que
 * ya tenías configurada.
 */
private data class GridSpec(val columns: Int, val rows: Int)

/** Rango permitido para columnas y filas en el editor numérico del menú. */
private val GRID_AXIS_RANGE = 2..16

/**
 * Las formas de guía de composición disponibles — estándar real de apps
 * premium de edición (Lightroom, Photoshop y editores de video pro traen
 * variantes parecidas: cuadrícula, diagonal, diagonal cruzada, etc.).
 * Cada forma declara cómo se llaman sus ejes editables:
 *  - `axisXLabel` es el eje principal — columnas, "tamaño" de celda, o la
 *    única cantidad de líneas que tiene sentido en las diagonales de una
 *    sola dirección. Es null cuando la forma NO tiene ningún número que
 *    editar (caso de [CROSS], que siempre es la misma cruz centrada).
 *  - `axisYLabel` es null cuando la forma geométricamente solo tiene UN
 *    grado de libertad (o ninguno) — una diagonal en una sola dirección
 *    no tiene "filas" propias, son las mismas líneas nomás con más o
 *    menos cantidad — en ese caso [GridMenu] oculta el segundo stepper
 *    en vez de mostrar un control que no haría nada, que sería peor UX
 *    que directamente no mostrarlo.
 */
private enum class GridShape(val label: String, val axisXLabel: String?, val axisYLabel: String?) {
    RECTANGLE("Rectángulo", "Columnas", "Filas"),
    DIAGONAL_RIGHT("Diagonal ↗", "Líneas", null),
    DIAGONAL_LEFT("Diagonal ↖", "Líneas", null),
    DIAGONAL_CROSS("Diagonal cruzada", "Columnas", "Filas"),
    ROUND("Redondo", "Columnas", "Filas"),
    // Cuadrícula de celdas cuadradas parejas (estilo grilla de Blender),
    // pero plana/derecha — sin la perspectiva de "piso" del viewport 3D,
    // tal como pediste. Un solo eje ("Tamaño") define el lado de cada
    // celda; al ser cuadrada, ese mismo número gobierna ambas
    // direcciones — no necesita un segundo eje independiente.
    SQUARE("Cuadrado", "Tamaño", null),
    // Cruz de composición centrada — una sola línea vertical y una sola
    // horizontal cruzando el centro exacto del cuadro, sin densidad
    // configurable (no tiene ningún número que editar).
    CROSS("Cruz", null, null)
}

/** Orden en el que aparecen las formas en el carrusel de [GridMenu]. */
private val GRID_SHAPES = GridShape.entries

/**
 * Cuántas formas se ven a la vez en el carrusel — el mismo hueco que
 * antes ocupaban los 3 presets de densidad, para no agrandar el menú ni
 * un dp, tal cual pediste.
 */
// Rango de escala para las manijas de "Edición > Imagen" (esquina inf.
// derecha = escala uniforme, lateral derecha = solo ancho, inferior
// centro = solo alto) y para el pellizco de dos dedos. ARREGLADO: antes
// el rango iba de 0.2x a 5x — un tope bastante bajo que se sentía en
// cualquier uso real (agrandar una capa para un fondo, o achicarla para
// un detalle chico) y que ningún editor premium (CapCut, Canva,
// Photoshop) impone en la práctica. Ahora el rango es lo bastante amplio
// como para no sentirse como un límite en absoluto — solo existe un piso
// y un techo numéricos para que el motor de render nunca reciba una
// escala 0 o negativa (eso sí rompería el dibujo), no para restringir
// ningún uso real.
private const val MIN_LAYER_SCALE = 0.03f
private const val MAX_LAYER_SCALE = 40f

// --- Rotación libre 360°: antes `rotation` se recortaba con
// .coerceIn(-180f, 180f), lo que hacía que el giro se "trabara" en seco
// al llegar a ±180° (el dedo seguía moviéndose pero la capa ya no
// respondía). Pedido explícito: que gire completo, sin tope, en
// cualquier dirección. En vez de dejar crecer el float sin límite
// (impreciso a largo plazo y feo de mostrar en el slider de -180..180),
// se NORMALIZA a un rango equivalente de -180 a 180 después de cada
// actualización — mismo ángulo visual, mismo comportamiento fluido, sin
// límite de vueltas y sin que el número crezca indefinidamente.
private fun normalizeRotationDeg(angle: Float): Float {
    var a = angle % 360f
    if (a > 180f) a -= 360f
    if (a <= -180f) a += 360f
    return a
}

private const val GRID_CAROUSEL_VISIBLE = 3

/**
 * Dibuja las guías de `shape` con la densidad de `spec` dentro del
 * DrawScope actual — función ÚNICA y compartida entre el overlay real
 * del canvas del editor y las miniaturas de vista previa del carrusel
 * del menú, para que la vista previa sea SIEMPRE fiel a lo que se ve en
 * el canvas de verdad, sin sorpresas.
 */
private fun DrawScope.drawGridGuides(shape: GridShape, spec: GridSpec, color: Color, strokeWidth: Float) {
    val columns = spec.columns.coerceAtLeast(1)
    val rows = spec.rows.coerceAtLeast(1)
    when (shape) {
        GridShape.RECTANGLE -> {
            for (i in 1 until columns) {
                val x = size.width * i / columns
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
            }
            for (i in 1 until rows) {
                val y = size.height * i / rows
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
            }
        }
        GridShape.DIAGONAL_RIGHT -> drawDiagonalGuideLines(columns, ascending = true, color, strokeWidth)
        GridShape.DIAGONAL_LEFT -> drawDiagonalGuideLines(columns, ascending = false, color, strokeWidth)
        GridShape.DIAGONAL_CROSS -> {
            // Las dos direcciones juntas — Columnas controla un sentido,
            // Filas el otro, totalmente independientes entre sí, tal
            // como confirmaste que debía ser esta opción (aparte de las
            // diagonales de una sola dirección, no el resultado de
            // "activar las dos a la vez").
            drawDiagonalGuideLines(columns, ascending = true, color, strokeWidth)
            drawDiagonalGuideLines(rows, ascending = false, color, strokeWidth)
        }
        GridShape.ROUND -> {
            val cellW = size.width / columns
            val cellH = size.height / rows
            val radius = minOf(cellW, cellH) / 2f
            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    val center = Offset((c + 0.5f) * cellW, (r + 0.5f) * cellH)
                    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
                }
            }
        }
        GridShape.SQUARE -> {
            // Celdas realmente CUADRADAS (mismo lado en X e Y), a
            // diferencia de RECTANGLE que reparte columnas/filas
            // independientes y termina con celdas rectangulares si el
            // encuadre no es cuadrado. El lado sale de `columns` sobre el
            // ancho — mismo criterio de una grilla de referencia tipo
            // Blender, pero plana, sin la perspectiva del piso 3D.
            //
            // CENTRADO: el ancho SIEMPRE cae justo (cell = width/columns
            // es una división exacta), pero el mismo tamaño de celda
            // aplicado al alto casi nunca entra un número entero de
            // veces — antes eso dejaba SIEMPRE el "sobrante" pegado
            // contra el borde inferior (arrancaba a dibujar desde arriba
            // sin más), y la cuadrícula se veía corrida/descentrada
            // verticalmente. Ahora el sobrante se reparte MITAD arriba,
            // MITAD abajo (y lo mismo en X, por las dudas de que el
            // ancho no caiga perfecto por redondeo de punto flotante) —
            // igual que una hoja cuadriculada centrada en el marco, en
            // vez de pegada a una esquina.
            val cell = (size.width / columns).coerceAtLeast(1f)

            val colsFit = (size.width / cell).toInt().coerceAtLeast(1)
            val xMargin = (size.width - colsFit * cell) / 2f
            var x = xMargin + cell
            val xEnd = size.width - xMargin - cell * 0.5f
            while (x < xEnd) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
                x += cell
            }

            val rowsFit = (size.height / cell).toInt().coerceAtLeast(1)
            val yMargin = (size.height - rowsFit * cell) / 2f
            var y = yMargin + cell
            val yEnd = size.height - yMargin - cell * 0.5f
            while (y < yEnd) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
                y += cell
            }
        }
        GridShape.CROSS -> {
            // Cruz simple y fija: una línea vertical y una horizontal
            // cruzando el centro exacto del cuadro — sin densidad
            // configurable, por eso no usa `columns`/`rows` para nada.
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawLine(color, Offset(cx, 0f), Offset(cx, size.height), strokeWidth)
            drawLine(color, Offset(0f, cy), Offset(size.width, cy), strokeWidth)
        }
    }
}

/**
 * Rasteriza la cuadrícula de composición a un [Bitmap], reutilizando TAL
 * CUAL la misma función [drawGridGuides] que dibuja cualquiera de las 7
 * formas — no se duplica ni un poco la matemática de cada una, así el
 * resultado es pixel-idéntico a como se veía el overlay antes.
 *
 * BUG/COMPORTAMIENTO REAL corregido: antes la cuadrícula se dibujaba como
 * un Canvas de Compose flotando SIEMPRE por encima de las capas (dentro
 * del mismo Box, pero después del GLPreview en el orden de hijos = más
 * arriba en el z-order) — tapando imágenes, logos, texto, cualquier cosa
 * que hubiera debajo, algo que ningún programa profesional (Photoshop,
 * Lightroom, Premiere, CapCut) hace: en esos programas la guía de
 * composición vive EN el lienzo, detrás de las capas reales, y solo se
 * asoma por donde una capa es transparente o no llega a cubrir.
 *
 * Para lograr eso de verdad (no solo simularlo con transparencia) la
 * cuadrícula se rasteriza acá a un bitmap del mismo tamaño que el lienzo
 * y se sube al motor GL como una textura más — dibujada PRIMERO, antes
 * que cualquier capa real (ver GLRenderer.onDrawFrame). Así, el propio
 * pipeline de composición GL hace que cualquier píxel opaco de una capa
 * tape la cuadrícula donde corresponde, exactamente como en un canvas
 * profesional.
 */
private fun rasterizeGridBitmap(
    widthPx: Int,
    heightPx: Int,
    shape: GridShape,
    spec: GridSpec,
    color: Color,
    strokeWidthPx: Float
): Bitmap? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    // NO tocar setPremultiplied acá: android.graphics.Canvas EXIGE que su
    // bitmap destino esté premultiplicado si tiene alpha — usar
    // setPremultiplied(false) antes de dibujar hace que el propio
    // constructor de Canvas tire una RuntimeException ("trying to use a
    // non-premultiplied bitmap") apenas se activa la cuadrícula. El
    // arreglo real del color apagado NO va acá — va en el blend function
    // de OpenGL, en [LayerDrawer], que es quien tiene que saber que el
    // bitmap que le llega viene premultiplicado (ver comentario ahí).
    val androidCanvas = android.graphics.Canvas(bitmap)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = androidx.compose.ui.graphics.Canvas(androidCanvas),
        size = Size(widthPx.toFloat(), heightPx.toFloat())
    ) {
        drawGridGuides(shape, spec, color, strokeWidthPx)
    }
    return bitmap
}

/**
 * Color real con el que se dibujan las líneas de guía en el canvas —
 * blanco de toda la vida (mismo look de siempre) si el usuario no
 * activó un color personalizado, o el matiz elegido en
 * [GridLineColorBar] si lo activó. Se aplica IGUAL sin importar la
 * forma activa (Rectángulo, Cuadrado, Diagonales, etc.), tal como
 * pediste — es un ajuste independiente de la forma y de la densidad.
 * Misma [opacity] en los dos casos (blanco o color), para que activar
 * un color no cambie de golpe qué tan "fuerte" se ve la guía sobre el
 * video — solo cambia el tono, no la intensidad.
 *
 * `opacity` ANTES era un 0.4f fijo a fuego acá adentro, sin control
 * ninguno — con fondos muy saturados (el verde chroma-key por defecto)
 * y un matiz casi-complementario (magenta, violeta), 0.4 de mezcla
 * daba un resultado gris/apagado, matemáticamente correcto pero nada
 * vívido. Ahora [GridOpacitySlider] deja subirlo cuando hace falta más
 * presencia, sin tocar el matiz — 0.4f sigue siendo el default, así
 * que un proyecto guardado antes de este control se ve exactamente
 * igual al reabrirlo.
 */
private fun gridLineDrawColor(colorEnabled: Boolean, hue: Float, opacity: Float): Color {
    val clampedOpacity = opacity.coerceIn(0f, 1f)
    return if (colorEnabled) Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f).copy(alpha = clampedOpacity)
    else Color.White.copy(alpha = clampedOpacity)
}

/**
 * `count` líneas diagonales parejas a 45°, cubriendo todo el ancho y
 * alto del DrawScope de punta a punta (el barrido arranca antes del
 * borde izquierdo y termina después del derecho, para que ninguna
 * esquina quede sin cubrir — por eso el Canvas que llama a esto necesita
 * `clipToBounds()`). `ascending = true` = pendiente "/" (subiendo de
 * izquierda a derecha, la dirección que confirmaste como "Diagonal ↗");
 * `false` = pendiente "\" ("Diagonal ↖", la dirección contraria).
 */
private fun DrawScope.drawDiagonalGuideLines(count: Int, ascending: Boolean, color: Color, strokeWidth: Float) {
    val lines = count.coerceAtLeast(1)
    val span = size.width + size.height
    val step = span / lines
    for (i in 0 until lines) {
        val offset = step * (i + 0.5f) - size.height
        val start: Offset
        val end: Offset
        if (ascending) {
            start = Offset(offset, size.height)
            end = Offset(offset + size.height, 0f)
        } else {
            start = Offset(offset, 0f)
            end = Offset(offset + size.height, size.height)
        }
        drawLine(color, start, end, strokeWidth)
    }
}

/**
 * Los dos ejes de [GridSpec] que se pueden editar manualmente desde
 * [GridAxisInputDialog] — identifica cuál de los dos números está
 * editando el usuario en un momento dado. El TEXTO visible ya no vive
 * fijo acá adentro (antes era siempre "Columnas"/"Filas") — ahora
 * depende de la forma activa (ver [GridShape.axisXLabel] /
 * [GridShape.axisYLabel]), así que se resuelve aparte en [GridMenu].
 */
private enum class GridAxis { COLUMNS, ROWS }

/**
 * Paradas de color para la franja de [GridLineColorBar] — calcadas del
 * recorrido EXACTO de la franja de referencia que mandaste: arranca en
 * magenta, pasa por rojo, naranja, amarillo, verde, y termina en
 * celeste/azul clarito — SIN dar la vuelta completa al círculo de matiz
 * (no pasa por violeta ni azul puro antes de cortar, tal como se ve en
 * tu imagen). Por eso el recorrido no es un simple 0°→360°: arranca en
 * [HUE_START] (magenta, ~300°) y avanza [HUE_SPAN] grados (260°) —
 * suficiente para llegar bien pasado el verde hasta el celeste, pero
 * sin volver a entrar en la zona violeta/azul puro por el otro extremo.
 */
private const val HUE_START = 300f
private const val HUE_SPAN = 260f

/** fracción de la franja (0f–1f) → matiz HSV real (0°–360°), siguiendo el recorrido [HUE_START]→[HUE_START]+[HUE_SPAN]. */
private fun gridBarFractionToHue(fraction: Float): Float {
    val hue = (HUE_START + fraction.coerceIn(0f, 1f) * HUE_SPAN) % 360f
    return if (hue < 0f) hue + 360f else hue
}

/** matiz HSV real (0°–360°) → fracción de la franja (0f–1f), la inversa de [gridBarFractionToHue]. */
private fun gridBarHueToFraction(hue: Float): Float {
    var diff = (hue - HUE_START) % 360f
    if (diff < 0f) diff += 360f
    return (diff / HUE_SPAN).coerceIn(0f, 1f)
}

private val HUE_GRADIENT_STOPS: List<Color> = (0..12).map { step ->
    Color.hsv(gridBarFractionToHue(step / 12f), 1f, 1f)
}

/**
 * Barra de color de las líneas de guía — selector de matiz horizontal
 * calcado del que mostraste de Blender: franja arcoíris de ancho
 * completo con una línea vertical que marca el matiz elegido. Tocar en
 * cualquier punto de la franja O arrastrar el dedo por ella mueve esa
 * línea y actualiza el color EN VIVO — un solo gesto cubre las dos
 * formas de usarla, no hace falta soltar y volver a tocar para
 * "empezar" un arrastre.
 * Es INDEPENDIENTE de la forma activa y de los steppers de arriba — se
 * aplica igual sin importar qué figura esté eligiendo el usuario, tal
 * como pediste — por eso tiene su propio switch "activar/desactivar":
 * apagado, las líneas quedan blancas (el look de toda la vida); prendido,
 * usan el matiz elegido acá. La franja se puede seguir tocando con el
 * switch apagado (para dejar elegido un color de antemano), solo que se
 * ve atenuada mientras tanto.
 */
@Composable
private fun GridLineColorBar(
    enabled: Boolean,
    hue: Float,
    onToggle: () -> Unit,
    onHueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Color de línea",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier
                    .height(20.dp)
                    .scale(0.7f),
                colors = SwitchDefaults.colors(checkedTrackColor = BrandPurpleLight)
            )
        }

        // Ancho medido de la franja (en px) — necesario para convertir
        // la posición X del dedo en un matiz de 0 a 360.
        var barWidthPx by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                // Atenuada mientras el switch está apagado — mismo
                // lenguaje visual que un control deshabilitado — pero
                // sigue siendo tocable, para poder dejar el matiz
                // elegido de antemano sin tener que prender el switch
                // primero.
                .alpha(if (enabled) 1f else 0.35f)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(HUE_GRADIENT_STOPS))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .onSizeChanged { barWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    // Gesto único y manual (no detectTapGestures +
                    // detectHorizontalDragGestures por separado) para
                    // que el PRIMER toque ya mueva la línea a esa
                    // posición, y arrastrar desde ahí la siga
                    // actualizando en el mismo gesto — sin esto, un
                    // toque simple (sin arrastre) no movería nada,
                    // porque los detectores de arrastre solo reaccionan
                    // después de cruzar el umbral de movimiento.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (barWidthPx > 0f) {
                            onHueChange(gridBarFractionToHue(down.position.x / barWidthPx))
                        }
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            change.consume()
                            if (barWidthPx > 0f) {
                                onHueChange(gridBarFractionToHue(change.position.x / barWidthPx))
                            }
                        }
                    }
                }
        ) {
            // Línea indicadora — el "handle" que marca el matiz elegido,
            // calcada de la referencia de Blender.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .offset {
                        val x = if (barWidthPx > 0f) gridBarHueToFraction(hue) * barWidthPx else 0f
                        IntOffset((x - 1.dp.toPx()).roundToInt(), 0)
                    }
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.35f))
            )
        }
    }
}

/** Rango de grosor permitido para las líneas de guía, en dp. */
private const val GRID_THICKNESS_MIN_DP = 0.5f
private const val GRID_THICKNESS_MAX_DP = 6f

/**
 * Slider de grosor de línea — mismo lenguaje visual que [GridLineColorBar]
 * (franja de 26dp, mismo radio y borde, mismo patrón de gesto tap+drag en
 * un solo detector), pero en vez de matiz controla el grosor real que usa
 * [drawGridGuides] (0.5dp a 6dp). Se aplica por igual a CUALQUIER forma
 * activa — por eso vive entre el carrusel de formas y los steppers de
 * Columnas/Filas: un solo control para todas las formas, en vez de
 * repetirlo por cada una.
 */
@Composable
private fun GridThicknessSlider(
    thicknessDp: Float,
    onThicknessChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Grosor",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                // Un decimal alcanza — el usuario arrastra por sensación
                // visual, no tipea un número exacto (a diferencia de los
                // steppers de Columnas/Filas, este control no tiene
                // diálogo numérico).
                "${(thicknessDp * 10).roundToInt() / 10f} dp",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // Thumb más chico y prolijo (14dp, antes 18dp) — un thumb de ese
        // tamaño sobre una franja de 26dp de alto se leía "gordo"/poco
        // premium; este tamaño es el mismo lenguaje que sliders nativos
        // de iOS/Android (thumb claramente más chico que el alto total
        // del control táctil).
        val thumbDiameter = 14.dp
        val thumbRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { (thumbDiameter / 2).toPx() }

        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val fraction = ((thicknessDp - GRID_THICKNESS_MIN_DP) / (GRID_THICKNESS_MAX_DP - GRID_THICKNESS_MIN_DP))
            .coerceIn(0f, 1f)

        // BUG REAL corregido: antes el thumb (18dp) vivía DENTRO de un Box
        // con .clip(RoundedCornerShape(6.dp)) aplicado a esa misma cadena
        // de modifiers — como el thumb se centra sobre la posición X del
        // valor y a fraction=1 su mitad derecha queda más allá del ancho
        // del track, esa mitad se recortaba contra el borde redondeado
        // ("se ve cortado el final del slider"). Ahora el thumb es un
        // Box HERMANO sin clip (nunca se recorta) y el track reserva
        // `thumbDiameter / 2` de padding a cada lado — el mismo patrón
        // que un Slider de Material — así el thumb siempre queda 100%
        // visible, en cualquier extremo, sin salirse tampoco del menú.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = thumbDiameter / 2)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    // Gesto único (no tap + drag por separado): el primer
                    // toque ya mueve el thumb a esa posición, y arrastrar
                    // desde ahí lo sigue actualizando en el mismo gesto —
                    // idéntico patrón que la franja de color de arriba.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (trackWidthPx > 0f) {
                            val f = (down.position.x / trackWidthPx).coerceIn(0f, 1f)
                            onThicknessChange(GRID_THICKNESS_MIN_DP + f * (GRID_THICKNESS_MAX_DP - GRID_THICKNESS_MIN_DP))
                        }
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            change.consume()
                            if (trackWidthPx > 0f) {
                                val f = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                                onThicknessChange(GRID_THICKNESS_MIN_DP + f * (GRID_THICKNESS_MAX_DP - GRID_THICKNESS_MIN_DP))
                            }
                        }
                    }
                }
        ) {
            // Riel delgado (4dp, antes 26dp macizo) — el mismo criterio
            // fino/premium que usan Instagram, CapCut o Lightroom para
            // este tipo de control: una línea sutil, no una barra gruesa.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(2.dp))
            )
            // Relleno hasta el valor actual, sobre el mismo riel delgado.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(fraction.coerceAtLeast(0.001f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrandPurpleLight)
            )
            // Thumb circular — el "handle" que marca el grosor elegido.
            // Vive FUERA del riel clippeado (ver comentario arriba) para
            // no recortarse nunca, y el padding horizontal del Box padre
            // garantiza que, aun centrado en los extremos, no se salga
            // del menú.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val x = if (trackWidthPx > 0f) fraction * trackWidthPx else 0f
                        IntOffset((x - thumbRadiusPx).roundToInt(), 0)
                    }
                    .size(thumbDiameter)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
            )
        }
    }
}

/** Rango de opacidad permitido para las líneas de guía. 0.05 (no 0) para que
 *  siempre quede algo visible — un slider que llegue a "invisible del
 *  todo" es indistinguible de un bug para el usuario. */
private const val GRID_OPACITY_MIN = 0.05f
private const val GRID_OPACITY_MAX = 1f

/**
 * Slider de opacidad de línea — MISMO lenguaje visual y patrón de gesto
 * que [GridThicknessSlider] (riel de 4dp, thumb de 14dp, tap+drag en un
 * solo detector), pero controla [gridLineDrawColor]'s alpha en vez del
 * grosor. Se agregó porque, con un fondo bien saturado (el verde
 * chroma-key por defecto) y un matiz casi-complementario elegido en
 * [GridLineColorBar] (magenta, violeta), la alpha fija de 0.4 que había
 * antes mezclaba demasiado con el fondo y el color se veía apagado/
 * grisáceo — correcto matemáticamente (es blending, no bug), pero nada
 * vívido. Vive debajo de Grosor (pedido puntual), arriba de los
 * steppers de Columnas/Filas.
 */
@Composable
private fun GridOpacitySlider(
    opacity: Float,
    onOpacityChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Opacidad",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${(opacity * 100).roundToInt()}%",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        val thumbDiameter = 14.dp
        val thumbRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { (thumbDiameter / 2).toPx() }

        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val fraction = ((opacity - GRID_OPACITY_MIN) / (GRID_OPACITY_MAX - GRID_OPACITY_MIN))
            .coerceIn(0f, 1f)

        // Mismo patrón táctil que [GridThicknessSlider]: gesto único
        // (tap ya mueve el thumb a esa posición, arrastrar lo sigue
        // actualizando en el mismo gesto, sin esperar a cruzar un
        // umbral) y thumb HERMANO del riel clippeado (no adentro), para
        // que nunca se recorte contra el borde redondeado en los
        // extremos.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = thumbDiameter / 2)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (trackWidthPx > 0f) {
                            val f = (down.position.x / trackWidthPx).coerceIn(0f, 1f)
                            onOpacityChange(GRID_OPACITY_MIN + f * (GRID_OPACITY_MAX - GRID_OPACITY_MIN))
                        }
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            change.consume()
                            if (trackWidthPx > 0f) {
                                val f = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                                onOpacityChange(GRID_OPACITY_MIN + f * (GRID_OPACITY_MAX - GRID_OPACITY_MIN))
                            }
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(2.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(fraction.coerceAtLeast(0.001f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrandPurpleLight)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val x = if (trackWidthPx > 0f) fraction * trackWidthPx else 0f
                        IntOffset((x - thumbRadiusPx).roundToInt(), 0)
                    }
                    .size(thumbDiameter)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
            )
        }
    }
}

/**
 * Ventana premium que abre el texto "Edición" de la barra superior,
 * al lado del ícono Grabar. Por ahora tiene una sola opción, "Imagen",
 * con una casilla cuadrada propia (ic_checkbox_unchecked / _checked —
 * no el Switch/Checkbox default de Material3, para que el check tenga
 * identidad visual propia y consistente con el resto de íconos SVG a
 * medida de la app).
 *
 * Ancho: antes fijo en 168dp (mucho más ancho que "Imagen" + su
 * casilla), así que centrado bajo un ancla angosta como el texto
 * "Edición" sobraba de sobra hacia la derecha, tapando el ícono Grabar
 * que está pegado a su derecha. Ahora envuelve su contenido
 * (wrapContentWidth) con paddings reducidos — la fila queda tan angosta
 * como su propio contenido ("Imagen" + casilla nomás).
 *
 * Posición: [BelowAnchorCenteredPopupPositionProvider], igual que
 * [GridMenu] — centrado bajo el ancla. ARREGLADO: hubo un intento previo
 * de alinearlo por el borde izquierdo en vez de centrarlo, pensando que
 * así se evitaba el solape con Grabar — pero al ya ser angosto
 * (wrapContentWidth), alinear a la izquierda dejaba el popup visualmente
 * corrido hacia la derecha de "Edición" (su borde izquierdo coincidía
 * con el de "Edición", pero como el popup es más ancho que el texto,
 * todo el resto se notaba desplazado). Centrado, con este ancho
 * compacto, el popup ya no llega a alcanzar a Grabar (la diferencia de
 * ancho entre el popup y "Edición" es bastante menor que los 16dp de
 * separación hacia el ícono), así que se puede volver al mismo criterio
 * que el resto de menús de la barra sin reintroducir el bug original.
 */
@Composable
private fun EdicionMenu(
    imagenChecked: Boolean,
    onImagenToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        popupPositionProvider = BelowAnchorCenteredPopupPositionProvider(gapPx = 8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(12.dp)),
            color = SurfaceTintedElevated,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onImagenToggle() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "Imagen",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    painter = painterResource(
                        id = if (imagenChecked) R.drawable.ic_checkbox_checked else R.drawable.ic_checkbox_unchecked
                    ),
                    contentDescription = if (imagenChecked) "Imagen activada" else "Activar Imagen",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Menú que se despliega justo debajo del ícono de cuadrícula de la barra
 * de arriba. Con estándar de apps profesionales premium (Figma,
 * Photoshop, Lightroom, editores de video pro):
 *  - Switch arriba para prender/apagar sin perder el ajuste guardado.
 *  - Carrusel de FORMAS (Rectángulo, Diagonal ↗, Diagonal ↖, Diagonal
 *    cruzada, Redondo, Cuadrado, Cruz) — 3 visibles a la vez, mismo
 *    hueco de siempre, pero con flechas ‹ › a los costados Y arrastrable
 *    con el dedo como una rueda real: el carrusel sigue el dedo 1:1
 *    mientras arrastrás (nada de esperar a cruzar un umbral fijo para
 *    reaccionar) y se asienta con un resorte suave al soltar.
 *  - Debajo, los steppers numéricos de la forma ACTIVA — con las dos
 *    formas de ajustar que ya pediste (– / + y tocar el número). Si la
 *    forma solo tiene un eje con sentido geométrico (las diagonales de
 *    una sola dirección), el segundo stepper directamente no se
 *    muestra.
 *  - Al pie de todo, [GridLineColorBar]: una barra de color INDEPENDIENTE
 *    de la forma/densidad — se aplica igual sin importar qué figura esté
 *    activa — con su propio switch "activar/desactivar" (si está
 *    apagada, las líneas quedan blancas, el look de toda la vida) y la
 *    franja arcoíris arrastrable para elegir el matiz, calcada del
 *    selector de color de Blender que mandaste de referencia.
 * Todo en una Column angosta (no una Row ancha), y el Popup se mantiene
 * en el mismo ancho de siempre (184dp) — no se agranda ni se achica.
 */
@Composable
private fun GridMenu(
    enabled: Boolean,
    shape: GridShape,
    spec: GridSpec,
    lineColorEnabled: Boolean,
    lineHue: Float,
    lineThicknessDp: Float,
    lineOpacity: Float,
    onShapeSelect: (GridShape) -> Unit,
    onAxisChange: (GridSpec) -> Unit,
    onToggle: () -> Unit,
    onLineColorToggle: () -> Unit,
    onLineHueChange: (Float) -> Unit,
    onThicknessChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    // Qué eje se está escribiendo a mano ahora mismo (o ninguno). El
    // diálogo numérico vive en un Dialog aparte — una ventana propia,
    // focusable, para que el teclado del sistema funcione normal — así
    // que puede convivir sin problema con el Popup no-focusable del menú.
    var editingAxis by remember { mutableStateOf<GridAxis?>(null) }

    // Índice del primer elemento visible del carrusel de formas — va de
    // 0 a (cantidad de formas − formas visibles). Arrastrar con el dedo
    // O tocar las flechas ‹ › mueven este MISMO estado; un solo estado,
    // dos formas de cambiarlo, tal como pediste.
    val maxCarouselStart = (GRID_SHAPES.size - GRID_CAROUSEL_VISIBLE).coerceAtLeast(0)
    // Arranca centrado en la forma YA SELECCIONADA, no siempre en 0 —
    // antes, cada vez que se cerraba y volvía a abrir el menú, el
    // Popup se desmontaba por completo y este estado se perdía, así
    // que el carrusel "saltaba" de vuelta al principio y la forma
    // elegida (si no era de las primeras 3) quedaba fuera de vista,
    // dando la sensación de que "se movía" o se perdía la selección.
    // Ahora, al volver a abrir, el carrusel arranca ya posicionado
    // para que la forma activa se vea de entrada, sin tener que
    // buscarla arrastrando.
    var carouselStart by remember {
        val selectedIndex = GRID_SHAPES.indexOf(shape).coerceAtLeast(0)
        val centeredStart = selectedIndex - GRID_CAROUSEL_VISIBLE / 2
        mutableIntStateOf(centeredStart.coerceIn(0, maxCarouselStart))
    }
    val coroutineScope = rememberCoroutineScope()

    Popup(
        popupPositionProvider = BelowAnchorCenteredPopupPositionProvider(gapPx = 8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            modifier = Modifier
                .width(184.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(14.dp)),
            color = SurfaceTintedElevated,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Cuadrícula",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier
                            .height(20.dp)
                            .scale(0.7f),
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandPurpleLight)
                    )
                }

                // --- Carrusel de formas: flecha izquierda, 3 formas
                // visibles (arrastrables), flecha derecha. El gesto de
                // arrastre se detecta sobre TODO el Row del medio (no
                // solo sobre las 3 cajitas individuales) para que
                // arrastrar desde cualquier punto de esa franja funcione,
                // como una rueda de verdad.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GridCarouselArrow(
                        iconRes = R.drawable.ic_chevron_left,
                        contentDescription = "Formas anteriores",
                        enabled = carouselStart > 0,
                        onClick = { if (carouselStart > 0) carouselStart-- }
                    )

                    // Ancho medido del carril (en px) — se usa para saber
                    // cuánto mide cada forma y así poder mover el
                    // carrusel a la MISMA velocidad que el dedo (1:1),
                    // en vez de esperar a cruzar un umbral fijo antes de
                    // reaccionar. Vive en mutableFloatStateOf (no en un
                    // array) porque si cambia (rotación, resize) el
                    // carrusel debe redibujarse con el nuevo ancho.
                    var railWidthPx by remember { mutableFloatStateOf(0f) }
                    // Offset visual EN VIVO del carrusel mientras se
                    // arrastra — a diferencia de la versión anterior
                    // (que solo reaccionaba tras cruzar 26dp de un tirón,
                    // sintiéndose "trabada"/con lag), acá las 3 formas
                    // se deslizan pegadas al dedo desde el primer
                    // milímetro de arrastre, como una rueda de verdad.
                    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .onSizeChanged { railWidthPx = it.width.toFloat() }
                            .pointerInput(maxCarouselStart) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        // Al soltar, vuelve a 0 con un
                                        // resorte suave — el "asentado"
                                        // final típico de un carrusel
                                        // premium (Lightroom/Photos).
                                        val start = dragOffsetPx
                                        coroutineScope.launch {
                                            animate(
                                                initialValue = start,
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            ) { value, _ -> dragOffsetPx = value }
                                        }
                                    },
                                    onDragCancel = { dragOffsetPx = 0f },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        val itemWidthPx = railWidthPx / GRID_CAROUSEL_VISIBLE
                                        // Si todavía no se midió el carril
                                        // (primer frame), no hay con qué
                                        // calcular el paso — se ignora
                                        // ese evento nomás, el siguiente
                                        // ya va a tener el ancho posta.
                                        if (itemWidthPx > 0f) {
                                            var offset = dragOffsetPx + dragAmount
                                            // Arrastrar a la izquierda
                                            // avanza el carrusel (revela
                                            // formas siguientes); a la
                                            // derecha retrocede. El
                                            // `while` (no `if`) es a
                                            // propósito: un arrastre largo
                                            // en un solo gesto puede pasar
                                            // varias formas de una, como
                                            // una rueda real — pero acá el
                                            // offset visual se ajusta en
                                            // el momento, sin saltos, para
                                            // que las formas nunca
                                            // "salten" de golpe.
                                            while (offset <= -itemWidthPx && carouselStart < maxCarouselStart) {
                                                carouselStart++
                                                offset += itemWidthPx
                                            }
                                            while (offset >= itemWidthPx && carouselStart > 0) {
                                                carouselStart--
                                                offset -= itemWidthPx
                                            }
                                            // Efecto "goma" en los
                                            // extremos: si ya no hay más
                                            // formas para revelar de ese
                                            // lado, el arrastre se frena
                                            // en vez de deslizarse
                                            // libremente al vacío.
                                            val rubberBandLimit = itemWidthPx * 0.35f
                                            if (carouselStart == 0 && offset > rubberBandLimit) offset = rubberBandLimit
                                            if (carouselStart == maxCarouselStart && offset < -rubberBandLimit) offset = -rubberBandLimit
                                            dragOffsetPx = offset
                                        }
                                    }
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (slot in 0 until GRID_CAROUSEL_VISIBLE) {
                            val shapeOption = GRID_SHAPES[carouselStart + slot]
                            GridShapeOption(
                                shape = shapeOption,
                                spec = spec,
                                isSelected = enabled && shape == shapeOption,
                                lineColorEnabled = lineColorEnabled,
                                lineHue = lineHue,
                                onClick = { onShapeSelect(shapeOption) },
                                modifier = Modifier
                                    .weight(1f)
                                    .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                            )
                        }
                    }

                    GridCarouselArrow(
                        iconRes = R.drawable.ic_chevron_right,
                        contentDescription = "Más formas",
                        enabled = carouselStart < maxCarouselStart,
                        onClick = { if (carouselStart < maxCarouselStart) carouselStart++ }
                    )
                }

                // Grosor de línea — INDEPENDIENTE de la forma/densidad, se
                // aplica igual sin importar qué figura esté activa (mismo
                // criterio que [GridLineColorBar] más abajo). Va acá, entre
                // el carrusel de formas y los steppers de Columnas/Filas,
                // tal como pediste.
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                GridThicknessSlider(
                    thicknessDp = lineThicknessDp,
                    onThicknessChange = onThicknessChange
                )

                // Opacidad de línea — pedida explícitamente para ir JUSTO
                // debajo del slider de Grosor (mismo criterio: control
                // independiente de la forma, se aplica igual a
                // cualquiera de las 7). Va ACÁ y no al pie junto a
                // [GridLineColorBar] porque afecta tanto al blanco por
                // defecto como al color elegido — es una propiedad de
                // "cuánto se nota la línea", no del color en sí.
                GridOpacitySlider(
                    opacity = lineOpacity,
                    onOpacityChange = onOpacityChange
                )

                // Los steppers (y el divisor de arriba) solo aparecen si
                // la forma activa tiene algún número que editar — [CROSS]
                // no tiene ninguno (es siempre la misma cruz centrada),
                // así que en ese caso el menú se queda corto, sin dejar
                // un divisor colgado arriba de nada.
                val axisXLabel = shape.axisXLabel
                if (axisXLabel != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    GridAxisStepper(
                        label = axisXLabel,
                        value = spec.columns,
                        onValueChange = { onAxisChange(spec.copy(columns = it)) },
                        onValueTap = { editingAxis = GridAxis.COLUMNS }
                    )
                    // El segundo eje solo se muestra si la forma activa
                    // realmente lo usa geométricamente (ver comentario en
                    // GridShape.axisYLabel más arriba).
                    if (shape.axisYLabel != null) {
                        GridAxisStepper(
                            label = shape.axisYLabel,
                            value = spec.rows,
                            onValueChange = { onAxisChange(spec.copy(rows = it)) },
                            onValueTap = { editingAxis = GridAxis.ROWS }
                        )
                    }
                }

                // Color de las líneas — independiente de la forma y de
                // los steppers de arriba: se aplica igual sin importar
                // qué figura esté activa (Rectángulo, Cuadrado,
                // Diagonales, etc.), por eso vive SIEMPRE al pie del
                // menú, incluso con [GridShape.CROSS] que no tiene
                // ningún stepper.
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                GridLineColorBar(
                    enabled = lineColorEnabled,
                    hue = lineHue,
                    onToggle = onLineColorToggle,
                    onHueChange = onLineHueChange
                )
            }
        }
    }

    // El diálogo de entrada manual vive AFUERA del Popup (no anidado
    // adentro) — así es una ventana propia e independiente que se puede
    // enfocar para que el teclado del sistema aparezca sin pelear con las
    // propiedades no-focusable del Popup del menú.
    val axisBeingEdited = editingAxis
    if (axisBeingEdited != null) {
        // shape.axisXLabel solo puede ser null para CROSS, que no tiene
        // stepper ninguno — así que nunca llega acá con editingAxis
        // seteado; el "?:" es solo una red de seguridad para que el
        // compilador no exija un valor no-nulo que en la práctica
        // siempre está presente en este punto del flujo.
        val axisLabel = if (axisBeingEdited == GridAxis.COLUMNS) (shape.axisXLabel ?: "")
            else (shape.axisYLabel ?: shape.axisXLabel ?: "")
        GridAxisInputDialog(
            label = axisLabel,
            initialValue = if (axisBeingEdited == GridAxis.COLUMNS) spec.columns else spec.rows,
            range = GRID_AXIS_RANGE,
            onDismiss = { editingAxis = null },
            onConfirm = { newValue ->
                onAxisChange(
                    if (axisBeingEdited == GridAxis.COLUMNS) spec.copy(columns = newValue)
                    else spec.copy(rows = newValue)
                )
                editingAxis = null
            }
        )
    }
}

/** Flechita chica del carrusel de formas — mismo lenguaje visual que [GridStepperButton], apenas más compacta (20dp) para dejarle espacio a las 3 formas. */
@Composable
private fun GridCarouselArrow(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f),
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * Diálogo compacto para escribir a mano la cantidad de líneas de la
 * forma activa — la segunda forma de ajustar el valor que pidió el
 * usuario, además de los botones – / +. Mismo patrón visual que
 * [RenameProjectDialog] (Dialog + Surface + OutlinedTextField), para
 * consistencia con el resto de la app. Solo acepta dígitos y solo deja
 * confirmar si el número entra en [range] — mismo límite que ya
 * respetan los botones – / + del stepper, para que nunca haya manera de
 * terminar con una cuadrícula fuera de rango.
 */
@Composable
private fun GridAxisInputDialog(
    label: String,
    initialValue: Int,
    range: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initialValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed in range
    val focusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Entre ${range.first} y ${range.last}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        // Solo dígitos, máximo 2 caracteres — el rango
                        // permitido (GRID_AXIS_RANGE) nunca llega a 3
                        // cifras, así que no hace falta más.
                        if (new.length <= 2 && new.all { it.isDigit() }) text = new
                    },
                    singleLine = true,
                    isError = text.isNotEmpty() && !isValid,
                    supportingText = {
                        if (text.isNotEmpty() && !isValid) {
                            Text("Ingresá un número entre ${range.first} y ${range.last}")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (isValid) onConfirm(parsed!!) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { if (isValid) onConfirm(parsed!!) }, enabled = isValid) { Text("Aplicar") }
                }
            }
        }
    }

    // Foco automático al abrir, para que el teclado aparezca de una sin
    // que el usuario tenga que tocar el campo — mismo criterio "menos
    // toques" que el resto de la app.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * Una forma del carrusel del menú — dibuja EN VIVO su propia guía con
 * [drawGridGuides] usando la densidad actual (`spec`) Y el color de
 * línea actual (`lineColorEnabled`/`lineHue`), en vez de un ícono
 * estático o un blanco fijo, para que la vista previa sea EXACTAMENTE
 * fiel a cómo se va a ver en el canvas real si se elige — antes la
 * miniatura ignoraba el color elegido en [GridLineColorBar] y siempre
 * se veía blanca, lo cual contradecía la fidelidad que promete este
 * mismo comentario; quedó corregido. Cuadrada y compacta para que las 3
 * visibles quepan holgadas en un menú angosto.
 */
@Composable
private fun GridShapeOption(
    shape: GridShape,
    spec: GridSpec,
    isSelected: Boolean,
    lineColorEnabled: Boolean,
    lineHue: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) BrandPurpleLight.copy(alpha = 0.28f)
                else Color.White.copy(alpha = 0.06f)
            )
            .then(
                if (isSelected) Modifier.border(1.5.dp, BrandPurpleLight, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            val previewAlpha = if (isSelected) 0.95f else 0.7f
            val lineColor = if (lineColorEnabled) {
                Color.hsv(lineHue.coerceIn(0f, 360f), 1f, 1f).copy(alpha = previewAlpha)
            } else {
                Color.White.copy(alpha = previewAlpha)
            }
            drawGridGuides(shape, spec, lineColor, 1.dp.toPx())
        }
    }
}

/**
 * Stepper numérico compacto (– valor +) para un solo eje de la forma
 * activa — así el usuario "manipula los valores en números" como pidió.
 * TRES formas de ajustar el valor, todas disponibles al mismo tiempo:
 *  1. Los botones – / + de siempre, para tocar y afinar de a uno — y
 *     ahora además con AUTO-REPETICIÓN: mantenerlos apretados dispara el
 *     valor en ráfaga, cada vez más rápido, hasta soltar (ver
 *     [GridStepperButton]) — mismo estándar que cualquier stepper
 *     numérico de software profesional (Premiere, los +/- de iOS/macOS).
 *  2. Tocar el número en sí (`onValueTap`) abre [GridAxisInputDialog]
 *     para escribirlo directo con el teclado — pedido puntual del
 *     usuario para poner un valor exacto sin tocar +/- muchas veces.
 *  3. Arrastrar el dedo VERTICALMENTE sobre el número: arriba sube,
 *     abajo baja, a razón de un paso cada pocos dp recorridos — y si el
 *     gesto se suelta con velocidad (un "flick" rápido, no un arrastre
 *     lento) se suma un empujón extra de pasos en esa misma dirección,
 *     así un toque-y-suelta rápido hacia arriba/abajo también mueve el
 *     valor aunque el recorrido haya sido corto, tal como pediste.
 * El número vive en su propia "cajita" con fondo sutil (en vez de texto
 * suelto) para que se vea, a simple vista, que es tocable/arrastrable —
 * mismo lenguaje visual que los botones – / + de al lado.
 * Acotado a [GRID_AXIS_RANGE]; los botones – / + se deshabilitan solos
 * al llegar al límite, mismo criterio visual que el resto de la barra
 * superior (undo/redo atenuados cuando no hay nada que hacer).
 */
@Composable
private fun GridAxisStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueTap: () -> Unit
) {
    // Referencia siempre-actualizada al valor actual, para leerla desde
    // adentro del gesto de arrastre vertical (que vive en un
    // pointerInput(Unit) de vida larga — no se reinicia en cada
    // recomposición, así que leer `value` directo ahí adentro daría un
    // valor viejo "congelado" del primer arranque del gesto).
    val currentValue = rememberUpdatedState(value)
    // Mismo motivo para `onValueChange`: es una FUNCIÓN, no solo un
    // dato, y closures armadas en la composición de más arriba (en
    // GridMenu) capturan el `spec` de ESE momento. Sin este
    // rememberUpdatedState, el gesto de arrastre de abajo llamaría para
    // siempre a la primera versión de `onValueChange` que vio al
    // montarse — con un `spec` viejo adentro — y terminaría PISANDO el
    // otro eje (por ejemplo, arrastrar Columnas después de haber tocado
    // Filas revertiría Filas al valor que tenía cuando se montó este
    // stepper). Bug real, ya corregido acá.
    val latestOnValueChange = rememberUpdatedState(onValueChange)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(modifier = Modifier.weight(1f))
        val canDecrease = value > GRID_AXIS_RANGE.first
        val canIncrease = value < GRID_AXIS_RANGE.last
        GridStepperButton(
            iconRes = R.drawable.ic_remove,
            contentDescription = "Menos $label",
            enabled = canDecrease,
            onClick = { if (currentValue.value > GRID_AXIS_RANGE.first) onValueChange(currentValue.value - 1) }
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable(onClick = onValueTap)
                .pointerInput(Unit) {
                    // Sensibilidad del arrastre: cuántos px de recorrido
                    // vertical equivalen a un paso — compacto a
                    // propósito (la cajita mide apenas 28dp), así que no
                    // hace falta arrastrar muy lejos para notar el
                    // cambio.
                    val pxPerStep = 12.dp.toPx()
                    // Umbral de velocidad (px/s) a partir del cual un
                    // gesto se considera un "flick" rápido en vez de un
                    // arrastre lento — cada tramo de esta velocidad por
                    // encima del umbral suma un paso extra al soltar.
                    val flingPxPerSecondPerStep = 900f
                    var dragAccumulatorPx = 0f
                    var appliedSteps = 0
                    var baseValue = currentValue.value
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragAccumulatorPx = 0f
                            appliedSteps = 0
                            baseValue = currentValue.value
                            velocityTracker.resetTracking()
                        },
                        onDragEnd = {
                            val flingVelocityY = velocityTracker.calculateVelocity().y
                            if (abs(flingVelocityY) > flingPxPerSecondPerStep) {
                                // Negativo = flick hacia arriba (sube);
                                // positivo hacia abajo (baja) — mismo
                                // sentido que el arrastre normal de más
                                // arriba.
                                val flingSteps = (-flingVelocityY / flingPxPerSecondPerStep).toInt()
                                if (flingSteps != 0) {
                                    val newValue = (currentValue.value + flingSteps).coerceIn(GRID_AXIS_RANGE)
                                    latestOnValueChange.value(newValue)
                                }
                            }
                        },
                        onDragCancel = { }
                    ) { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        dragAccumulatorPx += dragAmount
                        // Arriba (recorrido acumulado negativo) sube el
                        // valor; abajo lo baja — mismo sentido "natural"
                        // que arrastrar un fader hacia arriba para subir.
                        val targetSteps = (-dragAccumulatorPx / pxPerStep).toInt()
                        if (targetSteps != appliedSteps) {
                            appliedSteps = targetSteps
                            val newValue = (baseValue + appliedSteps).coerceIn(GRID_AXIS_RANGE)
                            latestOnValueChange.value(newValue)
                        }
                    }
                }
                .width(28.dp)
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                value.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        GridStepperButton(
            iconRes = R.drawable.ic_add,
            contentDescription = "Más $label",
            enabled = canIncrease,
            onClick = { if (currentValue.value < GRID_AXIS_RANGE.last) onValueChange(currentValue.value + 1) }
        )
    }
}

/**
 * Botón chico y redondo para el stepper — 24dp, para no ensanchar el
 * menú. Con AUTO-REPETICIÓN al mantener apretado: el primer toque aplica
 * un paso al instante (como siempre), y si el dedo se queda apretando
 * más de ~380ms, arranca a repetir solo — cada repetición un poco más
 * rápido que la anterior (hasta un piso de ~45ms entre pasos) — hasta
 * que se suelta. Mismo estándar de cualquier stepper numérico de
 * software profesional (los +/- de iOS/macOS, los steppers de Premiere y
 * Photoshop). El círculo se ve un toque más claro mientras está
 * apretado, para que el auto-repeat tenga feedback visual de que el
 * botón sigue "activo".
 */
@Composable
private fun GridStepperButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // Referencia siempre-actualizada a onClick — el gesto de auto-repeat
    // vive en un pointerInput de vida larga (keyed por `enabled`, no por
    // `onClick`, para que CADA paso que dispara onClick() y cambia el
    // valor no reinicie el gesto a mitad de un apretón sostenido).
    val latestOnClick = rememberUpdatedState(onClick)
    var isPressed by remember { mutableStateOf(false) }
    val backgroundAlpha = when {
        !enabled -> 0.03f
        isPressed -> 0.18f
        else -> 0.08f
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = backgroundAlpha))
            .then(
                if (enabled) {
                    Modifier.pointerInput(enabled) {
                        awaitEachGesture {
                            awaitFirstDown()
                            isPressed = true
                            try {
                                latestOnClick.value()
                                // Espera antes de empezar a repetir —
                                // así un toque simple y rápido dispara
                                // UN solo paso, no una ráfaga.
                                var delayMs = 380L
                                while (true) {
                                    val released = withTimeoutOrNull(delayMs) { waitForUpOrCancellation() }
                                    if (released != null) break
                                    latestOnClick.value()
                                    // Acelera un poco en cada repetición,
                                    // con un piso para que nunca quede
                                    // descontrolado.
                                    delayMs = (delayMs * 0.72f).toLong().coerceAtLeast(45L)
                                }
                            } finally {
                                isPressed = false
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.3f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Centra el Popup horizontalmente bajo su ancla (no alineado al borde
 * izquierdo, como el popup angosto de capa en TimelineView.kt —
 * ese sirve para paneles angostos pegados a un ícono chico; este menú es
 * más ancho que el ícono de cuadrícula que lo abre, así que centrarlo se
 * ve mejor). Recorta contra los bordes de la pantalla para que nunca
 * quede cortado si el ícono está cerca del borde derecho.
 */
private class BelowAnchorCenteredPopupPositionProvider(
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        var x = anchorCenterX - popupContentSize.width / 2
        x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = anchorBounds.bottom + gapPx
        return IntOffset(x, y)
    }
}

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
 * en el timeline. Ya no dice "Agregar pista" (término de producción
 * musical) — la opción "+ Imagen" es el título/acción en sí. Audio /
 * Modelo 3D / Grabar audio se suman como filas nuevas más adelante.
 */
@Composable
private fun AddTrackDialog(onDismiss: () -> Unit, onImportImageClick: () -> Unit) {
    // Tamaño fijo y cuadrado (ancho == alto aprox.), como el popup
    // compacto de FL Studio Mobile — antes usaba fillMaxWidth() y se
    // estiraba al ancho de pantalla completo, quedando una franja
    // horizontal en vez de una ventana cuadrada.
    val dialogSize = 260.dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .width(dialogSize)
                    .height(dialogSize)
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Antes decía "Agregar pista" (término de producción
                    // musical, no aplica a esta app de películas) y el "+"
                    // vivía suelto como botón flotante en la esquina de la
                    // pantalla. Ahora "+ Imagen" es directamente la opción
                    // clickeable, sin ese título — a futuro, Audio / Modelo
                    // 3D / Grabar audio se agregan como filas debajo de esta.
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onImportImageClick)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Imagen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
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
