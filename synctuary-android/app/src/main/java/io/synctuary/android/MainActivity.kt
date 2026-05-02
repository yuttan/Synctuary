package io.synctuary.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.synctuary.android.data.secret.SecretStore
import io.synctuary.android.ui.debug.PairingTestScreen
import io.synctuary.android.ui.devices.DevicesScreen
import io.synctuary.android.ui.devices.DevicesViewModel
import io.synctuary.android.ui.favorites.AddToFavoritesDialog
import io.synctuary.android.ui.favorites.BiometricHelper
import io.synctuary.android.ui.favorites.FavoriteListDetailScreen
import io.synctuary.android.ui.favorites.FavoritesScreen
import io.synctuary.android.ui.favorites.FavoritesViewModel
import io.synctuary.android.ui.files.FileBrowserScreen
import io.synctuary.android.ui.files.FileBrowserViewModel
import io.synctuary.android.ui.navigation.BottomNavBar
import io.synctuary.android.ui.navigation.NavRoute
import io.synctuary.android.ui.onboarding.MnemonicScreen
import io.synctuary.android.ui.onboarding.OnboardingViewModel
import io.synctuary.android.ui.onboarding.PairingProgressScreen
import io.synctuary.android.ui.onboarding.ServerUrlScreen
import io.synctuary.android.ui.preview.ImagePreviewScreen
import io.synctuary.android.ui.preview.MediaPreviewScreen
import io.synctuary.android.ui.preview.PreviewViewModel
import io.synctuary.android.ui.settings.SettingsScreen
import io.synctuary.android.ui.settings.SettingsViewModel
import io.synctuary.android.ui.theme.SynctuaryTheme

class MainActivity : FragmentActivity() {
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
    val previewVm: PreviewViewModel = viewModel()
    val favoritesVm: FavoritesViewModel = viewModel()
    val devicesVm: DevicesViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()

    val context = LocalContext.current
    val activity = context as? FragmentActivity

    var favDialogFile by remember { mutableStateOf<Pair<String, String>?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                favoritesVm.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startRoute = if (onboardingVm.isPaired()) {
        NavRoute.TabFiles.route
    } else {
        NavRoute.ServerUrl.route
    }

    val settingsState by settingsVm.uiState.collectAsState()
    val leftHandMode = settingsState.leftHandMode

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
                    leftHandMode = leftHandMode,
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
                FileBrowserScreen(
                    viewModel = fileBrowserVm,
                    leftHandMode = leftHandMode,
                    onPreview = { entry ->
                        val current = fileBrowserVm.uiState.value.currentPath
                        val fullPath = if (current == "/") "/${entry.name}" else "$current/${entry.name}"
                        val mime = entry.mime_type ?: ""
                        when {
                            mime.startsWith("image/") ->
                                navController.navigate(NavRoute.ImagePreview.createRoute(fullPath))
                            mime.startsWith("video/") || mime.startsWith("audio/") ->
                                navController.navigate(NavRoute.MediaPreview.createRoute(fullPath))
                        }
                    },
                    onAddToFavorites = { entry, path ->
                        favDialogFile = Pair(entry.name, path)
                    },
                )
            }

            composable(NavRoute.TabSettings.route) {
                SettingsScreen(
                    viewModel = settingsVm,
                    onUnpaired = {
                        navController.navigate(NavRoute.ServerUrl.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(NavRoute.TabDevices.route) {
                DevicesScreen(viewModel = devicesVm)
            }

            composable(NavRoute.TabFavorites.route) {
                FavoritesScreen(
                    viewModel = favoritesVm,
                    onRequestBiometric = {
                        activity?.let { act ->
                            BiometricHelper.prompt(
                                activity = act,
                                onSuccess = { favoritesVm.onHiddenUnlocked() },
                                onError = { },
                            )
                        }
                    },
                    onListTap = { list ->
                        navController.navigate(NavRoute.FavoriteListDetail.createRoute(list.id, list.name))
                    },
                )
            }

            // Favorites detail (full-screen, no bottom nav)
            composable(
                route = NavRoute.FavoriteListDetail.route,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: return@composable
                val name = backStackEntry.arguments?.getString("name") ?: ""
                FavoriteListDetailScreen(
                    listId = id,
                    listName = name,
                    viewModel = favoritesVm,
                    onBack = { navController.popBackStack() },
                )
            }

            // Preview (full-screen, no bottom nav)
            composable(
                route = NavRoute.ImagePreview.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: return@composable
                ImagePreviewScreen(
                    remotePath = path,
                    viewModel = previewVm,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = NavRoute.MediaPreview.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: return@composable
                MediaPreviewScreen(
                    remotePath = path,
                    viewModel = previewVm,
                    onBack = { navController.popBackStack() },
                )
            }

            // Debug
            composable(NavRoute.PairingDebug.route) {
                PairingTestScreen()
            }
        }
    }

    favDialogFile?.let { (fileName, path) ->
        val secretStore = remember { SecretStore.create(context) }
        AddToFavoritesDialog(
            fileName = fileName,
            filePath = path,
            secretStore = secretStore,
            onDismiss = { favDialogFile = null },
            onDone = { favDialogFile = null },
        )
    }
}

