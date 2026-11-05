package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mixes interleaved 16-bit channels to mono. A channel that has only ever delivered zeros is left out, and the
 * sum is scaled by the square root of the live channel count and clipped: one talker near one microphone stays
 * audible instead of being divided by the number of microphones.
 */
internal class MicrophoneDownmix(val channels: Int) {
    init {
        require(channels >= 2) { "a downmix needs at least two channels" }
    }

    private val frameBytes = channels * 2
    private val live = BooleanArray(channels)
    private var liveChannels = 0
    private var scale = 0.0
    private val pending = ByteArray(frameBytes)
    private var pendingBytes = 0

    /** The loudest absolute sample of each channel in the latest [mix] call. */
    val peaks = IntArray(channels)

    /** The mono bytes [mix] can write for [inputBytes] input bytes, including a frame carried from the last call. */
    fun outputCapacity(inputBytes: Int): Int = (inputBytes / frameBytes + 1) * 2

    /** Mixes [count] bytes of [input] into [output]; a partial frame waits for the next call. Returns bytes written. */
    fun mix(input: ByteArray, count: Int, output: ByteArray): Int {
        peaks.fill(0)
        var index = 0
        var written = 0
        if (pendingBytes > 0) {
            val take = minOf(frameBytes - pendingBytes, count)
            input.copyInto(pending, pendingBytes, 0, take)
            pendingBytes += take
            index = take
            if (pendingBytes < frameBytes) return 0
            written += mixFrame(pending, 0, output, written)
            pendingBytes = 0
        }
        while (count - index >= frameBytes) {
            written += mixFrame(input, index, output, written)
            index += frameBytes
        }
        if (index < count) {
            input.copyInto(pending, 0, index, count)
            pendingBytes = count - index
        }
        return written
    }

    private fun mixFrame(source: ByteArray, at: Int, output: ByteArray, outputAt: Int): Int {
        var sum = 0L
        for (channel in 0 until channels) {
            val offset = at + channel * 2
            val sample = (source[offset].toInt() and 0xff) or (source[offset + 1].toInt() shl 8)
            val magnitude = abs(sample)
            if (magnitude > peaks[channel]) peaks[channel] = magnitude
            if (sample != 0 && !live[channel]) {
                live[channel] = true
                liveChannels++
                scale = 1.0 / sqrt(liveChannels.toDouble())
            }
            if (live[channel]) sum += sample
        }
        val mixed = (sum * scale).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        output[outputAt] = mixed.toByte()
        output[outputAt + 1] = (mixed shr 8).toByte()
        return 2
    }
}
