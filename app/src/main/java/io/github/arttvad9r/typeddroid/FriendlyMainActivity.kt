package io.github.arttvad9r.typeddroid

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arttvad9r.typeddroid.security.AgentTrustMode
import io.github.arttvad9r.typeddroid.security.ApprovalTicket
import io.github.arttvad9r.typeddroid.security.ToolRisk

class FriendlyMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairingIntent(intent)
        enableEdgeToEdge()
        setContent {
            FriendlyTypedDroidTheme {
                val vm: BridgeViewModel = viewModel()
                val state by vm.state.collectAsState()
                val uiCapture by UiCaptureSessionRuntime.state.collectAsState()

                val fileTreeLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree(),
                    onResult = { uri -> uri?.let(vm::grantFileTree) },
                )
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { vm.refreshNotificationPermission() },
                )
                val notificationListenerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                    onResult = { vm.refreshNotificationListenerAccess() },
                )
                val usageAccessLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                    onResult = { vm.refreshUsageAccess() },
                )

                FriendlyHomeScreen(
                    state = state,
                    uiCapture = uiCapture,
                    onPairingInputChange = vm::updatePairingCode,
                    onPairManually = vm::pair,
                    onFinishSetup = vm::finishSetup,
                    onRefreshHealth = vm::refreshHealth,
                    onApprove = vm::approveAction,
                    onDeny = vm::denyAction,
                    onSetTrustMode = vm::setAgentTrustMode,
                    onOpenNotificationAccess = {
                        notificationListenerLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestAppNotifications = {
                        if (requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.refreshNotificationPermission()
                        }
                    },
                    onChooseFileTree = { fileTreeLauncher.launch(null) },
                    onRevokeFileAccess = vm::revokeFileAccess,
                    onOpenUsageAccess = {
                        usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onPairEmbeddedAdb = vm::pairEmbeddedAdb,
                    onRestoreEmbeddedAdb = vm::restoreEmbeddedAdb,
                    onForgetEmbeddedAdb = vm::forgetEmbeddedAdb,
                    onOpenDeveloperSettings = {
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    },
                    onStartUiCapture = {
                        startActivity(Intent(this, UiCaptureConsentActivity::class.java))
                    },
                    onStopUiCapture = {
                        stopService(Intent(this, UiCaptureForegroundService::class.java))
                    },
                    onRevokePairing = vm::revokePairing,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun handlePairingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val value = intent.dataString ?: return
        val bootstrap = parsePairingBootstrapInput(value) ?: return
        BridgeRuntime.update(ConnectionState.PAIRING)
        runCatching {
            BridgeForegroundService.pair(applicationContext, bootstrap.code, bootstrap.endpoints)
        }.onFailure { error ->
            BridgeRuntime.update(
                ConnectionState.ERROR,
                error.message ?: "Не удалось подключить телефон.",
            )
        }
    }
}

@Composable
private fun FriendlyTypedDroidTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun FriendlyHomeScreen(
    state: BridgeUiState,
    uiCapture: UiCaptureSessionState,
    onPairingInputChange: (String) -> Unit,
    onPairManually: () -> Unit,
    onFinishSetup: () -> Unit,
    onRefreshHealth: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onSetTrustMode: (AgentTrustMode) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRequestAppNotifications: () -> Unit,
    onChooseFileTree: () -> Unit,
    onRevokeFileAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onPairEmbeddedAdb: (String) -> Unit,
    onRestoreEmbeddedAdb: () -> Unit,
    onForgetEmbeddedAdb: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onStartUiCapture: () -> Unit,
    onStopUiCapture: () -> Unit,
    onRevokePairing: () -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }
    var showManualPairing by remember { mutableStateOf(false) }
    var showTrustConfirmation by remember { mutableStateOf(false) }
    var showRevokeConfirmation by remember { mutableStateOf(false) }
    var wirelessDebugCode by remember { mutableStateOf("") }

    if (showTrustConfirmation) {
        AlertDialog(
            onDismissRequest = { showTrustConfirmation = false },
            title = { Text("Включить полный доступ?") },
            text = {
                Text(
                    "Подключённый агент сможет управлять интерфейсом, выполнять системные команды и использовать shell в пределах возможностей Android. Все действия остаются в журнале TypedDroid."
                )
            },
            dismissButton = {
                TextButton(onClick = { showTrustConfirmation = false }) { Text("Отмена") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTrustConfirmation = false
                        onSetTrustMode(AgentTrustMode.TRUSTED)
                    },
                ) { Text("Включить") }
            },
        )
    }

    if (showRevokeConfirmation) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmation = false },
            title = { Text("Отвязать компьютер?") },
            text = { Text("Для повторного подключения понадобится новый QR-код.") },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmation = false }) { Text("Отмена") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeConfirmation = false
                        onRevokePairing()
                    },
                ) { Text("Отвязать") }
            },
        )
    }

    val connected = state.connectionState == ConnectionState.CONNECTED

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                "TypedDroid",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Управление телефоном для ИИ-агента",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FriendlyStatusCard(state.connectionState)

            state.pendingApprovals.forEach { ticket ->
                FriendlyApprovalCard(
                    ticket = ticket,
                    onApprove = { onApprove(ticket.id) },
                    onDeny = { onDeny(ticket.id) },
                )
            }

            if (!connected) {
                PairingHomeCard(
                    connectionState = state.connectionState,
                    code = state.pairingCode,
                    message = state.message,
                    showManual = showManualPairing,
                    onToggleManual = { showManualPairing = !showManualPairing },
                    onCodeChange = onPairingInputChange,
                    onPair = onPairManually,
                )
            } else if (!state.setupCompleted) {
                SetupHomeCard(
                    trustMode = state.agentTrustMode,
                    embeddedAdb = state.embeddedAdb,
                    notificationAccessGranted = state.notificationListenerGranted,
                    onEnableTrusted = { showTrustConfirmation = true },
                    onDisableTrusted = { onSetTrustMode(AgentTrustMode.SAFE) },
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onFinish = onFinishSetup,
                    onOpenSystemAccess = { showAdvanced = true },
                )

                if (showAdvanced) {
                    SystemAccessCard(
                        state = state.embeddedAdb,
                        pairingCode = wirelessDebugCode,
                        onPairingCodeChange = { wirelessDebugCode = it.filter(Char::isDigit).take(6) },
                        onPair = { onPairEmbeddedAdb(wirelessDebugCode) },
                        onRestore = onRestoreEmbeddedAdb,
                        onForget = onForgetEmbeddedAdb,
                        onOpenDeveloperSettings = onOpenDeveloperSettings,
                    )
                    AdvancedAccessCard(
                        notificationsGranted = state.notificationsGranted,
                        fileAccessConfigured = state.fileAccessConfigured,
                        usageAccessGranted = state.usageAccessGranted,
                        uiCaptureActive = uiCapture.status == UiCaptureSessionStatus.ACTIVE,
                        onRequestAppNotifications = onRequestAppNotifications,
                        onChooseFileTree = onChooseFileTree,
                        onRevokeFileAccess = onRevokeFileAccess,
                        onOpenUsageAccess = onOpenUsageAccess,
                        onStartUiCapture = onStartUiCapture,
                        onStopUiCapture = onStopUiCapture,
                    )
                    TextButton(
                        onClick = { showAdvanced = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Скрыть дополнительные настройки") }
                }
            } else {
                ReadyHomeCard(
                    trustMode = state.agentTrustMode,
                    embeddedAdbReady = state.embeddedAdb.ready,
                    notificationAccessGranted = state.notificationListenerGranted,
                )

                HealthSummaryCard(
                    health = state.health,
                    onRefresh = onRefreshHealth,
                )

                FilledTonalButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showAdvanced) "Скрыть настройки доступа" else "Настройки доступа")
                }

                if (showAdvanced) {
                    AccessSettingsCard(
                        trustMode = state.agentTrustMode,
                        notificationAccessGranted = state.notificationListenerGranted,
                        onEnableTrusted = { showTrustConfirmation = true },
                        onDisableTrusted = { onSetTrustMode(AgentTrustMode.SAFE) },
                        onOpenNotificationAccess = onOpenNotificationAccess,
                    )
                    SystemAccessCard(
                        state = state.embeddedAdb,
                        pairingCode = wirelessDebugCode,
                        onPairingCodeChange = { wirelessDebugCode = it.filter(Char::isDigit).take(6) },
                        onPair = { onPairEmbeddedAdb(wirelessDebugCode) },
                        onRestore = onRestoreEmbeddedAdb,
                        onForget = onForgetEmbeddedAdb,
                        onOpenDeveloperSettings = onOpenDeveloperSettings,
                    )
                    AdvancedAccessCard(
                        notificationsGranted = state.notificationsGranted,
                        fileAccessConfigured = state.fileAccessConfigured,
                        usageAccessGranted = state.usageAccessGranted,
                        uiCaptureActive = uiCapture.status == UiCaptureSessionStatus.ACTIVE,
                        onRequestAppNotifications = onRequestAppNotifications,
                        onChooseFileTree = onChooseFileTree,
                        onRevokeFileAccess = onRevokeFileAccess,
                        onOpenUsageAccess = onOpenUsageAccess,
                        onStartUiCapture = onStartUiCapture,
                        onStopUiCapture = onStopUiCapture,
                    )
                    TextButton(
                        onClick = { showRevokeConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отвязать компьютер") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FriendlyStatusCard(connectionState: ConnectionState) {
    val title: String
    val detail: String
    val container = when (connectionState) {
        ConnectionState.CONNECTED -> {
            title = "Подключено"
            detail = "Компьютер и телефон видят друг друга."
            MaterialTheme.colorScheme.primaryContainer
        }
        ConnectionState.PAIRING -> {
            title = "Подключаем…"
            detail = "Проверяем QR-код и создаём защищённую связь."
            MaterialTheme.colorScheme.secondaryContainer
        }
        ConnectionState.RECONNECTING -> {
            title = "Восстанавливаем связь…"
            detail = "TypedDroid переподключится автоматически."
            MaterialTheme.colorScheme.secondaryContainer
        }
        ConnectionState.ERROR -> {
            title = "Нет связи"
            detail = "Не удалось подключиться. Попробуйте QR-код ещё раз."
            MaterialTheme.colorScheme.errorContainer
        }
        ConnectionState.DISCONNECTED -> {
            title = "Не подключено"
            detail = "Отсканируйте QR-код, чтобы начать."
            MaterialTheme.colorScheme.surfaceContainer
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairingHomeCard(
    connectionState: ConnectionState,
    code: String,
    message: String?,
    showManual: Boolean,
    onToggleManual: () -> Unit,
    onCodeChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    val enabled = connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Подключить телефон", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Попросите агента на компьютере показать QR-код TypedDroid и отсканируйте его камерой.",
                style = MaterialTheme.typography.bodyMedium,
            )
            StepText("1", "Покажите QR-код на компьютере")
            StepText("2", "Отсканируйте его камерой телефона")
            StepText("3", "Откройте ссылку TypedDroid")

            message?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            TextButton(onClick = onToggleManual, enabled = enabled) {
                Text(if (showManual) "Скрыть ручное подключение" else "Не получается подключиться?")
            }

            if (showManual) {
                HorizontalDivider()
                Text(
                    "Ручной способ нужен только для диагностики.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    label = { Text("Код или pairing bundle") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                    Text("Подключить вручную")
                }
            }
        }
    }
}

@Composable
private fun StepText(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SetupHomeCard(
    trustMode: AgentTrustMode,
    embeddedAdb: EmbeddedAdbUiState,
    notificationAccessGranted: Boolean,
    onEnableTrusted: () -> Unit,
    onDisableTrusted: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onFinish: () -> Unit,
    onOpenSystemAccess: () -> Unit,
) {
    val configured = listOf(
        trustMode == AgentTrustMode.TRUSTED,
        embeddedAdb.ready,
        notificationAccessGranted,
    ).count { it }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Настройте доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$configured из 3 основных возможностей настроено. Всё можно изменить позже.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SetupRow(
                title = "Полный доступ агента",
                detail = if (trustMode == AgentTrustMode.TRUSTED) "Включён" else "Сейчас включён безопасный режим",
                ready = trustMode == AgentTrustMode.TRUSTED,
                action = if (trustMode == AgentTrustMode.TRUSTED) "Ограничить" else "Включить",
                onAction = if (trustMode == AgentTrustMode.TRUSTED) onDisableTrusted else onEnableTrusted,
            )
            HorizontalDivider()
            SetupRow(
                title = "Управление системой",
                detail = if (embeddedAdb.ready) "Готово" else "Нужно для shell и системных команд",
                ready = embeddedAdb.ready,
                action = if (embeddedAdb.ready) "Готово" else "Настроить",
                onAction = onOpenSystemAccess,
                actionEnabled = !embeddedAdb.ready,
            )
            HorizontalDivider()
            SetupRow(
                title = "Уведомления телефона",
                detail = if (notificationAccessGranted) "Доступ разрешён" else "Чтение, открытие и ответы",
                ready = notificationAccessGranted,
                action = if (notificationAccessGranted) "Настройки" else "Разрешить",
                onAction = onOpenNotificationAccess,
            )

            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("Готово")
            }
            Text(
                "Можно завершить настройку и без дополнительных разрешений — TypedDroid будет использовать только доступные возможности.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    detail: String,
    ready: Boolean,
    action: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (ready) "Готово" else "Не настроено",
                style = MaterialTheme.typography.labelMedium,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction, enabled = actionEnabled) { Text(action) }
    }
}

@Composable
private fun ReadyHomeCard(
    trustMode: AgentTrustMode,
    embeddedAdbReady: Boolean,
    notificationAccessGranted: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Готов к работе", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Подключённый агент может работать с телефоном через TypedDroid.")
            Spacer(Modifier.height(2.dp))
            SummaryLine("Режим агента", if (trustMode == AgentTrustMode.TRUSTED) "Полный доступ" else "Безопасный")
            SummaryLine("Системное управление", if (embeddedAdbReady) "Готово" else "Ограничено")
            SummaryLine("Уведомления", if (notificationAccessGranted) "Доступны" else "Не подключены")
        }
    }
}

