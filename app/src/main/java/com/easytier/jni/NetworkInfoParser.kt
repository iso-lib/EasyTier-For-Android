package com.easytier.jni

import android.util.Log
import androidx.annotation.Keep
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

/**
 * 【JSON解析工具类】
 * 负责将从EasyTier JNI获取的JSON字符串解析为结构化的Kotlin数据对象。
 */
object NetworkInfoParser {

    // --- 公共入口函数 ---

    /**
     * 主解析函数，用于解析完整的网络快照。
     * @param jsonString 从JNI获取的原始JSON字符串。
     * @param instanceName 当前运行的实例名称。
     * @return 一个包含所有解析信息的 DetailedNetworkInfo 对象。
     */
    fun parse(jsonString: String, instanceName: String): DetailedNetworkInfo? {
        try {
            val root = JSONObject(jsonString)
            val mapObj = root.optJSONObject("map")
            
            if (mapObj == null) {
                Log.e("NetworkInfoParser", "JSON missing 'map' key. Keys: ${root.keys().asSequence().toList()}")
                Log.d("NetworkInfoParser", "JSON preview (first 300 chars): ${jsonString.take(300)}")
                return null
            }
            
            val instance = mapObj.optJSONObject(instanceName)
            
            if (instance == null) {
                val availableKeys = mapObj.keys().asSequence().toList()
                Log.e("NetworkInfoParser", "Instance '$instanceName' not found in map. Available keys: $availableKeys")
                // Fall back to first available key (most common case: single-instance mismatch)
                if (availableKeys.isNotEmpty()) {
                    val fallbackKey = availableKeys.first()
                    Log.w("NetworkInfoParser", "Falling back to first available key: '$fallbackKey'")
                    val fallbackInstance = mapObj.optJSONObject(fallbackKey)
                    if (fallbackInstance != null) {
                        return parseImpl(fallbackInstance)
                    }
                }
                return null
            }
            
            return parseImpl(instance)
        } catch (e: Exception) {
            Log.e("NetworkInfoParser", "Top-level parse failed", e)
            return null
        }
    }
    
    private fun parseImpl(instance: JSONObject): DetailedNetworkInfo {

        val myNodeJson = instance.optJSONObject("my_node_info") ?: JSONObject()
        val routesJson = instance.optJSONArray("routes") ?: org.json.JSONArray()
        val peersJson = instance.optJSONArray("peers") ?: org.json.JSONArray()
        val eventsJson = instance.optJSONArray("events") ?: org.json.JSONArray()
        
        val myNode = parseMyNodeInfo(myNodeJson)
        val routesMap = parseRoutes(routesJson)
        val peersMap = parsePeers(peersJson)
        val snapshotEvents = parseEventList(eventsJson)

        val finalPeerList = routesMap.values.map { route ->
            val peerConn = peersMap[route.peerId]
            if (peerConn != null) {
                FinalPeerInfo(
                    hostname = route.hostname,
                    virtualIp = route.virtualIp,
                    isDirectConnection = true,
                    connectionDetails = peerConn.physicalAddr,
                    latency = "${peerConn.latencyUs / 1000} ms",
                    traffic = "${formatBytes(peerConn.rxBytes)} / ${formatBytes(peerConn.txBytes)}",
                    version = route.version,
                    natType = route.natType,
                    routeCost = route.cost,
                    nextHopPeerId = route.nextHopPeerId,
                    peerId = route.peerId,
                    instId = route.instId
                )
            } else {
                val nextHopHostname = routesMap[route.nextHopPeerId]?.hostname ?: "未知"
                FinalPeerInfo(
                    hostname = route.hostname,
                    virtualIp = route.virtualIp,
                    isDirectConnection = false,
                    connectionDetails = "通过 $nextHopHostname",
                    latency = "${route.pathLatency} ms (路径)",
                    traffic = "N/A",
                    version = route.version,
                    natType = route.natType,
                    routeCost = route.cost,
                    nextHopPeerId = route.nextHopPeerId,
                    peerId = route.peerId,
                    instId = route.instId
                )
            }
        }

        return DetailedNetworkInfo(
            myNode = myNode,
            events = snapshotEvents,
            finalPeerList = finalPeerList.sortedBy { it.hostname }
        )
    }

    /**
     * 从完整的网络快照JSON中，提取出原始的事件JSON字符串数组。
     * 供 ViewModel 的增量日志收集逻辑调用。
     */
    fun extractRawEventStrings(jsonString: String, instanceName: String): List<String> {
        return try {
            val root = JSONObject(jsonString)
            val instance = root.getJSONObject("map").getJSONObject(instanceName)
            val eventsArray = instance.getJSONArray("events")
            (0 until eventsArray.length()).map { eventsArray.getString(it) }.reversed()
        } catch (e: Exception) {
            Log.e("NetworkInfoParser", "Failed to extract raw event strings", e)
            emptyList()
        }
    }

    // --- 私有解析函数 ---

