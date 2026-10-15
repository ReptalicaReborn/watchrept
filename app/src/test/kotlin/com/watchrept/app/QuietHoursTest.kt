package com.watchrept.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    @Test
    fun `window that wraps midnight`() {
        assertTrue(QuietHours.isQuiet(hour = 23, startHour = 22, endHour = 7))
        assertTrue(QuietHours.isQuiet(hour = 0, startHour = 22, endHour = 7))
        assertTrue(QuietHours.isQuiet(hour = 6, startHour = 22, endHour = 7))
        assertFalse(QuietHours.isQuiet(hour = 7, startHour = 22, endHour = 7))
        assertFalse(QuietHours.isQuiet(hour = 12, startHour = 22, endHour = 7))
        assertTrue(QuietHours.isQuiet(hour = 22, startHour = 22, endHour = 7))
    }

    @Test
    fun `same-day window`() {
        assertTrue(QuietHours.isQuiet(hour = 13, startHour = 12, endHour = 14))
        assertFalse(QuietHours.isQuiet(hour = 14, startHour = 12, endHour = 14))
        assertFalse(QuietHours.isQuiet(hour = 11, startHour = 12, endHour = 14))
    }

    @Test
    fun `equal start and end means never quiet`() {
        assertFalse(QuietHours.isQuiet(hour = 5, startHour = 5, endHour = 5))
    }
}
