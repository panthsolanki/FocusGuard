package com.focusguard.blocker.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.focusguard.blocker.R
import com.focusguard.blocker.data.AppDatabase
import com.focusguard.blocker.engine.BlockVerdict
import com.focusguard.blocker.engine.RuleEvaluator
import com.focusguard.blocker.ui.BlockOverlayActivity
import com.focusguard.blocker.ui.MainActivity
import kotlinx.coroutines.*

class AppBlockerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var currentForegroundPkg: String? = null
    private var lastBlockedPkg: String? = null

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    companion object {
        const val CHANNEL_ID = "focusguard_monitor_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AppBlockerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppBlockerService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startInForeground()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startInForeground()
        if (monitorJob == null || monitorJob?.isActive != true) {
            startMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusGuard Active")
            .setContentText("Monitoring focus rules and app limits")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val pkg = updateForegroundApp()
                    if (pkg != null && pkg != packageName && pkg != "com.android.systemui") {
                        if (isLauncherPackage(pkg)) {
                            hideBlockOverlay()
                        } else {
                            checkAndEnforce(pkg)
                        }
                    } else if (pkg == "com.android.systemui" || pkg == null) {
                        hideBlockOverlay()
                    }
                } catch (e: Exception) {
                    // Prevent unhandled exceptions from terminating loop
                }
                delay(500L)
            }
        }
    }

    private fun updateForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return currentForegroundPkg
        val now = System.currentTimeMillis()

        // Query sliding window of last 15 seconds to ensure batched system events are never missed
        val events = usm.queryEvents(now - 15_000L, now)

        val event = UsageEvents.Event()
        val targetResumeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        var latestEventTime = 0L
        var latestPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                targetResumeType -> {
                    if (event.timeStamp >= latestEventTime) {
                        latestEventTime = event.timeStamp
                        latestPackage = event.packageName
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> {
                    if (event.timeStamp >= latestEventTime) {
                        latestEventTime = event.timeStamp
                        latestPackage = null
                    }
                }
            }
        }

        if (latestPackage != null) {
            currentForegroundPkg = latestPackage
        } else if (currentForegroundPkg == null) {
            // Secondary fallback query
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
            val top = stats.maxByOrNull { it.lastTimeUsed }
            if (top != null && (now - top.lastTimeUsed) < 15_000L) {
                currentForegroundPkg = top.packageName
            }
        }

        return currentForegroundPkg
    }

    private suspend fun checkAndEnforce(pkg: String) {
        val dao = AppDatabase.getInstance(applicationContext).blockDao()
        val rule = dao.getActiveRuleForPackage(pkg)
        if (rule == null) {
            if (lastBlockedPkg == pkg) {
                hideBlockOverlay()
                lastBlockedPkg = null
            }
            return
        }

        val verdict = RuleEvaluator.evaluate(applicationContext, rule)
        if (verdict is BlockVerdict.Blocked) {
            lastBlockedPkg = pkg
            showBlockOverlay(verdict.appName, verdict.reason)
        } else {
            if (lastBlockedPkg == pkg) {
                hideBlockOverlay()
                lastBlockedPkg = null
            }
        }
    }

    private fun showBlockOverlay(appName: String, reason: String) {
        if (!Settings.canDrawOverlays(this)) return

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                val view = createOverlayView(appName, reason)
                windowManager?.addView(view, layoutParams)
                overlayView = view
            } catch (e: Exception) {
                // Secondary fallback: launch activity via PendingIntent
                launchOverlayActivity(appName, reason)
            }
        }
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
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = "App Blocked"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
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
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
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
            setTextColor(Color.LTGRAY)
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
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3F51B5"))
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
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

    private fun launchOverlayActivity(appName: String, reason: String) {
        try {
            val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("APP_NAME", appName)
                putExtra("REASON", reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                val pi = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    options.toBundle()
                )
                pi.send(options.toBundle())
            } else {
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun isLauncherPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val defaultHome = resolveInfo?.activityInfo?.packageName
        return pkg == defaultHome ||
            pkg.contains("launcher", ignoreCase = true) ||
            pkg == "com.google.android.apps.nexuslauncher" ||
            pkg == "com.sec.android.app.launcher" ||
            pkg == "com.miui.home" ||
            pkg == "net.oneplus.launcher"
    }

    override fun onDestroy() {
        isRunning = false
        hideBlockOverlay()
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
