package com.dev.macro4curtipay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tipo de tarefa da Curtipay. */
enum class TaskKind {
    UNKNOWN,
    FOLLOW,
    LIKE,
    OTHER;

    val label: String
        get() = when (this) {
            FOLLOW -> "Seguir perfil"
            LIKE -> "Curtir publicação"
            OTHER -> "Outra tarefa"
            UNKNOWN -> "Identificando..."
        }
}

/** Fases da automação. */
enum class AutomationState {
    STOPPED,
    LOOKING_FOR_TASK,
    SELECTING_PROFILE,
    ON_TASK_DETAIL,
    OPENING_INSTAGRAM,
    IN_INSTAGRAM,
    WAITING_MANUAL,
    RETURNING,
    CONFIRMING_TASK
}

/** Estado global compartilhado entre a Activity, o Overlay e o Serviço de Acessibilidade. */
object AutomationManager {

    const val CURTIPAY_URL = "https://curtipay.com/tarefas/instagram"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentState = MutableStateFlow(AutomationState.STOPPED)
    val currentState: StateFlow<AutomationState> = _currentState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Automação parada")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount.asStateFlow()

    private val _skippedCount = MutableStateFlow(0)
    val skippedCount: StateFlow<Int> = _skippedCount.asStateFlow()

    private val _currentTaskKind = MutableStateFlow(TaskKind.UNKNOWN)
    val currentTaskKind: StateFlow<TaskKind> = _currentTaskKind.asStateFlow()

    private val _currentTaskTitle = MutableStateFlow("")
    val currentTaskTitle: StateFlow<String> = _currentTaskTitle.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _manualMode = MutableStateFlow(false)
    val manualMode: StateFlow<Boolean> = _manualMode.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    // ---- Ajustes de tempo (ms) ----
    /** Intervalo entre cada leitura de tela feita pelo motor de automação. */
    var pollIntervalMs: Long = 650L

    /** Tempo mínimo entre dois toques para não parecer robô. */
    var actionCooldownMs: Long = 1400L

    /** Pausa (aleatória) depois de executar a ação no Instagram, antes de voltar. */
    var humanPauseMinMs: Long = 1600L
    var humanPauseMaxMs: Long = 3200L

    /** Tempo máximo esperando o Instagram abrir. */
    var instagramTimeoutMs: Long = 20_000L

    /** No modo manual, quanto tempo esperar o usuário voltar. */
    var manualWaitMs: Long = 5 * 60_000L

    fun startAutomation() {
        _isRunning.value = true
        _currentState.value = AutomationState.LOOKING_FOR_TASK
        _statusMessage.value = "Procurando tarefas na Curtipay..."
    }

    fun stopAutomation() {
        _isRunning.value = false
        _currentState.value = AutomationState.STOPPED
        _statusMessage.value = "Automação parada"
    }

    fun toggleAutomation() {
        if (_isRunning.value) stopAutomation() else startAutomation()
    }

    /** Usado pelo serviço de acessibilidade: troca de fase + mensagem. */
    fun goTo(state: AutomationState, message: String) {
        _currentState.value = state
        _statusMessage.value = message
    }

    /** Atualiza só a mensagem (mesmo com a automação parada). */
    fun notify(message: String) {
        _statusMessage.value = message
    }

    fun setTask(kind: TaskKind, title: String) {
        _currentTaskKind.value = kind
        _currentTaskTitle.value = title
    }

    fun incrementCompleted() {
        _completedCount.value += 1
    }

    fun incrementSkipped() {
        _skippedCount.value += 1
    }

    fun resetStats() {
        _completedCount.value = 0
        _skippedCount.value = 0
    }

    fun setManualMode(enabled: Boolean) {
        _manualMode.value = enabled
    }

    fun setServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val line = "${timeFormat.format(Date())}  $message"
        _logLines.value = (_logLines.value + line).takeLast(80)
    }

    fun clearLog() {
        _logLines.value = emptyList()
    }

    private const val TAG = "CurtipayAuto"
}
