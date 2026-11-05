package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.RootShell.CommandOutput
import com.shilapi.xcertplay.network.RootShell.Result
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellTest {
    @Test fun runsTheCommandThroughSuWithTheTimeout() {
        var command: List<String>? = null
        var timeout: Long? = null
        val result = RootShell.run("id -u", timeoutMillis = 2_000) { cmd, millis ->
            command = cmd
            timeout = millis
            CommandOutput(0, "0\n")
        }
        assertEquals(Result.Completed(0, "0\n"), result)
        assertEquals(listOf("su", "-c", "id -u"), command)
        assertEquals(2_000L, timeout)
    }

    @Test fun waitsLongEnoughForAFirstRootPromptByDefault() {
        var timeout: Long? = null
        RootShell.run("id -u") { _, millis -> timeout = millis; CommandOutput(0, "") }
        assertEquals(RootShell.DEFAULT_TIMEOUT_MILLIS, timeout)
    }

    @Test fun aFailedCommandKeepsItsExitCodeAndOutput() {
        assertEquals(Result.Completed(1, "Permission denied"),
            RootShell.run("id -u") { _, _ -> CommandOutput(1, "Permission denied") })
    }

    @Test fun missingSuAndTimeoutAreUnavailable() {
        assertEquals(Result.Unavailable("su unavailable (IOException)"),
            RootShell.run("id -u") { _, _ -> throw IOException("no su") })
        assertEquals(Result.Unavailable("su unavailable (SecurityException)"),
            RootShell.run("id -u") { _, _ -> throw SecurityException() })
        assertEquals(Result.Unavailable("su timed out"),
            RootShell.run("id -u") { _, _ -> CommandOutput(RootShell.TIMED_OUT, "") })
    }

    @Test fun anInterruptedWaitKeepsTheInterruptForTheCaller() {
        try {
            assertEquals(Result.Unavailable("interrupted"),
                RootShell.run("id -u") { _, _ -> throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun onlyPlainInterfaceNamesAreSafe() {
        assertTrue(RootShell.isSafeInterfaceName("wlan1"))
        assertTrue(RootShell.isSafeInterfaceName("ap_br-wlan0.1"))
        for (name in listOf(null, "", "wlan1 ", "wlan1;id", "wlan1'", "\$(id)", "a".repeat(16))) {
            assertFalse("$name", RootShell.isSafeInterfaceName(name))
        }
    }
}
