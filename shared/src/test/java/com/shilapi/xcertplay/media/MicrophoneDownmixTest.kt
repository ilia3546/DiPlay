package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneDownmixTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun samples(bytes: ByteArray, count: Int) = IntArray(count / 2) { index ->
        (bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)
    }

    private fun mix(downmix: MicrophoneDownmix, input: ByteArray): IntArray {
        val output = ByteArray(downmix.outputCapacity(input.size))
        return samples(output, downmix.mix(input, input.size, output))
    }

    @Test fun silentChannelsAreLeftOutOfTheMix() {
        val downmix = MicrophoneDownmix(4)
        // Only channel 1 has ever carried sound, so the talker on it keeps its level.
        assertArrayEquals(intArrayOf(1000, -500), mix(downmix, pcm(0, 1000, 0, 0, 0, -500, 0, 0)))
        assertArrayEquals(intArrayOf(0, 1000, 0, 0), downmix.peaks)
    }

    @Test fun liveChannelsAreSummedAndScaledByTheSquareRootOfTheirCount() {
        val downmix = MicrophoneDownmix(2)
        assertArrayEquals(intArrayOf(Math.round(3000 / Math.sqrt(2.0)).toInt()), mix(downmix, pcm(1000, 2000)))
        // A channel stays live after it falls silent.
        assertArrayEquals(intArrayOf(Math.round(1000 / Math.sqrt(2.0)).toInt()), mix(downmix, pcm(1000, 0)))
    }

    @Test fun loudChannelsAreClippedInsteadOfWrappingAround() {
        val downmix = MicrophoneDownmix(4)
        assertArrayEquals(intArrayOf(32767), mix(downmix, pcm(32000, 32000, 32000, 32000)))
        assertArrayEquals(intArrayOf(-32768), mix(downmix, pcm(-32768, -32768, -32768, -32768)))
    }

    @Test fun aPartialFrameWaitsForTheNextRead() {
        val downmix = MicrophoneDownmix(2)
        val frames = pcm(100, 300, 500, 700)
        val output = ByteArray(downmix.outputCapacity(frames.size))
        assertEquals(0, downmix.mix(frames.copyOfRange(0, 3), 3, output))
        val rest = frames.copyOfRange(3, frames.size)
        val written = downmix.mix(rest, rest.size, output)
        val scale = 1 / Math.sqrt(2.0)
        assertArrayEquals(intArrayOf(Math.round(400 * scale).toInt(), Math.round(1200 * scale).toInt()), samples(output, written))
    }
}