@Composable
private fun HealthSummaryCard(
    health: DeviceHealthSnapshot?,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Телефон", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRefresh) { Text("Обновить") }
            }
            if (health == null) {
                Text("Данные пока недоступны", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SummaryLine("Батарея", health.batteryPercent?.let { "$it%" } ?: "—")
                SummaryLine("Свободная память", friendlyBytes(health.availableMemoryBytes))
                SummaryLine("Свободное место", friendlyBytes(health.availableStorageBytes))
            }
        }
    }
}

@Composable
private fun AccessSettingsCard(
    trustMode: AgentTrustMode,
    notificationAccessGranted: Boolean,
    onEnableTrusted: () -> Unit,
    onDisableTrusted: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Основной доступ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SummaryLine("Агент", if (trustMode == AgentTrustMode.TRUSTED) "Полный доступ" else "Безопасный режим")
            Button(
                onClick = if (trustMode == AgentTrustMode.TRUSTED) onDisableTrusted else onEnableTrusted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (trustMode == AgentTrustMode.TRUSTED) "Включить безопасный режим" else "Включить полный доступ")
            }
            HorizontalDivider()
            SummaryLine("Уведомления телефона", if (notificationAccessGranted) "Разрешены" else "Не разрешены")
            OutlinedButton(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
                Text("Настроить уведомления")
            }
        }
    }
}

