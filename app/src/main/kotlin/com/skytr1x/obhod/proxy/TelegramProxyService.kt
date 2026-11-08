package com.skytr1x.obhod.proxy

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.notification.ObhodNotifications

class TelegramProxyService : Service() {
    companion object {
        const val ACTION_START = "com.skytr1x.obhod.telegram.START"
        const val ACTION_STOP = "com.skytr1x.obhod.telegram.STOP"
        const val ACTION_STATE_CHANGED = "com.skytr1x.obhod.telegram.STATE_CHANGED"
        private const val TAG = "TelegramProxyService"
    }

    @Volatile private var running = false
    @Volatile private var starting = false
    private val proxyExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        ObhodNotifications.createChannel(this)
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
        ObhodNotifications.markStarting(this, "telegram_proxy")
        AppLog.i(TAG, "Запуск прокси Телеграм")
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
                AppLog.i(TAG, "MTProto слушает ${TelegramProxyConfig.HOST}:${TelegramProxyConfig.PORT}")
            } catch (error: Throwable) {
                AppLog.e(TAG, "Не удалось запустить прокси Телеграм", error)
                starting = false
                stopForeground(STOP_FOREGROUND_DETACH)
                publishState(false)
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
                AppLog.e(TAG, "Ошибка остановки прокси Телеграм", error)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                publishState(false)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = ObhodNotifications.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ObhodNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else startForeground(ObhodNotifications.NOTIFICATION_ID, notification)
    }

    private fun publishState(value: Boolean) {
        val prefs = getSharedPreferences("telegram_proxy", MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("running", false)
        prefs.edit()
            .putBoolean("running", value)
            .putBoolean("starting", false)
            .apply()
        ObhodNotifications.refresh(this)
        if (wasRunning != value) {
            AppLog.i("Telegram", if (value) "Прокси Телеграм включён" else "Прокси Телеграм выключен")
        }
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        if (running) runCatching { NativeTelegramProxy.stop() }
        running = false
        starting = false
        stopForeground(STOP_FOREGROUND_DETACH)
        publishState(false)
        proxyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
