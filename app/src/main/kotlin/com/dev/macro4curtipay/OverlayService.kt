package com.dev.macro4curtipay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Botão flutuante para ligar/desligar a automação e ver o status sem sair do navegador. */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (floatingView != null) return
        floatingView = createFloatingView()
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
        }
        try {
            windowManager?.addView(floatingView, params)
        } catch (t: Throwable) {
            AutomationManager.log("Não consegui mostrar a sobreposição: ${t.message}")
        }
    }

    private fun startForegroundNotification() {
        try {
            val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Macro Curtipay",
                    NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Macro Curtipay ativo")
                    .setContentText("Botão flutuante de automação ligado")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Macro Curtipay ativo")
                    .setContentText("Botão flutuante de automação ligado")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .build()
            }
            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            AutomationManager.log("Falha ao iniciar em primeiro plano: ${t.message}")
        }
    }

    private fun createFloatingView(): View {
        val context = this
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE1B1B1B.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = 12f
        }

        val titleView = TextView(context).apply {
            text = "Macro Curtipay"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(titleView)

        val statusView = TextView(context).apply {
            text = AutomationManager.statusMessage.value
            setTextColor(0xFF7CFC7C.toInt())
            textSize = 11f
            maxWidth = dp(230)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(statusView)

        val taskView = TextView(context).apply {
            text = ""
            setTextColor(0xFFFFE082.toInt())
            textSize = 11f
            maxWidth = dp(230)
        }
        root.addView(taskView)

        val statsView = TextView(context).apply {
            text = "Feitas: 0 | Puladas: 0"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(statsView)

        val toggleButton = Button(context).apply {
            text = "INICIAR"
            textSize = 12f
            setOnClickListener {
                if (AutomationManager.isRunning.value) {
                    AutomationManager.stopAutomation()
                } else {
                    AutomationManager.startAutomation()
                }
            }
        }
        root.addView(toggleButton)

        serviceScope.launch {
            launch {
                AutomationManager.statusMessage.collect { message -> statusView.text = message }
            }
            launch {
                combine(
                    AutomationManager.completedCount,
                    AutomationManager.skippedCount
                ) { done, skipped -> "Feitas: $done | Puladas: $skipped" }
                    .collect { text -> statsView.text = text }
            }
            launch {
                combine(
                    AutomationManager.currentTaskKind,
                    AutomationManager.currentTaskTitle
                ) { kind, title ->
                    when {
                        kind == TaskKind.UNKNOWN -> ""
                        title.isEmpty() -> kind.label
                        else -> "${kind.label}: $title"
                    }
                }.collect { text -> taskView.text = text }
            }
            launch {
                AutomationManager.isRunning.collect { running ->
                    toggleButton.text = if (running) "PARAR" else "INICIAR"
                    toggleButton.setBackgroundColor(if (running) 0xFFFF5555.toInt() else 0xFF55FF55.toInt())
                }
            }
        }

        // Arrasta a janelinha pela tela.
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        root.setOnTouchListener { _, event ->
            val manager = windowManager ?: return@setOnTouchListener false
            val layoutParams = root.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    manager.updateViewLayout(root, layoutParams)
                    true
                }
                else -> false
            }
        }

        return root
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (t: Throwable) {
                // ignora
            }
        }
        floatingView = null
    }

    companion object {
        private const val CHANNEL_ID = "curtipay_overlay"
        private const val NOTIFICATION_ID = 101
    }
}