@Composable
private fun SystemAccessCard(
    state: EmbeddedAdbUiState,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    onRestore: () -> Unit,
    onForget: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Системное управление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    state.ready -> "Готово. TypedDroid может использовать системные команды Android."
                    state.wasPaired -> "Привязка сохранена, но системный доступ нужно восстановить."
                    else -> "Нужно один раз связать TypedDroid с Wireless debugging Android."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!state.ready) {
                OutlinedButton(
                    onClick = onOpenDeveloperSettings,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) { Text("Открыть параметры разработчика") }
            }

            if (!state.wasPaired) {
                Text(
                    "Откройте «Отладка по Wi‑Fi» → «Подключить устройство с помощью кода» и введите 6 цифр ниже.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = onPairingCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    singleLine = true,
                    label = { Text("6-значный код") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = onPair,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy && pairingCode.length == 6,
                ) { Text("Подключить системный доступ") }
            } else if (!state.ready) {
                Button(onClick = onRestore, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Text("Восстановить доступ")
                }
            }

            if (state.wasPaired) {
                TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                    Text("Сбросить системную привязку")
                }
            }
        }
    }
}

@Composable
private fun AdvancedAccessCard(
    notificationsGranted: Boolean,
    fileAccessConfigured: Boolean,
    usageAccessGranted: Boolean,
    uiCaptureActive: Boolean,
    onRequestAppNotifications: () -> Unit,
    onChooseFileTree: () -> Unit,
    onRevokeFileAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onStartUiCapture: () -> Unit,
    onStopUiCapture: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Дополнительно", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            SummaryLine("Служебные уведомления", if (notificationsGranted) "Разрешены" else "Выключены")
            if (!notificationsGranted && requiresRuntimePermission(Build.VERSION.SDK_INT)) {
                OutlinedButton(onClick = onRequestAppNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text("Разрешить служебные уведомления")
                }
            }
            HorizontalDivider()

            SummaryLine("Выбранная папка", if (fileAccessConfigured) "Доступна" else "Не выбрана")
            OutlinedButton(onClick = onChooseFileTree, modifier = Modifier.fillMaxWidth()) {
                Text(if (fileAccessConfigured) "Сменить папку" else "Выбрать папку")
            }
            if (fileAccessConfigured) {
                TextButton(onClick = onRevokeFileAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Убрать доступ к папке")
                }
            }
            HorizontalDivider()

            SummaryLine("Статистика приложений", if (usageAccessGranted) "Доступна" else "Не разрешена")
            OutlinedButton(onClick = onOpenUsageAccess, modifier = Modifier.fillMaxWidth()) {
                Text("Настроить статистику приложений")
            }
            HorizontalDivider()

            SummaryLine("Захват экрана", if (uiCaptureActive) "Активен" else "Выключен")
            OutlinedButton(
                onClick = if (uiCaptureActive) onStopUiCapture else onStartUiCapture,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uiCaptureActive) "Остановить захват экрана" else "Разрешить захват экрана")
            }
        }
    }
}

@Composable
private fun FriendlyApprovalCard(
    ticket: ApprovalTicket,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val risk = when (ticket.risk) {
        ToolRisk.READ_ONLY -> "Чтение"
        ToolRisk.MUTATING -> "Изменение данных"
        ToolRisk.PRIVILEGED -> "Расширенное действие"
        ToolRisk.UI_CONTROL -> "Управление экраном"
        ToolRisk.TRUSTED_CONTROL -> "Системное действие"
        ToolRisk.RAW_SHELL -> "Команда shell"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Нужно ваше разрешение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(risk, style = MaterialTheme.typography.labelLarge)
            Text(ticket.displaySummary.ifBlank { ticket.tool })
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Отклонить") }
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text("Разрешить") }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun friendlyBytes(value: Long?): String {
    if (value == null || value < 0) return "—"
    val gib = value.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 10) "%.0f ГБ".format(gib) else "%.1f ГБ".format(gib)
}
