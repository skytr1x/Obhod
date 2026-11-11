package com.skytr1x.obhod.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.skytr1x.obhod.R
import com.skytr1x.obhod.ui.MainActivity
import com.skytr1x.obhod.vpn.ZapretVpnService

object ObhodNotifications {
    private const val ACTION_STOP_ALL = "com.skytr1x.obhod.notification.STOP_ALL"
    private const val STOP_REQUEST_CODE = 7020
    private const val LEGACY_TELEGRAM_NOTIFICATION_ID = 41
    const val CHANNEL_ID = "obhod_status"
    const val NOTIFICATION_ID = 42

    private const val VPN_PREFS = "zapret"

    private object Text {
        const val CHANNEL_NAME = "Обход"
        const val TITLE = "Обход"
        const val STOP_ACTION_LABEL = "Выключить обход"
        const val RUNNING = "Системный обход и MTProxy работают"
        const val STARTING = "Запуск обхода…"
        const val STOPPING = "Остановка обхода…"
    }

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            Text.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        manager.cancel(LEGACY_TELEGRAM_NOTIFICATION_ID)
    }

    fun markStarting(context: Context) {
        context.getSharedPreferences(VPN_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("starting", true)
            .apply()
        ObhodTileService.requestUpdate(context)
    }

    fun build(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle(Text.TITLE)
        .setContentText(statusText(context))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_stop),
                Text.STOP_ACTION_LABEL,
                stopAllPendingIntent(context)
            ).build()
        )
        .build()

    fun refresh(context: Context) {
        val vpnPrefs = context.getSharedPreferences(VPN_PREFS, Context.MODE_PRIVATE)
        val hasActiveService = vpnPrefs.getBoolean("running", false) ||
            vpnPrefs.getBoolean("starting", false)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (hasActiveService) manager.notify(NOTIFICATION_ID, build(context))
        else manager.cancel(NOTIFICATION_ID)
        ObhodTileService.requestUpdate(context)
    }

    private fun statusText(context: Context): String {
        val vpnPrefs = context.getSharedPreferences(VPN_PREFS, Context.MODE_PRIVATE)
        return when {
            vpnPrefs.getBoolean("running", false) -> Text.RUNNING
            vpnPrefs.getBoolean("starting", false) -> Text.STARTING
            else -> Text.STOPPING
        }
    }

    private fun stopAllPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            STOP_REQUEST_CODE,
            Intent(context, StopObhodReceiver::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    internal fun isStopAllAction(action: String?): Boolean = action == ACTION_STOP_ALL
}

class StopObhodReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ObhodNotifications.isStopAllAction(intent.action)) return

        context.startService(
            Intent(context, ZapretVpnService::class.java)
                .setAction(ZapretVpnService.ACTION_STOP)
        )
    }
}
