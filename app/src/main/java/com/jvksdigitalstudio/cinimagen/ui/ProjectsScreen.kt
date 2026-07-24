package com.jvksdigitalstudio.cinimagen.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jvksdigitalstudio.cinimagen.R
import com.jvksdigitalstudio.cinimagen.data.DESCRIPTION_MAX_LENGTH
import com.jvksdigitalstudio.cinimagen.data.MoveDirection
import com.jvksdigitalstudio.cinimagen.data.ProjectStorage
import com.jvksdigitalstudio.cinimagen.data.ProjectSummary
import com.jvksdigitalstudio.cinimagen.data.takeCodePoints
import com.jvksdigitalstudio.cinimagen.engine.AspectRatioPreset
import com.jvksdigitalstudio.cinimagen.viewmodel.AVAILABLE_PROJECT_FPS
import com.jvksdigitalstudio.cinimagen.viewmodel.DEFAULT_PROJECT_FPS
import com.jvksdigitalstudio.cinimagen.viewmodel.MAX_PROJECT_DURATION_MS
import com.jvksdigitalstudio.cinimagen.viewmodel.MIN_PROJECT_DURATION_MS
import kotlinx.coroutines.launch

/**
 * Relación de aspecto ancho/alto de la tarjeta de portada en "Mis
 * proyectos" — la usa tanto [ProjectCard] como [CoverAdjustDialog], para
 * que el recuadro de ajuste de portada muestre EXACTAMENTE el mismo
 * encuadre que después se ve en la tarjeta (nada de sorpresas al guardar).
 */
const val PROJECT_CARD_ASPECT_RATIO = 9f / 14f

