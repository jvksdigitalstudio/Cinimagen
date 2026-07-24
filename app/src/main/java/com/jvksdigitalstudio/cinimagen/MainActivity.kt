package com.jvksdigitalstudio.cinimagen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jvksdigitalstudio.cinimagen.data.LayerRepository
import com.jvksdigitalstudio.cinimagen.data.ProjectStorage
import com.jvksdigitalstudio.cinimagen.engine.AspectRatioPreset
import com.jvksdigitalstudio.cinimagen.ui.EditorScreen
import com.jvksdigitalstudio.cinimagen.ui.ProjectsScreen
import com.jvksdigitalstudio.cinimagen.ui.theme.CinimagenGradient
import com.jvksdigitalstudio.cinimagen.ui.theme.CinimagenTheme
import com.jvksdigitalstudio.cinimagen.viewmodel.EditorViewModel
import com.jvksdigitalstudio.cinimagen.viewmodel.EditorViewModelFactory
import kotlinx.coroutines.launch

/** Mime type propio del archivo exportado ".olyze" — también declarado en AndroidManifest.xml. */
private const val OLYZE_MIME_TYPE = "application/x-olyze"

/**
 * Punto de entrada de la UI. Maneja una navegación deliberadamente simple
 * de dos pantallas (sin agregar Navigation Compose como dependencia nueva):
 * `openProjectId == null` → "Mis proyectos"; si no, el editor de ese
 * proyecto. `openProjectId` se guarda con `rememberSaveable` para que,
 * si el sistema mata el proceso en background y lo recrea, el usuario
 * vuelva exactamente al proyecto que tenía abierto, no a la lista.
 */
class MainActivity : ComponentActivity() {

