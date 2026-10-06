package com.dev.macro4curtipay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "overlay_channel",
                "Macro Curtipay Overlay",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            
            val notification = android.app.Notification.Builder(this, "overlay_channel")
                .setContentTitle("Macro Curtipay Rodando")
                .setContentText("Sobreposição flutuante ativa")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
            startForeground(101, notification)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val inflater = LayoutInflater.from(this)
        // We can inflate or construct a simple layout view
        // For simplicity and robustness without external xml layouts, we construct programmatically or inflate.
        // Let's create floating UI programmatically.
        floatingView = createFloatingView()

        val LAYOUT_FLAG = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            LAYOUT_FLAG,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        try {
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Observe automation state to update overlay UI
        serviceScope.launch {
            launch {
                AutomationManager.isRunning.value
                // update button text
            }
        }
    }

    private fun createFloatingView(): View {
        val context = this
        val view = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xEE222222.toInt())
            setPadding(24, 24, 24, 24)
            elevation = 16f
        }

        val titleView = TextView(context).apply {
            text = "Macro Curtipay"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        view.addView(titleView)

        val statusView = TextView(context).apply {
            text = "Parado"
            setTextColor(0xFF00FF00.toInt())
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        view.addView(statusView)

        val statsView = TextView(context).apply {
            text = "Feitas: 0 | Puladas: 0"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 12f
        }
        view.addView(statsView)

        val toggleButton = Button(context).apply {
            text = "Ligar / Desligar"
            textSize = 12f
            setOnClickListener {
                if (AutomationManager.isRunning.value) {
                    AutomationManager.stopAutomation()
                } else {
                    AutomationManager.startAutomation()
                }
            }
        }
        view.addView(toggleButton)

        // Observe changes to update views live
        serviceScope.launch {
            launch {
                AutomationManager.statusMessage.collect { msg ->
                    statusView.text = msg
                }
            }
            launch {
                kotlinx.coroutines.flow.combine(
                    AutomationManager.completedCount,
                    AutomationManager.skippedCount
                ) { done, skipped ->
                    "Feitas: $done | Puladas: $skipped"
                }.collect { text ->
                    statsView.text = text
                }
            }
            launch {
                AutomationManager.isRunning.collect { running ->
                    toggleButton.text = if (running) "PARAR" else "INICIAR"
                    toggleButton.setBackgroundColor(if (running) 0xFFFF5555.toInt() else 0xFF55FF55.toInt())
                }
            }
        }

        // Make window draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val params = WindowManager.LayoutParams() // Will use layout params during touch if needed

        view.setOnTouchListener { _, event ->
            val wm = windowManager ?: return@setOnTouchListener false
            val layoutParams = view.layoutParams as WindowManager.LayoutParams
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
                    wm.updateViewLayout(view, layoutParams)
                    true
                }
                else -> false
            }
        }

        return view
    }



    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
