package com.focusguard.blocker.engine

import android.content.Context
import com.focusguard.blocker.data.TargetWithSchedules
import java.util.Calendar

data class FocusScoreSummary(
    val score: Int,
    val totalDistractionMinutes: Long,
    val trackedAppsCount: Int,
    val activeSchedulesCount: Int
)

object FocusScoreCalculator {
    fun calculate(context: Context, rules: List<TargetWithSchedules>): FocusScoreSummary {
        val now = Calendar.getInstance()
        var totalDistractionMillis = 0L
        var totalPenalty = 0f
        var activeSchedules = 0
        var trackedWithLimits = 0

        for (rule in rules) {
            if (!rule.target.isEnabled) continue

            val usageMillis = UsageCalculator.getTodayUsageMillis(context, rule.target.packageName)
            totalDistractionMillis += usageMillis

            val isScheduled = rule.schedules.any { ScheduleHelper.isScheduleActiveNow(now, it) }
            if (isScheduled) {
                activeSchedules++
            }

            val limitMins = rule.target.dailyLimitMinutes
            if (limitMins != null && limitMins > 0) {
                trackedWithLimits++
                val usedMins = usageMillis / 60_000f
                val ratio = usedMins / limitMins.toFloat()
                if (ratio >= 1.0f) {
                    totalPenalty += 25f + ((ratio - 1.0f) * 15f).coerceAtMost(25f)
                } else {
                    totalPenalty += ratio * 15f
                }
            }
        }

        val baseScore = if (trackedWithLimits == 0 && activeSchedules == 0) 100 else (100 - totalPenalty.toInt())
        val finalScore = baseScore.coerceIn(0, 100)
        val totalDistractionMins = totalDistractionMillis / 60_000L

        return FocusScoreSummary(
            score = finalScore,
            totalDistractionMinutes = totalDistractionMins,
            trackedAppsCount = rules.size,
            activeSchedulesCount = activeSchedules
        )
    }
}
