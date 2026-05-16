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
import androidx.compose.ui.res.stringResource
import io.synctuary.android.R

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val tabs = listOf(
    NavTab(NavRoute.TabSettings.route, R.string.tab_settings, Icons.Filled.Settings),
    NavTab(NavRoute.TabDevices.route, R.string.tab_devices, Icons.Filled.Devices),
    NavTab(NavRoute.TabFavorites.route, R.string.tab_favorites, Icons.Filled.Favorite),
    NavTab(NavRoute.TabFiles.route, R.string.tab_files, Icons.Filled.Folder),
)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    leftHandMode: Boolean = false,
) {
    val ordered = if (leftHandMode) tabs.reversed() else tabs
    NavigationBar {
        ordered.forEach { tab ->
            val label = stringResource(tab.labelRes)
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onTabSelected(tab.route) },
                icon = { Icon(tab.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}