/**
 * Pantalla de inicio: la biblioteca de proyectos guardados, estilo "hub" de
 * cualquier editor de video profesional (CapCut, Premiere Rush, LumaFusion).
 * Cada tarjeta muestra la miniatura REAL del proyecto —con el look
 * cinematográfico ya aplicado, generada por [com.jvksdigitalstudio.cinimagen.engine.ThumbnailRenderer]—
 * no un ícono genérico.
 *
 * [refreshKey] se incrementa desde fuera (MainActivity) cada vez que se
 * vuelve del editor, para refrescar la lista sin necesidad de un
 * ViewModel propio para esta pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projectStorage: ProjectStorage,
    refreshKey: Int,
    onOpenProject: (String) -> Unit,
    onCreateProject: (id: String, name: String, aspect: AspectRatioPreset, durationMs: Long, fps: Int) -> Unit,
    // Lanza el selector de imágenes del sistema para elegir una portada
    // personalizada; vive en MainActivity (nivel Activity) porque el picker
    // de SAF necesita registrarse ahí — ver pickCoverLauncher.
    onPickCoverImage: (onPicked: (android.net.Uri) -> Unit) -> Unit,
    // Exporta el proyecto como archivo .olyze y abre la hoja para
    // compartir con cualquier app instalada (WhatsApp, Telegram, Drive,
    // correo, Bluetooth...) — vive en MainActivity porque lanzar un
    // Intent.ACTION_SEND necesita un Activity Context.
    onShareProject: (projectId: String, projectName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var localTick by remember { mutableStateOf(0) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var menuTargetId by remember { mutableStateOf<String?>(null) }
    // A diferencia de renameTarget/deleteTarget (que abren un Dialog flotante
    // centrado en toda la pantalla), el panel de info se dibuja DENTRO de la
    // tarjeta correspondiente — por eso acá alcanza con guardar el id: cada
    // ProjectCard decide mostrar su propio overlay comparando contra este id.
    var infoTargetId by remember { mutableStateOf<String?>(null) }
    // Imagen recién elegida desde el picker del sistema, pendiente de que
    // el usuario la encuadre en CoverAdjustDialog antes de guardarla como
    // portada — ver comentario grande en [onPickCoverImage] de MainActivity
    // y en [CoverAdjustDialog].
    var coverAdjustState by remember { mutableStateOf<Pair<String, android.net.Uri>?>(null) }

    fun reload() {
        scope.launch {
            projects = projectStorage.listProjects()
            isLoading = false
        }
    }

    LaunchedEffect(refreshKey, localTick) { reload() }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = Color.White,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cinimagen", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Mis proyectos",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            // El botón flotante "+" solo tiene sentido una vez que ya existe
            // al menos un proyecto — con la lista vacía, el CTA central
            // "Crear tu primer proyecto" ya cubre esa acción; mostrar los
            // dos a la vez es redundante. En cuanto se crea el primero, el
            // FAB aparece y queda fijo en su lugar (comportamiento nativo
            // del Scaffold: no se mueve al scrollear la grilla).
            if (projects.isNotEmpty()) {
                FloatingActionButton(onClick = { showCreateDialog = true }) { Text("+") }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                projects.isEmpty() -> EmptyProjectsState(
                    modifier = Modifier.align(Alignment.Center),
                    onCreateProject = { showCreateDialog = true }
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            menuExpanded = menuTargetId == project.id,
                            onOpenMenu = { menuTargetId = project.id },
                            onCloseMenu = { menuTargetId = null },
                            onOpen = { onOpenProject(project.id) },
                            onRename = { renameTarget = project },
                            onDuplicate = {
                                scope.launch {
                                    projectStorage.duplicateProject(project.id, "Copia de ${project.name}")
                                    localTick++
                                }
                            },
                            onDelete = { deleteTarget = project },
                            onSetCover = {
                                // Ya NO se guarda directo a ciegas: se abre
                                // el editor de encuadre (CoverAdjustDialog,
                                // más abajo) y recién cuando el usuario
                                // confirma ahí se persiste la portada.
                                onPickCoverImage { uri -> coverAdjustState = project.id to uri }
                            },
                            onShare = { onShareProject(project.id, project.name) },
                            onRemoveCover = {
                                scope.launch {
                                    projectStorage.removeCoverImage(project.id)
                                    localTick++
                                }
                            },
                            onMoveUp = {
                                scope.launch {
                                    projectStorage.moveProject(project.id, MoveDirection.UP)
                                    localTick++
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    projectStorage.moveProject(project.id, MoveDirection.DOWN)
                                    localTick++
                                }
                            },
                            onShowInfo = { infoTargetId = project.id },
                            onCloseInfo = { infoTargetId = null },
                            showInfo = infoTargetId == project.id
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, aspect, durationMs, fps ->
                showCreateDialog = false
                val newId = projectStorage.newProjectId()
                onCreateProject(newId, name.ifBlank { "Proyecto sin título" }, aspect, durationMs, fps)
            }
        )
    }

    renameTarget?.let { target ->
        EditProjectDialog(
            project = target,
            onDismiss = { renameTarget = null },
            onConfirm = { name, description ->
                renameTarget = null
                scope.launch {
                    projectStorage.renameProject(
                        target.id,
                        name.ifBlank { target.name },
                        description.takeCodePoints(DESCRIPTION_MAX_LENGTH)
                    )
                    localTick++
                }
            }
        )
    }

    coverAdjustState?.let { (targetProjectId, uri) ->
        CoverAdjustDialog(
            imageUri = uri,
            onDismiss = { coverAdjustState = null },
            onConfirm = { croppedBitmap ->
                coverAdjustState = null
                scope.launch {
                    projectStorage.setCoverImageBitmap(targetProjectId, croppedBitmap)
                    croppedBitmap.recycle()
                    localTick++
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Eliminar \"${target.name}\"?") },
            text = { Text("Esta acción no se puede deshacer. Se borrarán todas sus capas, keyframes e imágenes guardadas.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        projectStorage.deleteProject(target.id)
                        localTick++
                    }
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun EmptyProjectsState(modifier: Modifier = Modifier, onCreateProject: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(32.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_film),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Todavía no tenés proyectos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Creá uno y convertí tus imágenes en una escena con cámara animada.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onCreateProject) { Text("Crear tu primer proyecto") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: ProjectSummary,
    menuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSetCover: () -> Unit,
    onShare: () -> Unit,
    onRemoveCover: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onShowInfo: () -> Unit,
    onCloseInfo: () -> Unit,
    showInfo: Boolean
) {
    val context = LocalContext.current
    val hasDescription = project.description.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PROJECT_CARD_ASPECT_RATIO)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = onOpenMenu)
    ) {
        // Portada: prioriza la elegida a mano por el usuario sobre la
        // miniatura auto-generada — ver [ProjectSummary.displayImageFile].
        val coverImage = project.displayImageFile
        if (coverImage != null) {
            // Coil cachea por defecto usando la ruta del archivo como clave,
            // así que sobrescribir "cover.jpg"/"thumbnail.jpg" EN EL MISMO
            // PATH (que es justo lo que hace ProjectStorage al cambiar la
            // portada) no invalida esa caché — la tarjeta seguía mostrando
            // la imagen vieja hasta reabrir la app. Se arregla incluyendo la
            // fecha de última modificación del archivo en la clave de caché
            // de memoria/disco: apenas cambia el contenido, cambia la clave,
            // y Coil vuelve a decodificar en vez de servir el bitmap stale.
            val cacheKey = remember(coverImage.path, coverImage.lastModified()) {
                "${coverImage.path}#${coverImage.lastModified()}"
            }
            AsyncImage(
                model = remember(cacheKey) {
                    ImageRequest.Builder(context)
                        .data(coverImage)
                        .memoryCacheKey(cacheKey)
                        .diskCacheKey(cacheKey)
                        .build()
                },
                contentDescription = project.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_image_placeholder),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Cabecera superior: scrim + TÍTULO grande y bien destacado —
        // ligeramente más grande que el resto de la UI de la tarjeta (es
        // portada, no una fila de lista), pero sin llegar a un tamaño
        // desproporcionado que rompa el balance de la miniatura.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                    )
                )
                .padding(horizontal = 32.dp, vertical = 12.dp)
        ) {
            Text(
                project.name,
                color = Color.White,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Pie: SOLO aparece cuando el proyecto tiene descripción — la
        // metadata técnica (fecha/capas) ya no vive acá, se movió entera al
        // panel del ícono ⓘ para no duplicar información en la portada. Si
        // no hay descripción, no se dibuja nada abajo. Si la hay, un
        // degradado oscuro que arranca a la mitad de la tarjeta hacia abajo
        // le da fondo propio; el bloque "Acerca de" + descripción se ancla
        // ARRIBA de esa mitad (no pegado al borde inferior de la tarjeta),
        // para que quede un margen de aire antes de que termine la portada.
        if (hasDescription) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.2f),
                                Color.Black.copy(alpha = 0.78f),
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Column {
                    Text(
                        "Acerca de",
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        project.description,
                        color = Color.White.copy(alpha = 0.95f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Botón de menú "⋮" arriba a la derecha de la tarjeta.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Surface(
                modifier = Modifier.clickable { onOpenMenu() },
                color = Color.Black.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more_vert),
                    contentDescription = "Opciones de \"${project.name}\"",
                    tint = Color.White,
                    modifier = Modifier.padding(6.dp).size(18.dp)
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onCloseMenu) {
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_edit), contentDescription = null) },
                    onClick = { onCloseMenu(); onRename() }
                )
                DropdownMenuItem(
                    text = { Text(if (project.coverImageFile != null) "Cambiar portada" else "Agregar portada") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_image_placeholder), contentDescription = null) },
                    onClick = { onCloseMenu(); onSetCover() }
                )
                if (project.coverImageFile != null) {
                    DropdownMenuItem(
                        text = { Text("Quitar portada", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                painterResource(id = R.drawable.ic_close),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { onCloseMenu(); onRemoveCover() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Compartir") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_share), contentDescription = null) },
                    onClick = { onCloseMenu(); onShare() }
                )
                DropdownMenuItem(
                    text = { Text("Duplicar") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_copy), contentDescription = null) },
                    onClick = { onCloseMenu(); onDuplicate() }
                )
                DropdownMenuItem(
                    text = { Text("Mover arriba") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_chevron_up), contentDescription = null) },
                    enabled = project.canMoveUp,
                    onClick = { onCloseMenu(); onMoveUp() }
                )
                DropdownMenuItem(
                    text = { Text("Mover abajo") },
                    leadingIcon = { Icon(painterResource(id = R.drawable.ic_chevron_down), contentDescription = null) },
                    enabled = project.canMoveDown,
                    onClick = { onCloseMenu(); onMoveDown() }
                )
                DropdownMenuItem(
                    text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            painterResource(id = R.drawable.ic_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { onCloseMenu(); onDelete() }
                )
            }
        }

        // Ícono "ⓘ" — info del proyecto — abajo a la derecha, DENTRO de la
        // portada, encima del scrim. Se oculta mientras el panel de info
        // está abierto (lo reemplaza el botón "cerrar" del propio panel).
        if (!showInfo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Surface(
                    modifier = Modifier.clickable { onShowInfo() },
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info),
                        contentDescription = "Información de \"${project.name}\"",
                        tint = Color.White,
                        modifier = Modifier.padding(5.dp).size(16.dp)
                    )
                }
            }
        }

        // Panel de detalles del proyecto: a diferencia de los demás diálogos
        // de la pantalla (que son un Dialog centrado en TODA la pantalla),
        // este se dibuja DENTRO del mismo Box de la tarjeta — hereda su
        // recorte de esquinas redondeadas y queda contenido exactamente
        // dentro de la portada correspondiente, nunca flotando afuera.
        if (showInfo) {
            ProjectInfoOverlay(project = project, onDismiss = onCloseInfo)
        }
    }
}

/**
 * Overlay de info que se dibuja DENTRO de la portada del proyecto (ver
 * comentario en el llamado desde [ProjectCard]) — no es un Dialog del
 * sistema, es contenido normal dentro del mismo Box recortado a las
 * esquinas redondeadas de la tarjeta. `.clickable(onClick = {})` sin más
 * que un lambda vacío consume cualquier toque sobre el panel para que no
 * se filtre hacia el `combinedClickable` de la tarjeta de atrás (que abre
 * el proyecto).
 */
