package io.synctuary.android

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.synctuary.android.BuildConfig
import io.synctuary.android.data.secret.SecretStore
import io.synctuary.android.ui.debug.PairingTestScreen
import io.synctuary.android.ui.devices.DevicesScreen
import io.synctuary.android.ui.devices.DevicesViewModel
import io.synctuary.android.ui.favorites.AddToFavoritesDialog
import io.synctuary.android.ui.favorites.BiometricHelper
import io.synctuary.android.ui.favorites.FavoriteListDetailScreen
import io.synctuary.android.ui.favorites.FavoritesScreen
import io.synctuary.android.ui.favorites.FavoritesViewModel
import io.synctuary.android.ui.files.FileBrowserViewModel
import io.synctuary.android.ui.files.FilesTabScreen
import io.synctuary.android.ui.files.LocalFilesViewModel
import io.synctuary.android.ui.navigation.BottomNavBar
import io.synctuary.android.ui.navigation.NavRoute
import io.synctuary.android.ui.onboarding.MnemonicScreen
import io.synctuary.android.ui.onboarding.OnboardingViewModel
import io.synctuary.android.ui.onboarding.PairingProgressScreen
import io.synctuary.android.ui.onboarding.QrScannerScreen
import io.synctuary.android.ui.onboarding.ServerUrlScreen
import io.synctuary.android.ui.preview.ImagePreviewScreen
import io.synctuary.android.ui.preview.MediaPreviewScreen
import io.synctuary.android.ui.preview.PreviewViewModel
import io.synctuary.android.ui.preview.VideoPlayerViewModel
import io.synctuary.android.ui.settings.ConnectionPickerScreen
import io.synctuary.android.ui.settings.SettingsScreen
import io.synctuary.android.ui.settings.SettingsViewModel
import io.synctuary.android.ui.theme.SynctuaryTheme

class MainActivity : AppCompatActivity() {
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
    val videoPlayerVm: VideoPlayerViewModel = viewModel()
    val localFilesVm: LocalFilesViewModel = viewModel()
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

    val isPaired = onboardingVm.isPaired()
    val startRoute = if (isPaired) {
        NavRoute.TabFiles.route
    } else {
        NavRoute.ServerUrl.route
    }

    val connectionState by onboardingVm.connectionState.collectAsState()

    // On startup, check server reachability if paired
    LaunchedEffect(isPaired) {
        if (isPaired) {
            onboardingVm.checkConnection()
        }
    }

    // Navigate to ConnectionPicker when server is unreachable
    LaunchedEffect(connectionState.reachable) {
        if (connectionState.reachable == false) {
            val current = navController.currentDestination?.route
            if (current != NavRoute.ConnectionPicker.route) {
                navController.navigate(NavRoute.ConnectionPicker.route) {
                    launchSingleTop = true
                }
            }
        } else if (connectionState.reachable == true) {
            val current = navController.currentDestination?.route
            if (current == NavRoute.ConnectionPicker.route) {
                navController.popBackStack()
            }
            // Refresh all VMs to use the (possibly changed) active URL
            fileBrowserVm.resetConnection()
            favoritesVm.resetConnection()
            devicesVm.resetConnection()
            settingsVm.refreshState()
        }
    }

    val settingsState by settingsVm.uiState.collectAsState()
    val leftHandMode = settingsState.leftHandMode

