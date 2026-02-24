package com.easytier.app.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Language
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.easytier.app.ConfigData
import com.easytier.app.R
import com.easytier.app.Screen
import com.easytier.jni.DetailedNetworkInfo
import com.easytier.jni.EasyTierManager
import kotlinx.coroutines.launch

data class TabItem(val title: Int, val icon: ImageVector)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    allConfigs: List<ConfigData>,
    activeConfig: ConfigData,
    onActiveConfigChange: (ConfigData) -> Unit,
    onConfigChange: (ConfigData) -> Unit,
    onAddNewConfig: () -> Unit,
    onDeleteConfig: (ConfigData) -> Unit,
    status: EasyTierManager.EasyTierStatus?,
    isRunning: Boolean,
    onControlButtonClick: () -> Unit,
    detailedInfo: DetailedNetworkInfo?,
    rawEventHistory: List<String>,
    onRefreshDetailedInfo: () -> Unit,
    onCopyJsonClick: () -> Unit,
    onExportLogsClicked: () -> Unit,
    onExportConfig: (Uri) -> Unit,
    onImportConfig: (Uri) -> Unit,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    val tabs = listOf(
        TabItem(R.string.tab_control, Icons.Default.Settings),
        TabItem(R.string.tab_status, Icons.Default.ShowChart),
        TabItem(R.string.tab_log, Icons.Default.List)
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    var showLanguageMenu by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = isRunning) {
        if (isRunning) {
            pagerState.animateScrollToPage(1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showLanguageMenu = true }) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = stringResource(R.string.language),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_en)) },
                                onClick = {
                                    onLanguageChange("en")
                                    showLanguageMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.language_zh)) },
                                onClick = {
                                    onLanguageChange("zh")
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, tabItem ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(stringResource(tabItem.title)) },
                        icon = { Icon(tabItem.icon, contentDescription = stringResource(tabItem.title)) }
                    )
                }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> ControlTab(
                        allConfigs = allConfigs,
                        activeConfig = activeConfig,
                        onActiveConfigChange = onActiveConfigChange,
                        onAddNewConfig = onAddNewConfig,
                        onDeleteConfig = onDeleteConfig,
                        onConfigChange = onConfigChange,
                        isRunning = isRunning,
                        onControlButtonClick = onControlButtonClick,
                        onExportConfig = onExportConfig,
                        onImportConfig = onImportConfig
                    )

                    1 -> StatusTab(
                        status = status, isRunning = isRunning, detailedInfo = detailedInfo,
                        onRefreshDetailedInfo = onRefreshDetailedInfo,
                        onPeerClick = { peer ->
                            navController.navigate(
                                Screen.PeerDetail.createRoute(
                                    peer.peerId
                                )
                            )
                        },
                        onCopyJsonClick = onCopyJsonClick
                    )

                    2 -> LogTab(
                        rawEvents = rawEventHistory,
                        onExportClicked = onExportLogsClicked
                    )
                }
            }
        }
    }
}