package io.synctuary.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.synctuary.android.ui.debug.PairingTestScreen
import io.synctuary.android.ui.files.FileBrowserScreen
import io.synctuary.android.ui.files.FileBrowserViewModel
import io.synctuary.android.ui.navigation.BottomNavBar
import io.synctuary.android.ui.navigation.NavRoute
import io.synctuary.android.ui.onboarding.MnemonicScreen
import io.synctuary.android.ui.onboarding.OnboardingViewModel
import io.synctuary.android.ui.onboarding.PairingProgressScreen
import io.synctuary.android.ui.onboarding.ServerUrlScreen
import io.synctuary.android.ui.theme.SynctuaryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynctuaryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SynctuaryNavHost()
                }
            }
        }
    }
}

private val tabRoutes = setOf(
    NavRoute.TabSettings.route,
    NavRoute.TabDevices.route,
    NavRoute.TabFavorites.route,
    NavRoute.TabFiles.route,
)

@Composable
private fun SynctuaryNavHost() {
    val navController = rememberNavController()
    val onboardingVm: OnboardingViewModel = viewModel()
    val fileBrowserVm: FileBrowserViewModel = viewModel()

    val startRoute = if (onboardingVm.isPaired()) {
        NavRoute.TabFiles.route
    } else {
        NavRoute.ServerUrl.route
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomNav = currentRoute in tabRoutes

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onTabSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(NavRoute.TabFiles.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(padding),
        ) {
            // Onboarding
            composable(NavRoute.ServerUrl.route) {
                ServerUrlScreen(
                    viewModel = onboardingVm,
                    onNext = { navController.navigate(NavRoute.Mnemonic.route) },
                )
            }

            composable(NavRoute.Mnemonic.route) {
                MnemonicScreen(
                    viewModel = onboardingVm,
                    onBack = { navController.popBackStack() },
                    onStartPairing = { navController.navigate(NavRoute.PairingProgress.route) },
                )
            }

            composable(NavRoute.PairingProgress.route) {
                PairingProgressScreen(
                    viewModel = onboardingVm,
                    onPairingComplete = {
                        navController.navigate(NavRoute.TabFiles.route) {
                            popUpTo(NavRoute.ServerUrl.route) { inclusive = true }
                        }
                    },
                )
            }

            // Main tabs
            composable(NavRoute.TabFiles.route) {
                FileBrowserScreen(viewModel = fileBrowserVm)
            }

            composable(NavRoute.TabSettings.route) {
                TabPlaceholder("Settings")
            }

            composable(NavRoute.TabDevices.route) {
                TabPlaceholder("Devices")
            }

            composable(NavRoute.TabFavorites.route) {
                TabPlaceholder("Favorites")
            }

            // Debug
            composable(NavRoute.PairingDebug.route) {
                PairingTestScreen()
            }
        }
    }
}

@Composable
private fun TabPlaceholder(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$name — coming soon",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
