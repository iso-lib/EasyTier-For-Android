package com.easytier.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.easytier.app.R
import com.easytier.jni.FinalPeerInfo
import com.easytier.app.ui.StatusRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailScreen(peer: FinalPeerInfo, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${stringResource(R.string.peer_detail_title)}: ${peer.hostname}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel)) // Reuse cancel or back string if available, using cancel for now or add back
                    }
                }
            )
        }
    ) { paddingValues ->
        //使用可滚动的Column并增加卡片间距
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 卡片1: 连接状态 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.base_info), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Divider(Modifier.padding(vertical = 8.dp))

                    StatusRow(stringResource(R.string.hostname), peer.hostname)
                    StatusRow(stringResource(R.string.virtual_ipv4), peer.virtualIp, isCopyable = true)
                    StatusRow(stringResource(R.string.path_type), if (peer.isDirectConnection) stringResource(R.string.p2p) else stringResource(R.string.relay))
                    StatusRow(if (peer.isDirectConnection) stringResource(R.string.remote_address) else stringResource(R.string.connection_path), peer.connectionDetails, isCopyable = true)
                }
            }

            // --- 卡片2: 性能与路由 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.connection_stats), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Divider(Modifier.padding(vertical = 8.dp))

                    StatusRow(stringResource(R.string.latency), peer.latency)
                    StatusRow(stringResource(R.string.upload_bytes), peer.traffic) // Assuming traffic string contains both
                    StatusRow(stringResource(R.string.route_cost), peer.routeCost.toString())
                    StatusRow("Next Hop:", peer.nextHopPeerId.toString())
                }
            }

            // --- 卡片3: 节点信息 ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.peer_detail_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Divider(Modifier.padding(vertical = 8.dp))

                    StatusRow(stringResource(R.string.version), peer.version)
                    StatusRow("NAT Type:", peer.natType)
                    StatusRow(stringResource(R.string.peer_id), peer.peerId.toString())
                    StatusRow("Instance ID:", peer.instId)
                }
            }
        }
    }
}
