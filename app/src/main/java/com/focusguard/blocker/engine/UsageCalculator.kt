package com.focusguard.blocker.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object UsageCalculator {
    fun getTodayUsageMillis(context: Context, targetPackage: String): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val events = usm.queryEvents(startOfDay, now)
        val event = UsageEvents.Event()
        var totalTime = 0L
        var lastResumeTime: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != targetPackage) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (lastResumeTime == null) lastResumeTime = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    lastResumeTime?.let { start ->
                        totalTime += (event.timeStamp - start).coerceAtLeast(0L)
                        lastResumeTime = null
                    }
                }
            }
        }
        lastResumeTime?.let { start ->
            totalTime += (now - start).coerceAtLeast(0L)
        }
        return totalTime
    }
}
