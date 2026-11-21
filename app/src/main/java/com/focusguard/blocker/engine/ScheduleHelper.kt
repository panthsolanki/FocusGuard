package com.focusguard.blocker.engine

import com.focusguard.blocker.data.BlockSchedule
import java.util.Calendar

object ScheduleHelper {

    const val MASK_MONDAY = 1
    const val MASK_TUESDAY = 2
    const val MASK_WEDNESDAY = 4
    const val MASK_THURSDAY = 8
    const val MASK_FRIDAY = 16
    const val MASK_SATURDAY = 32
    const val MASK_SUNDAY = 64

    const val MASK_WEEKDAYS = 31 // Mon-Fri
    const val MASK_WEEKENDS = 96 // Sat-Sun
    const val MASK_ALL_DAYS = 127 // Everyday

    fun calendarDayToBit(calDay: Int): Int = when (calDay) {
        Calendar.MONDAY -> MASK_MONDAY
        Calendar.TUESDAY -> MASK_TUESDAY
        Calendar.WEDNESDAY -> MASK_WEDNESDAY
        Calendar.THURSDAY -> MASK_THURSDAY
        Calendar.FRIDAY -> MASK_FRIDAY
        Calendar.SATURDAY -> MASK_SATURDAY
        Calendar.SUNDAY -> MASK_SUNDAY
        else -> 0
    }

    fun parseTimeToMinutes(hour12: Int, minute: Int, isPm: Boolean): Int {
        val safeHour12 = when {
            hour12 < 1 -> 12
            hour12 > 12 -> 12
            else -> hour12
        }
        val safeMinute = minute.coerceIn(0, 59)
        val h = when {
            safeHour12 == 12 && !isPm -> 0
            safeHour12 == 12 && isPm -> 12
            isPm -> (safeHour12 % 12) + 12
            else -> safeHour12 % 12
        }
        return (h * 60 + safeMinute).coerceIn(0, 1439)
    }

    fun splitMinutesTo12h(totalMinutes: Int): Triple<Int, Int, Boolean> {
        val mins = ((totalMinutes % 1440) + 1440) % 1440
        val hour24 = mins / 60
        val minute = mins % 60
        val isPm = hour24 >= 12
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        return Triple(hour12, minute, isPm)
    }

    fun formatTime12h(totalMinutes: Int): String {
        val (h, m, isPm) = splitMinutesTo12h(totalMinutes)
        val amPm = if (isPm) "PM" else "AM"
        return String.format("%d:%02d %s", h, m, amPm)
    }

    fun formatDaysMask(mask: Int): String = when (mask) {
        MASK_ALL_DAYS -> "Everyday"
        MASK_WEEKDAYS -> "Weekdays (Mon-Fri)"
        MASK_WEEKENDS -> "Weekends (Sat-Sun)"
        else -> {
            val days = mutableListOf<String>()
            if (mask and MASK_MONDAY != 0) days.add("Mon")
            if (mask and MASK_TUESDAY != 0) days.add("Tue")
            if (mask and MASK_WEDNESDAY != 0) days.add("Wed")
            if (mask and MASK_THURSDAY != 0) days.add("Thu")
            if (mask and MASK_FRIDAY != 0) days.add("Fri")
            if (mask and MASK_SATURDAY != 0) days.add("Sat")
            if (mask and MASK_SUNDAY != 0) days.add("Sun")
            if (days.isEmpty()) "No days" else days.joinToString(", ")
        }
    }

    fun formatScheduleSummary(schedule: BlockSchedule): String {
        val daysStr = formatDaysMask(schedule.daysOfWeekMask)
        val startStr = formatTime12h(schedule.startMinuteOfDay)
        val endStr = formatTime12h(schedule.endMinuteOfDay)
        val overnight = if (schedule.startMinuteOfDay > schedule.endMinuteOfDay) " (Overnight)" else ""
        return "$daysStr • $startStr - $endStr$overnight"
    }

    fun isScheduleActiveNow(cal: Calendar, schedule: BlockSchedule): Boolean {
        if (schedule.daysOfWeekMask == 0) return false

        val currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val todayBit = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
        val start = schedule.startMinuteOfDay
        val end = schedule.endMinuteOfDay

        return if (start == end) {
            // Full 24-hour block for selected days
            (schedule.daysOfWeekMask and todayBit) != 0
        } else if (start < end) {
            // Standard intraday window (e.g. 9:00 AM to 5:00 PM)
            (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute in start until end
        } else {
            // Overnight window (e.g. 7:00 PM to 6:00 PM or 7:00 PM to 6:00 AM)
            val yesterdayCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayBit = calendarDayToBit(yesterdayCal.get(Calendar.DAY_OF_WEEK))

            // Window started earlier today during evening
            val activeFromTonight = (schedule.daysOfWeekMask and todayBit) != 0 && currentMinute >= start
            // Window started yesterday evening and extends into today before end time
            val activeFromYesterday = (schedule.daysOfWeekMask and yesterdayBit) != 0 && currentMinute < end

            activeFromTonight || activeFromYesterday
        }
    }
}
