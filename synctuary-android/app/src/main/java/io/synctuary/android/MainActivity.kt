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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.synctuary.android.ui.debug.PairingTestScreen
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

@Composable
private fun SynctuaryNavHost() {
    val navController = rememberNavController()
    val onboardingVm: OnboardingViewModel = viewModel()

    val startRoute = if (onboardingVm.isPaired()) {
        NavRoute.Home.route
    } else {
        NavRoute.ServerUrl.route
    }

    NavHost(navController = navController, startDestination = startRoute) {

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
                    navController.navigate(NavRoute.Home.route) {
                        popUpTo(NavRoute.ServerUrl.route) { inclusive = true }
                    }
                },
            )
        }

        composable(NavRoute.Home.route) {
            HomePlaceholder()
        }

        composable(NavRoute.PairingDebug.route) {
            PairingTestScreen()
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Paired — file browser coming in Phase 3",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
