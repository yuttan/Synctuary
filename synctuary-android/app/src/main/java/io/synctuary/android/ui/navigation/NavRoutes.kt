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

    // Debug
    data object PairingDebug : NavRoute("debug/pairing")
}
