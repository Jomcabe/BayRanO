package com.bayrano.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bayrano.app.BayRanOApp

/** Supplies [BayRanOApp] to AndroidViewModels without a DI framework. */
class ViewModelFactory(private val app: BayRanOApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(AssistantViewModel::class.java) ->
            AssistantViewModel(app) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(app) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
