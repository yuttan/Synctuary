package io.synctuary.android.ui.navigation

sealed class NavRoute(val route: String) {
    data object ServerUrl : NavRoute("onboarding/server_url")
    data object Mnemonic : NavRoute("onboarding/mnemonic")
    data object PairingProgress : NavRoute("onboarding/pairing")
    data object Home : NavRoute("home")
    data object PairingDebug : NavRoute("debug/pairing")
}
