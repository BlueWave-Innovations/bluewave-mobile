package com.example.bluewave_mobile.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.screens.ChatScreen
import com.example.bluewave_mobile.ui.screens.CreateGroupScreen
import com.example.bluewave_mobile.ui.screens.DeviceListScreen
import com.example.bluewave_mobile.ui.screens.FoldersManagementScreen
import com.example.bluewave_mobile.ui.screens.GroupChatScreen
import com.example.bluewave_mobile.ui.screens.ProfileScreen
import com.example.bluewave_mobile.ui.screens.QrShareScreen
import com.example.bluewave_mobile.ui.screens.SettingsScreen

/**
 * One descriptor per bottom-nav tab.
 *
 * @property route Type-safe Compose-Navigation route this tab opens.
 * @property labelResId String resource shown under the icon and used
 *   for content description.
 * @property icon The Material icon rendered above the label.
 */
private data class BottomTab(
    val route: Any,
    val labelResId: Int,
    val icon: ImageVector,
)

private val bottomTabs: List<BottomTab> = listOf(
    BottomTab(
        route = DeviceListRoute,
        labelResId = R.string.nav_chats,
        icon = Icons.AutoMirrored.Filled.Chat,
    ),
    BottomTab(
        route = SettingsRoute,
        labelResId = R.string.nav_settings,
        icon = Icons.Filled.Settings,
    ),
    BottomTab(
        route = ProfileRoute,
        labelResId = R.string.nav_profile,
        icon = Icons.Filled.Person,
    ),
)

/**
 * Three-tab Material 3 [Scaffold] with a [NavigationBar] driven
 * by [NavHostController].
 *
 * Tab routes (`DeviceListRoute`, `SettingsRoute`, `ProfileRoute`)
 * live in the navigation back-stack like normal destinations; the
 * bottom bar restores their previously-visited state instead of
 * blowing it away on every tap (`launchSingleTop = true`,
 * `popUpTo(...) { saveState = true }`).
 *
 * Sub-screens (`Chat`, `GroupChat`, `FoldersManagement`,
 * `CreateGroup`, `QrShare`) push on top and hide the bottom bar
 * naturally because the picker only shows the bar for top-level
 * destinations.
 */
@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Only top-level tabs render the bottom bar — sub-screens
            // (chat, profile-edit, groups, …) hide it for full-screen
            // working surfaces.
            if (isTopLevelRoute(currentRoute)) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        val tabRouteName = routeName(tab.route)
                        val selected = currentRoute == tabRouteName ||
                            backStack?.destination?.hierarchy?.any { it.route == tabRouteName } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onTabClicked(navController, tab.route) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = stringResource(id = tab.labelResId),
                                )
                            },
                            label = { Text(text = stringResource(id = tab.labelResId)) },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = DeviceListRoute,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable<DeviceListRoute> {
                DeviceListScreen(
                    onDeviceClick = { mac ->
                        navController.navigate(ChatRoute(deviceMac = mac))
                    },
                    onCreateGroupClick = { navController.navigate(CreateGroupRoute) },
                    onShareQrClick = { navController.navigate(QrShareRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenFolders = { navController.navigate(FoldersManagementRoute) },
                )
            }
            composable<ProfileRoute> {
                ProfileScreen(
                    onShareQrClick = { navController.navigate(QrShareRoute) },
                )
            }
            composable<ChatRoute> { backStackEntry ->
                val chatRoute: ChatRoute = backStackEntry.toRoute()
                ChatScreen(deviceMac = chatRoute.deviceMac)
            }
            composable<GroupChatRoute> { backStackEntry ->
                val groupRoute: GroupChatRoute = backStackEntry.toRoute()
                GroupChatScreen(groupId = groupRoute.groupId)
            }
            composable<FoldersManagementRoute> {
                FoldersManagementScreen(
                    onClose = { navController.popBackStack() },
                )
            }
            composable<CreateGroupRoute> {
                CreateGroupScreen(
                    onClose = { navController.popBackStack() },
                    onGroupCreated = { groupId ->
                        navController.popBackStack()
                        navController.navigate(GroupChatRoute(groupId = groupId))
                    },
                )
            }
            composable<QrShareRoute> {
                QrShareScreen(
                    onClose = { navController.popBackStack() },
                    onContactScanned = { mac ->
                        navController.popBackStack()
                        navController.navigate(ChatRoute(deviceMac = mac))
                    },
                )
            }
        }
    }
}

/**
 * Whether the bottom navigation bar should be visible for the
 * given destination's serial route name.
 *
 * Returning `false` for sub-screens (chat / profile-edit / etc.)
 * gives those surfaces the full vertical space, mirroring the
 * Telegram-style design in the mockup.
 */
private fun isTopLevelRoute(route: String?): Boolean {
    if (route == null) return true
    return route == routeName(DeviceListRoute) ||
        route == routeName(SettingsRoute) ||
        route == routeName(ProfileRoute)
}

/**
 * Resolves the type-safe route into the string name Compose
 * Navigation generates internally — `package.ClassName` for
 * `data class` routes, `package.ClassName` for objects.
 *
 * We rely on the qualified name because Compose Navigation 2.9
 * uses it as the back-stack identifier for type-safe routes.
 */
private fun routeName(route: Any): String =
    route::class.qualifiedName ?: route::class.toString()

/**
 * Handle tab clicks the way Material 3 expects:
 *  * pop back to the start destination so we never grow an
 *    arbitrary back-stack inside a tab;
 *  * save / restore the per-tab state so e.g. the chats scroll
 *    position survives a quick "Settings → back to Chats";
 *  * `launchSingleTop` prevents stacking duplicates of the same
 *    tab.
 */
private fun onTabClicked(navController: NavHostController, route: Any) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
