package com.devicemanager.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayStatusParserTest {
    @Test fun parsesNativeValuesWithoutOverrides() {
        val status = DisplayStatusParser.parse("Physical size: 720x1600\nPhysical density: 320\n")
        assertEquals(720, status.physicalWidth)
        assertEquals(1600, status.activeHeight)
        assertEquals(320, status.activeDensity)
        assertFalse(status.hasSizeOverride)
        assertFalse(status.hasDensityOverride)
    }

    @Test fun keepsPhysicalBaselineSeparateFromCurrentOverrides() {
        val status = DisplayStatusParser.parse(
            "Physical size: 720x1600\nOverride size: 576x1280\n" +
                "Physical density: 320\nOverride density: 256\n"
        )
        assertEquals(720, status.physicalWidth)
        assertEquals(576, status.activeWidth)
        assertEquals(320, status.physicalDensity)
        assertEquals(256, status.activeDensity)
        assertTrue(status.hasSizeOverride)
        assertTrue(status.hasDensityOverride)
    }

    @Test fun scaleUsesSameFactorForResolutionAndDensity() {
        val target = DisplayScale.target(720, 1560, 500, .8f)
        assertEquals(576, target.width)
        assertEquals(1248, target.height)
        assertEquals(400, target.density)
    }

    @Test fun scaleRoundingIsConsistent() {
        val target = DisplayScale.target(720, 1560, 500, .67f)
        assertEquals(482, target.width)
        assertEquals(1045, target.height)
        assertEquals(335, target.density)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingPhysicalBaseline() {
        DisplayStatusParser.parse("Override size: 500x1000\nPhysical density: 320")
    }
}
