package com.devicemanager.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CpuStatParserTest {
    @Test fun parsesAggregateCpuCountersAndIncludesIoWaitAsIdle() {
        val counter = CpuStatParser.parse("cpu 100 0 50 850 20 0 0 0 7 2")
        assertNotNull(counter)
        assertEquals(1020L, counter!!.total)
        assertEquals(870L, counter.idle)
    }

    @Test fun calculatesBusyPercentageFromTwoSamples() {
        val before = CpuStatParser.parse("cpu 100 0 50 850 20 0 0 0")!!
        val after = CpuStatParser.parse("cpu 200 0 100 1700 40 0 0 0")!!
        assertEquals(14.7059f, CpuStatParser.percentage(before, after)!!, .001f)
    }

    @Test fun rejectsMalformedRowsAndInvalidDeltas() {
        assertNull(CpuStatParser.parse("cpu0 10 20 30 40"))
        val before = CpuStatCounter(100, 80)
        assertNull(CpuStatParser.percentage(before, CpuStatCounter(90, 75)))
    }
}
