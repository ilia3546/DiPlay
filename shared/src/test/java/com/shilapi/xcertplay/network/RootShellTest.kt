package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.RootShell.CommandOutput
import com.shilapi.xcertplay.network.RootShell.Result
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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

    @Test fun aLargePayloadGoesThroughStdin() {
        var command: List<String>? = null
        var input: ByteArray? = null
        val payload = ByteArray(30_000) { 'A'.code.toByte() }
        assertEquals(Result.Completed(0, "ok"), RootShell.runWithInput("helper", payload) { cmd, _, stdin ->
            command = cmd
            input = stdin
            CommandOutput(0, "ok")
        })
        assertEquals(listOf("su", "-c", "helper"), command)
        assertTrue(payload.contentEquals(input))
    }

    @Test fun onlyPlainInterfaceNamesAreSafe() {
        assertTrue(RootShell.isSafeInterfaceName("wlan1"))
        assertTrue(RootShell.isSafeInterfaceName("ap_br-wlan0.1"))
        for (name in listOf(null, "", "wlan1 ", "wlan1;id", "wlan1'", "\$(id)", "a".repeat(16))) {
            assertFalse("$name", RootShell.isSafeInterfaceName(name))
        }
    }

    @Test fun outputLargerThanThePipeIsReadWhileTheCommandRuns() {
        // dumpsys wifi prints far more than a pipe holds; the command cannot exit until its output is read.
        val payload = "x".repeat(1_000_000)
        val pipe = PipedOutputStream()
        val stdout = PipedInputStream(pipe, 1_024)
        val writer = thread { pipe.use { it.write(payload.toByteArray()) } }
        val process = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = stdout
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int { writer.join(); return 0 }
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                writer.join(unit.toMillis(timeout))
                return !writer.isAlive
            }
            override fun exitValue(): Int = if (writer.isAlive) throw IllegalThreadStateException() else 0
            override fun destroy() = Unit
        }
        val output = RootShell.execute(process, timeoutMillis = 5_000) { it.close() }
        assertEquals(0, output.exitCode)
        assertEquals(payload.length, output.stdout.length)
    }
}
