package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.HostapdChannelReader.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostapdChannelReaderTest {
    @Test fun readsTheChannelFromTheVendorHostapdConfiguration() {
        var command: String? = null
        val result = HostapdChannelReader.read("wlan1") { cmd ->
            command = cmd
            RootShell.Result.Completed(0, "channel=149\n")
        }
        assertEquals(Result.Found(149), result)
        assertEquals("grep -E '^channel=' /data/vendor/wifi/hostapd/hostapd_wlan1.conf", command)
    }

    @Test fun neverRunsAShellForAnUnsafeInterfaceName() {
        for (name in listOf(null, "", "wlan1; reboot", "wlan1'", "a".repeat(16))) {
            val result = HostapdChannelReader.read(name) { error("must not run") }
            assertTrue("$name", result is Result.Unavailable)
        }
    }

    @Test fun missingRootDeniedRootAndTimeoutAreUnavailable() {
        assertEquals(Result.Unavailable("su unavailable (IOException)"),
            HostapdChannelReader.read("wlan1") { RootShell.Result.Unavailable("su unavailable (IOException)") })
        assertEquals(Result.Unavailable("su exit 1"),
            HostapdChannelReader.read("wlan1") { RootShell.Result.Completed(1, "Permission denied") })
        assertEquals(Result.Unavailable("su timed out"),
            HostapdChannelReader.read("wlan1") { RootShell.Result.Unavailable("su timed out") })
        assertEquals(Result.Unavailable("no channel line"),
            HostapdChannelReader.read("wlan1") { RootShell.Result.Completed(0, "") })
    }

    @Test fun rejectsAutoAndOutOfRangeChannels() {
        assertNull(HostapdChannelReader.parseChannel("channel=0"))
        assertNull(HostapdChannelReader.parseChannel("channel=acs_survey"))
        assertNull(HostapdChannelReader.parseChannel("channel=197"))
        assertEquals(36, HostapdChannelReader.parseChannel("  channel=36  \nchannel=149"))
    }

    @Test fun parsesAccessPointBssidBytes() {
        assertArrayEquals(byteArrayOf(0x02, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e),
            HotspotInterfaceBssid.toBytes("02:1a:2b:3c:4d:5e"))
        assertArrayEquals(byteArrayOf(0xDA.toByte(), 0x12, 0x34, 0x56, 0x78, 0x9A.toByte()),
            HotspotInterfaceBssid.toBytes("DA:12:34:56:78:9A"))
        for (mac in listOf("", "00:00:00:00:00:00", "01:00:5e:00:00:fb", "02:1a:2b:3c:4d", "02:1a:2b:3c:4d:5", "zz:1a:2b:3c:4d:5e")) {
            assertNull(mac, HotspotInterfaceBssid.toBytes(mac))
        }
    }

    @Test fun withoutRootDetailsTheHotspotIsNeitherReadNorGivenABssid() {
        val logs = mutableListOf<String>()
        val details = ManualHotspotManager.radioDetails(0, "wlan1", "02:1a:2b:3c:4d:5e", rootHotspotDetails = false,
            readHostapdChannel = { error("must not run su") }, onDiagnostic = logs::add)
        assertEquals(0, details.channel)
        assertEquals("unknown", details.channelSource)
        assertNull(details.accessPointBssid)
        assertEquals(emptyList<String>(), logs)

        val framework = ManualHotspotManager.radioDetails(36, "wlan1", "02:1a:2b:3c:4d:5e", rootHotspotDetails = false,
            readHostapdChannel = { error("must not run su") }, onDiagnostic = {})
        assertEquals(36, framework.channel)
        assertEquals("framework", framework.channelSource)
        assertNull(framework.accessPointBssid)
    }

    @Test fun rootDetailsReadHostapdOnlyWhenAndroidHidesTheChannel() {
        val reads = mutableListOf<String?>()
        val hostapd = ManualHotspotManager.radioDetails(0, "wlan1", "02:1a:2b:3c:4d:5e", rootHotspotDetails = true,
            readHostapdChannel = { reads += it; Result.Found(149) }, onDiagnostic = {})
        assertEquals(149, hostapd.channel)
        assertEquals("hostapd", hostapd.channelSource)
        assertArrayEquals(byteArrayOf(0x02, 0x1a, 0x2b, 0x3c, 0x4d, 0x5e), hostapd.accessPointBssid)
        assertEquals(listOf<String?>("wlan1"), reads)

        val framework = ManualHotspotManager.radioDetails(36, "wlan1", null, rootHotspotDetails = true,
            readHostapdChannel = { error("Android already reported the channel") }, onDiagnostic = {})
        assertEquals(36, framework.channel)
        assertNull(framework.accessPointBssid)

        val logs = mutableListOf<String>()
        val unavailable = ManualHotspotManager.radioDetails(0, "wlan1", null, rootHotspotDetails = true,
            readHostapdChannel = { Result.Unavailable("su exit 1") }, onDiagnostic = logs::add)
        assertEquals(0, unavailable.channel)
        assertEquals("unknown", unavailable.channelSource)
        assertEquals(listOf("Manual hotspot hostapd channel unavailable iface=wlan1: su exit 1"), logs)
    }
}