@Composable
private fun ProjectInfoOverlay(project: ProjectSummary, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = {})
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(end = 20.dp)
        ) {
            Text(
                project.name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            InfoDetailRow("Creado", formatFullDate(project.createdAtMs))
            InfoDetailRow("Editado", formatFullDate(project.updatedAtMs))
            InfoDetailRow("Tamaño", formatFileSize(project.sizeBytes))
            InfoDetailRow("Capas", "${project.layerCount}")
            InfoDetailRow("Duración", formatProjectDuration(project.projectDurationMs))
            InfoDetailRow("Formato", aspectRatioLabel(project.aspectRatio))
            InfoDetailRow("FPS", "${project.fps} fps")
        }

        // Botón "cerrar" arriba a la derecha del panel — mismo lugar donde
        // vivía el menú "⋮", que se oculta mientras este panel está abierto.
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable { onDismiss() },
            color = Color.White.copy(alpha = 0.12f),
            shape = androidx.compose.foundation.shape.CircleShape
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_close),
                contentDescription = "Cerrar información",
                tint = Color.White,
                modifier = Modifier.padding(5.dp).size(14.dp)
            )
        }
    }
}

/** Fila compacta "etiqueta: valor" para el panel de info dentro de la portada. */
@Composable
private fun InfoDetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun aspectRatioLabel(aspectRatio: String): String = when (aspectRatio) {
    "REELS" -> "9:16"
    "SQUARE" -> "1:1"
    "WIDESCREEN" -> "16:9"
    else -> aspectRatio
}

