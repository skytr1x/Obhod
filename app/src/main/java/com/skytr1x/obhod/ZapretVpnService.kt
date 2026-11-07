package com.skytr1x.obhod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

class ZapretVpnService : VpnService(), NativeBridge.Protector, NativeBridge.Listener {
    companion object {
        const val ACTION_START = "com.skytr1x.obhod.zapret.START"
        const val ACTION_STOP = "com.skytr1x.obhod.zapret.STOP"
        const val ACTION_STATE_CHANGED = "com.skytr1x.obhod.zapret.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PROFILE = "profile"
        private const val CHANNEL_ID = "zapret_vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ZapretVpnService"
    }

    @Volatile private var nativeHandle = 0L
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var starting = false
    private val vpnExecutor = Executors.newSingleThreadExecutor()
    private val nativeLock = Any()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkKey()
        override fun onLost(network: Network) = updateNetworkKey()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            updateNetworkKey()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обходка", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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
            vpnInterface = Builder()
                .setSession("Obhod · ${StrategyProfiles.label(profileId)}")
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fc00::1", 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(InetAddress.getByName("1.1.1.1"))
                .addDnsServer(InetAddress.getByName("8.8.8.8"))
                .addDisallowedApplication(packageName)
                .establish() ?: error("Android не создал VPN-интерфейс")

            nativeHandle = NativeBridge.create(profileFile.absolutePath, this, this)
            if (nativeHandle == 0L) error("Не удалось создать нативный движок")
            updateNetworkKey()
            runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
                .onFailure { Log.w(TAG, "Network callback registration failed", it) }
            NativeBridge.setTunFd(nativeHandle, vpnInterface!!.fd)
            NativeBridge.start(nativeHandle)
            val coreStats = NativeBridge.getStatsJson(nativeHandle) ?: error("Нативный движок не вернул состояние")
            if (!coreStats.contains("\"status\":\"running\"")) {
                error("SOCKS5-движок не запустился: $coreStats")
            }

            val tunnelConfig = File(filesDir, "hev-socks5-tunnel.yml").apply {
                writeText(
                    """tunnel:
  name: tun0
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
  icmp: 'off'
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
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
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
        publishState(false, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        val candidates = buildList {
            connectivity.activeNetwork?.let { add(it) }
            addAll(connectivity.allNetworks.filterNot { it == connectivity.activeNetwork })
        }
        val network = candidates.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        } ?: return "default"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "default"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val telephony = getSystemService(TelephonyManager::class.java)
            val operator = (telephony.networkOperator ?: "")
                .ifBlank { telephony.simOperator ?: "" }
            return if (operator.isBlank()) "mobile" else "mobile_$operator"
        }
        val link = connectivity.getLinkProperties(network)
        val material = buildString {
            append(link?.interfaceName ?: "")
            link?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()
                ?.forEach { append('|').append(it) }
            link?.routes?.map { "${it.destination}/${it.gateway?.hostAddress}" }?.sorted()
                ?.forEach { append('|').append(it) }
        }
        return "wifi_" + sha1(material).take(12)
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun publishState(running: Boolean, profileId: String) {
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putBoolean("running", running)
            .putString("profile", profileId)
            .apply()
        sendBroadcast(android.content.Intent(ACTION_STATE_CHANGED).setPackage(packageName)
            .putExtra(EXTRA_RUNNING, running).putExtra(EXTRA_PROFILE, profileId))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Obhod")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 1, android.content.Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        shutdown()
        vpnExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        vpnExecutor.execute {
            shutdown()
            stopSelf()
        }
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = super.onBind(intent)
}
