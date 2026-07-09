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

    // Archive browser (full-screen, no bottom nav). `path` is the archive
    // file's path; `share` scopes it to a drive (optional).
    data object ArchiveBrowser : NavRoute("archive?path={path}&share={share}") {
        fun createRoute(path: String, share: String?): String {
            val shareParam = share?.let { "&share=${android.net.Uri.encode(it)}" } ?: ""
            return "archive?path=${android.net.Uri.encode(path)}$shareParam"
        }
    }

    // Favorites detail (full-screen, no bottom nav)
    data object FavoriteListDetail : NavRoute("favorites/detail?id={id}&name={name}") {
        fun createRoute(id: String, name: String): String =
            "favorites/detail?id=${android.net.Uri.encode(id)}&name=${android.net.Uri.encode(name)}"
    }

    // Connection picker (shown when server is unreachable)
    data object ConnectionPicker : NavRoute("connection_picker")

    // QR scanner (onboarding)
    data object QrScanner : NavRoute("onboarding/qr_scanner")

    // QR scanner (settings — remote URL capture)
    data object SettingsQrScanner : NavRoute("settings/qr_scanner")

    // Debug
    data object PairingDebug : NavRoute("debug/pairing")
}