    // Los pickers del sistema (SAF) se registran una sola vez a nivel de
    // Activity; qué hacer con el resultado se decide en el momento del
    // lanzamiento vía estas referencias, para poder apuntar siempre al
    // ViewModel del proyecto que esté abierto en ese instante.
    private var onImagesPicked: ((List<Uri>) -> Unit)? = null
    private var onBackgroundPicked: ((Uri?) -> Unit)? = null
    private var onReplacementPicked: ((Uri?) -> Unit)? = null
    private var onAudioPicked: ((Uri?) -> Unit)? = null
    private var onCoverPicked: ((Uri?) -> Unit)? = null

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onImagesPicked?.invoke(uris) }

    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onBackgroundPicked?.invoke(uri) }

    private val pickReplacementLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onReplacementPicked?.invoke(uri) }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onAudioPicked?.invoke(uri) }

    // Portada personalizada de un proyecto, elegida desde "Mis proyectos"
    // (menú "⋮" → Portada), no desde el editor — por eso vive acá al lado
    // de los demás pickers de nivel Activity en vez de en EditorScreen.
    private val pickCoverLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> onCoverPicked?.invoke(uri) }

    // Uri de un archivo ".olyze" recibido desde AFUERA de la app —
    // alguien lo compartió por WhatsApp/Telegram/Drive/correo/etc. y el
    // usuario tocó "Abrir con Cinimagen", o lo abrió directo desde el
    // explorador de archivos. Se guarda como propiedad de clase (no dentro
    // de setContent) para que tanto onCreate como onNewIntent puedan
    // completarla por igual — Compose la observa como cualquier State.
    private var incomingImportUri: Uri? by mutableStateOf(null)

    /**
     * Extrae el Uri del archivo compartido/abierto, sin importar si llegó
     * como ACTION_VIEW (abrir directo, típico al tocar el archivo en un
     * explorador o en un chat) o ACTION_SEND (otra app lo compartió hacia
     * Cinimagen desde su propia hoja "Compartir").
     */
    private fun extractImportUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode="singleTask" (ver AndroidManifest.xml) hace que la
        // Activity ya viva en memoria reciba acá el intent nuevo, en vez de
        // levantar una segunda instancia — así importar un archivo con la
        // app ya abierta funciona igual que con la app cerrada.
        extractImportUri(intent)?.let { incomingImportUri = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layerRepository = LayerRepository(applicationContext)
        val projectStorage = ProjectStorage(applicationContext)
        extractImportUri(intent)?.let { incomingImportUri = it }

        setContent {
            var openProjectId by rememberSaveable { mutableStateOf<String?>(null) }
            var projectsRefreshKey by remember { mutableStateOf(0) }
            var pendingProjectName by rememberSaveable { mutableStateOf("Proyecto sin título") }
            var pendingProjectAspect by rememberSaveable { mutableStateOf(AspectRatioPreset.REELS) }
            var pendingProjectDurationMs by rememberSaveable { mutableStateOf(8000L) }
            var pendingProjectFps by rememberSaveable { mutableStateOf(30) }

            CinimagenTheme {
                // Fondo de marca único para TODA la app: degradado morado
                // (dominante) → azul. Surface se deja transparente para
                // que ninguna pantalla lo tape con un color sólido; cada
                // Scaffold (ProjectsScreen, EditorScreen) usa
                // containerColor = Color.Transparent para heredarlo.
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CinimagenGradient),
                    color = Color.Transparent,
                    contentColor = Color.White
                ) {
                    // Importación de un ".olyze" recibido de afuera: se
                    // procesa acá (nivel raíz de la navegación) para que
                    // funcione sin importar si en ese momento se está
                    // viendo "Mis proyectos" o el editor de otro proyecto.
                    // Al terminar, abre directo el proyecto recién
                    // importado — igual que crear uno nuevo.
                    val pendingImportUri = incomingImportUri
                    LaunchedEffect(pendingImportUri) {
                        val uri = pendingImportUri ?: return@LaunchedEffect
                        incomingImportUri = null
                        val importedId = projectStorage.importProjectZip(uri)
                        if (importedId != null) {
                            projectsRefreshKey++
                            openProjectId = importedId
                            Toast.makeText(
                                this@MainActivity, "Proyecto importado ✅ — ya lo podés editar", Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Ese archivo no es un proyecto de Cinimagen válido (.olyze)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    val projectId = openProjectId
                    if (projectId == null) {
                        ProjectsScreen(
                            projectStorage = projectStorage,
                            refreshKey = projectsRefreshKey,
                            onOpenProject = { id -> openProjectId = id },
                            onCreateProject = { id, name, aspect, durationMs, fps ->
                                pendingProjectName = name
                                pendingProjectAspect = aspect
                                pendingProjectDurationMs = durationMs
                                pendingProjectFps = fps
                                openProjectId = id
                            },
                            onPickCoverImage = { onPicked ->
                                onCoverPicked = { uri -> if (uri != null) onPicked(uri) }
                                pickCoverLauncher.launch(arrayOf("image/*"))
                            },
                            onShareProject = { id, name ->
                                lifecycleScope.launch {
                                    val zipFile = projectStorage.exportProjectZip(id)
                                    if (zipFile == null) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "No se pudo preparar \"$name\" para compartir",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val uri = FileProvider.getUriForFile(
                                        this@MainActivity, "$packageName.fileprovider", zipFile
                                    )
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = OLYZE_MIME_TYPE
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, name)
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            "Te comparto mi proyecto de Cinimagen \"$name\" — abrilo con la app Cinimagen para seguir editándolo en tu teléfono."
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    // Intent.createChooser lista TODAS las apps instaladas
                                    // que puedan recibir un archivo adjunto — WhatsApp,
                                    // Telegram, Instagram, Gmail, Drive, Bluetooth, etc. —
                                    // sin tener que integrar cada red social a mano.
                                    startActivity(Intent.createChooser(sendIntent, "Compartir \"$name\""))
                                }
                            }
                        )
                    } else {
                        val factory = remember(projectId) {
                            EditorViewModelFactory(
                                layerRepository, projectStorage, projectId,
                                pendingProjectName, pendingProjectAspect, pendingProjectDurationMs, pendingProjectFps
                            )
                        }
                        val viewModel: EditorViewModel = viewModel(factory = factory, key = projectId)

                        // Se llama cada vez que se (re)entra a ESTE projectId. Como el
                        // ViewModel puede venir reciclado del ViewModelStore de la
                        // Activity, esto es lo que garantiza que el proyecto se vea
                        // siempre desde el principio y pausado al abrirlo, nunca a
                        // mitad de una reproducción que quedó corriendo en segundo
                        // plano — y, por el mismo motivo (ViewModel reciclado), que el
                        // NOMBRE mostrado y el que se autoguarda estén siempre al día
                        // con lo último escrito desde "Mis proyectos" (renombrar), en
                        // vez de con lo que este ViewModel tenía en memoria de una
                        // visita anterior — ver refreshProjectNameFromDisk().
                        LaunchedEffect(projectId) {
                            viewModel.resetPlaybackState()
                            viewModel.refreshProjectNameFromDisk()
                        }

                        EditorScreen(
                            viewModel = viewModel,
                            onBackToProjects = {
                                // Frena cualquier reproducción en curso ANTES de salir: al
                                // no destruirse el ViewModel (mismo motivo de arriba), si no
                                // se para acá el loop de reproducción sigue corriendo en
                                // segundo plano mientras se ve la lista de proyectos.
                                viewModel.resetPlaybackState()
                                // Guarda inmediatamente (sin esperar el debounce normal) antes
                                // de volver a la lista, para que la miniatura y el nombre ya
                                // estén al día apenas se ve "Mis proyectos" de nuevo.
                                viewModel.saveNow {
                                    projectsRefreshKey++
                                    openProjectId = null
                                }
                            },
                            onImportClick = {
                                onImagesPicked = { uris -> if (uris.isNotEmpty()) viewModel.importImages(uris) }
                                pickImagesLauncher.launch(arrayOf("image/png", "image/*"))
                            },
                            onImportBackgroundClick = {
                                onBackgroundPicked = { uri -> if (uri != null) viewModel.importAsBackground(uri) }
                                pickBackgroundLauncher.launch(arrayOf("image/*"))
                            },
                            onReplaceImageClick = { layerId ->
                                onReplacementPicked = { uri -> if (uri != null) viewModel.replaceLayerImage(layerId, uri) }
                                pickReplacementLauncher.launch(arrayOf("image/*"))
                            },
                            onImportAudioClick = {
                                onAudioPicked = { uri -> if (uri != null) viewModel.importAudio(this@MainActivity, uri) }
                                pickAudioLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                    }
                }
            }
        }
    }
}
