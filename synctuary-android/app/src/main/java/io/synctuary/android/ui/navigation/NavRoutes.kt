package io.synctuary.android.ui.navigation

sealed class NavRoute(val route: String) {
    // Onboarding
    data object ServerUrl : NavRoute("onboarding/server_url")
    data object Mnemonic : NavRoute("onboarding/mnemonic")
    data object PairingProgress : NavRoute("onboarding/pairing")

    // Main tabs (bottom nav order: Settings → Devices → Favorites → Files)
    data object TabSettings : NavRoute("tab/settings")
    data object TabDevices : NavRoute("tab/devices")
    data object TabFavorites : NavRoute("tab/favorites")
    data object TabFiles : NavRoute("tab/files")

    // Preview (full-screen, no bottom nav)
    data object ImagePreview : NavRoute("preview/image?path={path}") {
        fun createRoute(path: String): String =
            "preview/image?path=${android.net.Uri.encode(path)}"
    }
    data object MediaPreview : NavRoute("preview/media?path={path}") {
        fun createRoute(path: String): String =
            "preview/media?path=${android.net.Uri.encode(path)}"
    }

    // Debug
    data object PairingDebug : NavRoute("debug/pairing")
}
