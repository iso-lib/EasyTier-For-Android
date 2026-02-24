package com.easytier.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.easytier.app.ConfigData
import com.easytier.app.PortForwardItem
import com.easytier.app.R
import com.easytier.app.ui.ConfigSwitchWithInlineHelp

// --- 标签页1: 控制 (Control) ---
/**
 * “控制”标签页的UI
 * 集成了多配置管理（切换、添加、删除）和当前配置的完整编辑功能。
 *
 * @param allConfigs 所有已保存的配置列表。
 * @param activeConfig 当前激活的配置。
 * @param onActiveConfigChange 当用户切换配置时调用。
 * @param onAddNewConfig 当用户点击“添加新配置”时调用。
 * @param onDeleteConfig 当用户确认删除当前配置时调用。
 * @param onConfigChange 当用户编辑当前配置的任何字段时调用。
 * @param isRunning 服务当前是否在运行。
 * @param onControlButtonClick 当主启动/停止按钮被点击时调用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlTab(
    allConfigs: List<ConfigData>,
    activeConfig: ConfigData,
    onActiveConfigChange: (ConfigData) -> Unit,
    onAddNewConfig: () -> Unit,
    onDeleteConfig: (ConfigData) -> Unit,
    onConfigChange: (ConfigData) -> Unit,
    isRunning: Boolean,
    onControlButtonClick: () -> Unit,
    onExportConfig: (Uri) -> Unit,
    onImportConfig: (Uri) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // --- 文件导出器 (创建文件) ---
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/toml"),
        onResult = { uri: Uri? ->
            uri?.let {
                onExportConfig(it)
                // 可以在 ViewModel 中处理成功提示
            }
        }
    )


    // --- 文件导入器 (打开文件) ---
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                onImportConfig(it)
            }
        }
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- 顶部控制行 ---
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onControlButtonClick,
                modifier = Modifier.weight(1f),
                enabled = activeConfig.instanceName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) { Text(if (isRunning) stringResource(R.string.stop_service) else stringResource(R.string.start_service), fontSize = 18.sp) }

            Box {
                IconButton(onClick = { showMenu = true }, enabled = !isRunning) {
                    Icon(
                        Icons.Default.MoreVert,
                        stringResource(R.string.config_options)
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    allConfigs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text(config.instanceName) },
                            onClick = { onActiveConfigChange(config); showMenu = false },
                            leadingIcon = {
                                if (config.id == activeConfig.id) Icon(
                                    Icons.Default.Check,
                                    stringResource(R.string.current_selected)
                                )
                            }
                        )
                    }
                    Divider()
                    DropdownMenuItem(
                        { Text(stringResource(R.string.add_new_config)) },
                        { onAddNewConfig(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Add, stringResource(R.string.add)) })
                    DropdownMenuItem(
                        { Text(stringResource(R.string.delete_current_config)) },
                        { showDeleteDialog = true; showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, stringResource(R.string.delete)) },
                        enabled = allConfigs.size > 1
                    )
                    Divider() // --- 分隔线，让导入导出更清晰 ---

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_config)) },
                        onClick = {
                            // 启动文件选择器来打开文件
                            importLauncher.launch(arrayOf("application/toml", "text/plain", "*/*"))
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.FileOpen, stringResource(R.string.import_label)) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_current_config)) },
                        onClick = {
                            // 推荐的文件名，用户可以修改
                            val fileName = "${activeConfig.instanceName}.toml"
                            // 启动文件选择器来创建文件
                            exportLauncher.launch(fileName)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.IosShare, stringResource(R.string.export_label)) }
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // --- 核心配置 ---
        CollapsibleConfigSection(title = stringResource(R.string.core_config), initiallyExpanded = true) {
            ConfigTextField(
                stringResource(R.string.instance_name),
                activeConfig.instanceName,
                { onConfigChange(activeConfig.copy(instanceName = it)) },
                enabled = !isRunning
            )
            ConfigTextField(
                stringResource(R.string.network_name),
                activeConfig.networkName,
                { onConfigChange(activeConfig.copy(networkName = it)) },
                enabled = !isRunning
            )
            ConfigTextField(
                stringResource(R.string.network_secret),
                activeConfig.networkSecret,
                { onConfigChange(activeConfig.copy(networkSecret = it)) },
                enabled = !isRunning
            )
            ConfigTextField(
                label = stringResource(R.string.peer_urls),
                value = activeConfig.peers,
                onValueChange = { onConfigChange(activeConfig.copy(peers = it)) },
                enabled = !isRunning,
                singleLine = false,
                modifier = Modifier.height(120.dp),
                placeholder = stringResource(R.string.peer_urls_placeholder)
            )
        }

        // --- IP & 接口配置 ---
        CollapsibleConfigSection(title = "IP & Interface") {
            // DHCP 开关
            ConfigSwitch(
                label = "DHCP",
                checked = activeConfig.dhcp,
                onCheckedChange = { dhcpEnabled ->
                    // 启用DHCP时清空静态IP，禁用时不做改变让用户手动输入
                    onConfigChange(
                        if (dhcpEnabled) activeConfig.copy(dhcp = true)
                        else activeConfig.copy(dhcp = false)
                    )
                },
                enabled = !isRunning
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = activeConfig.virtualIpv4,
                    onValueChange = { onConfigChange(activeConfig.copy(virtualIpv4 = it)) },
                    label = { Text(stringResource(R.string.virtual_ipv4)) },
                    enabled = !isRunning && !activeConfig.dhcp,
                    modifier = Modifier.weight(3f),
                    placeholder = { Text("10.0.0.1") }
                )
                Text("/", modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(
                    value = activeConfig.networkLength.toString(),
                    onValueChange = {
                        onConfigChange(
                            activeConfig.copy(
                                networkLength = it.toIntOrNull()?.coerceIn(1, 32)
                                    ?: activeConfig.networkLength
                            )
                        )
                    },
                    label = { Text("Mask") },
                    enabled = !isRunning && !activeConfig.dhcp,
                    modifier = Modifier.weight(1.5f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Spacer(Modifier.height(8.dp))

            ConfigTextField(
                stringResource(R.string.hostname),
                activeConfig.hostname,
                { onConfigChange(activeConfig.copy(hostname = it)) },
                enabled = !isRunning,
                placeholder = "Empty for auto"
            )
            ConfigTextField(
                stringResource(R.string.listeners),
                activeConfig.listenerUrls,
                { onConfigChange(activeConfig.copy(listenerUrls = it)) },
                enabled = !isRunning,
                singleLine = false,
                modifier = Modifier.height(100.dp),
                placeholder = stringResource(R.string.listeners_placeholder)
            )
            ConfigTextField(
                "Mapped Listeners",
                activeConfig.mappedListeners,
                { onConfigChange(activeConfig.copy(mappedListeners = it)) },
                enabled = !isRunning,
                singleLine = false,
                modifier = Modifier.height(100.dp),
                placeholder = "Public address of listeners, one per line."
            )
            ConfigTextField(
                "TUN Device Name",
                activeConfig.devName,
                { onConfigChange(activeConfig.copy(devName = it)) },
                enabled = !isRunning,
                placeholder = "Empty for auto"
            )
            ConfigTextField(
                "MTU",
                activeConfig.mtu,
                { onConfigChange(activeConfig.copy(mtu = it)) },
                enabled = !isRunning,
                placeholder = "Default: 1380 (no enc), 1360 (enc). Range: 400-1380",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

        }

        // --- 功能标志 (Flags) ---
        CollapsibleConfigSection(title = stringResource(R.string.flags)) {
            ConfigSwitchWithInlineHelp(
                stringResource(R.string.relay_network_whitelist),
                activeConfig.enableRelayNetworkWhitelist,
                { onConfigChange(activeConfig.copy(enableRelayNetworkWhitelist = it)) },
                stringResource(R.string.help_relay_whitelist),
                enabled = !isRunning
            )
            ConfigTextField(
                stringResource(R.string.relay_network_whitelist),
                activeConfig.relayNetworkWhitelist,
                { onConfigChange(activeConfig.copy(relayNetworkWhitelist = it)) },
                enabled = !isRunning && activeConfig.enableRelayNetworkWhitelist
            )
            Spacer(Modifier.height(8.dp))

            // 其他所有开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    ConfigSwitchWithInlineHelp(
                        stringResource(R.string.latency_first),
                        activeConfig.latencyFirst,
                        { onConfigChange(activeConfig.copy(latencyFirst = it)) },
                        stringResource(R.string.help_latency_first),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Private Mode",
                        activeConfig.privateMode,
                        { onConfigChange(activeConfig.copy(privateMode = it)) },
                        stringResource(R.string.help_private_mode),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Enable KCP",
                        activeConfig.enableKcpProxy,
                        { onConfigChange(activeConfig.copy(enableKcpProxy = it)) },
                        stringResource(R.string.help_enable_kcp),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable P2P",
                        activeConfig.disableP2p,
                        { onConfigChange(activeConfig.copy(disableP2p = it)) },
                        stringResource(R.string.help_disable_p2p),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        stringResource(R.string.exit_nodes),
                        activeConfig.enableExitNode,
                        { onConfigChange(activeConfig.copy(enableExitNode = it)) },
                        stringResource(R.string.help_enable_exit_node),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable KCP Input",
                        activeConfig.disableKcpInput,
                        { onConfigChange(activeConfig.copy(disableKcpInput = it)) },
                        stringResource(R.string.help_disable_kcp_input),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Enable QUIC",
                        activeConfig.enableQuicProxy,
                        { onConfigChange(activeConfig.copy(enableQuicProxy = it)) },
                        stringResource(R.string.help_enable_quic),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable QUIC Input",
                        activeConfig.disableQuicInput,
                        { onConfigChange(activeConfig.copy(disableQuicInput = it)) },
                        stringResource(R.string.help_disable_quic_input),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Multi-Thread",
                        activeConfig.multiThread,
                        { onConfigChange(activeConfig.copy(multiThread = it)) },
                        stringResource(R.string.help_multi_thread),
                        !isRunning
                    )
                }
                Column(Modifier.weight(1f)) {
                    ConfigSwitchWithInlineHelp(
                        "Disable Encryption",
                        activeConfig.disableEncryption,
                        { onConfigChange(activeConfig.copy(disableEncryption = it)) },
                        stringResource(R.string.help_disable_encryption),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable IPv6",
                        activeConfig.disableIpv6,
                        { onConfigChange(activeConfig.copy(disableIpv6 = it)) },
                        stringResource(R.string.help_disable_ipv6),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Magic DNS",
                        activeConfig.acceptDns,
                        { onConfigChange(activeConfig.copy(acceptDns = it)) },
                        stringResource(R.string.help_accept_dns),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Bind Device",
                        activeConfig.bindDevice,
                        { onConfigChange(activeConfig.copy(bindDevice = it)) },
                        stringResource(R.string.help_bind_device),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable UDP Hole Punching",
                        activeConfig.disableUdpHolePunching,
                        { onConfigChange(activeConfig.copy(disableUdpHolePunching = it)) },
                        stringResource(R.string.help_disable_udp_hole_punching),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Relay All RPC",
                        activeConfig.relayAllPeerRpc,
                        { onConfigChange(activeConfig.copy(relayAllPeerRpc = it)) },
                        stringResource(R.string.help_relay_all_rpc),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Proxy Forward By System",
                        activeConfig.proxyForwardBySystem,
                        { onConfigChange(activeConfig.copy(proxyForwardBySystem = it)) },
                        stringResource(R.string.help_proxy_forward_by_system),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Disable Sym Hole Punching",
                        activeConfig.disableSymHolePunching,
                        { onConfigChange(activeConfig.copy(disableSymHolePunching = it)) },
                        stringResource(R.string.help_disable_sym_hole_punching),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "Use SmolTCP",
                        activeConfig.useSmoltcp,
                        { onConfigChange(activeConfig.copy(useSmoltcp = it)) },
                        stringResource(R.string.help_use_smoltcp),
                        !isRunning
                    )
                    ConfigSwitchWithInlineHelp(
                        "No TUN",
                        activeConfig.noTun,
                        { onConfigChange(activeConfig.copy(noTun = it)) },
                        stringResource(R.string.help_no_tun),
                        !isRunning
                    )
                }
            }
        }
        // --- 高级路由配置 ---
        CollapsibleConfigSection(title = stringResource(R.string.advanced_settings)) {
            ConfigTextField(
                stringResource(R.string.proxy_networks),
                activeConfig.proxyNetworks,
                { onConfigChange(activeConfig.copy(proxyNetworks = it)) },
                enabled = !isRunning,
                singleLine = false,
                modifier = Modifier.height(100.dp),
                placeholder = stringResource(R.string.proxy_networks_placeholder)
            )
            ConfigSwitch(
                stringResource(R.string.manual_routes),
                activeConfig.enableManualRoutes,
                { onConfigChange(activeConfig.copy(enableManualRoutes = it)) },
                enabled = !isRunning
            )
            ConfigTextField(
                stringResource(R.string.manual_routes),
                activeConfig.routes,
                { onConfigChange(activeConfig.copy(routes = it)) },
                enabled = !isRunning && activeConfig.enableManualRoutes,
                singleLine = false,
                modifier = Modifier.height(120.dp),
                placeholder = stringResource(R.string.help_manual_routes)
            )
            ConfigTextField(
                stringResource(R.string.exit_nodes),
                activeConfig.exitNodes,
                { onConfigChange(activeConfig.copy(exitNodes = it)) },
                enabled = !isRunning,
                singleLine = false,
                modifier = Modifier.height(100.dp),
                placeholder = "List of Exit Nodes"
            )
        }
        // --- 服务与门户 ---
        CollapsibleConfigSection(title = "Services & Portal") {
            ConfigSwitchWithInlineHelp(
                "Enable SOCKS5",
                activeConfig.enableSocks5,
                { onConfigChange(activeConfig.copy(enableSocks5 = it)) },
                stringResource(R.string.help_socks5_server),
                enabled = !isRunning
            )
            OutlinedTextField(
                value = activeConfig.socks5Port.toString(),
                onValueChange = {
                    onConfigChange(
                        activeConfig.copy(
                            socks5Port = it.toIntOrNull() ?: 1080
                        )
                    )
                },
                label = { Text("SOCKS5 Port") },
                enabled = !isRunning && activeConfig.enableSocks5,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(16.dp))
            ConfigSwitch(
                "Enable VPN Portal",
                activeConfig.enableVpnPortal,
                { onConfigChange(activeConfig.copy(enableVpnPortal = it)) },
                enabled = !isRunning
            )
            ConfigTextField(
                "VPN Portal Client CIDR",
                activeConfig.vpnPortalClientNetworkAddr,
                { onConfigChange(activeConfig.copy(vpnPortalClientNetworkAddr = it)) },
                enabled = !isRunning && activeConfig.enableVpnPortal
            )
            OutlinedTextField(
                value = activeConfig.vpnPortalListenPort.toString(),
                onValueChange = {
                    onConfigChange(
                        activeConfig.copy(
                            vpnPortalListenPort = it.toIntOrNull() ?: 11011
                        )
                    )
                },
                label = { Text("VPN Portal Port") },
                enabled = !isRunning && activeConfig.enableVpnPortal,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            CollapsibleConfigSection(title = "RPC") {
                ConfigTextField(
                    label = "RPC Portal Address",
                    value = activeConfig.rpcPortal,
                    onValueChange = { onConfigChange(activeConfig.copy(rpcPortal = it)) },
                    enabled = !isRunning,
                    placeholder = "e.g. 0.0.0.0:15888"
                )

                ConfigTextField(
                    label = "RPC Whitelist",
                    value = activeConfig.rpcPortalWhitelist,
                    onValueChange = { onConfigChange(activeConfig.copy(rpcPortalWhitelist = it)) },
                    enabled = !isRunning,
                    singleLine = false,
                    modifier = Modifier.height(100.dp),
                    placeholder = "e.g. 127.0.0.1/24"
                )
            }
        }

        // --- 端口转发配置 ---
        CollapsibleConfigSection(title = stringResource(R.string.port_forwarding)) {
            // 添加帮助文本说明
            Text(
                text = stringResource(R.string.help_port_forward),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Display existing port forward rules
            activeConfig.portForwards.forEachIndexed { index, item ->
                PortForwardRow(
                    item = item,
                    onItemChange = { updatedItem ->
                        val newList = activeConfig.portForwards.toMutableList()
                        newList[index] = updatedItem
                        onConfigChange(activeConfig.copy(portForwards = newList))
                    },
                    onDeleteItem = {
                        val newList = activeConfig.portForwards.toMutableList()
                        newList.removeAt(index)
                        onConfigChange(activeConfig.copy(portForwards = newList))
                    },
                    isDeleteEnabled = !isRunning
                )
                if (index < activeConfig.portForwards.size - 1) {
                    Divider(Modifier.padding(vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            // Add new rule button
            Button(
                onClick = {
                    val newList = activeConfig.portForwards + PortForwardItem()
                    onConfigChange(activeConfig.copy(portForwards = newList))
                },
                enabled = !isRunning
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.add_rule))
            }
        }

        // --- 删除配置按钮 ---
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.delete_config_title)) },
                text = { Text(stringResource(R.string.delete_config_message, activeConfig.instanceName)) },
                confirmButton = {
                    Button(
                        { onDeleteConfig(activeConfig); showDeleteDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = { OutlinedButton({ showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }
}


@Composable
fun PortForwardRow(
    item: PortForwardItem,
    onItemChange: (PortForwardItem) -> Unit,
    onDeleteItem: () -> Unit,
    isDeleteEnabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(item.proto.uppercase())
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.select_protocol))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("TCP") },
                        onClick = { onItemChange(item.copy(proto = "tcp")); expanded = false })
                    DropdownMenuItem(
                        text = { Text("UDP") },
                        onClick = { onItemChange(item.copy(proto = "udp")); expanded = false })
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDeleteItem, enabled = isDeleteEnabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_rule),
                    tint = if (isDeleteEnabled) MaterialTheme.colorScheme.error else Color.Gray
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.bindIp,
                onValueChange = { onItemChange(item.copy(bindIp = it)) },
                label = { Text(stringResource(R.string.local_ip)) },
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = item.bindPort?.toString() ?: "",
                onValueChange = { onItemChange(item.copy(bindPort = it.toIntOrNull())) },
                label = { Text(stringResource(R.string.port)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.dstIp,
                onValueChange = { onItemChange(item.copy(dstIp = it)) },
                label = { Text(stringResource(R.string.dest_ip)) },
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = item.dstPort?.toString() ?: "",
                onValueChange = { onItemChange(item.copy(dstPort = it.toIntOrNull())) },
                label = { Text(stringResource(R.string.port)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

@Composable
fun CollapsibleConfigSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "折叠" else "展开",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth()
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = true,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = singleLine,
        keyboardOptions = keyboardOptions
    )
}

@Composable
fun ConfigSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}