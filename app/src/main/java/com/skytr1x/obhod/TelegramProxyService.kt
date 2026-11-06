package com.skytr1x.obhod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TelegramProxyService : Service() {
    companion object {
        const val ACTION_START = "com.skytr1x.obhod.telegram.START"
        const val ACTION_STOP = "com.skytr1x.obhod.telegram.STOP"
        const val ACTION_STATE_CHANGED = "com.skytr1x.obhod.telegram.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        private const val CHANNEL_ID = "telegram_proxy"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "TelegramProxyService"
    }

    @Volatile private var running = false
    @Volatile private var starting = false
    private val proxyExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Прокси Telegram", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProxy()
            ACTION_START, null -> startProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (running) return
        if (starting) return
        starting = true
        startForegroundCompat()
        proxyExecutor.execute {
            try {
                val result = NativeTelegramProxy.start(
                    TelegramProxyConfig.HOST,
                    TelegramProxyConfig.PORT,
                    TelegramProxyConfig.getOrCreateSecret(this),
                    cacheDir.absolutePath
                )
                if (result != 0) error("Native proxy failed to start ($result)")
                running = true
                starting = false
                publishState(true)
                Log.i(TAG, "Telegram MTProto proxy listening on ${TelegramProxyConfig.HOST}:${TelegramProxyConfig.PORT}")
                updateNotification("Обход Телеграм включён")
            } catch (error: Throwable) {
                Log.e(TAG, "Telegram proxy startup failed", error)
                starting = false
                publishState(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProxy() {
        starting = false
        proxyExecutor.execute {
            try {
                if (running || starting) NativeTelegramProxy.stop()
            } catch (error: Throwable) {
                Log.e(TAG, "Telegram proxy shutdown failed", error)
            } finally {
                running = false
                publishState(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Запуск обхода Telegram…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Прокси Telegram")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun publishState(value: Boolean) {
        getSharedPreferences("telegram_proxy", MODE_PRIVATE).edit().putBoolean("running", value).apply()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName).putExtra(EXTRA_RUNNING, value))
    }

    override fun onDestroy() {
        if (running) runCatching { NativeTelegramProxy.stop() }
        running = false
        starting = false
        publishState(false)
        proxyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
