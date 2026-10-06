package com.dev.macro4curtipay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

/**
 * Comparação de textos de interface: ignora maiúsculas/minúsculas e acentos,
 * para funcionar igual em "Concluí a tarefa", "Conclui a tarefa" e "CONCLUI A TAREFA".
 */
object Text {

    private val ACCENT_MARKS = Regex("\\p{Mn}+")
    private val COIN_VALUE = Regex("\\+\\s*\\d+")
    private val FOLLOW_TASK = Regex("\\b(seguir|seguidores?|follow)\\b")
    private val LIKE_TASK = Regex("\\b(curtir|curtidas?|likes?)\\b|curta (a )?publicac")
    private val HANDLE = Regex("@[a-z0-9._]{2,40}")
    private val INSTAGRAM_URL = Regex("(https?://)?(www\\.)?instagram\\.com(/[^\\s\"'<>]*)?")
    private val POST_URL = Regex("instagram\\.com/(p|reel|reels|tv)/[a-z0-9_-]+")

    fun norm(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.replace(ACCENT_MARKS, "").lowercase().trim()
    }

    fun startsWithAny(value: String, prefixes: Collection<String>): Boolean =
        prefixes.any { value.startsWith(it) }

    /**
     * Compara por "palavra inicial", evitando falsos positivos:
     * "curtir" casa com "curtir", "curtir foto de X" e "curtir. 12 curtidas",
     * mas NÃO casa com "curtida" nem "curtidas".
     */
    fun startsWithWord(value: String, prefixes: Collection<String>): Boolean =
        prefixes.any { prefix ->
            value == prefix || value.startsWith("$prefix ") ||
                value.startsWith("$prefix.") || value.startsWith("$prefix,")
        }

    /** Texto que indica tarefa de SEGUIR. */
    fun looksLikeFollowTask(value: String): Boolean = FOLLOW_TASK.containsMatchIn(norm(value))

    /** Texto que indica tarefa de CURTIR. */
    fun looksLikeLikeTask(value: String): Boolean = LIKE_TASK.containsMatchIn(norm(value))

    /** Texto com recompensa em moedas (ex.: "+20"), típico dos cartões de tarefa. */
    fun hasCoinValue(value: String): Boolean = COIN_VALUE.containsMatchIn(value)

    fun firstHandle(value: String): String {
        val found = HANDLE.find(norm(value))?.value ?: return ""
        return found
    }

    fun firstInstagramUrl(value: String): String {
        val match = INSTAGRAM_URL.find(value)?.value ?: return ""
        return match.trim().trimEnd('.', ',', ')', '"', '\'')
    }

    fun firstPostUrl(value: String): String {
        val match = POST_URL.find(norm(value))?.value ?: return ""
        return match
    }
}

/** Cópia imutável dos dados de um nó da árvore de acessibilidade. */
class UiNode(val info: AccessibilityNodeInfo) {

    val text: String = info.text?.toString()?.trim().orEmpty()
    val description: String = info.contentDescription?.toString()?.trim().orEmpty()
    val className: String = info.className?.toString().orEmpty()
    val viewId: String = info.viewIdResourceName.orEmpty()
    val clickable: Boolean = info.isClickable
    val enabled: Boolean = info.isEnabled
    val editable: Boolean = info.isEditable
    val focused: Boolean = info.isFocused
    val checked: Boolean = info.isChecked
    val checkable: Boolean = info.isCheckable
    val scrollable: Boolean = info.isScrollable
    val label: String = if (text.isNotEmpty()) text else description

    val norm: String = Text.norm(label)
    val normText: String = Text.norm(text)
    val normDescription: String = Text.norm(description)

    private val rect: Rect = Rect().also { info.getBoundsInScreen(it) }

    val left: Int get() = rect.left
    val top: Int get() = rect.top
    val right: Int get() = rect.right
    val bottom: Int get() = rect.bottom
    val width: Int get() = rect.right - rect.left
    val height: Int get() = rect.bottom - rect.top
    val centerX: Float get() = (rect.left + rect.right) / 2f
    val centerY: Float get() = (rect.top + rect.bottom) / 2f
    val visible: Boolean get() = rect.right > rect.left && rect.bottom > rect.top

    fun hasClickableAncestor(maxLevels: Int = 4): Boolean {
        var parent: AccessibilityNodeInfo? = try {
            info.parent
        } catch (t: Throwable) {
            null
        }
        var level = 0
        while (level < maxLevels) {
            val current = parent ?: break
            if (current.isClickable) return true
            parent = try {
                current.parent
            } catch (t: Throwable) {
                null
            }
            level++
        }
        return false
    }

    /** Texto de todo o bloco (usado para ler os dados do cartão de tarefa). */
    fun subtreeLabels(maxNodes: Int = 80): List<String> {
        val out = ArrayList<String>(16)
        collectLabels(info, out, 0, maxNodes)
        return out
    }

    override fun toString(): String =
        "UiNode(class=$className, text='$text', desc='$description', id='$viewId', rect=[$left,$top,$right,$bottom])"