    // When active mode changes from Settings, refresh all repositories
    val activeMode = settingsState.activeMode
    LaunchedEffect(activeMode) {
        fileBrowserVm.resetConnection()
        favoritesVm.resetConnection()
        devicesVm.resetConnection()
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomNav = currentRoute in tabRoutes

    // Prevent the system back gesture from sending the app to background.
    // On tab screens the gesture is silently consumed; on detail screens
    // (preview, favorite detail, etc.) it pops the nav stack instead.
    BackHandler(enabled = true) {
        if (currentRoute != null && currentRoute !in tabRoutes) {
            navController.popBackStack()
        }
        // On tab screens: do nothing — swallow the gesture.
    }

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
                    onScanQr = { navController.navigate(NavRoute.QrScanner.route) },
                )
            }

            composable(NavRoute.QrScanner.route) {
                QrScannerScreen(
                    onScanned = { url ->
                        onboardingVm.setServerUrl(url)
                        navController.popBackStack()
                    },
                    onPairingUri = { url, masterKeyB64, tlsFpHex ->
                        onboardingVm.setQrPairingData(url, masterKeyB64, tlsFpHex)
                        navController.navigate(NavRoute.PairingProgress.route) {
                            popUpTo(NavRoute.ServerUrl.route)
                        }
                    },
                    onBack = { navController.popBackStack() },
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

            // Connection picker (server unreachable)
            composable(NavRoute.ConnectionPicker.route) {
                ConnectionPickerScreen(
                    homeUrl = onboardingVm.getHomeUrl(),
                    remoteUrl = onboardingVm.getRemoteUrl(),
                    activeMode = onboardingVm.getActiveMode(),
                    connecting = connectionState.checking,
                    error = connectionState.error,
                    onSelectHome = { onboardingVm.switchToHome() },
                    onSelectRemote = { url -> onboardingVm.switchToRemote(url) },
                    onRetry = { onboardingVm.checkConnection() },
                )
            }

            // Main tabs
            composable(NavRoute.TabFiles.route) {
                FilesTabScreen(
                    fileBrowserVm = fileBrowserVm,
                    localFilesVm = localFilesVm,
                    previewVm = previewVm,
                    leftHandMode = leftHandMode,
                    onPreview = { entry ->
                        val current = fileBrowserVm.uiState.value.currentPath
                        val fullPath = if (current == "/") "/${entry.name}" else "$current/${entry.name}"
                        val mime = entry.mime_type ?: ""
                        when {
                            mime.startsWith("image/") -> {
                                val imageEntries = fileBrowserVm.uiState.value.filteredEntries
                                    .filter { (it.mime_type ?: "").startsWith("image/") }
                                val imagePaths = imageEntries.map { e ->
                                    if (current == "/") "/${e.name}" else "$current/${e.name}"
                                }
                                previewVm.setImageList(imagePaths)
                                navController.navigate(NavRoute.ImagePreview.createRoute(fullPath))
                            }
                            mime.startsWith("video/") || mime.startsWith("audio/") -> {
                                videoPlayerVm.currentShareId = fileBrowserVm.currentShare.value?.id
                                navController.navigate(NavRoute.MediaPreview.createRoute(fullPath))
                            }
                        }
                    },
                    onAddToFavorites = { entry, path ->
                        favDialogFile = Pair(entry.name, path)
                    },
                    onUploadFromLocal = { uri ->
                        fileBrowserVm.startUpload(uri)
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
                    onItemTap = { path ->
                        // Navigate to the file browser at the given path.
                        // For folders, browse into it; for files, browse the parent.
                        val fileName = path.substringAfterLast('/')
                        val isLikelyFolder = '.' !in fileName
                        val targetDir = if (isLikelyFolder) path
                                        else path.substringBeforeLast('/', "/")
                        fileBrowserVm.loadDirectory(targetDir)
                        navController.navigate(NavRoute.TabFiles.route) {
                            popUpTo(NavRoute.TabFiles.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            // Preview (full-screen, no bottom nav)
            composable(
                route = NavRoute.ImagePreview.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
                enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 4 } },
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
                enterTransition = { fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 } },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(200)) },
                popExitTransition = { fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 4 } },
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: return@composable
                MediaPreviewScreen(
                    remotePath = path,
                    viewModel = previewVm,
                    videoPlayerVm = videoPlayerVm,
                    onBack = { navController.popBackStack() },
                    onFullscreenChanged = { fullscreen, videoWidth, videoHeight ->
                        activity?.let { act ->
                            val window = act.window
                            val insetsController = androidx.core.view.WindowCompat.getInsetsController(
                                window, window.decorView
                            )
                            if (fullscreen) {
                                // Choose orientation based on video aspect ratio.
                                // Portrait videos get portrait fullscreen; landscape videos
                                // get landscape fullscreen. When dimensions are unknown,
                                // default to landscape (most common for video content).
                                act.requestedOrientation = if (videoHeight > videoWidth && videoWidth > 0) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                                insetsController.hide(
                                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                )
                                insetsController.systemBarsBehavior =
                                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            } else {
                                // Restore full-sensor rotation so the device orientation
                                // takes over (respects the user's rotation lock setting).
                                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                insetsController.show(
                                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                                )
                                insetsController.systemBarsBehavior =
                                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                            }
                        }
                    },
                )
            }

            // Debug — only reachable in debug builds; release strips
            // this branch so the route is never registered.
            if (BuildConfig.DEBUG) {
                composable(NavRoute.PairingDebug.route) {
                    PairingTestScreen()
                }
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
            onDone = { favDialogFile = null; favoritesVm.loadLists() },
        )
    }
}

