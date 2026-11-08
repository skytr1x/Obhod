@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.skytr1x.obhod.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.skytr1x.obhod.logging.AppLog
import com.skytr1x.obhod.diagnostics.TransferTestConfig
import com.skytr1x.obhod.R
import com.skytr1x.obhod.proxy.TelegramProxyConfig
import com.skytr1x.obhod.strategy.StrategyProfileOption
import com.skytr1x.obhod.strategy.StrategyProfiles
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private enum class ObhodTab(val label: String, val icon: Int) {
    Home("Главная", R.drawable.ic_home),
    Logs("Логи", R.drawable.ic_audit),
    Source("Исходник", R.drawable.ic_github)
}

@Composable
internal fun ObhodApp(
    vpnRunning: Boolean,
    telegramRunning: Boolean,
    starting: Boolean,
    selectedStrategyIndex: Int,
    onStrategySelected: (Int) -> Unit,
    onToggle: () -> Unit,
    showTacticHelp: Boolean,
    onTacticHelpAccepted: () -> Unit,
    updatingDomainList: Boolean,
    domainListStatus: String,
    onUpdateDomainList: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onExportDiagnosticReport: (String) -> Unit,
    transferTestRunning: Boolean,
    transferTestStatus: String,
    onRunTransferTest: (TransferTestConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pageStateHolder = rememberSaveableStateHolder()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var showHelp by rememberSaveable(showTacticHelp) { mutableStateOf(showTacticHelp) }
    val selectedTab = ObhodTab.entries[tabIndex]

    val copyText: (String) -> Unit = remember(context, snackbarHostState, scope) {
        { text ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Obhod", text))
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Скопировано")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ObhodNavigationBar(
                selected = tabIndex,
                onSelect = { tabIndex = it }
            )
        }
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            pageStateHolder.SaveableStateProvider(selectedTab.name) {
                when (selectedTab) {
                    ObhodTab.Home -> HomeScreen(
                        vpnRunning = vpnRunning,
                        telegramRunning = telegramRunning,
                        starting = starting,
                        selectedStrategyIndex = selectedStrategyIndex,
                        onStrategySelected = onStrategySelected,
                        updatingDomainList = updatingDomainList,
                        domainListStatus = domainListStatus,
                        onUpdateDomainList = onUpdateDomainList,
                        batteryOptimizationIgnored = batteryOptimizationIgnored,
                        onRequestBatteryOptimization = onRequestBatteryOptimization,
                        onToggle = onToggle,
                        onCopyText = copyText
                    )

                    ObhodTab.Logs -> LogsScreen(
                        onCopyText = copyText,
                        onExportDiagnosticReport = onExportDiagnosticReport,
                        vpnRunning = vpnRunning,
                        transferTestRunning = transferTestRunning,
                        transferTestStatus = transferTestStatus,
                        onRunTransferTest = onRunTransferTest
                    )
                    ObhodTab.Source -> SourceScreen()
                }
            }
            if (showHelp) {
                TacticHelpDialog(
                    onAccepted = {
                        onTacticHelpAccepted()
                        showHelp = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TacticHelpDialog(onAccepted: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var secondsRemaining by rememberSaveable { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        repeat(5) {
            delay(1_000)
            secondsRemaining -= 1
        }
    }

    val accepting = page == 1
    val body = if (accepting) {
        buildAnnotatedString {
            append("Что отличает list от других кроме стабильности, так это отсутствие проблем с UDP запросами. Проще говоря, ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("используя list шанс на рабочие звонки в том же дискорде намного выше (почти 100%), чем с general или alt в идеальных состояниях для их работы.")
            pop()
        }
    } else {
        buildAnnotatedString {
            append("По умолчанию выбрана тактика list, которая избавляет от блокировок точечно. Это ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("не")
            pop()
            append(" надежная тактика, которая при любом обнаружении доп. блокировок требует обновления.\n\n")
            append("В свою очередь другие тактики (general, alt, simple fake) работают по методу перебора TTL, из-за которого возникают много нюансов:\n\n")
            append("• Без root-прав перебор TTL работает ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("не стабильно")
            pop()
            append(" и зависит от многих факторов сети (начиная от провайдера, заканчивая типом блокировки),\n\n")
            append("• TTL перебор даже с рут правами может выдавать ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("нестабильные результаты")
            pop()
            append(" банально из-за особенности андроид.\n\n")
            append("Важно понимать, что general работает по принципу fake-TLS, когда как alt пробует много других возможных способов обхода.")
        }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text("Справка по тактикам") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "${page + 1} / 2",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = page > 0,
                    onClick = { page -= 1 },
                    modifier = Modifier.semantics {
                        contentDescription = "Предыдущая страница"
                    }
                ) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(
                    enabled = page < 1,
                    onClick = { page += 1 },
                    modifier = Modifier.semantics {
                        contentDescription = "Следующая страница"
                    }
                ) {
                    Text("→", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = accepting && secondsRemaining == 0,
                    onClick = onAccepted
                ) {
                    Text(
                        if (secondsRemaining > 0) {
                            "Принять (${secondsRemaining} с)"
                        } else {
                            "Принять"
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun ObhodNavigationBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            ObhodTab.entries.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            painter = painterResource(tab.icon),
                            contentDescription = tab.label
                        )
                    },
                    label = { Text(tab.label) },
                    alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    vpnRunning: Boolean,
    telegramRunning: Boolean,
    starting: Boolean,
    selectedStrategyIndex: Int,
    onStrategySelected: (Int) -> Unit,
    updatingDomainList: Boolean,
    domainListStatus: String,
    onUpdateDomainList: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onToggle: () -> Unit,
    onCopyText: (String) -> Unit
) {
    var showProxyDialog by rememberSaveable { mutableStateOf(false) }
    val running = vpnRunning || telegramRunning

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Обход",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "VPN дорогой нынче...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        StatusCard(
            vpnRunning = vpnRunning,
            telegramRunning = telegramRunning,
            starting = starting,
            onToggle = onToggle,
            onOpenProxy = { showProxyDialog = true }
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Стратегии",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))
        StrategySelector(
            options = StrategyProfiles.options,
            selectedIndex = selectedStrategyIndex,
            enabled = !running && !starting,
            onSelected = onStrategySelected,
            isStarting = starting,
            updatingDomainList = updatingDomainList,
            domainListStatus = domainListStatus,
            onUpdateDomainList = onUpdateDomainList
        )
        Spacer(Modifier.height(16.dp))
        BatterySettingsCard(
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            onRequestBatteryOptimization = onRequestBatteryOptimization
        )
        Spacer(Modifier.height(32.dp))
    }

    if (showProxyDialog) {
        ProxyDialog(
            onDismiss = { showProxyDialog = false },
            onCopyText = onCopyText
        )
    }
}

@Composable
private fun BatterySettingsCard(
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit
) {
    var showAutostartHelp by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Работа в фоне",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (batteryOptimizationIgnored) {
                    "Ограничение батареи снято. На некоторых телефонах отдельно нужен автозапуск."
                } else {
                    "Отключение ограничения батареи поможет держать Обход активным."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (!batteryOptimizationIgnored) {
                FilledTonalButton(
                    onClick = onRequestBatteryOptimization,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Разрешить работу без ограничений")
                }
                Spacer(Modifier.height(4.dp))
            }
            TextButton(
                onClick = { showAutostartHelp = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Как включить автозапуск")
            }
        }
    }

    if (showAutostartHelp) {
        AlertDialog(
            onDismissRequest = { showAutostartHelp = false },
            title = { Text("Автозапуск и фоновые ограничения") },
            text = {
                Text(
                    "На Xiaomi, Huawei, Samsung и других телефонах откройте настройки " +
                        "приложения Obhod и разрешите фоновую работу или автозапуск. " +
                        "Названия пунктов отличаются; обычно они находятся в разделах " +
                        "\"Батарея\", \"Запуск приложений\" или \"Автозапуск\"."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAutostartHelp = false }) {
                    Text("Понятно")
                }
            }
        )
    }
}

@Composable
private fun StatusCard(
    vpnRunning: Boolean,
    telegramRunning: Boolean,
    starting: Boolean,
    onToggle: () -> Unit,
    onOpenProxy: () -> Unit
) {
    val running = vpnRunning || telegramRunning
    val container = if (running) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (running) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val buttonContainer = if (running) content else MaterialTheme.colorScheme.primary
    val buttonContent = if (running) container else MaterialTheme.colorScheme.onPrimary
    val title = when {
        starting -> "Запуск обхода…"
        vpnRunning && telegramRunning -> "Обход включен"
        running -> "Работает один из режимов"
        else -> "Обход выключен"
    }
    val subtitle = when {
        starting -> "Старт системы..."
        vpnRunning && telegramRunning -> "Обход успешно включился"
        running -> "Второй режим пока не запустился"
        else -> "Выберите стратегию и включите обход"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(content, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onToggle,
                enabled = !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainer,
                    contentColor = buttonContent,
                    disabledContainerColor = buttonContainer.copy(alpha = 0.55f),
                    disabledContentColor = buttonContent.copy(alpha = 0.8f)
                )
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = buttonContent
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    Icon(
                        painter = painterResource(
                            if (running) R.drawable.ic_stop else R.drawable.ic_play
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = when {
                        starting -> "Запуск…"
                        running -> "Выключить обходку"
                        else -> "Включить обходку"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenProxy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, content.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = content)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Данные MTProxy", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun StrategySelector(
    options: List<StrategyProfileOption>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    isStarting: Boolean,
    updatingDomainList: Boolean,
    domainListStatus: String,
    onUpdateDomainList: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options[selectedIndex]

    // dropdown menu
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.title,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Стратегия") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.title, style = MaterialTheme.typography.titleSmall) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (index == selectedIndex) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Transparent
                            }
                        )
                    }
                )
            }
        }
    }
    if (selected.id == StrategyProfiles.DEFAULT_ID) {
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onUpdateDomainList,
            enabled = !updatingDomainList && !isStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (updatingDomainList) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(if (updatingDomainList) "Обновление списка…" else "Обновить список")
        }
        Text(
            text = domainListStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = selected.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!enabled) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Стратегию можно сменить после выключения",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// logs
@Composable
private fun LogsScreen(
    onCopyText: (String) -> Unit,
    onExportDiagnosticReport: (String) -> Unit,
    vpnRunning: Boolean,
    transferTestRunning: Boolean,
    transferTestStatus: String,
    onRunTransferTest: (TransferTestConfig) -> Unit
) {
    val entries by AppLog.entries.collectAsState()
    val newestFirst = entries.asReversed()
    var confirmDiagnosticExport by rememberSaveable { mutableStateOf(false) }
    var operatorRegion by remember { mutableStateOf("") }
    var configureTransferTest by rememberSaveable { mutableStateOf(false) }
    var payloadKiB by remember { mutableIntStateOf(150) }
    var idleTimeoutSeconds by remember { mutableIntStateOf(4) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Логи",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Записей: ${entries.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = { onCopyText(entries.joinToString("\n") { it.asLine() }) },
                enabled = entries.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "Скопировать все логи"
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = { AppLog.clear() },
                enabled = entries.isNotEmpty()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "Очистить логи"
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { confirmDiagnosticExport = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить диагностический отчёт")
        }
        Text(
            text = "Отчёт не отправляется автоматически",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { configureTransferTest = true },
            enabled = vpnRunning && !transferTestRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (transferTestRunning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (transferTestRunning) "Тест передачи выполняется…" else "Проверить HTTPS-передачу")
        }
        Text(
            text = when {
                transferTestRunning -> transferTestStatus
                !vpnRunning -> "Для проверки выбранных стратегий сначала включите VPN."
                transferTestStatus.isNotBlank() -> transferTestStatus
                else -> "Тест отправит до 200 KiB на каждый из четырёх выбранных сервисов."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(20.dp))
        if (entries.isEmpty()) {
            EmptyLogs()
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(newestFirst, key = { it.id }, contentType = { "log" }) { entry ->
                    LogCard(entry = entry, onClick = { onCopyText(entry.asLine()) })
                }
            }
        }
        if (confirmDiagnosticExport) {
            AlertDialog(
                onDismissRequest = {
                    confirmDiagnosticExport = false
                    operatorRegion = ""
                },
                title = { Text("Сохранить отчёт?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "В отчёт войдут версия приложения и устройства, тип сети, результаты " +
                                "калибровки и последние метрики. Домены, IP-адреса, SSID и содержимое " +
                                "трафика не включаются. Ничего не отправляется; выберите место, куда " +
                                "сохранить файл. Оператора и регион можно указать вручную."
                        )
                        OutlinedTextField(
                            value = operatorRegion,
                            onValueChange = { value ->
                                operatorRegion = value.filterNot(Char::isISOControl).take(80)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Оператор / регион (необязательно)") },
                            supportingText = { Text("Заполняется вручную; геолокация не используется.") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiagnosticExport = false
                            onExportDiagnosticReport(operatorRegion.trim())
                            operatorRegion = ""
                        }
                    ) {
                        Text("Выбрать место")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            confirmDiagnosticExport = false
                            operatorRegion = ""
                        }
                    ) {
                        Text("Отмена")
                    }
                }
            )
        }
        if (configureTransferTest) {
            AlertDialog(
                onDismissRequest = { configureTransferTest = false },
                title = { Text("Проверка передачи данных") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Тест по очереди установит TLS-соединение и запросит до выбранного " +
                                "объёма данных у YouTube, Discord, 4pda и Cloudflare. Запросы " +
                                "выполняются только после нажатия «Начать тест» и не содержат " +
                                "учётных данных. Результаты останутся в локальных логах."
                        )
                        TransferTestOption(
                            label = "Лимит данных на сервис",
                            value = payloadKiB,
                            suffix = "KiB",
                            options = TransferTestConfig.PAYLOAD_OPTIONS_KIB,
                            onSelected = { payloadKiB = it }
                        )
                        Text("Максимум 15 секунд на сервис.")
                        TransferTestOption(
                            label = "Таймаут без новых данных",
                            value = idleTimeoutSeconds,
                            suffix = "с",
                            options = TransferTestConfig.IDLE_TIMEOUT_OPTIONS_SECONDS,
                            onSelected = { idleTimeoutSeconds = it }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            configureTransferTest = false
                            onRunTransferTest(
                                TransferTestConfig(
                                    payloadKiB = payloadKiB,
                                    idleTimeoutSeconds = idleTimeoutSeconds
                                )
                            )
                        }
                    ) {
                        Text("Начать тест")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { configureTransferTest = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun TransferTestOption(
    label: String,
    value: Int,
    suffix: String,
    options: List<Int>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "$value $suffix",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(
                    androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable
                )
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option $suffix") },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun LogCard(entry: AppLog.Entry, onClick: () -> Unit) {
    val logColor = when (entry.level) {
        'W', 'E' -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (entry.level != 'I') {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.level.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = logColor
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = logColor,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyLogs() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_audit),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Пока пусто", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Здесь появятся логи обхода",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// sources
@Composable
private fun SourceScreen() {
    val uriHandler = LocalUriHandler.current
    val repoUrl = "https://github.com/skytr1x/Obhod"
    val zapretUrl = "https://github.com/flowseal/zapret-discord-youtube"
    val tgProxyUrl = "https://github.com/amurcanov/tg-ws-proxy-android/"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Surface(
            onClick = { uriHandler.openUri(repoUrl) },
            modifier = Modifier.size(112.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "Открыть GitHub",
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Obhod by skytr1x",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Text(
                text = "MIT License",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = { uriHandler.openUri(repoUrl) },
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Репозиторий")
        }
        Spacer(Modifier.height(32.dp))
        Surface(
            modifier = Modifier.widthIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Я не претендую на стратегии обхода. Все стратегии обхода принадлежат ")
                    withLink(
                        LinkAnnotation.Url(
                            url = zapretUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) {
                        append("Zapret от Flowseal")
                    }
                    append(
                        ". Система обхода Телеграм же, принадлежит уже "
                    )
                    withLink(
                        LinkAnnotation.Url(
                            url = tgProxyUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        )
                    ) {
                        append("tg-ws-android от amurcanov")
                    }
                    append(", который, к сожалению, архивирован.")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ProxyDialog(onDismiss: () -> Unit, onCopyText: (String) -> Unit) {
    val context = LocalContext.current
    val secret = remember(context) { TelegramProxyConfig.getOrCreateSecret(context) }
    val connection = "${TelegramProxyConfig.HOST}:${TelegramProxyConfig.PORT} — $secret"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_copy),
                contentDescription = null
            )
        },
        title = { Text("MTProto для Telegram") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProxyValue("Сервер", TelegramProxyConfig.HOST)
                ProxyValue("Порт", TelegramProxyConfig.PORT.toString())
                ProxyValue("Секрет", secret)
                FilledTonalButton(
                    onClick = {
                        onCopyText(connection)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Скопировать")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(TelegramProxyConfig.getTelegramLink(context))
                            )
                        )
                    }.onFailure {
                        Toast.makeText(context, "Не удалось открыть Телеграм", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            ) {
                Text("Открыть в Телеграм")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun ProxyValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace
        )
    }
}
