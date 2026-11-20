package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.YutaLogoAvatar
import com.example.ui.screens.AiChatScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HindiGuideScreen
import com.example.ui.screens.ModerationScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyberBorderSubtle
import com.example.ui.theme.CyberTextSecondary
import com.example.ui.theme.DeepBlack
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianSurface
import com.example.viewmodel.YutaViewModel

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_MODERATION = "moderation"
const val ROUTE_CHAT = "chat"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_GUIDE = "guide"

private data class YutaNavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val YutaDestinations = listOf(
    YutaNavDestination(
        route = ROUTE_DASHBOARD,
        label = "Dashboard",
        selectedIcon = Icons.Rounded.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_dashboard"
    ),
    YutaNavDestination(
        route = ROUTE_MODERATION,
        label = "Scanner",
        selectedIcon = Icons.Rounded.Security,
        unselectedIcon = Icons.Outlined.Security,
        testTag = "nav_moderation"
    ),
    YutaNavDestination(
        route = ROUTE_CHAT,
        label = "AI Chat",
        selectedIcon = Icons.Rounded.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        testTag = "nav_chat"
    ),
    YutaNavDestination(
        route = ROUTE_SETTINGS,
        label = "Settings",
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    ),
    YutaNavDestination(
        route = ROUTE_GUIDE,
        label = "Guide",
        selectedIcon = Icons.AutoMirrored.Rounded.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        testTag = "nav_guide"
    )
)

@Composable
fun YutaAppShell(viewModel: YutaViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_DASHBOARD
    val context = LocalContext.current

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = DeepBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useNavigationRail) {
                    Column {
                        HorizontalDivider(color = CyberBorderSubtle, thickness = 1.dp)
                        NavigationBar(
                            containerColor = ObsidianSurface,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            YutaDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = dest.label
                                        )
                                    },
                                    label = { Text(dest.label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DeepBlack,
                                        selectedTextColor = NeonCyan,
                                        indicatorColor = NeonCyan,
                                        unselectedIconColor = CyberTextSecondary,
                                        unselectedTextColor = CyberTextSecondary
                                    ),
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = ObsidianSurface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                        header = {
                            Spacer(modifier = Modifier.height(12.dp))
                            YutaLogoAvatar(
                                customLogoUri = uiState.settings.customLogoUri,
                                size = 44.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    ) {
                        YutaDestinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = DeepBlack,
                                    selectedTextColor = NeonCyan,
                                    indicatorColor = NeonCyan,
                                    unselectedIconColor = CyberTextSecondary,
                                    unselectedTextColor = CyberTextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                    VerticalDivider(color = CyberBorderSubtle, thickness = 1.dp)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_DASHBOARD,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1040.dp)
                    ) {
                        composable(ROUTE_DASHBOARD) {
                            DashboardScreen(
                                uiState = uiState,
                                onTogglePolling = viewModel::toggleBotPolling,
                                onToggleBackgroundMonitor = { viewModel.toggleBackgroundMonitor(context) },
                                onRetryStickerQueue = viewModel::retryPendingStickerQueue,
                                onSyncSharedBackend = viewModel::syncSharedBackendNow,
                                onRunCommandPreview = viewModel::runCommandPreview,
                                onOpenModeration = { navigateTo(ROUTE_MODERATION) },
                                onOpenSettings = { navigateTo(ROUTE_SETTINGS) }
                            )
                        }
                        composable(ROUTE_MODERATION) {
                            ModerationScreen(
                                uiState = uiState,
                                onSelectFilter = viewModel::setLogFilter,
                                onScanLocalMedia = { uri, kind ->
                                    viewModel.scanLocalMedia(context, uri, kind)
                                },
                                onClearLogs = viewModel::clearModerationLogs
                            )
                        }
                        composable(ROUTE_CHAT) {
                            AiChatScreen(
                                uiState = uiState,
                                onSendMessage = viewModel::sendChatMessage,
                                onClearChat = viewModel::clearChatHistory
                            )
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                uiState = uiState,
                                onSaveSharedBackend = viewModel::saveAndVerifySharedBackend,
                                onTestConnection = viewModel::runLiveConnectionTest,
                                onSetupWebhook = viewModel::setupSharedBackendWebhook,
                                onSaveTelegramConfig = viewModel::saveAndVerifyTelegramConfig,
                                onUpdateCustomLogoUri = viewModel::updateCustomLogoUri,
                                onToggleImageMod = viewModel::updateImageModeration,
                                onToggleStickerMod = viewModel::updateStickerModeration,
                                onToggleGifMod = viewModel::updateGifModeration,
                                onToggleVideoMod = viewModel::updateVideoModeration,
                                onToggleAiChat = viewModel::updateAiChatEnabled,
                                onToggleAutoDelete = viewModel::updateAutoDelete,
                                onToggleAdminNotify = viewModel::updateAdminNotifications,
                                onToggleDisableMediaRetention = viewModel::updateDisableMediaRetention,
                                onSelectSensitivity = viewModel::updateSensitivity
                            )
                        }
                        composable(ROUTE_GUIDE) {
                            HindiGuideScreen()
                        }
                    }
                }
            }
        }
    }
}
