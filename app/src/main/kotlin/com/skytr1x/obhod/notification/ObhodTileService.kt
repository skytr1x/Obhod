package com.skytr1x.obhod.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.skytr1x.obhod.strategy.StrategyProfiles
import com.skytr1x.obhod.ui.MainActivity
import com.skytr1x.obhod.vpn.ZapretVpnService

class ObhodTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("zapret", MODE_PRIVATE)
        val active = prefs.getBoolean("running", false) ||
            prefs.getBoolean("starting", false)
        if (active) {
            startService(
                Intent(this, ZapretVpnService::class.java)
                    .setAction(ZapretVpnService.ACTION_STOP)
            )
            return
        }
        val kernelMode = prefs.getString("selected_profile", StrategyProfiles.DEFAULT_ID) ==
            StrategyProfiles.SULINUX_ID
        if (kernelMode || VpnService.prepare(this) == null) {
            ObhodNotifications.markStarting(this)
            val started = runCatching {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ZapretVpnService::class.java)
                        .setAction(ZapretVpnService.ACTION_START)
                )
            }.isSuccess
            if (started) {
                updateTileState()
                return
            }
            prefs.edit().putBoolean("starting", false).apply()
        }
        val activityIntent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START_FROM_TILE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(activityIntent)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val prefs = getSharedPreferences("zapret", MODE_PRIVATE)
        val running = prefs.getBoolean("running", false)
        val starting = prefs.getBoolean("starting", false)
        val active = running || starting
        val kernelMode = prefs.getString("selected_profile", StrategyProfiles.DEFAULT_ID) ==
            StrategyProfiles.SULINUX_ID
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (active) "Обход включён" else "Обход выключен"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                subtitle = when {
                    starting -> "Запуск…"
                    running && kernelMode -> "SULinux (root)"
                    running -> "Включён"
                    kernelMode -> "SULinux: нужен root"
                    else -> "Нажмите, чтобы включить"
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                contentDescription = when {
                    starting -> "Обход запускается. Нажмите, чтобы остановить."
                    running -> "Обход включён. Нажмите, чтобы выключить."
                    else -> "Обход выключен. Нажмите, чтобы включить."
                }
            }
            updateTile()
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            requestListeningState(
                context,
                ComponentName(context, ObhodTileService::class.java)
            )
        }
    }
}
