package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMapBlankTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun theIphonesFlatFramesAreBlank() {
        assertTrue(looksBlank(IntArray(128) { rgb(0x1A, 0x1A, 0x1A) }, 8))
        val noisy = IntArray(128) { if (it % 7 == 0) rgb(0x1E, 0x1B, 0x19) else rgb(0x1A, 0x1A, 0x1A) }
        assertTrue("compression noise", looksBlank(noisy, 8))
        assertTrue(looksBlank(IntArray(128) { rgb(0x0D, 0x0D, 0x0D) }, 8))
    }

    @Test fun aMapIsNotBlank() {
        val map = IntArray(128) { rgb(0x2A, 0x30, 0x3C) }
        map[40] = rgb(0x3C, 0x9A, 0x50)
        assertFalse("one road is enough", looksBlank(map, 8))
        assertFalse(looksBlank(IntArray(128) { rgb(0x1A, 0x1A, 0x1A + (it % 2) * 9) }, 8))
    }

    @Test fun noPixelsSayNothing() {
        assertFalse(looksBlank(IntArray(0), 8))
    }
}
