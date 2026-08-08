package com.yeivikasdigitalstudio.olyze.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yeivikasdigitalstudio.olyze.data.ProjectStorage

class ProjectsViewModelFactory(
    private val projectStorage: ProjectStorage
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProjectsViewModel(projectStorage) as T
    }
}