    private fun parseMyNodeInfo(myNodeJson: JSONObject): MyNodeInfo {
        val myStunInfoJson = myNodeJson.optJSONObject("stun_info") ?: JSONObject()
        val ipsJson = myNodeJson.optJSONObject("ips") ?: JSONObject()
        val virtualIpv4Json = myNodeJson.optJSONObject("virtual_ipv4")
        val virtualIp = if (virtualIpv4Json != null) {
            "${
                parseIntegerToIp(
                    virtualIpv4Json.getJSONObject("address").getInt("addr")
                )
            }/${virtualIpv4Json.getInt("network_length")}"
        } else {
            "正在获取中..."
        }
        val listenersArray = myNodeJson.optJSONArray("listeners")
        val listenersList = if (listenersArray != null) {
            (0 until listenersArray.length()).map {
                listenersArray.getJSONObject(it).getString("url")
            }
        } else emptyList()
        val interfaceIpv4sArray = ipsJson.optJSONArray("interface_ipv4s")
        val interfaceIpsList = if (interfaceIpv4sArray != null) {
            (0 until interfaceIpv4sArray.length()).map {
                parseIntegerToIp(interfaceIpv4sArray.getJSONObject(it).getInt("addr"))
            }
        } else emptyList()
        val publicIpsArray = myStunInfoJson.optJSONArray("public_ip")
        val publicIpsStr =
            if (publicIpsArray != null && publicIpsArray.length() > 0) (0 until publicIpsArray.length()).joinToString(", ") {
                publicIpsArray.getString(it)
            } else "N/A"

        return MyNodeInfo(
            hostname = myNodeJson.optString("hostname", "未知"),
            version = myNodeJson.optString("version", "未知"),
            virtualIp = virtualIp, publicIp = publicIpsStr,
            natType = if (myStunInfoJson.has("udp_nat_type")) parseNatType(myStunInfoJson.getInt("udp_nat_type")) else "未知",
            listeners = listenersList, interfaceIps = interfaceIpsList
        )
    }

    private fun parseRoutes(routesJson: org.json.JSONArray): Map<Long, RouteData> {
        return (0 until routesJson.length()).mapNotNull { i ->
            try {
                val route = routesJson.getJSONObject(i)
                val peerId = route.getLong("peer_id")
                val ipv4AddrJson = route.optJSONObject("ipv4_addr")
                val virtualIp = if (ipv4AddrJson != null) parseIntegerToIp(
                    ipv4AddrJson.getJSONObject("address").getInt("addr")
                ) else "无虚拟IP"
                val stunInfoJson = route.optJSONObject("stun_info")
                peerId to RouteData(
                    peerId = peerId,
                    hostname = route.optString("hostname", "未知"),
                    virtualIp = virtualIp,
                    nextHopPeerId = route.optLong("next_hop_peer_id", 0),
                    pathLatency = route.optInt("path_latency", 0),
                    cost = route.optInt("cost", 0),
                    version = route.optString("version", "未知"),
                    natType = if (stunInfoJson != null) parseNatType(stunInfoJson.optInt("udp_nat_type", 0)) else "未知",
                    instId = route.optString("inst_id", "未知")
                )
            } catch (e: Exception) {
                Log.e("NetworkInfoParser", "Failed to parse route at index $i", e)
                null
            }
        }.toMap()
    }

    private fun parsePeers(peersJson: org.json.JSONArray): Map<Long, PeerConnectionData> {
        val peersMap = mutableMapOf<Long, PeerConnectionData>()
        for (i in 0 until peersJson.length()) {
            try {
                val peer = peersJson.getJSONObject(i)
                val conns = peer.optJSONArray("conns")
                if (conns != null && conns.length() > 0) {
                    val conn = conns.getJSONObject(0)
                    val peerId = conn.getLong("peer_id")
                    val tunnel = conn.optJSONObject("tunnel")
                    val remoteUrl = tunnel?.optJSONObject("remote_addr")?.optString("url", "未知") ?: "未知"
                    val stats = conn.optJSONObject("stats")
                    peersMap[peerId] = PeerConnectionData(
                        peerId = peerId,
                        physicalAddr = remoteUrl,
                        latencyUs = stats?.optLong("latency_us", 0) ?: 0,
                        rxBytes = stats?.optLong("rx_bytes", 0) ?: 0,
                        txBytes = stats?.optLong("tx_bytes", 0) ?: 0
                    )
                }
            } catch (e: Exception) {
                Log.e("NetworkInfoParser", "Failed to parse peer at index $i", e)
            }
        }
        return peersMap
    }

    private fun parseEventList(eventsJson: org.json.JSONArray): List<EventInfo> {
        val rawEventStrings =
            (0 until eventsJson.length()).map { eventsJson.getString(it) }.reversed()
        return rawEventStrings.mapNotNull { parseSingleRawEvent(it) }
    }

    fun parseSingleRawEvent(eventStr: String): EventInfo? {
        return try {
            val eventJson = JSONObject(eventStr)
            val rawTime = eventJson.getString("time")
            val time = rawTime.substring(11, 19)
            val eventObject = eventJson.getJSONObject("event")
            val eventType = eventObject.keys().next()

            val (message, level) = when (eventType) {
                "GeneratedTomlConfig" -> {
                    val tomlContent = eventObject.getString("GeneratedTomlConfig")
                    tomlContent to EventInfo.Level.INFO
                }

                "PeerConnAdded" -> {
                    val conn = eventObject.getJSONObject("PeerConnAdded")
                    val peerId = conn.getLong("peer_id").toString().takeLast(4)
                    val tunnelType =
                        conn.getJSONObject("tunnel").getString("tunnel_type").uppercase()
                    val remoteAddr =
                        conn.getJSONObject("tunnel").getJSONObject("remote_addr").getString("url")
                    "[$tunnelType] 节点($peerId)已连接: $remoteAddr" to EventInfo.Level.SUCCESS
                }

                "PeerConnRemoved" -> "节点(${
                    eventObject.getJSONObject("PeerConnRemoved").getLong("peer_id").toString()
                        .takeLast(4)
                })连接已断开" to EventInfo.Level.WARNING

                "PeerAdded" -> "发现新节点(${
                    eventObject.getLong("PeerAdded").toString().takeLast(4)
                })" to EventInfo.Level.INFO

                "PeerRemoved" -> "节点(${
                    eventObject.getLong("PeerRemoved").toString().takeLast(4)
                })已移除" to EventInfo.Level.WARNING

                "ConnectionAccepted" -> "接受来自 ${
                    eventObject.getJSONArray("ConnectionAccepted").getString(1)
                } 的连接" to EventInfo.Level.SUCCESS

                "ConnectionError" -> "连接错误: ${
                    eventObject.getJSONArray("ConnectionError").getString(2)
                }" to EventInfo.Level.ERROR

                "ListenerAdded" -> "开始监听: ${eventObject.getString("ListenerAdded")}" to EventInfo.Level.INFO
                "Connecting" -> "正在连接: ${eventObject.getString("Connecting")}" to EventInfo.Level.INFO
                "TunDeviceReady" -> "虚拟网卡已就绪" to EventInfo.Level.SUCCESS
                "DhcpIpv4Changed" -> {
                    val arr = eventObject.getJSONArray("DhcpIpv4Changed")
                    val oldIp = arr.optString(0, "无")
                    val newIp = arr.optString(1, "N/A")
                    "DHCP IP 变更: $oldIp -> $newIp" to EventInfo.Level.INFO
                }

                else -> {
                    val content = eventObject.get(eventType).toString()
                    "$eventType: $content" to EventInfo.Level.INFO
                }
            }
            EventInfo(time, message, level, rawTime)
        } catch (e: Exception) {
            Log.e("NetworkInfoParser", "Failed to parse single event string: $eventStr", e)
            null
        }
    }

    // --- 辅助函数 ---

    private fun parseIntegerToIp(addr: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            (addr ushr 24) and 0xFF, (addr ushr 16) and 0xFF, (addr ushr 8) and 0xFF, addr and 0xFF
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
    }

    private fun parseNatType(typeCode: Int): String {
        return when (typeCode) {
            0 -> "Unknown (未知类型)"; 1 -> "Open Internet (开放互联网)"; 2 -> "No PAT (无端口转换)"
            3 -> "Full Cone (完全锥形)"; 4 -> "Restricted Cone (限制锥形)"; 5 -> "Port Restricted (端口限制锥形)"
            6 -> "Symmetric (对称型)"; 7 -> "Symmetric UDP Firewall (对称UDP防火墙)"
            8 -> "Symmetric Easy Inc (对称型-端口递增)"; 9 -> "Symmetric Easy Dec (对称型-端口递减)"
            else -> "Other Type ($typeCode)"
        }
    }
}

