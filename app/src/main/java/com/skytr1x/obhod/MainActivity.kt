package com.skytr1x.obhod

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var vpnStatus: TextView
    private lateinit var telegramStatus: TextView
    private lateinit var toggle: Button
    private lateinit var strategySpinner: Spinner
    private lateinit var strategyDescription: TextView
    private var starting = false
    private var configuringSpinner = true

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            starting = false
            refreshState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        vpnStatus = findViewById(R.id.vpnStatusText)
        telegramStatus = findViewById(R.id.telegramStatusText)
        toggle = findViewById(R.id.toggleButton)
        strategySpinner = findViewById(R.id.strategySpinner)
        strategyDescription = findViewById(R.id.strategyDescription)

        val strategyAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            StrategyProfiles.options.map { it.title }
        )
        strategyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        strategySpinner.adapter = strategyAdapter
        val savedProfile = getSharedPreferences("zapret", MODE_PRIVATE)
            .getString("selected_profile", StrategyProfiles.DEFAULT_ID)
        strategySpinner.setSelection(StrategyProfiles.options.indexOfFirst { it.id == savedProfile }.coerceAtLeast(0))
        strategySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val option = StrategyProfiles.options[position]
                strategyDescription.text = option.description
                if (!configuringSpinner) {
                    getSharedPreferences("zapret", MODE_PRIVATE).edit()
                        .putString("selected_profile", option.id).apply()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        strategySpinner.post { configuringSpinner = false }

        toggle.setOnClickListener {
            if (isAnyServiceRunning() || starting) stopObhod() else requestVpnAndStart()
        }
        findViewById<Button>(R.id.mtProxyButton).setOnClickListener { showProxyDetails() }
        refreshState()
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
        refreshState()
    }

    private fun isAnyServiceRunning(): Boolean =
        getSharedPreferences("telegram_proxy", MODE_PRIVATE).getBoolean("running", false) ||
            getSharedPreferences("zapret", MODE_PRIVATE).getBoolean("running", false)

    private fun refreshState() {
        val telegramRunning = getSharedPreferences("telegram_proxy", MODE_PRIVATE).getBoolean("running", false)
        val zapretPrefs = getSharedPreferences("zapret", MODE_PRIVATE)
        val vpnRunning = zapretPrefs.getBoolean("running", false)
        val runningCount = listOf(telegramRunning, vpnRunning).count { it }
        status.text = when {
            runningCount == 2 -> "Обходка включена"
            runningCount == 1 -> "Работает один из двух режимов"
            starting -> "Запуск обходки…"
            else -> "Обходка выключена"
        }
        vpnStatus.text = if (vpnRunning) {
            "Системный обход: ${StrategyProfiles.label(zapretPrefs.getString("profile", "") ?: "")}"
        } else "Системный обход выключен"
        telegramStatus.text = if (telegramRunning) "Прокси Телеграм включён" else "Прокси Телеграм выключен"
        toggle.text = if (runningCount > 0 || starting) "Выключить обходку" else "Включить обходку"
        toggle.isEnabled = true
        strategySpinner.isEnabled = !vpnRunning && !starting
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQUEST_VPN)
        } else startObhod()
    }

    @Deprecated("Uses the platform VPN permission flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) startObhod()
        else Toast.makeText(this, "Для системного режима нужно разрешение VPN", Toast.LENGTH_LONG).show()
    }

    private fun startObhod() {
        val profileId = StrategyProfiles.options[strategySpinner.selectedItemPosition].id
        getSharedPreferences("zapret", MODE_PRIVATE).edit()
            .putString("selected_profile", profileId).apply()
        starting = true
        refreshState()
        ContextCompat.startForegroundService(this, Intent(this, ZapretVpnService::class.java).apply {
            action = ZapretVpnService.ACTION_START
            putExtra(ZapretVpnService.EXTRA_PROFILE, profileId)
        })
        ContextCompat.startForegroundService(this, Intent(this, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_START
        })
    }

    private fun stopObhod() {
        starting = false
        startService(Intent(this, ZapretVpnService::class.java).setAction(ZapretVpnService.ACTION_STOP))
        startService(Intent(this, TelegramProxyService::class.java).setAction(TelegramProxyService.ACTION_STOP))
        refreshState()
    }

    private fun showProxyDetails() {
        val secret = TelegramProxyConfig.getOrCreateSecret(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val values = "${TelegramProxyConfig.HOST}:${TelegramProxyConfig.PORT} — $secret"
        android.app.AlertDialog.Builder(this)
            .setTitle("MTProto для Телеграм")
            .setMessage("Сервер: ${TelegramProxyConfig.HOST}\nПорт: ${TelegramProxyConfig.PORT}\nСекрет: $secret")
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Скопировать") { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Telegram MTProxy", values))
                Toast.makeText(this, "Данные скопированы", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Открыть в Телеграме") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TelegramProxyConfig.getTelegramLink(this)))) }
                    .onFailure { Toast.makeText(this, "Не удалось открыть Телеграм", Toast.LENGTH_SHORT).show() }
            }
            .show()
    }

    companion object { private const val REQUEST_VPN = 7001 }
}
