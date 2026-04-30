package io.synctuary.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

private data class NavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    NavTab(NavRoute.TabSettings.route, "Settings", Icons.Filled.Settings),
    NavTab(NavRoute.TabDevices.route, "Devices", Icons.Filled.Devices),
    NavTab(NavRoute.TabFavorites.route, "Favorites", Icons.Filled.Favorite),
    NavTab(NavRoute.TabFiles.route, "Files", Icons.Filled.Folder),
)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
) {
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
