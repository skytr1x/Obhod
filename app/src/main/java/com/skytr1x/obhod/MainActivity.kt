package com.skytr1x.obhod

import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var running = false
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshStateFrom(intent?.getBooleanExtra(TelegramProxyService.EXTRA_RUNNING, false) == true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        toggle = findViewById(R.id.toggleButton)
        toggle.setOnClickListener { if (running) stopProxy() else startProxy() }
        findViewById<Button>(R.id.mtProxyButton).setOnClickListener { showProxyDetails() }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(TelegramProxyService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun refreshState() {
        running = getSharedPreferences("telegram_proxy", MODE_PRIVATE).getBoolean("running", false)
        status.text = if (running) "Обход Telegram включён" else "Обход Telegram выключен"
        toggle.text = if (running) "Остановить" else "Запустить обход Telegram"
    }

    private fun startProxy() {
        ContextCompat.startForegroundService(this, Intent(this, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_START
        })
        status.text = "Запуск обхода..."
        toggle.isEnabled = false
    }

    private fun stopProxy() {
        startService(Intent(this, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_STOP
        })
        refreshStateFrom(false)
    }

    private fun refreshStateFrom(value: Boolean) {
        running = value
        status.text = if (value) "Обходка включена" else "Обходка выключена"
        toggle.text = if (value) "Остановить" else "Запустить обходку"
        toggle.isEnabled = true
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
}
