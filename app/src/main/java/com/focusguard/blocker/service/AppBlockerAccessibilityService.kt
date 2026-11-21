package com.focusguard.blocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.focusguard.blocker.data.AppDatabase
import com.focusguard.blocker.engine.BlockVerdict
import com.focusguard.blocker.engine.RuleEvaluator
import com.focusguard.blocker.ui.BlockOverlayActivity
import kotlinx.coroutines.*

class AppBlockerAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentForegroundPkg: String? = null
    private var monitorJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        monitorJob = serviceScope.launch {
            while (isActive) {
                currentForegroundPkg?.let { checkAndEnforce(it) }
                delay(3_000L)
            }
        }
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
        val rule = dao.getActiveRuleForPackage(pkg) ?: return
        val verdict = RuleEvaluator.evaluate(applicationContext, rule)

        if (verdict is BlockVerdict.Blocked) {
            val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("APP_NAME", verdict.appName)
                putExtra("REASON", verdict.reason)
            }
            startActivity(intent)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
