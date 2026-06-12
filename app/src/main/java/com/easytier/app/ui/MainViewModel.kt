package com.easytier.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.easytier.app.ConfigData
import com.easytier.app.MainActivity
import com.easytier.app.SettingsRepository
import com.easytier.app.TomlConfig
import com.easytier.app.toConfigData
import com.easytier.app.toTomlConfig
import com.easytier.jni.DetailedNetworkInfo
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.EasyTierManager
import com.easytier.jni.EventInfo
import com.easytier.jni.NetworkInfoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.IOException
import com.easytier.app.R

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val settingsRepository = SettingsRepository(application)
    private var easyTierManager: EasyTierManager? = null

    // --- 状态变量 ---
    private val _toastEvents = MutableSharedFlow<String>()
    val toastEvents = _toastEvents.asSharedFlow()

    private val _allConfigs = mutableStateOf<List<ConfigData>>(emptyList())
    val allConfigs: State<List<ConfigData>> = _allConfigs

    private val _activeConfig = mutableStateOf(ConfigData())
    val activeConfig: State<ConfigData> = _activeConfig

    private val _statusState = mutableStateOf<EasyTierManager.EasyTierStatus?>(null)
    val statusState: State<EasyTierManager.EasyTierStatus?> = _statusState

    private val _detailedInfoState = mutableStateOf<DetailedNetworkInfo?>(null)
    val detailedInfoState: State<DetailedNetworkInfo?> = _detailedInfoState

    private val _fullEventHistory = mutableStateOf<List<EventInfo>>(emptyList())

    // 只存储完整的、原始的事件JSON字符串
    private val _fullRawEventHistory = mutableStateOf<List<String>>(emptyList())
    val fullRawEventHistory: State<List<String>> = _fullRawEventHistory

    private val _currentLanguage = mutableStateOf("zh") // Default to Chinese
    val currentLanguage: State<String> = _currentLanguage

    val isRunning: Boolean
        get() = _statusState.value?.isRunning == true

    init {
        viewModelScope.launch {
            loadAllConfigs()
            loadLanguage()
        }
        viewModelScope.launch {
            while (true) {
                _statusState.value = easyTierManager?.getStatus()
                if (isRunning) {
                    // 在循环中，同时刷新快照和收集新日志
                    refreshDetailedInfoSnapshot(false)
                    collectNewEvents()
                }
                delay(2000)
            }
        }
    }

    // --- 配置管理 ---

    private suspend fun loadLanguage() {
        val lang = settingsRepository.getLanguage()
        if (lang != null) {
            _currentLanguage.value = lang
        } else {
            // Default logic if needed, e.g., based on system locale
            // For now, default to "zh" as initialized
        }
    }

    fun setLanguage(language: String) {
        _currentLanguage.value = language
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
        }
    }

    private suspend fun loadAllConfigs() {
        val configs = settingsRepository.getAllConfigs()
        _allConfigs.value = if (configs.isEmpty()) listOf(ConfigData()) else configs
        val activeId = settingsRepository.getActiveConfigId()
        _activeConfig.value =
            _allConfigs.value.find { it.id == activeId } ?: _allConfigs.value.first()
    }

    fun setActiveConfig(config: ConfigData) {
        _activeConfig.value = config
        viewModelScope.launch {
            settingsRepository.setActiveConfigId(config.id)
        }
    }

    fun updateConfig(newConfig: ConfigData) {
        _activeConfig.value = newConfig
        val currentList = _allConfigs.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == newConfig.id }
        if (index != -1) {
            currentList[index] = newConfig
            _allConfigs.value = currentList
            viewModelScope.launch {
                settingsRepository.saveAllConfigs(currentList)
            }
        }
    }

    fun addNewConfig() {
        val existingNames = _allConfigs.value.map { it.instanceName }
        var newInstanceName = "easytier-${_allConfigs.value.size + 1}"
        var i = 2
        while (existingNames.contains(newInstanceName)) {
            newInstanceName = "easytier-$i"
            i++
        }
        val newConfig = ConfigData(instanceName = newInstanceName)
        _allConfigs.value = _allConfigs.value + newConfig
        setActiveConfig(newConfig)
        viewModelScope.launch {
            settingsRepository.saveAllConfigs(_allConfigs.value)
        }
    }

    fun deleteConfig(config: ConfigData) {
        val currentConfigs = _allConfigs.value

        // 保护逻辑：不允许删除最后一个配置
        if (currentConfigs.size <= 1) {
            viewModelScope.launch { _toastEvents.emit(getApplication<Application>().getString(R.string.cannot_delete_last_config)) }
            return
        }

        // 1在删除之前，记录下被删除项的索引
        val deletedIndex = currentConfigs.indexOfFirst { it.id == config.id }

        // 如果由于某种原因没找到，则不执行任何操作
        if (deletedIndex == -1) {
            Log.w(TAG, "Attempted to delete a config that does not exist in the list.")
            return
        }

        // 创建一个新的、不包含被删除项的列表
        val newList = currentConfigs.filterNot { it.id == config.id }
        _allConfigs.value = newList

        // 只有当被删除的配置是当前激活的配置时，才需要重新设置激活配置
        if (_activeConfig.value.id == config.id) {
            // 决定下一个激活项的索引
            // 如果被删除的是第一个，新的激活项就是新的第一个（索引仍为0）。
            // 否则，新的激活项就是被删除项的前一个（索引为 deletedIndex - 1）。
            val nextActiveIndex = (deletedIndex - 1).coerceAtLeast(0)

            // 从新列表中获取下一个要激活的配置并设置
            setActiveConfig(newList[nextActiveIndex])
        }

        // 将更新后的完整列表保存到 DataStore
        viewModelScope.launch {
            settingsRepository.saveAllConfigs(newList)
        }
    }

    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                // 1. 获取当前配置数据
                val configData = activeConfig.value

                // 2. 将 ConfigData 映射为 TomlConfig 并序列化
                val tomlString = generateTomlConfig(configData)

                // 3. 使用 ContentResolver 将字符串写入用户选择的文件
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(tomlString.toByteArray())
                }
                _toastEvents.emit(getApplication<Application>().getString(R.string.config_exported))
            } catch (e: Exception) {
                // 处理错误，例如显示错误消息
                e.printStackTrace()
            }
        }
    }

    // 导入配置
    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                // 1. 使用 ContentResolver 读取文件内容
                val stringBuilder = StringBuilder()
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).forEachLine {
                        stringBuilder.append(it).append("\n")
                    }
                }
                val tomlString = stringBuilder.toString()
                if (tomlString.isBlank()) {
                    _toastEvents.emit(getApplication<Application>().getString(R.string.import_failed_empty))
                    return@launch
                }


                // 2. 将 TOML 字符串反序列化为 TomlConfig 对象
                val tomlConfig = Toml.decodeFromString<TomlConfig>(tomlString)

                // 3. 将 TomlConfig 对象映射回应用的 ConfigData 对象
                var importedConfig = tomlConfig.toConfigData()

                // --- 4. 集成到现有配置管理逻辑中 ---

                val currentConfigs = _allConfigs.value
                val existingIds = currentConfigs.map { it.id }
                val existingNames = currentConfigs.map { it.instanceName }

                // 检查 ID 冲突：如果导入的 ID 已存在，则生成一个新的 ID
                if (existingIds.contains(importedConfig.id)) {
                    importedConfig = importedConfig.copy(id = UUID.randomUUID().toString())
                }

                // 检查名称冲突：如果名称已存在，则添加后缀，类似 addNewConfig 的逻辑
                var newName = importedConfig.instanceName
                var counter = 2
                while (existingNames.contains(newName)) {
                    newName = "${importedConfig.instanceName}_$counter"
                    counter++
                }
                if (newName != importedConfig.instanceName) {
                    importedConfig = importedConfig.copy(instanceName = newName)
                }

                // 5. 将处理好的新配置添加到列表，并保存
                val newList = currentConfigs + importedConfig
                _allConfigs.value = newList
                setActiveConfig(importedConfig) // 将导入的配置设为当前活动配置

                settingsRepository.saveAllConfigs(newList)

                // 6. 发送成功提示
                _toastEvents.emit(getApplication<Application>().getString(R.string.import_success, importedConfig.instanceName))

            } catch (e: kotlinx.serialization.SerializationException) {
                _toastEvents.emit(getApplication<Application>().getString(R.string.import_failed_format))
                Log.e(TAG, "TOML parsing failed", e)
            } catch (e: Exception) {
                _toastEvents.emit(getApplication<Application>().getString(R.string.import_failed_unknown))
                Log.e(TAG, "Import failed", e)
            }
        }
    }


    // --- 服务生命周期 ---

    fun handleControlButtonClick(activity: MainActivity) {
        if (isRunning) {
            stopEasyTier()
        } else {
            activity.requestVpnPermission()
        }
    }

    fun startEasyTier(activity: ComponentActivity) {
        if (isRunning) {
            Log.w(TAG, "EasyTier is already running.")
            return
        }

        try {
            val configToml = generateTomlConfig1(_activeConfig.value)
            Log.d(TAG, "Generated Config:\n$configToml")

            val timeStamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                java.util.Locale.getDefault()
            ).format(java.util.Date())


            val tomlLogEntry = JSONObject()
                .put("time", timeStamp)
                .put(
                    "event",
                    JSONObject().put(
                        "GeneratedTomlConfig",
                        "\n--- 使用的TOML配置 ---\n$configToml\n-----------------------------"
                    )
                )
                .toString()

            _fullRawEventHistory.value = listOf(tomlLogEntry)
            _fullEventHistory.value = emptyList()

            easyTierManager = EasyTierManager(
                activity = activity,
                instanceName = _activeConfig.value.instanceName,
                networkConfig = configToml
            )
            easyTierManager?.start()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library load failed", e)
            easyTierManager = null
            viewModelScope.launch { _toastEvents.emit("本地库加载失败: ${e.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start EasyTier", e)
            easyTierManager = null
            viewModelScope.launch { _toastEvents.emit("启动失败: ${e.message}") }
        }
    }

    fun stopEasyTier() {
        easyTierManager?.stop()
        easyTierManager = null
        _statusState.value = null
        _detailedInfoState.value = null
        _fullEventHistory.value = emptyList() // 清空UI用的日志
        _fullRawEventHistory.value = emptyList() //清空原始日志历史
    }

    override fun onCleared() {
        super.onCleared()
        stopEasyTier()
    }

    // --- 数据获取与导出 ---

    fun manualRefreshDetailedInfo() {
        viewModelScope.launch {
            refreshDetailedInfoSnapshot(true)
        }
    }

    private suspend fun refreshDetailedInfoSnapshot(showToast: Boolean) {
        if (easyTierManager == null || !isRunning) {
            _detailedInfoState.value = null
            if (showToast) viewModelScope.launch { _toastEvents.emit("服务未运行") }
            return
        }
        val info = withContext(Dispatchers.IO) {
            try {
                val jsonString = EasyTierJNI.collectNetworkInfos(10)
                if (jsonString != null) {
                    Log.d(TAG, "collectNetworkInfos returned ${jsonString.length} chars, parsing for instance: ${_activeConfig.value.instanceName}")
                    val result = NetworkInfoParser.parse(jsonString, _activeConfig.value.instanceName)
                    if (result != null) {
                        Log.d(TAG, "Detailed info parsed: ${result.finalPeerList.size} peers, hostname=${result.myNode.hostname}")
                    } else {
                        Log.w(TAG, "NetworkInfoParser.parse returned null. JSON preview: ${jsonString.take(500)}")
                    }
                    result
                } else {
                    Log.d(TAG, "collectNetworkInfos returned null")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse detailed info snapshot", e)
                if (showToast) viewModelScope.launch { _toastEvents.emit("解析信息失败: ${e.message}") }
                null
            }
        }
        _detailedInfoState.value = info
        if (showToast && info != null) {
            viewModelScope.launch { _toastEvents.emit("详细信息已刷新") }
        }
    }

    private suspend fun collectNewEvents() {
        if (!isRunning) return

        val fullJsonString = getRawJsonForClipboard() ?: return

        // 1. 直接从快照中提取原始事件字符串列表
        val snapshotRawEvents = withContext(Dispatchers.Default) {
            NetworkInfoParser.extractRawEventStrings(
                fullJsonString,
                _activeConfig.value.instanceName
            )
        }

        if (snapshotRawEvents.isEmpty()) return

        // 2. 使用原始字符串进行高效去重
        val currentHistory = _fullRawEventHistory.value
        val lastKnownEvent = currentHistory.lastOrNull()

        val newRawEventsToAdd = if (lastKnownEvent != null) {
            val lastIndex = snapshotRawEvents.lastIndexOf(lastKnownEvent)
            if (lastIndex != -1) {
                snapshotRawEvents.subList(lastIndex + 1, snapshotRawEvents.size)
            } else {
                Log.w(TAG, "Log buffer wrap-around detected. History may have gaps.")
                snapshotRawEvents // 缓冲区轮转，接受所有新日志
            }
        } else {
            snapshotRawEvents // 首次收集
        }

        // 3. 如果有新事件，直接添加到历史记录
        if (newRawEventsToAdd.isNotEmpty()) {
            _fullRawEventHistory.value = currentHistory + newRawEventsToAdd
        }
    }

    suspend fun getRawJsonForClipboard(): String? {
        if (easyTierManager == null || !isRunning) return null
        return withContext(Dispatchers.IO) {
            try {
                EasyTierJNI.collectNetworkInfos(10)
            } catch (e: Exception) {
                Log.e(TAG, "getRawJsonForClipboard failed", e)
                null
            }
        }
    }

    fun copyJsonToClipboard() {
        viewModelScope.launch {
            val jsonString = getRawJsonForClipboard()
            if (!jsonString.isNullOrBlank()) {
                val clipboard =
                    getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Network Info JSON", jsonString)
                clipboard.setPrimaryClip(clip)
                _toastEvents.emit("JSON 已复制到剪贴板")
            } else {
                _toastEvents.emit("获取信息失败")
            }
        }
    }

    /**
     * 获取用于“导出”功能的、格式化后的人类可读日志字符串。
     * 这个方法的数据源是 _fullEventHistory，确保了日志的完整性。
     */
    fun getFormattedLogsForExport(): String? {
        // 从 _fullEventHistory 获取完整的、不断累积的事件列表
        val events = _fullEventHistory.value

        if (events.isEmpty()) {
            return null // 如果没有历史记录，返回 null
        }

        // 将 EventInfo 对象列表格式化为一个单一的、多行的字符串
        return events.joinToString(separator = "\n") { event ->
            "[${event.time}] [${event.level}] ${event.message}"
        }
    }

    /**
     * 导出原始事件的方法
     */
    suspend fun getRawEventsJsonForExport(): String? {
        val rawEvents = _fullRawEventHistory.value
        if (rawEvents.isEmpty()) return null

        // 直接将原始字符串列表拼接成一个格式化的JSON数组字符串
        return withContext(Dispatchers.Default) {
            rawEvents.joinToString(
                separator = ",\n    ",
                prefix = "[\n    ",
                postfix = "\n]"
            )
        }
    }

    fun writeContentToUri(uri: Uri, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?.use { it.write(content.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "日志已成功导出", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write logs to file", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "导出失败: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    fun exportDebugLogs(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("=== EasyTier Debug Logs ===")
                sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}")
                sb.appendLine()
                
                // 1. Current config info
                val cfg = _activeConfig.value
                sb.appendLine("=== Active Config ===")
                sb.appendLine("Instance Name: ${cfg.instanceName}")
                sb.appendLine("Instance ID: ${cfg.id}")
                sb.appendLine("Network Name: ${cfg.networkName}")
                sb.appendLine("DHCP: ${cfg.dhcp}")
                sb.appendLine("Virtual IPv4: ${cfg.virtualIpv4}/${cfg.networkLength}")
                sb.appendLine("Peers: ${cfg.peers}")
                sb.appendLine("Enabled Flags: ${listOf(
                    "latencyFirst" to cfg.latencyFirst, "useSmoltcp" to cfg.useSmoltcp,
                    "disableIpv6" to cfg.disableIpv6, "enableKcpProxy" to cfg.enableKcpProxy,
                    "disableP2p" to cfg.disableP2p, "bindDevice" to cfg.bindDevice,
                    "noTun" to cfg.noTun, "enableExitNode" to cfg.enableExitNode,
                    "multiThread" to cfg.multiThread, "disableEncryption" to cfg.disableEncryption,
                    "acceptDns" to cfg.acceptDns, "privateMode" to cfg.privateMode
                ).filter { it.second }.joinToString(", ") { it.first }}")
                sb.appendLine()
                
                // 2. Service status
                val status = easyTierManager?.getStatus()
                sb.appendLine("=== Service Status ===")
                sb.appendLine("isRunning: ${status?.isRunning ?: "N/A"}")
                sb.appendLine("currentIpv4: ${status?.currentIpv4 ?: "N/A"}")
                sb.appendLine("proxyCidrs: ${status?.currentProxyCidrs?.joinToString(", ") ?: "N/A"}")
                sb.appendLine()
                
                // 3. Raw network info JSON
                sb.appendLine("=== Raw Network Info JSON ===")
                val jsonString = EasyTierJNI.collectNetworkInfos(10)
                if (jsonString != null) {
                    sb.appendLine("Length: ${jsonString.length} chars")
                    sb.appendLine(jsonString)
                } else {
                    sb.appendLine("collectNetworkInfos returned null")
                }
                sb.appendLine()
                
                // 4. System logcat (app process only)
                sb.appendLine("=== System Logcat (filter: MainViewModel, NetworkInfoParser, EasyTierManager) ===")
                try {
                    val pid = android.os.Process.myPid()
                    val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=${pid}", "-v", "threadtime"))
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                    val logLines = reader.readLines()
                        .filter { line ->
                            line.contains("MainViewModel") || line.contains("NetworkInfoParser") || 
                            line.contains("EasyTierManager") || line.contains("EasyTierVpnService") ||
                            line.contains("AndroidRuntime")
                        }
                    sb.appendLine("Total lines: ${logLines.size}")
                    logLines.forEach { sb.appendLine(it) }
                    process.waitFor()
                } catch (e: Exception) {
                    sb.appendLine("Failed to read logcat: ${e.message}")
                }
                
                // 5. Write to file
                val content = sb.toString()
                getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?.use { it.write(content.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "调试日志已导出 (${content.length} bytes)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export debug logs", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- 工具函数 ---

    private fun generateTomlConfig1(data: ConfigData): String {
        val sb = StringBuilder()

        // --- 辅助函数 ---
        fun appendIf(condition: Boolean, text: String) {
            if (condition) sb.appendLine(text)
        }
        fun appendString(key: String, value: String) {
            if (value.isNotBlank()) sb.appendLine("$key = \"$value\"")
        }
        fun appendStringList(key: String, value: String) {
            if (value.isNotBlank()) {
                val formatted = value.lines().filter { it.isNotBlank() }.joinToString(", ") { "\"$it\"" }
                if (formatted.isNotEmpty()) {
                    sb.appendLine("$key = [$formatted]")
                }
            }
        }

        // --- 顶级键值对 ---
        appendString("hostname", data.hostname)
        appendString("instance_name", data.instanceName)
        sb.appendLine("instance_id = \"${data.id}\"")

        if (data.dhcp) {
            sb.appendLine("dhcp = true")
        } else {
            sb.appendLine("dhcp = false")
            if (data.virtualIpv4.isNotBlank()) {
                sb.appendLine("ipv4 = \"${data.virtualIpv4}/${data.networkLength}\"")
            }
        }

        appendStringList("listeners", data.listenerUrls)
        appendStringList("mapped_listeners", data.mappedListeners)
        appendStringList("exit_nodes", data.exitNodes)
        appendString("rpc_portal", data.rpcPortal)
        appendStringList("rpc_portal_whitelist", data.rpcPortalWhitelist)
        appendStringList("routes", data.routes)

        if (data.enableSocks5) {
            sb.appendLine("socks5_proxy = \"socks5://0.0.0.0:${data.socks5Port}\"")
        }

        // --- [network_identity] ---
        sb.appendLine("\n[network_identity]")
        appendString("network_name", data.networkName)
        appendString("network_secret", data.networkSecret)

        // --- [[peer]] ---
        data.peers.lines().filter { it.isNotBlank() }.forEach {
            sb.appendLine("\n[[peer]]")
            sb.appendLine("uri = \"$it\"")
        }

        data.proxyNetworks.lines().filter { it.isNotBlank() }.forEach {
            sb.appendLine("\n[[proxy_network]]")
            sb.appendLine("cidr = \"$it\"")
        }

        // --- [vpn_portal_config] ---
        if (data.enableVpnPortal) {
            sb.appendLine("\n[vpn_portal_config]")
            sb.appendLine("client_cidr = \"${data.vpnPortalClientNetworkAddr}/${data.vpnPortalClientNetworkLen}\"")
            sb.appendLine("wireguard_listen = \"0.0.0.0:${data.vpnPortalListenPort}\"")
        }

        // --- [[port_forward]] ---
        data.portForwards.forEach { pf ->
            if (pf.bindPort != null && pf.dstPort != null && pf.dstIp.isNotBlank()) {
                sb.appendLine("\n[[port_forward]]")
                sb.appendLine("bind_addr = \"${pf.bindIp}:${pf.bindPort}\"")
                sb.appendLine("dst_addr = \"${pf.dstIp}:${pf.dstPort}\"")
                sb.appendLine("proto = \"${pf.proto}\"")
            }
        }

        // --- [flags] ---
        val defaultFlags = ConfigData() // 获取一个包含所有默认值的实例
        val flagLines = mutableListOf<String>()

        // 比较并只添加与默认值不同的 flags
        if (data.devName.isNotBlank()) flagLines.add("dev_name = \"${data.devName}\"")
        if (data.mtu.isNotBlank()) flagLines.add("mtu = ${data.mtu}")
        if (data.relayNetworkWhitelist != defaultFlags.relayNetworkWhitelist && data.enableRelayNetworkWhitelist) {
            flagLines.add("relay_network_whitelist = \"${data.relayNetworkWhitelist}\"")
        }

        // Booleans
        if (data.latencyFirst != defaultFlags.latencyFirst) flagLines.add("latency_first = ${data.latencyFirst}")
        // WARNING: use_smoltcp=true may cause native crash on Android.
        // SmolTCP is a userspace TCP/IP stack that conflicts with Android VpnService TUN.
        // if (data.useSmoltcp != defaultFlags.useSmoltcp) flagLines.add("use_smoltcp = ${data.useSmoltcp}")
        // WARNING: disable_ipv6=true causes native SIGSEGV on Android.
        // The VPN TUN device setup requires IPv6 support in Android VpnService.
        // if (data.disableIpv6 != defaultFlags.disableIpv6) flagLines.add("disable_ipv6 = ${data.disableIpv6}")
        if (data.enableKcpProxy != defaultFlags.enableKcpProxy) flagLines.add("enable_kcp_proxy = ${data.enableKcpProxy}")
        if (data.disableKcpInput != defaultFlags.disableKcpInput) flagLines.add("disable_kcp_input = ${data.disableKcpInput}")
        if (data.enableQuicProxy != defaultFlags.enableQuicProxy) flagLines.add("enable_quic_proxy = ${data.enableQuicProxy}")
        if (data.disableQuicInput != defaultFlags.disableQuicInput) flagLines.add("disable_quic_input = ${data.disableQuicInput}")
        if (data.disableP2p != defaultFlags.disableP2p) flagLines.add("disable_p2p = ${data.disableP2p}")
        // WARNING: bind_device=true causes native crash on Android.
        // SO_BINDTODEVICE requires CAP_NET_RAW capability, unavailable to normal apps.
        // if (data.bindDevice != defaultFlags.bindDevice) flagLines.add("bind_device = ${data.bindDevice}")
        // WARNING: no_tun=true is fundamentally incompatible with Android VpnService.
        // Android VpnService always creates a TUN fd and passes it via setTunFd().
        // Setting no_tun=true causes a fatal mismatch.
        // if (data.noTun != defaultFlags.noTun) flagLines.add("no_tun = ${data.noTun}")
        if (data.enableExitNode != defaultFlags.enableExitNode) flagLines.add("enable_exit_node = ${data.enableExitNode}")
        if (data.relayAllPeerRpc != defaultFlags.relayAllPeerRpc) flagLines.add("relay_all_peer_rpc = ${data.relayAllPeerRpc}")
        if (data.multiThread != defaultFlags.multiThread) flagLines.add("multi_thread = ${data.multiThread}")
        if (data.proxyForwardBySystem != defaultFlags.proxyForwardBySystem) flagLines.add("proxy_forward_by_system = ${data.proxyForwardBySystem}")
        // WARNING: disable_encryption=true causes native SIGSEGV on Android.
        // The Android VPN implementation requires WireGuard encryption to be enabled.
        // if (data.disableEncryption != defaultFlags.disableEncryption) flagLines.add("disable_encryption = ${data.disableEncryption}")
        if (data.disableUdpHolePunching != defaultFlags.disableUdpHolePunching) flagLines.add("disable_udp_hole_punching = ${data.disableUdpHolePunching}")
        if (data.disableSymHolePunching != defaultFlags.disableSymHolePunching) flagLines.add("disable_sym_hole_punching = ${data.disableSymHolePunching}")
        if (data.acceptDns != defaultFlags.acceptDns) flagLines.add("accept_dns = ${data.acceptDns}")
        if (data.privateMode != defaultFlags.privateMode) flagLines.add("private_mode = ${data.privateMode}")

        if (flagLines.isNotEmpty()) {
            sb.appendLine("\n[flags]")
            flagLines.forEach { sb.appendLine(it) }
        }

        return sb.toString()
    }

    /**
     * 将 ConfigData 对象序列化为 TOML 格式的字符串。
     * @param data 包含所有配置信息的 ConfigData 实例。
     * @return 格式化好的 TOML 字符串。
     */
    private fun generateTomlConfig(data: ConfigData): String {
        // 1. 将你的应用数据模型映射到可序列化的数据模型
        val serializableConfig = data.toTomlConfig()

        // 2. 使用 ktoml 库进行序列化
        //    这里的 Toml 实例可以配置，但默认行为通常就足够了
        return Toml.encodeToString(serializableConfig)
    }
}
