package com.yeivikasdigitalstudio.olyze.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yeivikasdigitalstudio.olyze.data.DEFAULT_PROJECT_NAME
import com.yeivikasdigitalstudio.olyze.data.LayerRepository
import com.yeivikasdigitalstudio.olyze.data.ProjectStorage
import com.yeivikasdigitalstudio.olyze.engine.scene.AspectRatioPreset
import com.yeivikasdigitalstudio.olyze.timeline.TimelineLimits

/**
 * [projectId] identifica QUÉ proyecto abre este ViewModel — se usa como
 * `key` en `viewModel(factory = ..., key = projectId)` para que Compose
 * cree una instancia nueva del ViewModel por cada proyecto distinto, en
 * vez de reusar el estado del proyecto anterior al navegar entre ellos.
 *
 * [initialAspect]/[initialFps] solo se usan la primera vez que se abre un
 * proyecto recién creado (todavía sin nada guardado en disco): son los
 * valores elegidos en el diálogo "Nuevo proyecto" de
 * [com.yeivikasdigitalstudio.olyze.ui.ProjectsScreen]. Si el proyecto ya
 * existía, [EditorViewModel] los ignora y usa los que ya estaban guardados.
 *
 * [initialDurationMs] se mantiene solo por compatibilidad de firma: la
 * duración de un proyecto nuevo ya no se elige desde ningún lado, siempre
 * arranca en [TimelineLimits.INITIAL_DURATION_MS] — ver
 * [com.yeivikasdigitalstudio.olyze.timeline.TimelineDurationManager].
 */
class EditorViewModelFactory(
    private val layerRepository: LayerRepository,
    private val projectStorage: ProjectStorage,
    private val projectId: String,
    private val initialName: String = DEFAULT_PROJECT_NAME,
    private val initialAspect: AspectRatioPreset = AspectRatioPreset.REELS,
    private val initialDurationMs: Long = TimelineLimits.INITIAL_DURATION_MS,
    private val initialFps: Int = 30
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return EditorViewModel(
            layerRepository, projectStorage, projectId,
            initialName, initialAspect, initialDurationMs, initialFps
        ) as T
    }
}
