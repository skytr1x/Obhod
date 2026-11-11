package com.skytr1x.obhod.vpn

import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.telephony.TelephonyManager
import org.json.JSONObject
import com.skytr1x.obhod.bridge.NativeBridge
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.network.DnsOverHttpsResolver
import com.skytr1x.obhod.network.NetworkCalibrator
import com.skytr1x.obhod.diagnostics.ConnectionFailureClassifier
import com.skytr1x.obhod.notification.ObhodNotifications
import com.skytr1x.obhod.proxy.NativeTelegramProxy
import com.skytr1x.obhod.proxy.TelegramProxyConfig
import com.skytr1x.obhod.strategy.DomainListRepository
import com.skytr1x.obhod.strategy.StrategyProfiles
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ZapretVpnService : VpnService(), NativeBridge.Protector, NativeBridge.Listener {
    companion object {
        const val ACTION_START = "com.skytr1x.obhod.zapret.START"
        const val ACTION_STOP = "com.skytr1x.obhod.zapret.STOP"
        const val ACTION_RELOAD_LIST = "com.skytr1x.obhod.zapret.RELOAD_LIST"
        const val ACTION_STATE_CHANGED = "com.skytr1x.obhod.zapret.STATE_CHANGED"
        const val EXTRA_PROFILE = "profile"
        private const val TAG = "ZapretVpnService"
    }

    @Volatile private var nativeHandle = 0L
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var telegramProxyStarted = false
    @Volatile private var starting = false
    @Volatile private var stopRequested = false
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var networkRefreshQueued = false
    @Volatile private var configuredIpv6 = false
    @Volatile private var adaptiveChainActive = false
    @Volatile private var configuredMtu = 1500
    @Volatile private var configuredNetworkFingerprint = ""
    private var networkChangeGeneration = 0L
    private val vpnExecutor = Executors.newSingleThreadExecutor()
    private val lastProblemWarnings = ConcurrentHashMap<String, Long>()
    private val nativeLock = Any()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = queueNetworkRefresh()
        override fun onLost(network: Network) = queueNetworkRefresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            queueNetworkRefresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            queueNetworkRefresh()
    }

    override fun onCreate() {
        super.onCreate()
        ObhodNotifications.createChannel(this)
        getSharedPreferences("telegram_proxy", MODE_PRIVATE).edit().clear().apply()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            starting = false
            vpnExecutor.execute {
                shutdown()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD_LIST) {
            if (currentProfile() != StrategyProfiles.DEFAULT_ID ||
                nativeHandle == 0L || starting
            ) {
                AppLog.w(TAG, "Обновлённый список не применён: VPN не работает с тактикой List")
                sendBroadcast(android.content.Intent(ACTION_STATE_CHANGED).setPackage(packageName))
                return START_STICKY
            }
            starting = true
            vpnExecutor.execute {
                unregisterNetworkCallback()
                releaseNativeAndVpn()
                configuredNetworkFingerprint = ""
                try {
                    startVpn(StrategyProfiles.DEFAULT_ID)
                } finally {
                    starting = false
                }
            }
            return START_STICKY
        }
        if (nativeHandle != 0L || vpnInterface != null || starting) return START_STICKY

        val profileId = intent?.getStringExtra(EXTRA_PROFILE)
            ?: getSharedPreferences("zapret", MODE_PRIVATE)
                .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
            ?: StrategyProfiles.DEFAULT_ID
        stopRequested = false
        ObhodNotifications.markStarting(this)
        val notification = ObhodNotifications.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ObhodNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else startForeground(ObhodNotifications.NOTIFICATION_ID, notification)
        starting = true
        vpnExecutor.execute {
            try {
                startVpn(profileId)
            } finally {
                starting = false
            }
        }
        return START_STICKY
    }

    private fun startVpn(profileId: String) {
        AppLog.i(TAG, "Запуск: ${StrategyProfiles.label(profileId)}")
        try {
            NativeBridge.load()
            val bundledProfileJson =
                assets.open("strategies/$profileId.json").bufferedReader().use { it.readText() }
            val baseProfileJson = try {
                DomainListRepository.applyCachedList(this, profileId, bundledProfileJson)
            } catch (error: Exception) {
                AppLog.w(TAG, "Кэш списка повреждён; используется список из APK", error)
                bundledProfileJson
            }
            adaptiveChainActive = profileId == StrategyProfiles.ADAPTIVE_ID
            val profileJson = baseProfileJson
            val network = underlyingNetwork()
            val networkKey = currentNetworkKey(network)
            val calibration = NetworkCalibrator.resolve(this, networkKey, network)
            val tcpOrder = if (adaptiveChainActive) {
                ""
            } else {
                networkTcpOrder(profileJson, calibration.signal)
            }
            AppLog.i(
                TAG,
                "Калибровка сети: ${calibration.signal.storageValue}" +
                    if (calibration.fromCache) " (кэш)" else ""
            )
            AppLog.i(TAG, "DNS Discord/system-vs-DoH: ${calibration.dnsComparison.storageValue}")
            if (adaptiveChainActive) {
                AppLog.i(TAG, "Adaptive Desync Chain: direct-first, profiles P1–P6, QUIC fallback enabled")
            }
            logNetworkDetails(calibration.networkDetails)
            AppLog.i(
                TAG,
                if (tcpOrder.isBlank()) {
                    "Порядок TCP-стратегий: порядок профиля"
                } else {
                    "Порядок TCP-стратегий для этой сети: $tcpOrder"
                }
            )
            val profileDir = File(filesDir, "strategy-profiles/$profileId").apply {
                if (!exists() && !mkdirs()) error("Не удалось создать каталог профиля")
            }
            val profileFile = File(profileDir, "active-strategy.json").apply { writeText(profileJson) }
            val ipv6Available = hasUnderlyingIpv6Route()
            val mtu = underlyingMtu(ipv6Available)
            configuredIpv6 = ipv6Available
            configuredMtu = mtu
            val vpnBuilder = Builder()
                .setSession("Obhod · ${StrategyProfiles.label(profileId)}")
                .setMtu(mtu)
                .addAddress("198.18.0.1", 15)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(InetAddress.getByName("1.1.1.1"))
                .addDnsServer(InetAddress.getByName("8.8.8.8"))
                .addDisallowedApplication(packageName)
                .allowFamily(OsConstants.AF_INET6)
            if (ipv6Available) {
                vpnBuilder
                    .addAddress("fc00::1", 64)
                    .addRoute("::", 0)
            }
            AppLog.i(TAG, "IPv6 ${if (ipv6Available) "через стратегии" else "в обход VPN"}, TUN MTU $mtu")
            vpnInterface = vpnBuilder
                .establish() ?: error("Android не создал VPN-интерфейс")

            nativeHandle = NativeBridge.create(profileFile.absolutePath, this, this)
            if (nativeHandle == 0L) error("Не удалось создать нативный движок")
            NativeBridge.setNetworkKey(nativeHandle, strategyNetworkKey(networkKey))
            NativeBridge.setNetworkStrategyOrder(nativeHandle, tcpOrder)
            NativeBridge.start(nativeHandle)
            val coreStats = NativeBridge.getStatsJson(nativeHandle) ?: error("Нативный движок не вернул состояние")
            if (!coreStats.contains("\"status\":\"running\"")) {
                error("SOCKS5-движок не запустился: $coreStats")
            }

            val tunnelConfig = File(filesDir, "hev-socks5-tunnel.yml").apply {
                val ipv6Config = if (ipv6Available) "  ipv6: 'fc00::1'\n" else ""
                writeText(
                    """tunnel:
  name: tun0
  mtu: $mtu
  ipv4: 198.18.0.1
${ipv6Config}  icmp: 'off'
socks5:
  port: 10800
  address: 127.0.0.1
  udp: 'udp'
misc:
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
"""
                )
            }
            if (!NativeBridge.startTunnel(vpnInterface!!.fd, tunnelConfig.absolutePath)) {
                error("Не удалось запустить tun2socks")
            }
            val telegramResult = NativeTelegramProxy.start(
                TelegramProxyConfig.HOST,
                TelegramProxyConfig.PORT,
                TelegramProxyConfig.getOrCreateSecret(this),
                cacheDir.absolutePath
            )
            if (telegramResult != 0) error("Не удалось запустить MTProxy ($telegramResult)")
            telegramProxyStarted = true
            AppLog.i(
                TAG,
                "TUN, SOCKS5 и MTProxy готовы: ${StrategyProfiles.label(profileId)}"
            )
            publishState(true, profileId)
            configuredNetworkFingerprint = currentUnderlyingNetworkFingerprint()
            registerNetworkCallback()
        } catch (error: Throwable) {
            AppLog.e(TAG, "Не удалось запустить системный обход", error)
            shutdown()
            stopForeground(STOP_FOREGROUND_DETACH)
            publishState(false, profileId)
            stopSelf()
        }
    }

    private fun shutdown() {
        starting = false
        unregisterNetworkCallback()
        releaseNativeAndVpn()
        configuredNetworkFingerprint = ""
        configuredIpv6 = false
        configuredMtu = 1500
        stopForeground(STOP_FOREGROUND_DETACH)
        publishState(false, "")
    }

    private fun releaseNativeAndVpn() {
        if (telegramProxyStarted) {
            runCatching { NativeTelegramProxy.stop() }
                .onFailure { AppLog.w(TAG, "Не удалось остановить MTProxy", it) }
            telegramProxyStarted = false
        }
        synchronized(nativeLock) {
            if (NativeBridge.isLoaded()) {
                runCatching { NativeBridge.stopTunnel() }.onFailure { AppLog.w(TAG, "Не удалось остановить tun2socks", it) }
                val handle = nativeHandle
                if (handle != 0L) {
                    runCatching { NativeBridge.stop(handle) }.onFailure { AppLog.w(TAG, "Не удалось остановить ядро", it) }
                    runCatching { NativeBridge.destroy(handle) }.onFailure { AppLog.w(TAG, "Не удалось освободить ядро", it) }
                    nativeHandle = 0L
                }
            }
        }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun resolveDns(query: ByteArray): ByteArray? =
        DnsOverHttpsResolver.resolve(query, underlyingNetwork()) { socket ->
            protect(socket)
        }

    override fun onState(state: Int, message: String) {
        AppLog.i(TAG, "Состояние ядра $state: $message")
        if (state == 4) publishState(false, currentProfile())
    }

    override fun onEvent(message: String) {
        AppLog.d(TAG, message)
        ConnectionFailureClassifier.fromLogMessage(message)?.let { finding ->
            AppLog.i(TAG, "Диагностика ${finding.code}: ${finding.summary}")
        }
        if (!message.startsWith("[PROBLEM]")) return

        val fields = message.substringAfter("[PROBLEM]").trim()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val separator = token.indexOf('=')
                if (separator <= 0) null
                else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()
        val type = fields["type"].orEmpty()
        val host = fields["host"].orEmpty()
        val port = fields["port"].orEmpty()
        val endpoint = if (host.isBlank()) {
            "удалённым узлом"
        } else {
            val remoteIp = fields["remote_ip"]?.takeIf { it.isNotBlank() }
            "$host:$port" + (remoteIp?.let { " (IP $it)" } ?: "")
        }
        val reason = fields["reason"].orEmpty()
        val warning = when (type) {
            "dns_doh_fallback" ->
                "DNS: DoH-провайдеры недоступны, запрос передан системному DNS."
            "dns_worker_queue_full" ->
                "DNS: очередь DoH перегружена, запрос передан системному DNS."
            "dns_resolution_failed" ->
                "DNS: не удалось получить ответ ни через DoH, ни через системный DNS."
            "tcp_connect_failed" -> {
                val why = when (reason) {
                    "timeout" -> "истёк таймаут TCP-подключения"
                    "stopped" -> "подключение прервано остановкой обхода"
                    else -> "TCP-подключение не установлено"
                }
                "Сеть: не удалось подключиться к $endpoint — $why."
            }
            "tls_unverified" -> {
                val issue = when (fields["stage"]) {
                    "reconnect" -> "не удалось повторно открыть TCP-соединение"
                    "strategy_send" -> "не удалось отправить TLS ClientHello"
                    else -> when (reason) {
                        "timeout" -> "за время проверки не получен заголовок TLS-ответа целиком"
                        "tls_alert" -> "сервер прислал TLS alert"
                        "server_closed" -> "сервер закрыл TCP-соединение до TLS-ответа"
                        "truncated_response" -> "TLS-ответ оборвался до полного заголовка"
                        "unexpected_response" -> "вместо TLS пришёл неожиданный ответ"
                        "not_tls" -> "получен ответ, не похожий на TLS"
                        "send_failed" -> "не удалось отправить TLS ClientHello"
                        else -> "TLS-ответ не подтверждён"
                    }
                }
                val attempted = fields["attempted"] ?: "?"
                val candidates = fields["candidates"] ?: "?"
                val generated = fields["generated"] ?: candidates
                val untried = fields["untried"] ?: "0"
                val omitted = fields["omitted_by_cap"] ?: "0"
                val elapsed = fields["elapsed_ms"]?.let { " за ${it} мс" }.orEmpty()
                val omittedNote = if (omitted != "0") {
                    " Ещё $omitted вариантов отброшено ограничением списка."
                } else {
                    ""
                }
                val limit = if (fields["retry_budget"] == "exhausted") {
                    " Достигнут лимит времени перебора."
                } else {
                    ""
                }
                val direct = when (fields["direct_replay"]) {
                    "sent_unverified" -> " Прямая отправка выполнена, но ответ не проверен."
                    "connect_failed" -> " Прямое повторное TCP-подключение тоже не удалось."
                    "send_failed" -> " Не удалось отправить данные и напрямую."
                    else -> ""
                }
                "Сеть: $endpoint — $issue. Проверено $attempted из $candidates включённых " +
                    "вариантов (сгенерировано $generated); не проверено $untried$elapsed." +
                    "$omittedNote$limit$direct"
            }
            "tcp_direct_replay_failed" -> {
                val stage = if (fields["stage"] == "connect") {
                    "TCP-подключение"
                } else {
                    "отправка данных"
                }
                "Сеть: прямая повторная попытка к $endpoint не удалась ($stage, причина: $reason)."
            }
            else -> "Сетевая ошибка для $endpoint (тип: $type, причина: $reason)."
        }

        val warningKey = "$type|$host|$port"
        val now = System.currentTimeMillis()
        var shouldShow = false
        lastProblemWarnings.compute(warningKey) { _, previous ->
            if (previous == null || now - previous >= 15_000L) {
                shouldShow = true
                now
            } else {
                previous
            }
        }
        if (shouldShow) AppLog.w(TAG, warning)
    }

    private fun currentProfile(): String =
        getSharedPreferences("zapret", MODE_PRIVATE).getString("profile", "") ?: ""

    private fun currentNetworkKey(network: Network? = underlyingNetwork()): String {
        network ?: return "default"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "default"
        val link = connectivity.getLinkProperties(network)
        val material = networkMaterial(network, link)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val telephony = getSystemService(TelephonyManager::class.java)
            val operator = runCatching { telephony.networkOperator }.getOrNull().orEmpty()
                .ifBlank { runCatching { telephony.simOperator }.getOrNull().orEmpty() }
            val mobile = if (operator.isBlank()) "mobile" else "mobile_$operator"
            return "${mobile}_${sha1(material).take(12)}"
        }
        return "wifi_" + sha1(material).take(12)
    }

    private fun strategyNetworkKey(networkKey: String): String =
        if (adaptiveChainActive) "${networkKey}_adaptive" else networkKey

    private fun networkTcpOrder(
        profileJson: String,
        signal: NetworkCalibrator.Signal
    ): String {
        if (signal != NetworkCalibrator.Signal.TLS_INTERFERENCE) return ""
        return runCatching {
            val root = JSONObject(profileJson)
            val defaults = root.optJSONObject("defaults")?.optJSONArray("tcp")
            val defaultIds = buildList {
                if (defaults != null) {
                    for (index in 0 until defaults.length()) {
                        defaults.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            val strategies = root.optJSONArray("strategies")
            val availableIds = buildSet {
                if (strategies != null) {
                    for (index in 0 until strategies.length()) {
                        strategies.optJSONObject(index)?.optString("id")
                            ?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            val measuredPriority = listOf(
                "split-sni",
                "split-1",
                "multisplit-mobile",
                "tlsrec-sni",
                "fake-tls",
                "fake-tls-4pda",
                "disorder-1"
            ).filter(availableIds::contains)
            (measuredPriority + defaultIds).distinct().joinToString(",")
        }.onFailure { AppLog.w(TAG, "Не удалось построить порядок стратегий сети", it) }
            .getOrDefault("")
    }

    private fun logNetworkDetails(details: NetworkCalibrator.NetworkDetails) {
        val privateDns = details.privateDnsActive?.let { if (it) "active" else "inactive" }
            ?: "unknown"
        val ipv6Only = details.ipv6Only?.let { if (it) "yes" else "no" } ?: "unknown"
        AppLog.i(TAG, "Network diagnostics: Private DNS=$privateDns, IPv6-only=$ipv6Only")
    }

    private fun hasUnderlyingIpv6Route(): Boolean {
        val network = underlyingNetwork() ?: return false
        val link = connectivity.getLinkProperties(network) ?: return false
        return linkHasIpv6DefaultRoute(link)
    }

    private fun linkHasIpv6DefaultRoute(link: LinkProperties): Boolean {
        val hasGlobalIpv6Address = link.linkAddresses.any { linkAddress ->
            val address = linkAddress.address as? Inet6Address
            val firstByte = address?.address?.firstOrNull()?.toInt()?.and(0xff)
            val isUniqueLocal = firstByte != null && (firstByte and 0xfe) == 0xfc
            address != null && !address.isLinkLocalAddress && !address.isLoopbackAddress &&
                !address.isAnyLocalAddress && !address.isMulticastAddress &&
                !address.isSiteLocalAddress && !isUniqueLocal
        }
        val hasIpv6DefaultRoute = link.routes.any { route ->
            route.destination.address is Inet6Address && route.destination.prefixLength == 0
        }
        return hasGlobalIpv6Address && hasIpv6DefaultRoute
    }

    private fun underlyingNetwork(): Network? {
        val activeNetwork = connectivity.activeNetwork
        val candidates = buildList {
            activeNetwork?.let { add(it) }
            addAll(connectivity.allNetworks.filterNot { it == activeNetwork })
        }
        return candidates.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        }
    }

    private fun networkMaterial(network: Network, link: LinkProperties?): String = buildString {
        append(network)
        append('|').append(link?.interfaceName ?: "")
        append('|').append(linkMtu(link))
        link?.linkAddresses?.map { it.toString() }?.sorted()
            ?.forEach { append('|').append(it) }
        link?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()
            ?.forEach { append('|').append(it) }
        link?.routes?.map { "${it.destination}/${it.gateway?.hostAddress}" }?.sorted()
            ?.forEach { append('|').append(it) }
    }

    @Synchronized
    private fun queueNetworkRefresh() {
        networkChangeGeneration++
        if (networkRefreshQueued) return
        networkRefreshQueued = true
        try {
            vpnExecutor.execute {
                try {
                    while (true) {
                        val generation = synchronized(this) { networkChangeGeneration }
                        refreshUnderlyingNetwork()
                        synchronized(this) {
                            if (generation == networkChangeGeneration) {
                                networkRefreshQueued = false
                                return@execute
                            }
                        }
                    }
                } catch (error: Throwable) {
                    AppLog.e(TAG, "Не удалось обновить сеть", error)
                    shutdown()
                    stopSelf()
                    synchronized(this) { networkRefreshQueued = false }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            networkRefreshQueued = false
            AppLog.w(TAG, "Сетевое событие после остановки сервиса проигнорировано", error)
        }
    }

    private fun refreshUnderlyingNetwork() {
        if (stopRequested || nativeHandle == 0L || vpnInterface == null) return
        val fingerprint = currentUnderlyingNetworkFingerprint()
        if (fingerprint == configuredNetworkFingerprint) return
        configuredNetworkFingerprint = fingerprint

        val network = underlyingNetwork()
        val networkKey = currentNetworkKey(network)
        val profileId = currentProfile().ifBlank {
            getSharedPreferences("zapret", MODE_PRIVATE)
                .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
                ?: StrategyProfiles.DEFAULT_ID
        }
        val profileJson = assets.open("strategies/$profileId.json").bufferedReader().use { it.readText() }
        val calibration = NetworkCalibrator.resolve(this, networkKey, network)
        val tcpOrder = networkTcpOrder(profileJson, calibration.signal)
        AppLog.i(
            TAG,
            "Смена сетевого профиля: ${calibration.signal.storageValue}" +
                if (calibration.fromCache) " (кэш)" else ""
        )
        AppLog.i(TAG, "DNS Discord/system-vs-DoH: ${calibration.dnsComparison.storageValue}")
        logNetworkDetails(calibration.networkDetails)
        AppLog.i(
            TAG,
            if (tcpOrder.isBlank()) {
                "Порядок TCP-стратегий: порядок профиля"
            } else {
                "Порядок TCP-стратегий для новой сети: $tcpOrder"
            }
        )

        val newIpv6State = hasUnderlyingIpv6Route()
        val newMtu = underlyingMtu(newIpv6State)
        AppLog.i(TAG, "Сеть изменилась; IPv6 ${if (newIpv6State) "включён" else "в обход"}, MTU $newMtu; перезапуск")

        if (newIpv6State != configuredIpv6 || newMtu != configuredMtu) {
            releaseNativeAndVpn()
            starting = true
            try {
                startVpn(profileId)
            } finally {
                starting = false
            }
            return
        }

        try {
            synchronized(nativeLock) {
                val handle = nativeHandle
                if (handle == 0L || !NativeBridge.isLoaded()) return
                NativeBridge.setNetworkKey(handle, strategyNetworkKey(networkKey))
                NativeBridge.setNetworkStrategyOrder(handle, tcpOrder)
                NativeBridge.stop(handle)
                NativeBridge.start(handle)
                val stats = NativeBridge.getStatsJson(handle)
                if (stats == null || !stats.contains("\"status\":\"running\"")) {
                    error("SOCKS5 engine failed after network change: $stats")
                }
            }
        } catch (error: Throwable) {
            AppLog.e(TAG, "Не удалось перезапустить сокеты после смены сети", error)
            shutdown()
            stopSelf()
        }
    }

    private fun currentUnderlyingNetworkFingerprint(): String {
        val network = underlyingNetwork() ?: return "none"
        val networkKey = currentNetworkKey(network)
        val material = networkMaterial(network, connectivity.getLinkProperties(network))
        return sha1("$networkKey|$material")
    }

    private fun underlyingMtu(ipv6Enabled: Boolean): Int {
        val network = underlyingNetwork() ?: return 1500
        val mtu = linkMtu(connectivity.getLinkProperties(network))
        if (mtu <= 0) return 1500
        val minimum = if (ipv6Enabled) 1280 else 576
        return mtu.coerceIn(minimum, 1500)
    }

    private fun linkMtu(link: LinkProperties?): Int {
        if (link == null) return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mtu = link.mtu
            if (mtu > 0) return mtu
        }
        val interfaceName = link.interfaceName ?: return 0
        return runCatching { NetworkInterface.getByName(interfaceName)?.mtu ?: 0 }
            .getOrDefault(0)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { AppLog.w(TAG, "Не удалось подписаться на события сети", it) }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            .onFailure { AppLog.w(TAG, "Не удалось отписаться от событий сети", it) }
        networkCallbackRegistered = false
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun publishState(running: Boolean, profileId: String) {
        val prefs = getSharedPreferences("zapret", MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("running", false)
        prefs.edit()
            .putBoolean("running", running)
            .putBoolean("starting", false)
            .putString("profile", profileId)
            .apply()
        ObhodNotifications.refresh(this)
        if (wasRunning != running) {
            if (running) AppLog.i("VPN", "Системный обход включён: ${StrategyProfiles.label(profileId)}")
            else AppLog.i("VPN", "Системный обход выключен")
        }
        sendBroadcast(android.content.Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        stopRequested = true
        shutdown()
        vpnExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopRequested = true
        vpnExecutor.execute {
            shutdown()
            stopSelf()
        }
    }
}
