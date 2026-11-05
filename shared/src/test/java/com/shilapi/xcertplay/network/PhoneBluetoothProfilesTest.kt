package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.PhoneBluetoothProfiles.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneBluetoothProfilesTest {
    private val helper = PhoneBluetoothProfilesMain::class.java.name

    @Test fun startsTheHelperForTheIphoneAddress() {
        assertEquals("CLASSPATH='/data/app/x/base.apk' app_process /system/bin $helper AA:BB:CC:01:02:03",
            PhoneBluetoothProfiles.command("/data/app/x/base.apk", "aa:bb:cc:01:02:03"))
    }

    @Test fun quotesTheApkPathForTheShell() {
        assertEquals("CLASSPATH='/data/app/it'\"'\"'s/base.apk' app_process /system/bin $helper AA:BB:CC:01:02:03",
            PhoneBluetoothProfiles.command("/data/app/it's/base.apk", "AA:BB:CC:01:02:03"))
    }

    @Test fun anythingButABluetoothAddressNeverReachesTheShell() {
        val bad = listOf(null, "", "AA:BB:CC:01:02", "AA:BB:CC:01:02:03:04", "AA-BB-CC-01-02-03", "AA:BB:CC:01:02:0G",
            "AA:BB:CC:01:02:03; reboot", "AA:BB:CC:01:02:03'", "\"AA:BB:CC:01:02:03\"", " AA:BB:CC:01:02:03",
            "AA:BB:CC:01:02:03\n")
        for (address in bad) {
            assertFalse("$address", PhoneBluetoothProfiles.validAddress(address))
            assertNull("$address", PhoneBluetoothProfiles.command("/data/app/x/base.apk", address))
            assertEquals("skipped: no valid iPhone Bluetooth address",
                PhoneBluetoothProfiles.disconnect("/data/app/x/base.apk", address) { error("must not run su") })
        }
    }

    @Test fun reportsTheHelperOutcome() {
        var command: String? = null
        val result = PhoneBluetoothProfiles.disconnect("/data/app/x/base.apk", "AA:BB:CC:01:02:03") {
            command = it
            RootShell.Result.Completed(0, "WARNING: linker noise\n${PhoneBluetoothProfiles.HEADER}|DISCONNECTED\n")
        }
        assertEquals("result=DISCONNECTED", result)
        assertEquals(PhoneBluetoothProfiles.command("/data/app/x/base.apk", "AA:BB:CC:01:02:03"), command)
    }

    @Test fun missingRootAndFailedHelpersAreReportedWithoutTheAddress() {
        assertEquals("failed: su timed out", PhoneBluetoothProfiles.disconnect("/a.apk", "AA:BB:CC:01:02:03") {
            RootShell.Result.Unavailable("su timed out")
        })
        assertEquals("failed: su exit 1", PhoneBluetoothProfiles.disconnect("/a.apk", "AA:BB:CC:01:02:03") {
            RootShell.Result.Completed(1, "Permission denied")
        })
        assertEquals("result=UNKNOWN", PhoneBluetoothProfiles.disconnect("/a.apk", "AA:BB:CC:01:02:03") {
            RootShell.Result.Completed(0, "")
        })
    }

    @Test fun parsesOnlyKnownProtocolLines() {
        assertEquals(Outcome.REFUSED, PhoneBluetoothProfiles.parse("${PhoneBluetoothProfiles.HEADER}|REFUSED"))
        assertEquals(Outcome.UNSUPPORTED, PhoneBluetoothProfiles.parse(" ${PhoneBluetoothProfiles.HEADER}|UNSUPPORTED \n"))
        assertEquals(Outcome.UNKNOWN, PhoneBluetoothProfiles.parse("${PhoneBluetoothProfiles.HEADER}|true"))
        assertEquals(Outcome.UNKNOWN, PhoneBluetoothProfiles.parse("DISCONNECTED"))
    }
}
