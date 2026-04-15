package com.watranscribe.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.watranscribe.ui.benchmark.BenchmarkScreen
import com.watranscribe.ui.onboarding.OnboardingScreen
import com.watranscribe.ui.onboarding.OnboardingViewModel
import com.watranscribe.ui.settings.SettingsScreen
import com.watranscribe.ui.transcriptions.TranscriptionListScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val TRANSCRIPTIONS = "transcriptions"
    const val SETTINGS = "settings"
    const val BENCHMARK = "benchmark"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    val onboardingVm: OnboardingViewModel = hiltViewModel()
    val folderUri by onboardingVm.folderUri.collectAsState()
    val startDest = if (folderUri != null) Routes.TRANSCRIPTIONS else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = startDest) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Routes.TRANSCRIPTIONS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.TRANSCRIPTIONS) {
            TranscriptionListScreen(
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onBenchmark = { navController.navigate(Routes.BENCHMARK) }
            )
        }
        composable(Routes.BENCHMARK) {
            BenchmarkScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
