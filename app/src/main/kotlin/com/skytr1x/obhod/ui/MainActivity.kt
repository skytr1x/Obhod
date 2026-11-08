package com.skytr1x.obhod.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.proxy.TelegramProxyService
import com.skytr1x.obhod.strategy.DomainListRepository
import com.skytr1x.obhod.strategy.StrategyProfiles
import com.skytr1x.obhod.vpn.ZapretVpnService
import java.text.DateFormat
import java.util.Date
import android.net.Uri
import android.content.ActivityNotFoundException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var starting by mutableStateOf(false)
    private var vpnRunning by mutableStateOf(false)
    private var telegramRunning by mutableStateOf(false)
    private var selectedStrategyIndex by mutableIntStateOf(0)
    private var updatingDomainList by mutableStateOf(false)
    private var domainListStatus by mutableStateOf("")
    private var batteryOptimizationIgnored by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { continueVpnAndStart() }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startObhod()
        else {
            AppLog.w("UI", "Разрешение VPN отклонено")
            Toast.makeText(this, "Для системного режима нужно разрешение VPN", Toast.LENGTH_LONG).show()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            starting = false
            refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("zapret", MODE_PRIVATE)
        domainListStatus = lastDomainListStatus()
        val needsTacticHelp = !preferences.getBoolean(KEY_TACTIC_HELP_ACCEPTED, false)
        if (needsTacticHelp) {
            preferences.edit()
                .putString("selected_profile", StrategyProfiles.DEFAULT_ID)
                .apply()
        }
        val savedProfile = preferences.getString("selected_profile", StrategyProfiles.DEFAULT_ID)
        selectedStrategyIndex =
            StrategyProfiles.options.indexOfFirst { it.id == savedProfile }.coerceAtLeast(0)

        setContent {
            ObhodTheme {
                ObhodApp(
                    vpnRunning = vpnRunning,
                    telegramRunning = telegramRunning,
                    starting = starting,
                    selectedStrategyIndex = selectedStrategyIndex,
                    onStrategySelected = ::selectStrategy,
                    onToggle = { if (isAnyServiceRunning() || starting) stopObhod() else requestVpnAndStart() },
                    showTacticHelp = needsTacticHelp,
                    onTacticHelpAccepted = ::acceptTacticHelp,
                    updatingDomainList = updatingDomainList,
                    domainListStatus = domainListStatus,
                    onUpdateDomainList = ::updateDomainList,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestBatteryOptimization = ::requestBatteryOptimizationExemption
                )
            }
        }
        refreshState()
        if (savedInstanceState == null) AppLog.i("UI", "Obhod запущен")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(TelegramProxyService.ACTION_STATE_CHANGED)
            addAction(ZapretVpnService.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshState()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptimizationState()
        refreshState()
    }

    private fun refreshBatteryOptimizationState() {
        val powerManager = getSystemService(PowerManager::class.java)
        batteryOptimizationIgnored = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || batteryOptimizationIgnored) return
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(request)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: SecurityException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun selectStrategy(index: Int) {
        selectedStrategyIndex = index
        val option = StrategyProfiles.options[index]
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putString("selected_profile", option.id).apply()
        AppLog.i("UI", "Выбрана стратегия: ${option.title}")
    }

    private fun acceptTacticHelp() {
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putBoolean(KEY_TACTIC_HELP_ACCEPTED, true)
            .apply()
    }

    private fun updateDomainList() {
        if (updatingDomainList || starting) return
        lifecycleScope.launch {
            updatingDomainList = true
            domainListStatus = "Загружаю список…"
            try {
                val result = DomainListRepository.update(this@MainActivity)
                val updatedAt = formatTimestamp(result.updatedAt)
                domainListStatus = "Обновлено $updatedAt · ${result.domainCount} доменов"
                AppLog.i("List", "Список обновлён: ${result.domainCount} доменов")
                val selectedProfile = StrategyProfiles.options[selectedStrategyIndex].id
                if (vpnRunning && selectedProfile == StrategyProfiles.DEFAULT_ID) {
                    starting = true
                    startService(
                        Intent(this@MainActivity, ZapretVpnService::class.java)
                            .setAction(ZapretVpnService.ACTION_RELOAD_LIST)
                    )
                }
            } catch (error: Exception) {
                domainListStatus = "Не удалось обновить: ${error.message ?: "ошибка загрузки"}"
                AppLog.e("List", "Не удалось обновить список доменов", error)
            } finally {
                updatingDomainList = false
            }
        }
    }

    private fun lastDomainListStatus(): String {
        val updatedAt = DomainListRepository.lastUpdated(this)
        return if (updatedAt == 0L) {
            "Список ещё не обновлялся"
        } else {
            "Последнее обновление: ${formatTimestamp(updatedAt)}"
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(timestamp))

    private fun isAnyServiceRunning(): Boolean =
        getSharedPreferences("telegram_proxy", MODE_PRIVATE).getBoolean("running", false) ||
            getSharedPreferences("zapret", MODE_PRIVATE).getBoolean("running", false)

    private fun refreshState() {
        val zapretPrefs = getSharedPreferences("zapret", MODE_PRIVATE)
        telegramRunning = getSharedPreferences("telegram_proxy", MODE_PRIVATE)
            .getBoolean("running", false)
        vpnRunning = zapretPrefs.getBoolean("running", false)
    }

    private fun requestVpnAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        continueVpnAndStart()
    }

    private fun continueVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else startObhod()
    }

    private fun startObhod() {
        val option = StrategyProfiles.options[selectedStrategyIndex]
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putString("selected_profile", option.id).apply()
        starting = true
        AppLog.i("UI", "Запуск обхода: ${option.title}")
        ContextCompat.startForegroundService(this, Intent(this, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_START
            putExtra(ZapretVpnService.EXTRA_PROFILE, option.id)
        })
        ContextCompat.startForegroundService(this, Intent(this, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_START
        })
    }

    private fun stopObhod() {
        starting = false
        AppLog.i("UI", "Остановка обхода")
        startService(Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
        startService(Intent(this, TelegramProxyService::class.java).setAction(TelegramProxyService.ACTION_STOP))
        refreshState()
    }

    companion object {
        private const val KEY_TACTIC_HELP_ACCEPTED = "tactic_help_accepted"
    }
}