/** Tamaño de archivo legible (B/KB/MB/GB), para el panel de info del proyecto. */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** Fecha y hora completas (no relativas), para el panel de info del proyecto. */
private fun formatFullDate(atMs: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("es")).format(java.util.Date(atMs))

/**
 * Diálogo de "Renombrar": ahora también edita la descripción corta que se
 * ve en la tarjeta. El campo arranca con la descripción real del proyecto
 * (vacío si todavía no tiene una) — no se rellena con ningún texto de
 * ejemplo fabricado; el placeholder es sólo una pista genérica que
 * desaparece apenas el usuario empieza a escribir. Se recorta siempre a
 * [DESCRIPTION_MAX_LENGTH] caracteres, con un contador visible.
 */
@Composable
private fun EditProjectDialog(
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf(project.name) }
    var description by remember { mutableStateOf(project.description) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Renombrar proyecto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))

                Text("Título", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Nombre del proyecto") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("Acerca de", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = description,
                    // takeCodePoints (no .take) para no partir un emoji por
                    // la mitad al llegar al límite — ver su doc en
                    // ProjectStorage.kt. El teclado emoji del sistema
                    // funciona acá sin ningún ajuste extra: Compose ya lo
                    // soporta de forma nativa en cualquier TextField.
                    onValueChange = { description = it.takeCodePoints(DESCRIPTION_MAX_LENGTH) },
                    placeholder = { Text("Describe un resumen de la película") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${description.codePointCount(0, description.length)}/$DESCRIPTION_MAX_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(name.trim(), description.trim()) }) { Text("Guardar") }
                }
            }
        }
    }
}

