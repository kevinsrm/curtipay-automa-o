package com.dev.macro4curtipay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random

/**
 * Motor da automação da Curtipay.
 *
 * Fluxo completo, por tarefa:
 *  1. identifica a tarefa na lista da Curtipay (curtir publicação OU seguir perfil);
 *  2. seleciona o perfil do Instagram conectado ("Selecione o perfil");
 *  3. clica em "Abrir no Instagram" (e em "Abrir com > Instagram" se aparecer o diálogo);
 *  4. executa a ação no Instagram (curtir / seguir) e confere se a ação pegou;
 *  5. volta para a Curtipay e clica em "Concluí a tarefa";
 *  6. volta para a lista de tarefas e repete com a próxima.
 */
class CurtipayAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var engineJob: Job? = null
    private val stepping = AtomicBoolean(false)

    private var screenHeight = 1920

    private var lastEventPackage = ""
    private var lastActionAt = 0L
    private var lastNavAt = 0L
    private var phaseStartedAt = 0L
    private var postActionDelayMs = 2000L
    private var lastDebugLogAt = 0L
    private var recentlyFinishedAt = 0L

    // ---- dados da tarefa atual ----
    private var taskKind = TaskKind.UNKNOWN
    private var taskTitle = ""
    private var taskHandle = ""
    private var taskUrl = ""
    private var taskKey = ""
    private var attempts = 0
    private var actionPerformed = false
    private var actionVerified = false
    private var likeClicked = false
    private var followClicked = false
    private var promptsDismissed = false
    private var backAttempts = 0
    private var scrollAttempts = 0

    private val finishedTasks = LinkedHashSet<String>()
    private val failedTasks = LinkedHashSet<String>()
    private val cardClickAttempts = HashMap<String, Int>()

    private class TaskCard(
        val node: UiNode,
        val key: String,
        val title: String,
        val kind: TaskKind,
        val handle: String,
        val url: String
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AutomationManager.setServiceConnected(true)
        screenHeight = resources.displayMetrics.heightPixels
        AutomationManager.log("Serviço de acessibilidade conectado")
        startEngine()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage != packageName) lastEventPackage = eventPackage
    }

    override fun onInterrupt() {
        // Interrupções acontecem em trocas de janela: não desligamos a automação aqui.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutomationManager.setServiceConnected(false)
        AutomationManager.log("Serviço de acessibilidade desconectado")
        engineJob?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        AutomationManager.setServiceConnected(false)
        engineJob?.cancel()
        scope.cancel()
        if (instance === this) instance = null
    }

    // =====================================================================
    // Motor
    // =====================================================================

    private fun startEngine() {
        engineJob?.cancel()
        engineJob = scope.launch {
            var wasRunning = false
            while (isActive) {
                try {
                    val running = AutomationManager.isRunning.value
                    if (running && !wasRunning) resetSession()
                    wasRunning = running
                    if (running) step()
                } catch (t: Throwable) {
                    AutomationManager.log("Erro no motor: ${t.message}")
                }
                delay(AutomationManager.pollIntervalMs)
            }
        }
    }

    /** Pausa a automação quando a tela está desligada (gestos não funcionariam). */
    private fun screenOn(): Boolean = try {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        powerManager?.isInteractive ?: true
    } catch (t: Throwable) {
        true
    }

    private fun step() {
        if (!stepping.compareAndSet(false, true)) return
        try {
            if (!screenOn()) {
                AutomationManager.notify("Tela desligada: automação pausada")
                return
            }
            val screen = readUi(this, packageName)
            val pkg = screen.packageName.ifEmpty { lastEventPackage }
            when (AutomationManager.currentState.value) {
                AutomationState.LOOKING_FOR_TASK -> stepFindTask(screen, pkg)
                AutomationState.SELECTING_PROFILE -> stepSelectProfile(screen, pkg)
                AutomationState.ON_TASK_DETAIL -> stepTaskDetail(screen, pkg)
                AutomationState.OPENING_INSTAGRAM -> stepOpeningInstagram(screen, pkg)
                AutomationState.IN_INSTAGRAM -> stepInstagram(screen, pkg)
                AutomationState.WAITING_MANUAL -> stepManual(screen, pkg)
                AutomationState.RETURNING -> stepReturning(screen, pkg)
                AutomationState.CONFIRMING_TASK -> stepConfirming(screen, pkg)
                AutomationState.STOPPED -> Unit
            }
        } finally {
            stepping.set(false)
        }
    }

    private fun resetSession() {
        finishedTasks.clear()
        failedTasks.clear()
        cardClickAttempts.clear()
        resetTaskProgress()
        phaseStartedAt = now()
        lastNavAt = 0L
        lastActionAt = 0L
        AutomationManager.resetStats()
        AutomationManager.log("Automação iniciada")
    }

    private fun resetTaskProgress() {
        taskKind = TaskKind.UNKNOWN
        taskTitle = ""
        taskHandle = ""
        taskUrl = ""
        taskKey = ""
        attempts = 0
        actionPerformed = false
        actionVerified = false
        likeClicked = false
        followClicked = false
        promptsDismissed = false
        backAttempts = 0
        scrollAttempts = 0
        AutomationManager.setTask(TaskKind.UNKNOWN, "")
    }

    // =====================================================================
    // 1) Lista de tarefas da Curtipay
    // =====================================================================

    private fun stepFindTask(screen: UiScreen, pkg: String) {
        if (isInstagramPackage(pkg)) {
            // Não deveríamos estar no Instagram entre tarefas.
            if (since(phaseStartedAt) > 1200 && cooldownOk()) back()
            return
        }

        if (!isCurtipayPage(screen, pkg)) {
            if (isOurPackage(pkg)) {
                AutomationManager.notify("Abra a Curtipay no navegador para continuar (automação em espera)")
                return
            }
            if (since(lastNavAt) < 7000) return
            if (isBrowser(screen, pkg) && backAttempts < 2 && cooldownOk()) {
                backAttempts++
                back()
                return
            }
            openCurtipayTasks()
            return
        }

        backAttempts = 0

        if (isProfileSelection(screen)) {
            go(AutomationState.SELECTING_PROFILE, "Selecionando o perfil do Instagram...")
            return
        }

        // Página de uma tarefa que já está em validação (sem botões de tarefa): só sair dela.
        if (screen.containsAny(AWAITING_MARKS) && screen.allWhere { isTaskActionButton(it) }.isEmpty()) {
            if (cooldownOk() && since(phaseStartedAt) > 2500) back()
            AutomationManager.notify("Voltando para a lista de tarefas...")
            return
        }

        if (isTaskDetailPage(screen)) {
            val key = taskKeyFromScreen(screen)
            if (key.isNotEmpty() && (finishedTasks.contains(key) || failedTasks.contains(key))) {
                // Página de uma tarefa já resolvida: volta para a lista.
                if (cooldownOk()) back()
                return
            }
            go(AutomationState.ON_TASK_DETAIL, "Lendo os dados da tarefa...")
            return
        }

        val card = pickTaskCard(screen)
        if (card == null) {
            AutomationManager.notify("Aguardando novas tarefas...")
            if (scrollAttempts == 0) debugScreen(screen, "lista de tarefas")
            maybeScroll(screen)
            return
        }

        taskKey = card.key
        taskKind = card.kind
        taskTitle = card.title
        taskHandle = card.handle
        taskUrl = card.url
        attempts = 0
        actionPerformed = false
        actionVerified = false
        AutomationManager.setTask(card.kind, card.title)

        if (!cooldownOk()) return
        if (click(card.node)) {
            act()
            backAttempts = 0
            cardClickAttempts[card.key] = (cardClickAttempts[card.key] ?: 0) + 1
            AutomationManager.log("Tarefa encontrada: ${card.title} (${card.kind.label})")
            go(AutomationState.ON_TASK_DETAIL, "Abrindo a tarefa: ${card.title}")
        } else {
            attempts++
            if (attempts > 2) {
                failedTasks.add(card.key)
                AutomationManager.incrementSkipped()
                AutomationManager.log("Não consegui clicar na tarefa: ${card.title}")
                resetTaskProgress()
            }
        }
    }

    /** Escolhe o próximo cartão de tarefa ainda não resolvido. */
    private fun pickTaskCard(screen: UiScreen): TaskCard? {
        for (button in screen.allWhere { isTaskActionButton(it) }.take(8)) {
            val card = buildCard(button) ?: continue
            if (isCardDiscarded(card)) continue
            return card
        }

        // Fallback: o próprio título da tarefa pode ser clicável.
        for (title in screen.allWhere { node ->
            node.norm.length in 6..120 &&
                !node.norm.contains("abrir") &&
                (Text.looksLikeFollowTask(node.norm) || Text.looksLikeLikeTask(node.norm))
        }.take(6)) {
            val card = buildCard(title) ?: continue
            if (isCardDiscarded(card)) continue
            if (title.clickable || title.hasClickableAncestor()) return card
        }
        return null
    }

    /** Cartão já resolvido, que falhou ou que não abre depois de 3 tentativas. */
    private fun isCardDiscarded(card: TaskCard): Boolean {
        if (finishedTasks.contains(card.key) || failedTasks.contains(card.key)) return true
        val tries = cardClickAttempts[card.key] ?: 0
        if (tries >= 3) {
            failedTasks.add(card.key)
            AutomationManager.log("Desisti de abrir a tarefa: ${card.title}")
            return true
        }
        return false
    }

    private fun isTaskActionButton(node: UiNode): Boolean {
        val n = node.norm
        if (n.isEmpty() || n.contains("instagram")) return false
        if (Text.startsWithAny(n, TASK_BUTTON_PREFIXES)) return true
        return n == "abrir" || n == "iniciar" || n == "comecar"
    }

    /**
     * Lê o bloco (cartão) que contém o botão da tarefa para descobrir título,
     * recompensa e tipo (seguir / curtir).
     */
    private fun buildCard(button: UiNode): TaskCard? {
        var parent: AccessibilityNodeInfo? = try {
            button.info.parent
        } catch (t: Throwable) {
            null
        }
        var best = button.label
        var level = 0
        while (level < 6) {
            val current = parent ?: break
            val joined = UiNode(current).subtreeLabels(120).joinToString("  ")
            best = joined
            val isTaskBlock = Text.looksLikeFollowTask(joined) ||
                Text.looksLikeLikeTask(joined) ||
                Text.hasCoinValue(joined)
            if (isTaskBlock) break
            parent = try {
                current.parent
            } catch (t: Throwable) {
                null
            }
            level++
        }

        if (isFinishedMark(Text.norm(best))) return null

        val kind = when {
            Text.looksLikeFollowTask(best) && !Text.looksLikeLikeTask(best) -> TaskKind.FOLLOW
            Text.looksLikeLikeTask(best) && !Text.looksLikeFollowTask(best) -> TaskKind.LIKE
            Text.looksLikeFollowTask(best) -> TaskKind.FOLLOW
            Text.looksLikeLikeTask(best) -> TaskKind.LIKE
            else -> TaskKind.UNKNOWN
        }

        val title = best.split("  ")
            .firstOrNull { part ->
                part.length in 6..120 &&
                    (Text.looksLikeFollowTask(part) || Text.looksLikeLikeTask(part))
            }
            ?.trim()
            ?: best.take(120)

        val handle = Text.firstHandle(best)
        val url = Text.firstInstagramUrl(best)
        return TaskCard(button, "${Text.norm(title)}|$handle", title, kind, handle, url)
    }

    private fun isFinishedMark(normalizedBlock: String): Boolean =
        FINISHED_MARKS.any { normalizedBlock.contains(it) }

    private fun taskKeyFromScreen(screen: UiScreen): String {
        val handle = Text.firstHandle(screen.allText())
        val title = screen.nodes
            .map { it.label }
            .firstOrNull { part ->
                part.length in 6..120 &&
                    (Text.looksLikeFollowTask(part) || Text.looksLikeLikeTask(part))
            }
            ?.let { Text.norm(it) }
            .orEmpty()
        if (title.isEmpty() && handle.isEmpty()) return ""
        return "$title|$handle"
    }

    private fun maybeScroll(screen: UiScreen) {
        if (!cooldownOk() || since(phaseStartedAt) < 3000) return
        val scrollable = screen.firstWhere { it.scrollable }
        if (scrollable != null) {
            val forward = scrollAttempts % 4 != 3
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val ok = try {
                scrollable.info.performAction(action)
            } catch (t: Throwable) {
                false
            }
            if (ok) {
                act()
                scrollAttempts++
                return
            }
        }
        val centerX = resources.displayMetrics.widthPixels / 2f
        if (scrollAttempts % 4 == 3) {
            swipe(centerX, screenHeight * 0.30f, centerX, screenHeight * 0.75f)
        } else {
            swipe(centerX, screenHeight * 0.75f, centerX, screenHeight * 0.30f)
        }
        act()
        scrollAttempts++
    }

    // =====================================================================
    // 2) Seleção do perfil do Instagram
    // =====================================================================

    private fun stepSelectProfile(screen: UiScreen, pkg: String) {
        if (!isCurtipayPage(screen, pkg)) {
            AutomationManager.notify("Aguardando a página da Curtipay...")
            if (since(phaseStartedAt) > 10_000) {
                go(AutomationState.LOOKING_FOR_TASK, "Voltando para as tarefas...")
            }
            return
        }
        if (!isProfileSelection(screen)) {
            go(AutomationState.ON_TASK_DETAIL, "Perfil pronto. Verificando a tarefa...")
            return
        }
        if (!cooldownOk()) return

        val candidates = screen.allWhere { node ->
            val n = node.norm
            n.isNotEmpty() && (n.contains("@") || n.contains("ativo") || n.contains("conectado"))
        }
        val target = candidates.firstOrNull { it.norm.contains("ativo") || it.norm.contains("conectado") }
            ?: candidates.firstOrNull { it.norm.contains("@") }

        if (target == null) {
            if (since(phaseStartedAt) > 12_000) {
                AutomationManager.log("Nenhum perfil do Instagram conectado na Curtipay")
                go(
                    AutomationState.LOOKING_FOR_TASK,
                    "Conecte um perfil do Instagram na Curtipay para continuar"
                )
            }
            return
        }

        if (click(target)) {
            act()
            AutomationManager.log("Perfil do Instagram selecionado")
            go(AutomationState.ON_TASK_DETAIL, "Perfil selecionado. Verificando a tarefa...")
        }
    }

    private fun isProfileSelection(screen: UiScreen): Boolean =
        screen.containsAny(PROFILE_SELECTION_MARKS)

    // =====================================================================
    // 3) Página de detalhe da tarefa -> "Abrir no Instagram"
    // =====================================================================

    private fun stepTaskDetail(screen: UiScreen, pkg: String) {
        if (!isCurtipayPage(screen, pkg)) {
            if (isInstagramPackage(pkg) || isBrowser(screen, pkg)) {
                go(AutomationState.RETURNING, "Voltando para a página da tarefa...")
            } else if (since(phaseStartedAt) > 6000) {
                go(AutomationState.LOOKING_FOR_TASK, "Voltando para as tarefas...")
            }
            return
        }

        if (isProfileSelection(screen)) {
            go(AutomationState.SELECTING_PROFILE, "Selecionando o perfil do Instagram...")
            return
        }

        // Tarefa que já está em validação na Curtipay: não fazer de novo.
        if (screen.containsAny(AWAITING_MARKS)) {
            returnToList("Esta tarefa já está aguardando validação na Curtipay")
            return
        }

        readTaskInfo(screen)

        val openButton = findOpenInstagramButton(screen)
        if (openButton == null) {
            if (findConfirmButton(screen) != null) {
                go(AutomationState.CONFIRMING_TASK, "Confirmando a tarefa...")
                return
            }
            if (isTasksList(screen)) {
                go(AutomationState.LOOKING_FOR_TASK, "Voltando para a lista de tarefas...")
                return
            }
            if (since(phaseStartedAt) > 6000) {
                attempts++
                debugScreen(screen, "detalhe da tarefa sem botão")
                if (cooldownOk()) back()
            }
            if (attempts > 3) markCurrentTaskFailed("Não encontrei o botão 'Abrir no Instagram'")
            return
        }

        if (!cooldownOk()) return

        if (click(openButton)) {
            act()
            attempts = 0
            AutomationManager.log("Clicou em 'Abrir no Instagram'")
            val manual = AutomationManager.manualMode.value
            go(
                AutomationState.OPENING_INSTAGRAM,
                if (manual) "Abra o Instagram e faça a tarefa" else "Abrindo o Instagram..."
            )
        } else {
            attempts++
            if (attempts > 3) markCurrentTaskFailed("O botão 'Abrir no Instagram' não respondeu")
        }
    }

    private fun readTaskInfo(screen: UiScreen) {
        val detected = detectTaskKind(screen)
        if (detected == TaskKind.UNKNOWN) return
        taskKind = detected
        if (taskHandle.isEmpty()) taskHandle = Text.firstHandle(screen.allText())
        if (taskUrl.isEmpty()) taskUrl = screen.findInstagramUrl()
        if (taskTitle.isEmpty()) {
            taskTitle = screen.nodes
                .map { it.label }
                .firstOrNull { part ->
                    part.length in 6..120 &&
                        (Text.looksLikeFollowTask(part) || Text.looksLikeLikeTask(part))
                }
                .orEmpty()
        }
        if (taskKey.isEmpty()) taskKey = "${Text.norm(taskTitle)}|$taskHandle"
        AutomationManager.setTask(detected, taskTitle)
    }

    /** Decide o tipo da tarefa pelo texto da página, na ordem de leitura. */
    private fun detectTaskKind(screen: UiScreen): TaskKind {
        for (node in screen.nodes) {
            val n = node.norm
            if (n.isEmpty() || n.length > 140) continue
            if (IGNORED_FOR_KIND.any { n.contains(it) }) continue
            val follow = Text.looksLikeFollowTask(n)
            val like = Text.looksLikeLikeTask(n)
            if (follow && !like) return TaskKind.FOLLOW
            if (like && !follow) return TaskKind.LIKE
        }
        return TaskKind.UNKNOWN
    }

    private fun findOpenInstagramButton(screen: UiScreen): UiNode? =
        screen.firstWhere { node ->
            val n = node.norm
            n.isNotEmpty() && (
                (n.contains("abrir") && n.contains("instagram")) ||
                    n.startsWith("abrir no app") || n.startsWith("abrir o app") ||
                    n.startsWith("ir para o instagram") || n.startsWith("acessar o instagram") ||
                    n.startsWith("continuar no instagram") || n.startsWith("abrir tarefa") ||
                    n.startsWith("iniciar tarefa") || n.startsWith("fazer tarefa") ||
                    n.startsWith("realizar tarefa")
                )
        }

    // =====================================================================
    // 4) Abertura do Instagram (diálogo "Abrir com" e instagram.com no meio)
    // =====================================================================

    private fun stepOpeningInstagram(screen: UiScreen, pkg: String) {
        val manual = AutomationManager.manualMode.value

        // Diálogo "Abrir com" do Android: escolher o Instagram.
        if (screen.containsAny(CHOOSER_MARKS)) {
            val instagramRow = screen.firstWhere { node ->
                node.norm.contains("instagram") && (node.clickable || node.hasClickableAncestor())
            }
            if (instagramRow != null && cooldownOk() && click(instagramRow)) {
                act()
                AutomationManager.log("Escolhi o Instagram no diálogo 'Abrir com'")
                return
            }
        }

        if (isInstagramPackage(pkg)) {
            attempts = 0
            actionPerformed = false
            actionVerified = false
            likeClicked = false
            followClicked = false
            promptsDismissed = false
            go(
                if (manual) AutomationState.WAITING_MANUAL else AutomationState.IN_INSTAGRAM,
                if (manual) {
                    "Faça a tarefa no Instagram e depois volte para a Curtipay"
                } else {
                    "Executando a tarefa no Instagram..."
                }
            )
            return
        }

        // Modo manual: não insistimos em abrir o app, deixamos o usuário agir.
        if (manual && isCurtipayPage(screen, pkg) && since(phaseStartedAt) > 4000) {
            go(AutomationState.WAITING_MANUAL, "Faça a tarefa no Instagram e depois volte para a Curtipay")
            return
        }

        // O navegador abriu o instagram.com (web): tentar entrar no app.
        if (isBrowser(screen, pkg) && screen.containsAny("instagram")) {
            val openInApp = screen.firstWhere { node ->
                val n = node.norm
                n.startsWith("abrir no app") || n.startsWith("abrir o app") ||
                    n.startsWith("abrir no instagram") || n.startsWith("abrir o instagram") ||
                    n.startsWith("usar o app")
            }
            if (openInApp != null && cooldownOk() && click(openInApp)) {
                act()
                return
            }
            if (since(lastNavAt) > 5000) {
                openInstagramDirect(screen)
                return
            }
        }

        // Continua na Curtipay: o clique não navegou. Tentar de novo.
        if (isCurtipayPage(screen, pkg)) {
            if (since(phaseStartedAt) < 2500) return
            if (attempts < 2 && cooldownOk()) {
                val openButton = findOpenInstagramButton(screen)
                if (openButton != null && click(openButton)) {
                    act()
                    attempts++
                    AutomationManager.log("Tentando abrir o Instagram novamente")
                    return
                }
            }
            if (since(phaseStartedAt) > 7000 && cooldownOk()) {
                debugScreen(screen, "não abriu o Instagram")
                openInstagramDirect(screen)
                return
            }
        }

        if (since(phaseStartedAt) > AutomationManager.instagramTimeoutMs) {
            markCurrentTaskFailed("Não consegui abrir o Instagram")
        }
    }

    /** Abre o Instagram direto (deep link): usado quando o botão/site não resolve. */
    private fun openInstagramDirect(screen: UiScreen): Boolean {
        var target = taskUrl
        if (target.isEmpty()) target = screen.findInstagramUrl()
        if (target.isEmpty() && taskKind == TaskKind.FOLLOW && taskHandle.isNotEmpty()) {
            target = "https://www.instagram.com/${taskHandle.trimStart('@')}/"
        }
        if (target.isEmpty()) return false
        if (!target.startsWith("http")) target = "https://$target"

        val intents = ArrayList<Intent>(3)
        intents.add(
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .setPackage(AutomationManager.INSTAGRAM_PACKAGE)
        )
        intents.add(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        try {
            val bare = target.substringAfter("://", target)
            intents.add(
                Intent.parseUri(
                    "intent://$bare#Intent;scheme=https;package=${AutomationManager.INSTAGRAM_PACKAGE};end",
                    Intent.URI_INTENT_SCHEME
                )
            )
        } catch (t: Throwable) {
            // sem problema: usamos os outros dois intents
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                lastNavAt = now()
                AutomationManager.log("Abrindo o Instagram direto: $target")
                return true
            } catch (t: Throwable) {
                // tenta o proximo
            }
        }
        return false
    }

    // =====================================================================
    // 5) Execução da tarefa dentro do Instagram
    // =====================================================================

    private fun stepInstagram(screen: UiScreen, pkg: String) {
        if (!isInstagramPackage(pkg)) {
            if (since(phaseStartedAt) > 2500) {
                go(AutomationState.RETURNING, "Voltando para a Curtipay...")
            }
            return
        }

        if (isInstagramLoginScreen(screen)) {
            stopWithAlert("Faça login no Instagram e depois inicie a automação novamente")
            return
        }

        if (keyboardVisible()) {
            if (cooldownOk()) back()
            return
        }

        if (screen.nodes.size < 6 && since(phaseStartedAt) < 6000) return

        if (!promptsDismissed) {
            promptsDismissed = true
            if (dismissInstagramPrompts(screen)) return
        }

        val kind = resolveKindInInstagram(screen)

        if (!actionPerformed) {
            if (since(phaseStartedAt) < 900) return
            when (kind) {
                TaskKind.LIKE -> performLike(screen)
                TaskKind.FOLLOW -> performFollow(screen)
                TaskKind.UNKNOWN, TaskKind.OTHER -> {
                    if (since(phaseStartedAt) > 8000) {
                        debugScreen(screen, "não identifiquei a ação no Instagram")
                        markCurrentTaskFailed("Não identifiquei a ação pedida na tela do Instagram")
                    } else {
                        AutomationManager.notify("Analisando a tela do Instagram...")
                    }
                }
            }
            return
        }

        // Confere se a ação realmente aconteceu.
        if (!actionVerified) {
            if (since(lastActionAt) < 900) return
            if (verifyAction(screen, kind)) {
                actionVerified = true
                AutomationManager.log("Ação confirmada no Instagram")
            } else if (attempts < 2 && !(kind == TaskKind.LIKE && likeClicked)) {
                attempts++
                actionPerformed = false
                AutomationManager.log("A ação não confirmou; tentando de novo")
                return
            } else {
                actionVerified = true
                AutomationManager.log("Não confirmei a ação, mas vou seguir em frente")
            }
        }

        if (since(lastActionAt) >= postActionDelayMs && cooldownOk()) {
            back()
            AutomationManager.log("Voltando para a Curtipay")
            go(AutomationState.RETURNING, "Voltando para a Curtipay...")
        }
    }

    private fun performLike(screen: UiScreen): Boolean {
        if (isAlreadyLiked(screen)) {
            actionPerformed = true
            actionVerified = true
            postActionDelayMs = 1200
            AutomationManager.log("A publicação já estava curtida")
            return true
        }

        val likeButtonFound = findLikeButton(screen)
        if (likeClicked && likeButtonFound == null) {
            // O botão mudou de estado depois do nosso clique: a curtida foi feita.
            actionPerformed = true
            actionVerified = true
            postActionDelayMs = 1200
            AutomationManager.log("Curtida enviada")
            return true
        }

        if (looksLikeProfilePage(screen) && likeButtonFound == null) {
            markCurrentTaskFailed("A tarefa de curtir abriu o perfil em vez da publicação")
            return false
        }

        val likeButton = likeButtonFound
        if (likeButton != null && cooldownOk() && click(likeButton)) {
            act()
            actionPerformed = true
            likeClicked = true
            postActionDelayMs = humanPause()
            AutomationManager.log("Curtiu a publicação")
            return true
        }

        attempts++
        // O toque duplo só é usado quando ainda não clicamos em curtir (evita descurtir sem querer).
        if (!likeClicked && attempts <= 2 && cooldownOk() && doubleTapMedia(screen)) {
            act()
            actionPerformed = true
            likeClicked = true
            postActionDelayMs = humanPause()
            AutomationManager.log("Curtiu com toque duplo na foto")
            return true
        }

        if (attempts >= 3 && since(phaseStartedAt) > 9000) {
            if (likeClicked) {
                actionPerformed = true
                actionVerified = true
                postActionDelayMs = 1200
                AutomationManager.log("Curtida enviada (não deu para conferir na tela)")
                return true
            }
            debugScreen(screen, "sem botão de curtir")
            markCurrentTaskFailed("Não encontrei o botão Curtir")
        }
        return false
    }

    private fun performFollow(screen: UiScreen): Boolean {
        if (isAlreadyFollowing(screen)) {
            actionPerformed = true
            actionVerified = true
            postActionDelayMs = 1200
            AutomationManager.log("O perfil já estava seguido")
            return true
        }

        val followButtonFound = findFollowButton(screen)
        if (followClicked && followButtonFound == null) {
            actionPerformed = true
            actionVerified = true
            postActionDelayMs = 1200
            AutomationManager.log("Perfil seguido")
            return true
        }

        val followButton = followButtonFound
        if (followButton != null && cooldownOk() && click(followButton)) {
            act()
            actionPerformed = true
            followClicked = true
            postActionDelayMs = humanPause()
            AutomationManager.log("Seguindo o perfil ${taskHandle}".trim())
            return true
        }

        attempts++
        if (attempts >= 3 && since(phaseStartedAt) > 9000) {
            if (followClicked) {
                actionPerformed = true
                actionVerified = true
                postActionDelayMs = 1200
                AutomationManager.log("Perfil seguido (não deu para conferir na tela)")
                return true
            }
            debugScreen(screen, "sem botão de seguir")
            markCurrentTaskFailed("Não encontrei o botão Seguir")
        }
        return false
    }

    /**
     * Quando a Curtipay não deixa claro o tipo da tarefa, descobrimos pelo que
     * está na tela do Instagram.
     */
    private fun resolveKindInInstagram(screen: UiScreen): TaskKind {
        if (taskKind == TaskKind.FOLLOW || taskKind == TaskKind.LIKE) return taskKind
        return when {
            findFollowButton(screen) != null || isAlreadyFollowing(screen) -> TaskKind.FOLLOW
            findLikeButton(screen) != null || isAlreadyLiked(screen) -> TaskKind.LIKE
            else -> TaskKind.UNKNOWN
        }
    }

    private fun verifyAction(screen: UiScreen, kind: TaskKind): Boolean = when (kind) {
        TaskKind.LIKE -> isAlreadyLiked(screen)
        TaskKind.FOLLOW -> isAlreadyFollowing(screen)
        else -> true
    }

    private fun isAlreadyLiked(screen: UiScreen): Boolean = screen.nodes.any { node ->
        val d = node.normDescription
        val t = node.normText
        (d.isNotEmpty() && Text.startsWithWord(d, LIKED_PREFIXES)) ||
            (t.isNotEmpty() && Text.startsWithWord(t, LIKED_PREFIXES)) ||
            d == "curtida" || t == "curtida"
    }

    private fun isAlreadyFollowing(screen: UiScreen): Boolean {
        val following = screen.nodes.any { node ->
            val n = node.norm
            n.startsWith("seguindo") || n == "following" || n.startsWith("following ")
        }
        if (following) return true
        if (findFollowButton(screen) != null) return false
        return screen.nodes.any { node ->
            node.norm.startsWith("enviar mensagem") || node.norm == "mensagem"
        }
    }

    private fun findFollowButton(screen: UiScreen): UiNode? {
        val headerLimit = screenHeight * 0.75f
        val inHeader = screen.firstWhere { node ->
            node.norm in FOLLOW_LABELS && node.enabled && node.visible &&
                node.centerY < headerLimit && (node.clickable || node.hasClickableAncestor())
        }
        if (inHeader != null) return inHeader
        return screen.firstWhere { node ->
            node.norm in FOLLOW_LABELS && node.enabled && node.visible
        }
    }

    private fun findLikeButton(screen: UiScreen): UiNode? {
        val byDescription = screen.firstWhere { node ->
            val d = node.normDescription
            d.isNotEmpty() && Text.startsWithWord(d, LIKE_PREFIXES) &&
                node.enabled && node.visible && (node.clickable || node.hasClickableAncestor())
        }
        if (byDescription != null) return byDescription

        val byText = screen.firstWhere { node ->
            val t = node.normText
            t.isNotEmpty() && Text.startsWithWord(t, LIKE_PREFIXES) && node.enabled && node.visible
        }
        if (byText != null) return byText

        return findLikeButtonByActionBar(screen)
    }

    /**
     * Recurso estrutural: na barra de ações do post (curtir, comentar, compartilhar, salvar)
     * o botão de curtir é o mais à esquerda da linha. Usamos o botão de
     * salvar/compartilhar/comentar como referência de posição.
     */
    private fun findLikeButtonByActionBar(screen: UiScreen): UiNode? {
        val reference = screen.firstWhere { node ->
            Text.startsWithWord(node.normDescription, ACTION_BAR_PREFIXES) && node.visible
        } ?: return null

        val referenceCenterY = reference.centerY
        val referenceHeight = reference.height.toFloat()
        val maxWidth = resources.displayMetrics.widthPixels * 0.3f

        return screen.allWhere { node ->
            node.clickable && node.text.isEmpty() && node.visible && node.enabled &&
                node.width < maxWidth &&
                node.height in (referenceHeight * 0.4f).toInt()..(referenceHeight * 3f).toInt() &&
                abs(node.centerY - referenceCenterY) <= referenceHeight &&
                node.centerX < reference.centerX
        }.minByOrNull { it.centerX }
    }

    /** Plano B: toque duplo na foto do post (o Instagram curte com toque duplo). */
    private fun doubleTapMedia(screen: UiScreen): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val media = screen.firstWhere { node ->
            node.className.contains("ImageView") && node.visible &&
                node.width > width * 0.5f && node.height > 120 &&
                (node.normDescription.contains("foto") || node.normDescription.contains("imagem") ||
                    node.normDescription.contains("photo") || node.normDescription.contains("image") ||
                    node.normDescription.contains("video") || node.normDescription.contains("reel"))
        } ?: return false
        return doubleTap(media.centerX, media.centerY)
    }

    private fun looksLikeProfilePage(screen: UiScreen): Boolean =
        screen.containsAny("publicacoes", "seguidores", "seguindo") &&
            (findFollowButton(screen) != null || screen.containsAny("editar perfil", "ver insights"))

    private fun dismissInstagramPrompts(screen: UiScreen): Boolean {
        if (!screen.containsAny(IG_PROMPT_MARKS)) return false
        val dismiss = screen.firstWhere { node -> node.norm in DISMISS_LABELS && node.enabled && node.visible }
            ?: return false
        if (!cooldownOk()) return false
        if (click(dismiss)) {
            act()
            AutomationManager.log("Fechei um aviso do Instagram")
            return true
        }
        return false
    }

    private fun isInstagramLoginScreen(screen: UiScreen): Boolean {
        val loginMarks = screen.containsAny(
            "esqueceu a senha", "forgot password", "entrar com facebook",
            "log in with facebook", "usuario ou email", "nome de usuario, email",
            "faca login", "entrar no instagram"
        )
        val signup = screen.containsAny("criar nova conta", "inscreva-se", "sign up")
        val password = screen.nodes.any {
            it.editable && (it.normDescription.contains("senha") || it.normText.contains("senha"))
        }
        return loginMarks || (signup && password)
    }

    // =====================================================================
    // 6) Modo manual: espera o usuário fazer a tarefa
    // =====================================================================

    private fun stepManual(screen: UiScreen, pkg: String) {
        if (isCurtipayPage(screen, pkg)) {
            go(AutomationState.CONFIRMING_TASK, "Voltou para a Curtipay. Confirmando a tarefa...")
            return
        }
        if (isInstagramPackage(pkg)) {
            AutomationManager.notify("Faça a tarefa no Instagram e volte para a Curtipay")
        } else {
            AutomationManager.notify("Aguardando você concluir a tarefa no Instagram...")
        }
        if (since(phaseStartedAt) > AutomationManager.manualWaitMs) {
            AutomationManager.log("Tempo de espera esgotado no modo manual")
            go(AutomationState.LOOKING_FOR_TASK, "Voltando para a lista de tarefas...")
        }
    }

    // =====================================================================
    // 7) Volta para a Curtipay
    // =====================================================================

    private fun stepReturning(screen: UiScreen, pkg: String) {
        if (isCurtipayPage(screen, pkg)) {
            go(AutomationState.CONFIRMING_TASK, "Confirmando a tarefa na Curtipay...")
            return
        }
        if (since(phaseStartedAt) < 900 || !cooldownOk()) return

        if (isInstagramPackage(pkg)) {
            backAttempts++
            if (backAttempts <= 3) {
                back()
            } else {
                backAttempts = 0
                openCurtipayTasks()
            }
            return
        }

        if (isBrowser(screen, pkg) || screen.hasWebView()) {
            backAttempts++
            if (backAttempts <= 3) {
                back()
            } else if (since(lastNavAt) > 6000) {
                backAttempts = 0
                openCurtipayTasks()
            }
            return
        }

        if (isOurPackage(pkg)) {
            AutomationManager.notify("Abra a Curtipay no navegador para continuar (automação em espera)")
            return
        }

        openCurtipayTasks()
        if (since(phaseStartedAt) > 25_000) {
            go(AutomationState.LOOKING_FOR_TASK, "Não consegui voltar para a Curtipay; continuando...")
        }
    }

    // =====================================================================
    // 8) "Concluí a tarefa"
    // =====================================================================

    private fun stepConfirming(screen: UiScreen, pkg: String) {
        if (!isCurtipayPage(screen, pkg)) {
            if (since(phaseStartedAt) > 4000) {
                go(AutomationState.RETURNING, "Voltando para a página da Curtipay...")
            }
            return
        }

        if (isProfileSelection(screen)) {
            go(AutomationState.SELECTING_PROFILE, "Selecionando o perfil do Instagram...")
            return
        }

        val confirmButton = findConfirmButton(screen)
        if (confirmButton != null) {
            if (!confirmButton.enabled) {
                if (screen.containsAny(AWAITING_MARKS)) {
                    returnToList("A Curtipay está validando esta tarefa")
                } else if (since(phaseStartedAt) > 9000) {
                    finishCurrentTask(false, "O botão de confirmar estava desabilitado")
                } else {
                    AutomationManager.notify("Aguardando a Curtipay liberar a confirmação...")
                }
                return
            }
            if (!cooldownOk()) return
            if (click(confirmButton)) {
                act()
                if (since(recentlyFinishedAt) < 6000) {
                    // Já confirmamos esta tarefa agora: não contar duas vezes.
                    returnToList("Esta tarefa já tinha sido confirmada")
                } else {
                    recentlyFinishedAt = now()
                    finishCurrentTask(true, "Tarefa confirmada na Curtipay")
                }
            } else {
                attempts++
                if (attempts > 3) finishCurrentTask(false, "O botão 'Concluí a tarefa' não respondeu")
            }
            return
        }

        if (isTasksList(screen)) {
            returnToList("Voltei para a lista sem encontrar o botão de confirmação")
            return
        }

        if (since(phaseStartedAt) > 7000) {
            debugScreen(screen, "sem botão de confirmação")
            finishCurrentTask(false, "Não encontrei o botão 'Concluí a tarefa'")
        }
    }

    private fun findConfirmButton(screen: UiScreen): UiNode? {
        if (!screen.containsAny(CONFIRM_PAGE_MARKS)) return null
        return screen.firstWhere { node ->
            val n = node.norm
            if (n.isEmpty() || n.length > 60) return@firstWhere false
            if (IGNORED_FOR_CONFIRM.any { n.contains(it) }) return@firstWhere false
            Text.startsWithAny(n, CONFIRM_PREFIXES) && !n.contains("concluid")
        }
    }

    // =====================================================================
    // Fim de tarefa / falhas
    // =====================================================================

    private fun finishCurrentTask(credited: Boolean, reason: String) {
        val key = taskKey.ifEmpty { Text.norm(taskTitle) }
        if (key.isNotEmpty()) finishedTasks.add(key)
        recentlyFinishedAt = now()
        if (credited) AutomationManager.incrementCompleted() else AutomationManager.incrementSkipped()
        AutomationManager.log("$reason — ${taskTitle.ifEmpty { key }}")
        resetTaskProgress()
        go(AutomationState.LOOKING_FOR_TASK, "Tarefa concluída! Procurando a próxima...")
    }

    private fun markCurrentTaskFailed(reason: String) {
        val key = taskKey.ifEmpty { Text.norm(taskTitle) }
        if (key.isNotEmpty()) failedTasks.add(key)
        recentlyFinishedAt = now()
        AutomationManager.incrementSkipped()
        AutomationManager.log("Falha: $reason")
        resetTaskProgress()
        go(AutomationState.LOOKING_FOR_TASK, "Pulando para a próxima tarefa...")
    }

    /** Sai da página da tarefa sem mexer nos contadores. */
    private fun returnToList(reason: String) {
        AutomationManager.log(reason)
        resetTaskProgress()
        go(AutomationState.LOOKING_FOR_TASK, "Voltando para a lista de tarefas...")
    }

    private fun stopWithAlert(message: String) {
        AutomationManager.stopAutomation()
        AutomationManager.notify(message)
        AutomationManager.log(message)
        vibrate()
    }

    // =====================================================================
    // Ações na tela
    // =====================================================================

    private fun click(node: UiNode?): Boolean {
        if (node == null) return false
        try {
            var target: AccessibilityNodeInfo? = node.info
            var level = 0
            while (level < 4) {
                val current = target ?: break
                if (current.isClickable && current.isEnabled &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
                target = current.parent
                level++
            }
        } catch (t: Throwable) {
            AutomationManager.log("Clique direto falhou: ${t.message}")
        }
        return tap(node.centerX, node.centerY)
    }

    private fun tap(x: Float, y: Float): Boolean = try {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    } catch (t: Throwable) {
        false
    }

    private fun doubleTap(x: Float, y: Float): Boolean = try {
        val first = Path().apply { moveTo(x, y) }
        val second = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0, 45))
            .addStroke(GestureDescription.StrokeDescription(second, 130, 45))
            .build()
        dispatchGesture(gesture, null, null)
    } catch (t: Throwable) {
        false
    }

    private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        try {
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            // ignora
        }
    }

    private fun back() {
        lastActionAt = now()
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun keyboardVisible(): Boolean = try {
        val currentWindows = windows
        var visible = false
        currentWindows?.forEach { window ->
            if (visible) return@forEach
            val windowPackage = try {
                window.root?.packageName?.toString().orEmpty()
            } catch (t: Throwable) {
                ""
            }
            if (windowPackage.contains("inputmethod") || windowPackage.contains("gboard") ||
                windowPackage.contains("keyboard") || windowPackage.contains("swiftkey")
            ) {
                visible = true
            }
        }
        visible
    } catch (t: Throwable) {
        false
    }

    private fun openCurtipayTasks(): Boolean {
        if (since(lastNavAt) < 6000) return false
        val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(AutomationManager.CURTIPAY_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (browser in BROWSER_PACKAGES) {
            try {
                startActivity(Intent(baseIntent).setPackage(browser))
                lastNavAt = now()
                AutomationManager.log("Abrindo a Curtipay no navegador $browser")
                return true
            } catch (t: Throwable) {
                // tenta o próximo navegador
            }
        }
        return try {
            startActivity(baseIntent)
            lastNavAt = now()
            AutomationManager.log("Abrindo a Curtipay no navegador padrão")
            true
        } catch (t: Throwable) {
            AutomationManager.log("Não consegui abrir o navegador: ${t.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(500)
            }
        } catch (t: Throwable) {
            // ignora
        }
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    private fun since(timestamp: Long): Long = now() - timestamp

    private fun act() {
        lastActionAt = now()
    }

    private fun cooldownOk(): Boolean = since(lastActionAt) >= AutomationManager.actionCooldownMs

    private fun humanPause(): Long =
        Random.nextLong(AutomationManager.humanPauseMinMs, AutomationManager.humanPauseMaxMs + 1)

    private fun go(state: AutomationState, message: String) {
        if (AutomationManager.currentState.value != state) phaseStartedAt = now()
        AutomationManager.goTo(state, message)
    }

    private fun debugScreen(screen: UiScreen, context: String) {
        if (since(lastDebugLogAt) < 6000) return
        lastDebugLogAt = now()
        AutomationManager.log("[$context] pkg=${screen.packageName} texto=${screen.allText().take(200)}")
    }

    // =====================================================================
    // Detecção de telas
    // =====================================================================

    private fun isCurtipayPage(screen: UiScreen, pkg: String): Boolean {
        if (isInstagramPackage(pkg)) return false
        if (!isBrowser(screen, pkg)) return false
        return screen.containsAny(CURTIPAY_MARKS)
    }

    private fun isTasksList(screen: UiScreen): Boolean =
        screen.containsAny("tarefas", "tarefas de hoje", "minhas tarefas") && !isTaskDetailPage(screen)

    private fun isTaskDetailPage(screen: UiScreen): Boolean {
        if (isProfileSelection(screen)) return false
        if (screen.containsAny("abrir no instagram", "abrir o instagram")) return true
        if (!screen.containsAny("conclui a tarefa")) return false
        // Detalhe da tarefa: poucas menções de tarefa na tela (a lista tem várias).
        val mentions = screen.nodes.count { node ->
            node.norm.length in 6..120 &&
                (Text.looksLikeFollowTask(node.norm) || Text.looksLikeLikeTask(node.norm))
        }
        return mentions in 1..3
    }

    private fun isInstagramPackage(pkg: String): Boolean = pkg.contains("instagram")

    private fun isOurPackage(pkg: String): Boolean = pkg == packageName

    private fun isBrowser(screen: UiScreen, pkg: String): Boolean {
        if (isInstagramPackage(pkg) || isOurPackage(pkg)) return false
        if (BROWSER_PACKAGES.contains(pkg) || screen.hasWebView()) return true
        val name = pkg.lowercase()
        return name.contains("browser") || name.contains("chrome") || name.contains("firefox") ||
            name.contains("opera") || name.contains("webview")
    }

    companion object {

        @Volatile
        var instance: CurtipayAccessibilityService? = null
            private set

        private val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.android.browser",
            "com.google.android.apps.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.focus",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.miui.browser",
            "com.heytap.browser",
            "com.vivo.browser",
            "com.transsion.phoenix",
            "com.ume.browser",
            "com.huawei.browser",
            "com.uc.browser.en",
            "com.ucmobile",
            "com.duckduckgo.mobile.android",
            "com.microsoft.emmx",
            "com.yandex.browser",
            "com.qwant.liberty"
        )

        private val TASK_BUTTON_PREFIXES = listOf(
            "fazer tarefa", "fazer a tarefa", "fazer agora", "iniciar tarefa", "iniciar",
            "comecar tarefa", "comecar", "realizar tarefa", "atender", "ver tarefa",
            "abrir tarefa", "ir para a tarefa", "continuar tarefa", "participar"
        )

        private val CURTIPAY_MARKS = listOf(
            "curtipay", "abrir no instagram", "conclui a tarefa", "selecione o perfil",
            "tarefas de hoje", "saldo disponivel", "minhas tarefas", "perfil do instagram"
        )

        private val PROFILE_SELECTION_MARKS = listOf(
            "selecione o perfil", "selecione um perfil", "escolha o perfil",
            "perfil do instagram", "selecione qual perfil", "qual perfil", "usar este perfil"
        )

        private val CHOOSER_MARKS = listOf(
            "abrir com", "usar o app", "escolha um app", "apenas uma vez", "sempre", "open with"
        )

        private val FINISHED_MARKS = listOf(
            "conclui", "concluid", "feito", "feita", "aguardando", "verificando",
            "validando", "em analise", "aprovad", "finalizad"
        )

        private val IGNORED_FOR_KIND = listOf(
            "abrir", "conclui", "confirmar", "cancelar", "voltar", "instagram.com",
            "suporte", "login", "entrar", "cadastr"
        )

        private val IGNORED_FOR_CONFIRM = listOf(
            "aguardando", "verificando", "validando", "em analise", "desabilitad", "cancelar"
        )

        private val AWAITING_MARKS = listOf(
            "aguardando validacao", "aguardando confirmacao", "aguardando a validacao",
            "em analise", "em verificacao", "verificando a tarefa", "sendo validada"
        )

        private val CONFIRM_PAGE_MARKS = listOf(
            "conclui a tarefa", "concluir a tarefa", "confirmar tarefa", "marcar como concluida",
            "marcar como feita", "ja curti", "ja segui", "ja fiz", "ja realizei",
            "realizei a tarefa", "fiz a tarefa", "tarefa concluida", "finalizar tarefa",
            "abrir no instagram", "abrir o instagram"
        )

        private val FOLLOW_LABELS = listOf("seguir", "follow", "seguir de volta", "follow back")

        private val LIKE_PREFIXES = listOf("curtir", "like")

        private val LIKED_PREFIXES = listOf(
            "descurtir", "unlike", "deixar de curtir", "remover curtida", "curtida removida"
        )

        private val ACTION_BAR_PREFIXES = listOf(
            "salvar", "save", "compartilhar", "share", "comentar", "comment"
        )

        private val CONFIRM_PREFIXES = listOf(
            "conclui a tarefa", "concluir a tarefa", "conclui", "concluir", "confirmar",
            "marcar como concluida", "marcar como feita", "ja curti", "ja segui", "ja fiz",
            "ja realizei", "realizei", "fiz a tarefa", "tarefa concluida", "finalizar"
        )

        private val DISMISS_LABELS = listOf(
            "agora nao", "nao agora", "not now", "mais tarde", "depois", "pular", "skip"
        )

        private val IG_PROMPT_MARKS = listOf(
            "notificacoes", "ativar notificacoes", "salvar informacoes de login",
            "informacoes de login", "ativar localizacao", "sincronizar contatos",
            "adicionar um numero", "encontre amigos"
        )
    }
}
