package com.jvksdigitalstudio.cinimagen.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.jvksdigitalstudio.cinimagen.data.LayerRepository
import com.jvksdigitalstudio.cinimagen.data.ProjectStorage
import com.jvksdigitalstudio.cinimagen.engine.AspectRatioPreset

/**
 * [projectId] identifica QUÉ proyecto abre este ViewModel — se usa como
 * `key` en `viewModel(factory = ..., key = projectId)` para que Compose
 * cree una instancia nueva del ViewModel por cada proyecto distinto, en
 * vez de reusar el estado del proyecto anterior al navegar entre ellos.
 *
 * [initialAspect]/[initialDurationMs]/[initialFps] solo se usan la primera
 * vez que se abre un proyecto recién creado (todavía sin nada guardado en
 * disco): son los valores elegidos en el diálogo "Nuevo proyecto" de
 * [com.jvksdigitalstudio.cinimagen.ui.ProjectsScreen]. Si el proyecto ya
 * existía, [EditorViewModel] los ignora y usa los que ya estaban guardados.
 */
class EditorViewModelFactory(
    private val layerRepository: LayerRepository,
    private val projectStorage: ProjectStorage,
    private val projectId: String,
    private val initialName: String = "Proyecto sin título",
    private val initialAspect: AspectRatioPreset = AspectRatioPreset.REELS,
    private val initialDurationMs: Long = 8000L,
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
