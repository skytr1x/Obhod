package com.skytr1x.obhod.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.skytr1x.obhod.diagnostics.DiagnosticReport
import com.skytr1x.obhod.diagnostics.DiagnosticReportData
import com.skytr1x.obhod.diagnostics.ConnectivitySelfTest
import com.skytr1x.obhod.diagnostics.TransferTestConfig
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.network.NetworkCalibrator
import com.skytr1x.obhod.strategy.DomainListRepository
import com.skytr1x.obhod.strategy.StrategyProfiles
import com.skytr1x.obhod.vpn.ZapretVpnService
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import android.content.ActivityNotFoundException
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private var starting by mutableStateOf(false)
    private var vpnRunning by mutableStateOf(false)
    private var selectedStrategyIndex by mutableIntStateOf(0)
    private var adaptiveChainEnabled by mutableStateOf(false)
    private var updatingDomainList by mutableStateOf(false)
    private var domainListStatus by mutableStateOf("")
    private var batteryOptimizationIgnored by mutableStateOf(false)
    private var pendingDiagnosticReport: String? = null
    private var transferTestRunning by mutableStateOf(false)
    private var transferTestStatus by mutableStateOf("")

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

    private val diagnosticReportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) {
                pendingDiagnosticReport = null
            } else {
                val report = pendingDiagnosticReport ?: createDiagnosticReport("")
                pendingDiagnosticReport = null
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val output = contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Не удалось открыть выбранный файл")
                            output.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "Отчёт сохранён",
                            Toast.LENGTH_LONG
                        ).show()
                        AppLog.i("Diagnostics", "Отчёт сохранён в выбранное пользователем место")
                    } catch (error: IOException) {
                        AppLog.e("Diagnostics", "Не удалось сохранить отчёт", error)
                        Toast.makeText(
                            this@MainActivity,
                            "Не удалось сохранить отчёт",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (error: SecurityException) {
                        AppLog.e("Diagnostics", "Нет доступа к выбранному файлу", error)
                        Toast.makeText(
                            this@MainActivity,
                            "Нет доступа к выбранному файлу",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
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
        var savedProfile = preferences.getString("selected_profile", StrategyProfiles.DEFAULT_ID)
            ?: StrategyProfiles.DEFAULT_ID
        if (savedProfile == StrategyProfiles.ADAPTIVE_ID) {
            savedProfile = StrategyProfiles.DEFAULT_ID
            preferences.edit()
                .putString("selected_profile", savedProfile)
                .putBoolean(StrategyProfiles.ADAPTIVE_PREF, true)
                .apply()
        }
        adaptiveChainEnabled =
            preferences.getBoolean(StrategyProfiles.ADAPTIVE_PREF, false)
        selectedStrategyIndex =
            StrategyProfiles.options.indexOfFirst { it.id == savedProfile }.coerceAtLeast(0)

        setContent {
            ObhodTheme {
                ObhodApp(
                    vpnRunning = vpnRunning,
                    starting = starting,
                    selectedStrategyIndex = selectedStrategyIndex,
                    onStrategySelected = ::selectStrategy,
                    adaptiveChainEnabled = adaptiveChainEnabled,
                    onAdaptiveChainChanged = ::setAdaptiveChain,
                    onToggle = {
                        if (isServiceActive() || starting) stopObhod() else requestVpnAndStart()
                    },
                    showTacticHelp = needsTacticHelp,
                    onTacticHelpAccepted = ::acceptTacticHelp,
                    updatingDomainList = updatingDomainList,
                    domainListStatus = domainListStatus,
                    onUpdateDomainList = ::updateDomainList,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestBatteryOptimization = ::requestBatteryOptimizationExemption,
                    onExportDiagnosticReport = ::requestDiagnosticReport,
                    transferTestRunning = transferTestRunning,
                    transferTestStatus = transferTestStatus,
                    onRunTransferTest = ::runTransferTest
                )
            }
        }
        refreshState()
        if (savedInstanceState == null) handleQuickSettingsIntent(intent)
        if (savedInstanceState == null) AppLog.i("UI", "Obhod запущен")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickSettingsIntent(intent)
    }

    private fun handleQuickSettingsIntent(intent: Intent?) {
        if (intent?.action != ACTION_START_FROM_TILE) return
        intent.action = null
        if (isServiceActive()) stopObhod() else requestVpnAndStart()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
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

    private fun setAdaptiveChain(enabled: Boolean) {
        adaptiveChainEnabled = enabled
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putBoolean(StrategyProfiles.ADAPTIVE_PREF, enabled).apply()
        AppLog.i("UI", "Adaptive Desync Chain: ${if (enabled) "включена" else "выключена"}")
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

    private fun requestDiagnosticReport(operatorRegion: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        pendingDiagnosticReport = createDiagnosticReport(operatorRegion)
        diagnosticReportLauncher.launch("obhod-diagnostics-$timestamp.txt")
    }

    private fun runTransferTest(config: TransferTestConfig) {
        if (transferTestRunning) return
        if (!vpnRunning) {
            Toast.makeText(this, "Сначала включите VPN", Toast.LENGTH_LONG).show()
            return
        }

        transferTestRunning = true
        transferTestStatus = "Проверка DNS и передачи выполняется…"
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.let(::underlyingNetworkForReport)
        AppLog.i(
            "SelfTest",
            "[SELFTEST_RUN] repetitions=${ConnectivitySelfTest.REPETITIONS} " +
                "payload_kib=${config.payloadKiB} " +
                "host_timeout_s=${config.hostTimeoutSeconds} " +
                "idle_timeout_s=${config.idleTimeoutSeconds}"
        )
        lifecycleScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    runInterruptible {
                        ConnectivitySelfTest.run(
                            config = config,
                            network = network,
                            onProgress = { service, attempt ->
                                val label = when (service) {
                                    "youtube" -> "YouTube"
                                    "discord" -> "Discord"
                                    "4pda" -> "4pda"
                                    else -> "Cloudflare"
                                }
                                runOnUiThread {
                                    transferTestStatus =
                                        "Прогон $attempt/${ConnectivitySelfTest.REPETITIONS}: $label"
                                }
                                AppLog.i(
                                    "SelfTest",
                                    "[SELFTEST_PROGRESS] attempt=$attempt service=$service"
                                )
                            },
                            onResult = { result ->
                                AppLog.i("SelfTest", result.asLogMessage())
                            }
                        )
                    }
                }
                val serviceStats = summary.serviceStats()
                val reached = serviceStats.sumOf { it.httpResponsesReceived }
                val total = serviceStats.sumOf { it.attempts }
                transferTestStatus =
                    "Готово: HTTP-ответ получен в $reached из $total попыток. Результаты в отчёте."
            } catch (error: IOException) {
                AppLog.e("SelfTest", "Тест передачи завершился с ошибкой", error)
                transferTestStatus = "Тест завершился с ошибкой; подробности в логах."
            } finally {
                transferTestRunning = false
            }
        }
    }

    private fun createDiagnosticReport(operatorRegion: String): String {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.let(::underlyingNetworkForReport)
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val details = network?.let { NetworkCalibrator.inspectNetwork(this, it) }
        val entries = AppLog.entries.value
        val messages = entries.map { it.message }
        val networkStart = messages.indexOfLast { message ->
            message.startsWith("Калибровка сети: ") ||
                message.startsWith("Смена сетевого профиля: ")
        }
        val currentNetworkMessages = if (networkStart >= 0) messages.drop(networkStart) else messages
        val calibrationSignal = messages.asReversed().firstNotNullOfOrNull { message ->
            when {
                message.startsWith("Калибровка сети: ") ->
                    message.removePrefix("Калибровка сети: ").substringBefore(" (кэш)")
                message.startsWith("Смена сетевого профиля: ") ->
                    message.removePrefix("Смена сетевого профиля: ").substringBefore(" (кэш)")
                else -> null
            }
        } ?: "неизвестно"
        val dnsComparison = messages.asReversed()
            .firstNotNullOfOrNull { message ->
                message.takeIf { it.startsWith("DNS Discord/system-vs-DoH: ") }
                    ?.removePrefix("DNS Discord/system-vs-DoH: ")
            } ?: "неизвестно"
        val selectedProfile = getSharedPreferences("zapret", MODE_PRIVATE)
            .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
            ?: "неизвестно"
        val networkClass = when {
            capabilities == null -> "неизвестно"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "другая"
        }
        val data = DiagnosticReportData(
            generatedAt = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.MEDIUM,
                Locale.ROOT
            ).format(Date()),
            appVersion = appVersionName(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80),
            networkClass = networkClass,
            networkValidated = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ),
            networkMetered = capabilities?.let {
                !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            },
            privateDnsActive = details?.privateDnsActive,
            ipv6Only = details?.ipv6Only,
            calibrationSignal = calibrationSignal,
            dnsComparison = dnsComparison,
            selectedProfile = selectedProfile,
            operatorRegion = operatorRegion,
            latestTlsSuccess = DiagnosticReport.latestTlsSuccess(currentNetworkMessages),
            tlsSamples = DiagnosticReport.tlsSampleSummary(currentNetworkMessages),
            selfTest = DiagnosticReport.latestSelfTest(currentNetworkMessages),
            latestFailure = DiagnosticReport.latestFailure(currentNetworkMessages),
            quicSuppressionCount = DiagnosticReport.quicSuppressionCount(currentNetworkMessages),
            adaptiveProfileSelected = adaptiveChainEnabled,
            adaptiveAttempts = currentNetworkMessages
                .filter { it.startsWith("[ADAPTIVE]") },
            logs = entries.map { it.asLine() }
        )
        return DiagnosticReport.format(data)
    }

    @Suppress("DEPRECATION")
    private fun underlyingNetworkForReport(connectivity: ConnectivityManager): Network? {
        val candidates = (listOfNotNull(connectivity.activeNetwork) +
            connectivity.allNetworks).distinct()
        return candidates.firstOrNull { candidate ->
            val capabilities = connectivity.getNetworkCapabilities(candidate)
                ?: return@firstOrNull false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

    private fun isServiceActive(): Boolean {
        val prefs = getSharedPreferences("zapret", MODE_PRIVATE)
        return prefs.getBoolean("running", false) || prefs.getBoolean("starting", false)
    }

    private fun refreshState() {
        val zapretPrefs = getSharedPreferences("zapret", MODE_PRIVATE)
        vpnRunning = zapretPrefs.getBoolean("running", false)
        starting = zapretPrefs.getBoolean("starting", false)
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
    }

    private fun stopObhod() {
        starting = false
        AppLog.i("UI", "Остановка обхода")
        startService(Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
        refreshState()
    }

    companion object {
        private const val KEY_TACTIC_HELP_ACCEPTED = "tactic_help_accepted"
        const val ACTION_START_FROM_TILE = "com.skytr1x.obhod.action.START_FROM_TILE"
    }
}
