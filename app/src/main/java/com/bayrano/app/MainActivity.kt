package com.bayrano.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bayrano.ui.AssistantScreen
import com.bayrano.ui.AssistantViewModel
import com.bayrano.ui.SettingsScreen
import com.bayrano.ui.SettingsViewModel
import com.bayrano.ui.ViewModelFactory
import com.bayrano.ui.theme.BayRanOTheme
import com.bayrano.wake.WakeService

private object Routes {
    const val ASSISTANT = "assistant"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resume hands-free wake if the user had it on (started here, while
        // foregrounded, so the foreground-service start is always permitted).
        val app = application as BayRanOApp
        if (app.appPreferences.wakeEnabled) WakeService.start(this)
        enableEdgeToEdge()
        setContent { BayRanOTheme { App() } }
    }
}

@Composable
private fun App() {
    val navController = rememberNavController()
    val factory = ViewModelFactory(LocalApp())

    NavHost(navController = navController, startDestination = Routes.ASSISTANT) {
        composable(Routes.ASSISTANT) {
            val vm: AssistantViewModel = viewModel(factory = factory)
            AssistantScreen(
                viewModel = vm,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun LocalApp(): BayRanOApp =
    androidx.compose.ui.platform.LocalContext.current.applicationContext as BayRanOApp
