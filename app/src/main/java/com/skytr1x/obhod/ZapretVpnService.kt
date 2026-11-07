package com.skytr1x.obhod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

class ZapretVpnService : VpnService(), NativeBridge.Protector, NativeBridge.Listener {
    companion object {
        const val ACTION_START = "com.skytr1x.obhod.zapret.START"
        const val ACTION_STOP = "com.skytr1x.obhod.zapret.STOP"
        const val ACTION_STATE_CHANGED = "com.skytr1x.obhod.zapret.STATE_CHANGED"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL_ID = "zapret_vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ZapretVpnService"
    }

    @Volatile private var nativeHandle = 0L
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var starting = false
    @Volatile private var stopRequested = false
    @Volatile private var networkCallbackRegistered = false
    @Volatile private var networkRefreshQueued = false
    @Volatile private var configuredIpv6 = false
    @Volatile private var configuredMtu = 1500
    @Volatile private var configuredNetworkFingerprint = ""
    private var networkChangeGeneration = 0L
    private val vpnExecutor = Executors.newSingleThreadExecutor()
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обходка", NotificationManager.IMPORTANCE_LOW)
        )
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
        if (nativeHandle != 0L || vpnInterface != null || starting) return START_STICKY

        val profileId = intent?.getStringExtra(EXTRA_PROFILE)
            ?: getSharedPreferences("zapret", MODE_PRIVATE)
                .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
            ?: StrategyProfiles.DEFAULT_ID
        stopRequested = false
        val notification = buildNotification("Запуск: ${StrategyProfiles.label(profileId)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
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
        try {
            NativeBridge.load()
            val profileJson = assets.open("strategies/$profileId.json").bufferedReader().use { it.readText() }
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
            Log.i(TAG, "Underlying IPv6 route ${if (ipv6Available) "available; IPv6 routed through strategies" else "unavailable; IPv6 bypasses VPN"}; TUN MTU $mtu")
            vpnInterface = vpnBuilder
                .establish() ?: error("Android не создал VPN-интерфейс")

            nativeHandle = NativeBridge.create(profileFile.absolutePath, this, this)
            if (nativeHandle == 0L) error("Не удалось создать нативный движок")
            updateNetworkKey()
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
            publishState(true, profileId)
            configuredNetworkFingerprint = currentUnderlyingNetworkFingerprint()
            registerNetworkCallback()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID, buildNotification("Работает: ${StrategyProfiles.label(profileId)}")
            )
        } catch (error: Throwable) {
            Log.e(TAG, "VPN strategy startup failed", error)
            shutdown()
            publishState(false, profileId)
            stopForeground(STOP_FOREGROUND_REMOVE)
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
        publishState(false, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun releaseNativeAndVpn() {
        synchronized(nativeLock) {
            if (NativeBridge.isLoaded()) {
                runCatching { NativeBridge.stopTunnel() }.onFailure { Log.w(TAG, "Tunnel stop failed", it) }
                val handle = nativeHandle
                if (handle != 0L) {
                    runCatching { NativeBridge.stop(handle) }.onFailure { Log.w(TAG, "Core stop failed", it) }
                    runCatching { NativeBridge.destroy(handle) }.onFailure { Log.w(TAG, "Core destroy failed", it) }
                    nativeHandle = 0L
                }
            }
        }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun protect(fd: Int): Boolean = super.protect(fd)

    override fun onState(state: Int, message: String) {
        Log.i(TAG, "Core state $state: $message")
        if (state == 4) publishState(false, currentProfile())
    }

    override fun onEvent(message: String) {
        Log.d(TAG, message)
    }

    private fun currentProfile(): String =
        getSharedPreferences("zapret", MODE_PRIVATE).getString("profile", "") ?: ""

    private fun updateNetworkKey() {
        synchronized(nativeLock) {
            val handle = nativeHandle
            if (handle == 0L || !NativeBridge.isLoaded()) return
            runCatching { NativeBridge.setNetworkKey(handle, currentNetworkKey()) }
                .onFailure { Log.w(TAG, "Network key update failed", it) }
        }
    }

    private fun currentNetworkKey(): String {
        val network = underlyingNetwork() ?: return "default"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "default"
        val link = connectivity.getLinkProperties(network)
        val material = networkMaterial(network, link)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val telephony = getSystemService(TelephonyManager::class.java)
            val operator = (telephony.networkOperator ?: "")
                .ifBlank { telephony.simOperator ?: "" }
            val mobile = if (operator.isBlank()) "mobile" else "mobile_$operator"
            return "${mobile}_${sha1(material).take(12)}"
        }
        return "wifi_" + sha1(material).take(12)
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
        append('|').append(link?.mtu ?: 0)
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
                    Log.e(TAG, "Network refresh failed", error)
                    shutdown()
                    stopSelf()
                    synchronized(this) { networkRefreshQueued = false }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            networkRefreshQueued = false
            Log.w(TAG, "Ignoring network change after service shutdown", error)
        }
    }

    private fun refreshUnderlyingNetwork() {
        if (stopRequested || nativeHandle == 0L || vpnInterface == null) return
        val fingerprint = currentUnderlyingNetworkFingerprint()
        if (fingerprint == configuredNetworkFingerprint) return
        configuredNetworkFingerprint = fingerprint

        val newIpv6State = hasUnderlyingIpv6Route()
        val newMtu = underlyingMtu(newIpv6State)
        val profileId = currentProfile().ifBlank {
            getSharedPreferences("zapret", MODE_PRIVATE)
                .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
                ?: StrategyProfiles.DEFAULT_ID
        }
        Log.i(TAG, "Underlying network changed; IPv6 ${if (newIpv6State) "enabled" else "bypassed"}, MTU $newMtu; restarting network stack")

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
            updateNetworkKey()
            synchronized(nativeLock) {
                val handle = nativeHandle
                if (handle == 0L || !NativeBridge.isLoaded()) return
                NativeBridge.stop(handle)
                NativeBridge.start(handle)
                val stats = NativeBridge.getStatsJson(handle)
                if (stats == null || !stats.contains("\"status\":\"running\"")) {
                    error("SOCKS5 engine failed after network change: $stats")
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to restart network sockets after handover", error)
            shutdown()
            stopSelf()
        }
    }

    private fun currentUnderlyingNetworkFingerprint(): String {
        val network = underlyingNetwork() ?: return "none"
        return sha1(networkMaterial(network, connectivity.getLinkProperties(network)))
    }

    private fun underlyingMtu(ipv6Enabled: Boolean): Int {
        val network = underlyingNetwork() ?: return 1500
        val mtu = connectivity.getLinkProperties(network)?.mtu ?: 0
        if (mtu <= 0) return 1500
        val minimum = if (ipv6Enabled) 1280 else 576
        return mtu.coerceIn(minimum, 1500)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure { Log.w(TAG, "Network callback registration failed", it) }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "Network callback unregistration failed", it) }
        networkCallbackRegistered = false
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun publishState(running: Boolean, profileId: String) {
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putBoolean("running", running)
            .putString("profile", profileId)
            .apply()
        sendBroadcast(android.content.Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Obhod")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, android.content.Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

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
