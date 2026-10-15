package com.watchrept.app

object QuietHours {
    /** True if [hour] (0-23) falls in the window [startHour, endHour), which may wrap past midnight. */
    fun isQuiet(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }
}
