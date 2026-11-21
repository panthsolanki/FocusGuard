package com.focusguard.blocker.engine

import android.content.Context
import com.focusguard.blocker.data.TargetWithSchedules
import java.util.Calendar

sealed class BlockVerdict {
    object Allowed : BlockVerdict()
    data class Blocked(val appName: String, val reason: String) : BlockVerdict()
}

object RuleEvaluator {
    fun evaluate(context: Context, rule: TargetWithSchedules): BlockVerdict {
        val target = rule.target
        if (!target.isEnabled) return BlockVerdict.Allowed

        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (schedule in rule.schedules) {
            if (ScheduleHelper.isScheduleActiveNow(now, schedule)) {
                val endStr = ScheduleHelper.formatTime12h(schedule.endMinuteOfDay)
                val overnightNote = if (schedule.startMinuteOfDay > schedule.endMinuteOfDay &&
                    currentMinute >= schedule.startMinuteOfDay) {
                    " tomorrow"
                } else ""
                return BlockVerdict.Blocked(
                    appName = target.appName,
                    reason = "Scheduled block active until $endStr$overnightNote"
                )
            }
        }

        val limitMins = target.dailyLimitMinutes
        if (limitMins != null && limitMins > 0) {
            val usedMillis = UsageCalculator.getTodayUsageMillis(context, target.packageName)
            val limitMillis = limitMins * 60_000L
            if (usedMillis >= limitMillis) {
                return BlockVerdict.Blocked(
                    appName = target.appName,
                    reason = "Daily time limit of ${limitMins}m reached"
                )
            }
        }

        return BlockVerdict.Allowed
    }
}
