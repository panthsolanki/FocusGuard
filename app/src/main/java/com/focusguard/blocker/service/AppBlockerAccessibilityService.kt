package com.focusguard.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.focusguard.blocker.data.AppDatabase
import com.focusguard.blocker.engine.BlockVerdict
import com.focusguard.blocker.engine.RuleEvaluator
import com.focusguard.blocker.ui.BlockOverlayActivity
import kotlinx.coroutines.*

class AppBlockerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentForegroundPkg: String? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    companion object {
        var isRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return
            currentForegroundPkg = pkg
            serviceScope.launch { checkAndEnforce(pkg) }
        }
    }

    private suspend fun checkAndEnforce(pkg: String) {
        val dao = AppDatabase.getInstance(applicationContext).blockDao()
        val rule = dao.getActiveRuleForPackage(pkg)
        if (rule == null) {
            hideBlockOverlay()
            return
        }

        val verdict = RuleEvaluator.evaluate(applicationContext, rule)
        if (verdict is BlockVerdict.Blocked) {
            showBlockOverlay(verdict.appName, verdict.reason)
        } else {
            hideBlockOverlay()
        }
    }

    private fun showBlockOverlay(appName: String, reason: String) {
        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post {
                try {
                    if (windowManager == null) {
                        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    }
                    if (overlayView != null) {
                        updateOverlayContent(overlayView!!, appName, reason)
                        return@post
                    }

                    val layoutParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }

                    val view = createOverlayView(appName, reason)
                    windowManager?.addView(view, layoutParams)
                    overlayView = view
                } catch (e: Exception) {
                    launchBlockOverlayActivity(appName, reason)
                }
            }
        } else {
            launchBlockOverlayActivity(appName, reason)
        }
    }

    private fun launchBlockOverlayActivity(appName: String, reason: String) {
        val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("APP_NAME", appName)
            putExtra("REASON", reason)
        }
        startActivity(intent)
    }

    private fun hideBlockOverlay() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (overlayView != null && windowManager != null) {
                    windowManager?.removeView(overlayView)
                    overlayView = null
                }
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    private fun createOverlayView(appName: String, reason: String): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#121212"))
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = "App Blocked"
            setTextColor(android.graphics.Color.parseColor("#FF5252"))
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        val spacer1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 32)
        }
        layout.addView(spacer1)

        val appNameView = TextView(this).apply {
            tag = "appNameView"
            text = appName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layout.addView(appNameView)

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 16)
        }
        layout.addView(spacer2)

        val reasonView = TextView(this).apply {
            tag = "reasonView"
            text = reason
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        layout.addView(reasonView)

        val spacer3 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 48)
        }
        layout.addView(spacer3)

        val returnHomeBtn = Button(this).apply {
            text = "Return to Home Screen"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#3F51B5"))
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                performGlobalAction(GLOBAL_ACTION_HOME)
                hideBlockOverlay()
            }
        }
        layout.addView(returnHomeBtn)

        return layout
    }

    private fun updateOverlayContent(view: View, appName: String, reason: String) {
        view.findViewWithTag<TextView>("appNameView")?.text = appName
        view.findViewWithTag<TextView>("reasonView")?.text = reason
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        hideBlockOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }
}
