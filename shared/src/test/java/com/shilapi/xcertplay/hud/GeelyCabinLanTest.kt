package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.network.RootShell
import java.io.ByteArrayInputStream
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyCabinLanTest {
    private val direct = mutableListOf<String>()
    private val root = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var now = 0L
    private var directError: Throwable? = null
    private var rootFailure: String? = null

    private val sender = GeelyCabinLan.Sender(
        direct = { _, _, json -> direct += json; directError?.let { throw it } },
        root = { _, _, json -> root += json; rootFailure },
        log = logs::add,
        now = { now },
    )

    @Test fun theAppProcessIsUsedWhileItWorks() {
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "b"))
        assertEquals(listOf("a", "b"), direct)
        assertEquals(emptyList<String>(), root)
        assertEquals(listOf("dashboard CabinLAN sends through the app process"), logs)
    }

    @Test fun aFailedAppProcessIsTriedAgainForTheNextMessage() {
        directError = InvocationTargetException(IllegalStateException("gone"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        directError = null
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 1, "b"))
        assertEquals(listOf("a", "b"), direct)
        assertEquals(listOf("a"), root)
        assertEquals(listOf("dashboard CabinLAN refused in the app process (IllegalStateException)",
            "dashboard CabinLAN sends through the root helper", "dashboard CabinLAN sends through the app process"), logs)
    }

    @Test fun aPolicyRefusalSkipsTheAppProcessForAWhile() {
        directError = InvocationTargetException(SecurityException("denied"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 1, "b"))
        assertEquals(listOf("a"), direct)
        assertEquals(listOf("a", "b"), root)
        now += GeelyCabinLan.Sender.DIRECT_RETRY_MILLIS
        directError = null
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "c"))
        assertEquals(listOf("a", "c"), direct)
        assertEquals(listOf("a", "b"), root)
        assertEquals(listOf("dashboard CabinLAN refused in the app process (SecurityException)",
            "dashboard CabinLAN sends through the root helper", "dashboard CabinLAN sends through the app process"), logs)
    }

    @Test fun aRepeatNeverStartsTheRootHelper() {
        directError = IllegalStateException()
        assertFalse(sender.sendWithoutRoot(GeelyCabinLan.MUSIC, 0, "a"))
        assertEquals(listOf("a"), direct)
        assertEquals(emptyList<String>(), root)
    }

    @Test fun aRootHelperThatKeepsFailingStopsUntilTheNextSession() {
        directError = NoClassDefFoundError()
        rootFailure = "su timed out"
        repeat(GeelyCabinLan.Sender.MAX_ROOT_FAILURES + 2) { assertFalse(sender.send(GeelyCabinLan.MUSIC, 0, "m$it")) }
        assertEquals(GeelyCabinLan.Sender.MAX_ROOT_FAILURES, root.size)
        assertEquals(listOf("dashboard CabinLAN refused in the app process (NoClassDefFoundError)",
            "dashboard CabinLAN root helper failed (su timed out)",
            "dashboard CabinLAN root helper failed 3 times in a row; stopped until the next session"), logs)
        sender.newSession()
        rootFailure = null
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "n"))
        assertEquals("n", root.last())
    }

    @Test fun anAttemptTellsAMessageThatSurelyStayedFromOneThatMayHaveLeft() {
        fun attempt() = sender.attempt(GeelyCabinLan.ENGINEERING_MODE, 0, "m").name
        assertEquals("SENT", attempt())
        directError = IllegalStateException()
        rootFailure = "FAILED SecurityException"
        assertEquals("the helper says it failed", "FAILED_KNOWN", attempt())
        rootFailure = "su timed out"
        assertEquals("UNKNOWN", attempt())
        rootFailure = "exit 1"
        assertEquals("UNKNOWN", attempt())
        assertEquals("the root helper is stopped and the app process failed", "FAILED_KNOWN", attempt())
        directError = SecurityException()
        assertEquals("FAILED_KNOWN", attempt())
        assertEquals("the app process is skipped and the root helper stopped", "NOT_ATTEMPTED", attempt())
    }

    @Test fun eachNewRootFailureReasonIsLoggedOnce() {
        directError = NoClassDefFoundError()
        rootFailure = "exit 1"
        sender.send(GeelyCabinLan.MUSIC, 0, "a")
        rootFailure = "FAILED SecurityException"
        sender.send(GeelyCabinLan.MUSIC, 0, "b")
        assertEquals(listOf("dashboard CabinLAN root helper failed (exit 1)",
            "dashboard CabinLAN root helper failed (FAILED SecurityException)"), logs.drop(1))
    }

    @Test fun theRootHelperGetsOnlyNumbersAsArguments() {
        assertEquals("CLASSPATH='/data/app/it'\"'\"'s/base.apk' app_process /system/bin ${GeelyCabinLanMain::class.java.name} 4097 0",
            GeelyCabinLan.rootCommand("/data/app/it's/base.apk", GeelyCabinLan.MUSIC, 0))
    }

    @Test fun onlyTheHelpersSentLineCounts() {
        assertNull(GeelyCabinLan.rootFailure(RootShell.Result.Completed(0, "warning\n${GeelyCabinLanMain.HEADER}|SENT\n")))
        assertEquals("FAILED SecurityException",
            GeelyCabinLan.rootFailure(RootShell.Result.Completed(0, "${GeelyCabinLanMain.HEADER}|FAILED SecurityException")))
        assertEquals("exit 1", GeelyCabinLan.rootFailure(RootShell.Result.Completed(1, "${GeelyCabinLanMain.HEADER}|SENT")))
        assertEquals("su timed out", GeelyCabinLan.rootFailure(RootShell.Result.Unavailable("su timed out")))
        assertEquals("no helper reply", GeelyCabinLan.rootFailure(RootShell.Result.Completed(0, "Permission denied")))
        assertEquals("no helper reply",
            GeelyCabinLan.rootFailure(RootShell.Result.Completed(0, "${GeelyCabinLanMain.HEADER}|FAILED {\"SongName\":\"x\"}")))
    }

    @Test fun theHelperReadsAMessageUpToItsLimit() {
        val largest = ByteArray(GeelyCabinLanMain.MAX_MESSAGE_BYTES) { 'a'.code.toByte() }
        assertEquals(GeelyCabinLanMain.MAX_MESSAGE_BYTES, GeelyCabinLanMain.readMessage(ByteArrayInputStream(largest)).length)
        assertThrows(IllegalArgumentException::class.java) {
            GeelyCabinLanMain.readMessage(ByteArrayInputStream(largest + 'a'.code.toByte()))
        }
    }
}