/**
 * Diálogo de creación de un proyecto nuevo: nombre, formato de salida
 * (9:16/1:1/16:9) y duración total — estas dos últimas quedan fijadas
 * desde el arranque porque son propiedades del PROYECTO en sí (afectan
 * todo el timeline, no solo el archivo final), a diferencia de la
 * calidad de bitrate/resolución, que sigue siendo pura configuración de
 * exportación y se elige recién al exportar (ver el ícono de exportar
 * dentro del editor).
 */
@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, aspect: AspectRatioPreset, durationMs: Long, fps: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(AspectRatioPreset.REELS) }
    var durationMs by remember { mutableStateOf(8000L) }
    var fps by remember { mutableStateOf(DEFAULT_PROJECT_FPS) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .widthIn(max = 420.dp)
            ) {
                Text("Nuevo proyecto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Proyecto sin título") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text("Formato de salida", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectRatioPreset.entries.forEach { preset ->
                        SelectableChip(
                            label = preset.label,
                            selected = preset == aspect,
                            onClick = { aspect = preset },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    aspect.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                Text("Cuadros por segundo (fps)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AVAILABLE_PROJECT_FPS.forEach { preset ->
                        SelectableChip(
                            label = "${preset}fps",
                            selected = preset == fps,
                            onClick = { fps = preset },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Text(
                    if (fps > 30) "Movimiento más fluido — archivo final más pesado" else "Estándar, el más liviano para compartir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                LabeledSlider(
                    "Duración del proyecto",
                    durationMs.toFloat(),
                    MIN_PROJECT_DURATION_MS.toFloat()..MAX_PROJECT_DURATION_MS.toFloat(),
                    valueLabel = { formatProjectDuration(it.toLong()) },
                    onValueChange = { durationMs = it.toLong() }
                )
                Text(
                    "Podés ajustarla más adelante desde el editor · hasta 7 minutos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(name.trim(), aspect, durationMs, fps) }) { Text("Crear") }
                }
            }
        }
    }
}

/** mm:ss para la duración del proyecto (hasta 7 min); segundos con un decimal por debajo de 1 minuto. */
private fun formatProjectDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return if (totalSeconds < 60) {
        "%.1fs".format(ms / 1000f)
    } else {
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
