package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2NowPlayingSeekTest {
    private fun config(nowPlayingSeekEnabled: Boolean) = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
        nowPlayingSeekEnabled = nowPlayingSeekEnabled,
    )

    private fun sentByAccessory(config: Iap2IdentificationConfig): List<Int> {
        val parameters = Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload).asList()
        val payload = parameters.single { it.id == 6 }.payload
        return (payload.indices step 2).map { i ->
            ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
        }
    }

    @Test
    fun setNowPlayingInformationIsDeclaredOnlyWithTheOption() {
        assertFalse(sentByAccessory(config(nowPlayingSeekEnabled = false)).contains(0x5003))
        assertTrue(sentByAccessory(config(nowPlayingSeekEnabled = true)).contains(0x5003))
    }

    @Test
    fun theOptionIsOffByDefaultAndAddsOnlyThatMessage() {
        assertFalse(config(nowPlayingSeekEnabled = true).copy(nowPlayingSeekEnabled = false).nowPlayingSeekEnabled)
        assertFalse(Iap2IdentificationConfig("x", "x", "x", "x", "1", "1", carPlayUsbInterfaceNumber = 3).nowPlayingSeekEnabled)
        assertEquals(
            sentByAccessory(config(nowPlayingSeekEnabled = false)),
            sentByAccessory(config(nowPlayingSeekEnabled = true)).filterNot { it == 0x5003 },
        )
    }

    @Test
    fun theWirelessTunnelKeepsTheDeclaration() {
        val wireless = config(nowPlayingSeekEnabled = true).forWirelessLink(
            Iap2WirelessLinkRole.RUNTIME_TUNNEL,
            Iap2WirelessIdentification(bluetoothMac = "00:11:22:33:44:55", ssid = "DiPlay"),
        )
        assertTrue(sentByAccessory(wireless).contains(0x5003))
    }

    @Test
    fun elapsedTimeIsParameterZeroInMilliseconds() {
        val frame = Iap2ControlMessages.setNowPlayingElapsed(73_500)

        assertEquals(0x5003, frame.messageId)
        assertArrayEquals(byteArrayOf(0, 8, 0, 0, 0, 1, 0x1f, 0x1c), frame.payload)
    }

    @Test
    fun anAcceptedIdentificationKeepsTheSeek() {
        val accepted = identify(config(nowPlayingSeekEnabled = true), start(), accepted())

        assertTrue(accepted.nowPlayingSeek)
        assertEquals(emptySet<Int>(), accepted.omittedMessages)
    }

    @Test
    fun anIphoneThatRejectsTheMessageLeavesTheConnectionWithoutSeek() {
        val rejected = Iap2Frame(
            Iap2IdentificationClient.IDENTIFICATION_REJECTED,
            Iap2ParameterList.of(Iap2Parameter(6, byteArrayOf(0x50, 0x03))).encode(),
        )
        val accepted = identify(config(nowPlayingSeekEnabled = true), start(), rejected, accepted())

        assertFalse(accepted.nowPlayingSeek)
        assertEquals(setOf(0x5003), accepted.omittedMessages)
    }

    @Test
    fun withoutTheOptionNoConnectionSeeks() {
        assertFalse(identify(config(nowPlayingSeekEnabled = false), start(), accepted()).nowPlayingSeek)
    }

    private fun identify(config: Iap2IdentificationConfig, vararg replies: Iap2Frame): Iap2IdentificationAccepted {
        val queue = ArrayDeque(replies.toList())
        return Iap2IdentificationClient.exchange(
            awaitReady = { true },
            recv = { queue.removeFirstOrNull() },
            send = { _, _ -> },
            config = config,
            timeoutMillis = 1_000,
            onProgress = {},
        )
    }

    private fun start() = Iap2Frame(Iap2IdentificationClient.START_IDENTIFICATION, ByteArray(0))
    private fun accepted() = Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_ACCEPTED, ByteArray(0))
}