// --- 数据模型 (Data Models) ---
@Keep
data class DetailedNetworkInfo(
    val myNode: MyNodeInfo,
    val events: List<EventInfo>,
    val finalPeerList: List<FinalPeerInfo>
)

@Keep
data class MyNodeInfo(
    val hostname: String,
    val version: String,
    val virtualIp: String,
    val publicIp: String,
    val natType: String,
    val listeners: List<String>,
    val interfaceIps: List<String>
)

@Keep
data class EventInfo(val time: String, val message: String, val level: Level, val rawTime: String) {
    enum class Level { INFO, SUCCESS, WARNING, ERROR, CONFIG }
}

@Keep
data class RouteData(
    val peerId: Long,
    val hostname: String,
    val virtualIp: String,
    val nextHopPeerId: Long,
    val pathLatency: Int,
    val cost: Int,
    val version: String,
    val natType: String,
    val instId: String
)

@Keep
data class PeerConnectionData(
    val peerId: Long,
    val physicalAddr: String,
    val latencyUs: Long,
    val rxBytes: Long,
    val txBytes: Long
)

@Keep
data class FinalPeerInfo(
    val hostname: String,
    val virtualIp: String,
    val isDirectConnection: Boolean,
    val connectionDetails: String,
    val latency: String,
    val traffic: String,
    val version: String,
    val natType: String,
    val routeCost: Int,
    val nextHopPeerId: Long,
    val peerId: Long,
    val instId: String
)