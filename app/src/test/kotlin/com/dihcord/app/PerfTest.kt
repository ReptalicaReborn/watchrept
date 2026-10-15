package com.dihcord.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PerfTest {
    private val gb = 1_073_741_824L

    @Test
    fun `cpu range parsing`() {
        assertEquals(5, Perf.parseCpuRange("0-4\n"))
        assertEquals(1, Perf.parseCpuRange("0"))
        assertEquals(6, Perf.parseCpuRange("0-3,6-7"))
        assertNull(Perf.parseCpuRange(""))
        assertNull(Perf.parseCpuRange("garbage"))
        assertNull(Perf.parseCpuRange("4-1"))
    }

    @Test
    fun `fast 2 GB watch with the low-ram flag stays out of lite mode`() {
        // Galaxy Watch7-class: 5 cores, ~1.7 GB usable, and Wear OS sets the low-RAM flag.
        assertNull(Perf.weaknessReason(Perf.Hardware(cores = 5, totalMemBytes = (1.7 * gb).toLong(), lowRamFlag = true)))
    }

    @Test
    fun `dual-core watches get lite mode`() {
        assertNotNull(Perf.weaknessReason(Perf.Hardware(cores = 2, totalMemBytes = 2 * gb, lowRamFlag = false)))
    }

    @Test
    fun `very little memory gets lite mode`() {
        assertNotNull(Perf.weaknessReason(Perf.Hardware(cores = 4, totalMemBytes = gb, lowRamFlag = false)))
    }
}
