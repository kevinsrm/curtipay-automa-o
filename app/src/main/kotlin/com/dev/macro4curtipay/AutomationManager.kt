package com.dev.macro4curtipay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskType {
    UNKNOWN,
    FOLLOW,
    LIKE
}

enum class AutomationState {
    STOPPED,
    SEARCHING_TASK,
    OPENING_INSTAGRAM,
    IN_INSTAGRAM,
    RETURNING_TO_CURTIPAY,
    CONFIRMING_TASK
}

object AutomationManager {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentState = MutableStateFlow(AutomationState.STOPPED)
    val currentState: StateFlow<AutomationState> = _currentState.asStateFlow()

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount.asStateFlow()

    private val _skippedCount = MutableStateFlow(0)
    val skippedCount: StateFlow<Int> = _skippedCount.asStateFlow()

    private val _statusMessage = MutableStateFlow("Automação parada")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _currentTaskType = MutableStateFlow(TaskType.UNKNOWN)
    val currentTaskType: StateFlow<TaskType> = _currentTaskType.asStateFlow()

    // Configuration settings
    var delayBetweenActionsMs: Long = 2000L
    var maxTimeoutSeconds: Int = 15

    fun startAutomation() {
        _isRunning.value = true
        _currentState.value = AutomationState.SEARCHING_TASK
        _statusMessage.value = "Procurando tarefa..."
    }

    fun stopAutomation() {
        _isRunning.value = false
        _currentState.value = AutomationState.STOPPED
        _statusMessage.value = "Automação parada"
    }

    fun toggleAutomation() {
        if (_isRunning.value) stopAutomation() else startAutomation()
    }

    fun updateState(newState: AutomationState, message: String) {
        if (!_isRunning.value && newState != AutomationState.STOPPED) return
        _currentState.value = newState
        _statusMessage.value = message
    }

    fun setTaskType(type: TaskType) {
        _currentTaskType.value = type
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
}