    private fun collectLabels(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
        limit: Int
    ) {
        if (out.size >= limit || depth > 12) return
        val label = node.text?.toString()?.trim().orEmpty()
        if (label.isNotEmpty()) out.add(label)
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (description.isNotEmpty()) out.add(description)
        val childCount = try {
            node.childCount
        } catch (t: Throwable) {
            0
        }
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            collectLabels(child, out, depth + 1, limit)
            if (out.size >= limit) return
        }
    }
}

/** Instantâneo (snapshot) da tela atual: pacote + lista de nós em ordem de leitura. */
class UiScreen(
    val root: AccessibilityNodeInfo?,
    val packageName: String,
    val nodes: List<UiNode>
) {

    private val labels: List<String> = nodes.map { it.norm }.filter { it.isNotEmpty() }
    private val joinedLabels: String = labels.joinToString("\n")

    val hasContent: Boolean get() = nodes.isNotEmpty()

    fun hasWebView(): Boolean = nodes.any { it.className.contains("WebView") }

    fun containsAny(vararg needles: String): Boolean =
        needles.any { needle -> joinedLabels.contains(Text.norm(needle)) }

    fun containsAny(needles: Collection<String>): Boolean =
        needles.any { needle -> joinedLabels.contains(Text.norm(needle)) }

    fun allText(): String = nodes.map { it.label }.filter { it.isNotEmpty() }.joinToString(" | ")

    fun firstWhere(predicate: (UiNode) -> Boolean): UiNode? = nodes.firstOrNull(predicate)

    fun allWhere(predicate: (UiNode) -> Boolean): List<UiNode> = nodes.filter(predicate)

    /** Primeiro nó (em ordem de leitura) cujo texto/descrição contém a agulha. */
    fun firstLabelContaining(needle: String): UiNode? {
        val target = Text.norm(needle)
        if (target.isEmpty()) return null
        return nodes.firstOrNull { it.norm.isNotEmpty() && it.norm.contains(target) }
    }

    fun firstLabelStartingWith(prefixes: Collection<String>): UiNode? =
        nodes.firstOrNull { it.norm.isNotEmpty() && Text.startsWithAny(it.norm, prefixes) }

    fun firstClickableLabelContaining(needle: String): UiNode? {
        val target = Text.norm(needle)
        if (target.isEmpty()) return null
        return nodes.firstOrNull {
            it.norm.isNotEmpty() && it.norm.contains(target) && (it.clickable || it.hasClickableAncestor())
        }
    }

    /** Procura uma URL do Instagram em qualquer texto da tela. */
    fun findInstagramUrl(): String {
        for (node in nodes) {
            val url = Text.firstInstagramUrl(node.label)
            if (url.isNotEmpty()) return url
        }
        return ""
    }

    /** Procura um link de publicação (usado quando a Curtipay manda abrir o post). */
    fun findPostUrl(): String {
        for (node in nodes) {
            val url = Text.firstPostUrl(node.label)
            if (url.isNotEmpty()) return url
        }
        return ""
    }
}

/** Lê a janela em primeiro plano (ignorando a sobreposição do próprio app). */
fun readUi(service: AccessibilityService, ownPackage: String): UiScreen {
    val active = try {
        service.rootInActiveWindow
    } catch (t: Throwable) {
        null
    }

    var root: AccessibilityNodeInfo? = null
    var packageName = ""
    if (active != null) {
        val activePackage = active.packageName?.toString().orEmpty()
        if (activePackage.isNotEmpty() && activePackage != ownPackage) {
            root = active
            packageName = activePackage
        }
    }

    if (root == null) {
        val windows = try {
            service.windows
        } catch (t: Throwable) {
            null
        }
        // Prefere a janela ativa; senão usa a última janela que não é a do próprio app.
        windows?.forEach { window ->
            val windowRoot = try {
                window.root
            } catch (t: Throwable) {
                null
            } ?: return@forEach
            val windowPackage = windowRoot.packageName?.toString().orEmpty()
            if (windowPackage == ownPackage) return@forEach
            if (window.isActive) {
                root = windowRoot
                packageName = windowPackage
                return@forEach
            }
            if (root == null) {
                root = windowRoot
                packageName = windowPackage
            }
        }
    }

    if (root == null) return UiScreen(null, "", emptyList())

    val collected = ArrayList<UiNode>(256)
    collectNodes(root, collected, 0, 3000)
    return UiScreen(root, packageName, collected)
}

private fun collectNodes(
    node: AccessibilityNodeInfo,
    out: MutableList<UiNode>,
    depth: Int,
    limit: Int
) {
    if (out.size >= limit || depth > 40) return
    out.add(UiNode(node))
    val childCount = try {
        node.childCount
    } catch (t: Throwable) {
        0
    }
    for (i in 0 until childCount) {
        val child = try {
            node.getChild(i)
        } catch (t: Throwable) {
            null
        } ?: continue
        collectNodes(child, out, depth + 1, limit)
        if (out.size >= limit) return
    }
}
