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
import java.util.Collections
import java.util.concurrent.CountDownLatch
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

    @Test fun streamsLinesThroughSu() {
        var command: List<String>? = null
        val lines = mutableListOf<String>()
        val result = RootShell.stream("logcat -d", 2_000, { lines += it }) { cmd, _, onLine ->
            command = cmd
            onLine("one")
            onLine("two")
            CommandOutput(0, "")
        }
        assertEquals(Result.Completed(0, ""), result)
        assertEquals(listOf("su", "-c", "logcat -d"), command)
        assertEquals(listOf("one", "two"), lines)
    }

    @Test fun aStreamThatCannotStartOrTimesOutIsUnavailable() {
        assertEquals(Result.Unavailable("su unavailable (IOException)"),
            RootShell.stream("logcat -d", 2_000, {}) { _, _, _ -> throw IOException("no su") })
        assertEquals(Result.Unavailable("su timed out"),
            RootShell.stream("logcat -d", 2_000, {}) { _, _, _ -> CommandOutput(RootShell.TIMED_OUT, "") })
        try {
            assertEquals(Result.Unavailable("interrupted"),
                RootShell.stream("logcat -d", 2_000, {}) { _, _, _ -> throw InterruptedException() })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun onlyMagisksDenialLineCountsAsDenied() {
        assertTrue(RootShell.denied(1, "Permission denied\n"))
        assertTrue(RootShell.denied(1, "su: permission denied"))
        assertFalse(RootShell.denied(0, "Permission denied"))
        assertFalse(RootShell.denied(1, "logcat: read failure"))
        assertFalse(RootShell.denied(1, "Permission denied\nother line"))
    }

    @Test fun aProcessIsStreamedLineByLineWithItsErrorsAndExitCode() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val output = RootShell.streamProcess(listOf("/bin/sh", "-c", "printf 'one\\ntwo\\n'; echo three >&2; exit 3"), 5_000) {
            lines += it
        }
        assertEquals(3, output.exitCode)
        assertEquals(setOf("one", "two", "three"), lines.toSet())
    }

    @Test fun aStreamedProcessOverTheTimeoutIsStoppedAndItsLinesKept() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val started = System.nanoTime()
        val output = RootShell.streamProcess(listOf("/bin/sh", "-c", "echo before; exec sleep 30"), 500) { lines += it }
        assertEquals(RootShell.TIMED_OUT, output.exitCode)
        assertEquals(listOf("before"), lines.toList())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }

    @Test fun aStreamWaitsForLinesThatArriveAfterTheProcessExited() {
        // More than the fixed drain time a non-streaming run allows itself.
        val pipe = PipedOutputStream()
        val stdout = PipedInputStream(pipe)
        val writer = thread { Thread.sleep(1_500); pipe.use { it.write("late\n".toByteArray()) } }
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val output = RootShell.executeStreaming(fakeProcess(stdout, exited = true), 5_000) { lines += it }
        writer.join()
        assertEquals(0, output.exitCode)
        assertEquals(listOf("late"), lines.toList())
    }

    @Test fun aStreamThatTimesOutKeepsTheLinesAlreadyRead() {
        val pipe = PipedOutputStream()
        val stdout = PipedInputStream(pipe)
        val closed = CountDownLatch(1)
        thread { pipe.write("early\n".toByteArray()); pipe.flush(); closed.await(); pipe.close() }
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val output = RootShell.executeStreaming(fakeProcess(stdout, exited = false) { closed.countDown() }, 300) { lines += it }
        assertEquals(RootShell.TIMED_OUT, output.exitCode)
        assertEquals(listOf("early"), lines.toList())
    }

    private fun fakeProcess(stdout: InputStream, exited: Boolean, onDestroy: () -> Unit = {}) = object : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!exited) Thread.sleep(unit.toMillis(timeout))
            return exited
        }
        override fun exitValue(): Int = if (exited) 0 else throw IllegalThreadStateException()
        override fun destroy() = onDestroy()
    }
}
