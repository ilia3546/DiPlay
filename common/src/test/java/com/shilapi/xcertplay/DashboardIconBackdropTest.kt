package com.shilapi.xcertplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DashboardIconBackdropTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun aPlainPictureStaysAsItIs() {
        val colour = argb(255, 40, 120, 200)
        val pixels = IntArray(7 * 5) { colour }
        boxBlur(pixels, 7, 5, radius = 2, passes = 3, scratch = IntArray(pixels.size))
        assertArrayEquals(IntArray(7 * 5) { colour }, pixels)
    }

    @Test fun aDotSpreadsEvenlyInEveryDirection() {
        val size = 21
        val pixels = IntArray(size * size) { argb(255, 0, 0, 0) }
        pixels[10 * size + 10] = argb(255, 255, 255, 255)
        boxBlur(pixels, size, size, radius = 2, passes = 3, scratch = IntArray(pixels.size))
        fun red(x: Int, y: Int) = pixels[y * size + x] shr 16 and 0xFF
        assertTrue(red(10, 10) in 1..254)
        for (d in 1..6) {
            assertEquals(red(10 - d, 10), red(10 + d, 10))
            assertEquals(red(10, 10 - d), red(10, 10 + d))
            assertTrue("rounded once more across than down", abs(red(10 + d, 10) - red(10, 10 + d)) <= 1)
            assertTrue(red(10 + d, 10) <= red(10 + d - 1, 10))
        }
        assertEquals("three passes of 2 px reach 6 px", 0, red(10 + 7, 10))
        assertTrue((0 until size * size).all { pixels[it] ushr 24 == 255 })
    }

    @Test fun theEdgesRepeatOutward() {
        val pixels = IntArray(6) { if (it < 3) argb(255, 200, 0, 0) else argb(0, 0, 0, 0) }
        boxBlur(pixels, 6, 1, radius = 1, passes = 1, scratch = IntArray(6))
        assertEquals(argb(255, 200, 0, 0), pixels[0])
        assertEquals(argb(0, 0, 0, 0), pixels[5])
        assertEquals(argb(170, 133, 0, 0), pixels[2])
    }
}
