package com.focusguard.blocker.engine

import android.content.Context
import com.focusguard.blocker.data.BlockSchedule
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
        for (schedule in rule.schedules) {
            if (isScheduleActiveNow(now, schedule)) {
                val endStr = formatMinutes(schedule.endMinuteOfDay)
                return BlockVerdict.Blocked(
                    appName = target.appName,
                    reason = "Scheduled block active until $endStr"
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

    private fun isScheduleActiveNow(cal: Calendar, schedule: BlockSchedule): Boolean {
        val currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayBit = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay

        return if (start <= end) {
            (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute in start until end
        } else {
            val yesterdayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayBit = calendarDayToBit(yesterdayCal.get(Calendar.DAY_OF_WEEK))

            val activeLateTonight = (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute >= start
            val activeEarlyMorning = (schedule.daysOfWeekMask and yesterdayBit) != 0 && currentMinute < end
            activeLateTonight || activeEarlyMorning
        }
    }

    private fun calendarDayToBit(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 4
        Calendar.THURSDAY -> 8
        Calendar.FRIDAY -> 16
        Calendar.SATURDAY -> 32
        Calendar.SUNDAY -> 64
        else -> 0
    }

    private fun formatMinutes(totalMins: Int): String =
        String.format("%02d:%02d", totalMins / 60, totalMins % 60)
}
