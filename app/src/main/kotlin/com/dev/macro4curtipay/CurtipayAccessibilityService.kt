package com.dev.macro4curtipay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class CurtipayAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var automationJob: Job? = null
    private var lastActionTime = 0L
    private var stateTimer = 0

    companion object {
        var instance: CurtipayAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceScope.launch {
            AutomationManager.statusMessage.collect { msg ->
                // Keep track or update overlay if needed
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!AutomationManager.isRunning.value) return

        val rootNode = rootInActiveWindow ?: return
        val packageName = event.packageName?.toString() ?: ""

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastActionTime < 1200) return // rate limit actions

        when (AutomationManager.currentState.value) {
            AutomationState.SEARCHING_TASK -> {
                val textNodes = findAllTextNodes(rootNode)
                
                // Check if we are on the profile selection screen (as shown in user screenshot)
                var isOnProfileSelection = false
                for (text in textNodes) {
                    val lower = text.lowercase()
                    if (lower.contains("selecione o perfil") || lower.contains("perfil do instagram")) {
                        isOnProfileSelection = true
                        break
                    }
                }

                if (isOnProfileSelection) {
                    AutomationManager.updateState(AutomationState.SEARCHING_TASK, "Selecionando perfil do Instagram...")
                    val activeBtn = findNodeByText(rootNode, "Ativo") 
                        ?: findNodeByText(rootNode, "@")
                        ?: findNodeByText(rootNode, "fukashigi")
                    
                    if (activeBtn != null && clickNode(activeBtn)) {
                        lastActionTime = currentTime
                        stateTimer = 0
                    } else {
                        stateTimer++
                    }
                } else {
                    // Determine task type (Follow or Like)
                    var taskType = TaskType.UNKNOWN
                    for (text in textNodes) {
                        val lower = text.lowercase()
                        if (lower.contains("seguir") || lower.contains("perfil")) {
                            taskType = TaskType.FOLLOW
                            break
                        } else if (lower.contains("curtir") || lower.contains("publicação") || lower.contains("foto")) {
                            taskType = TaskType.LIKE
                            break
                        }
                    }
                    AutomationManager.setTaskType(taskType)

                    // Look for buttons that open Instagram
                    val openButton = findNodeByText(rootNode, "Abrir no Instagram") 
                        ?: findNodeByText(rootNode, "Abrir") 
                        ?: findNodeByText(rootNode, "Acessar")
                        ?: findNodeByText(rootNode, "Ir para")

                    if (openButton != null && clickNode(openButton)) {
                        lastActionTime = currentTime
                        stateTimer = 0
                        AutomationManager.updateState(
                            AutomationState.OPENING_INSTAGRAM,
                            "Abrindo tarefa no Instagram..."
                        )
                    } else {
                        // Check if there's "Concluí a tarefa" stuck from previous cycle
                        val completeButton = findNodeByText(rootNode, "Concluí a tarefa")
                            ?: findNodeByText(rootNode, "Concluir")
                            ?: findNodeByText(rootNode, "Confirmar")
                        
                        if (completeButton != null && clickNode(completeButton)) {
                            lastActionTime = currentTime
                            AutomationManager.incrementCompleted()
                            AutomationManager.updateState(
                                AutomationState.SEARCHING_TASK,
                                "Tarefa concluída!"
                            )
                            stateTimer = 0
                        } else {
                            // If no buttons found, maybe we need to scroll or wait
                            stateTimer++
                            if (stateTimer > 20) {
                                // Try skipping if stuck too long
                                val skipButton = findNodeByText(rootNode, "Pular")
                                if (skipButton != null && clickNode(skipButton)) {
                                    AutomationManager.incrementSkipped()
                                    AutomationManager.updateState(AutomationState.SEARCHING_TASK, "Tarefa pulada.")
                                }
                                stateTimer = 0
                            }
                        }
                    }
                }
            }

            AutomationState.OPENING_INSTAGRAM, AutomationState.IN_INSTAGRAM -> {
                if (packageName.contains("instagram")) {
                    AutomationManager.updateState(AutomationState.IN_INSTAGRAM, "No Instagram...")
                    val taskType = AutomationManager.currentTaskType.value

                    var actionFound = false
                    if (taskType == TaskType.FOLLOW) {
                        val followBtn = findNodeByText(rootNode, "Seguir")
                            ?: findNodeByText(rootNode, "Follow")
                            ?: findNodeByText(rootNode, "Seguir de volta")
                        
                        // Check if already following
                        val alreadyFollowing = findNodeByText(rootNode, "Seguindo") != null || 
                                              findNodeByText(rootNode, "Following") != null ||
                                              findNodeByText(rootNode, "Mensagem") != null ||
                                              findNodeByText(rootNode, "Enviar mensagem") != null

                        if (alreadyFollowing) {
                            actionFound = true
                        } else if (followBtn != null && clickNode(followBtn)) {
                            actionFound = true
                        }
                    } else if (taskType == TaskType.LIKE) {
                        val likeBtn = findNodeByContentDescription(rootNode, "Curtir")
                            ?: findNodeByContentDescription(rootNode, "Like")
                            ?: findNodeByText(rootNode, "Curtir")
                        
                        if (likeBtn != null && clickNode(likeBtn)) {
                            actionFound = true
                        }
                    }

                    // Wait a bit and go back
                    stateTimer++
                    if (actionFound || stateTimer > 8) {
                        serviceScope.launch {
                            delay(1000)
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            lastActionTime = System.currentTimeMillis()
                            stateTimer = 0
                            AutomationManager.updateState(
                                AutomationState.RETURNING_TO_CURTIPAY,
                                "Confirmando tarefa..."
                            )
                        }
                    }
                } else if (packageName.contains("browser") || packageName.contains("chrome")) {
                    // Sometimes it opens browser first, look for "Abrir no Instagram" again there
                    val openBtn = findNodeByText(rootNode, "Abrir no Instagram")
                    if (openBtn != null) clickNode(openBtn)
                } else {
                    stateTimer++
                    if (stateTimer > 15) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        AutomationManager.updateState(AutomationState.SEARCHING_TASK, "Timeout, voltando...")
                        stateTimer = 0
                    }
                }
            }

            AutomationState.RETURNING_TO_CURTIPAY -> {
                val completeButton = findNodeByText(rootNode, "Concluí a tarefa")
                    ?: findNodeByText(rootNode, "Concluir")
                    ?: findNodeByText(rootNode, "Confirmar")

                if (completeButton != null && clickNode(completeButton)) {
                    lastActionTime = currentTime
                    AutomationManager.incrementCompleted()
                    AutomationManager.updateState(
                        AutomationState.SEARCHING_TASK,
                        "Tarefa validada!"
                    )
                    stateTimer = 0
                } else {
                    stateTimer++
                    if (stateTimer > 10) {
                        // Maybe it already went back to searching or we missed it
                        AutomationManager.updateState(AutomationState.SEARCHING_TASK, "Buscando próxima...")
                        stateTimer = 0
                    }
                }
            }

            else -> {}
        }
        rootNode.recycle()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text != null && node.text.toString().contains(text, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (node.contentDescription != null && node.contentDescription.toString().contains(desc, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, desc)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findAllTextNodes(node: AccessibilityNodeInfo, list: MutableList<String> = mutableListOf()): List<String> {
        if (!node.text.isNullOrEmpty()) {
            list.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllTextNodes(child, list)
            child.recycle()
        }
        return list
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            target = target.parent
        }
        // If no clickable parent worked, try clicking the node itself directly
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // Fallback: Click center of bounds using gesture
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            try {
                val path = android.graphics.Path().apply {
                    moveTo(rect.exactCenterX(), rect.exactCenterY())
                }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()
                return dispatchGesture(gesture, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    override fun onInterrupt() {
        AutomationManager.stopAutomation()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
    }
}
