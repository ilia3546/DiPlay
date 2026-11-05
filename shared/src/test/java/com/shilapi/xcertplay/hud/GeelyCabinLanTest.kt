package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.network.RootShell
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyCabinLanTest {
    private val direct = mutableListOf<String>()
    private val root = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    @Test fun theAppProcessIsUsedWhileItWorks() {
        val sender = GeelyCabinLan.Sender(direct = { _, _, json -> direct += json }, root = { _, _, json -> root += json; true },
            log = logs::add)
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "b"))
        assertEquals(listOf("a", "b"), direct)
        assertEquals(emptyList<String>(), root)
        assertEquals(listOf("dashboard CabinLAN sends through the app process"), logs)
    }

    @Test fun aRefusedAppProcessFallsBackToRootForTheRestOfTheProcess() {
        val sender = GeelyCabinLan.Sender(
            direct = { _, _, json -> direct += json; throw InvocationTargetException(SecurityException("denied")) },
            root = { _, _, json -> root += json; true }, log = logs::add)
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        assertTrue(sender.send(GeelyCabinLan.MUSIC, 1, "b"))
        assertEquals(listOf("a"), direct)
        assertEquals(listOf("a", "b"), root)
        assertEquals(listOf("dashboard CabinLAN refused in the app process (SecurityException); using root",
            "dashboard CabinLAN sends through the root helper"), logs)
    }

    @Test fun aFailedRootHelperReportsFailure() {
        val sender = GeelyCabinLan.Sender(direct = { _, _, _ -> throw NoClassDefFoundError() }, root = { _, _, _ -> false },
            log = logs::add)
        assertFalse(sender.send(GeelyCabinLan.MUSIC, 0, "a"))
        assertEquals("dashboard CabinLAN root helper failed", logs.last())
    }

    @Test fun theRootHelperGetsOnlyNumbersAsArguments() {
        assertEquals("CLASSPATH='/data/app/it'\"'\"'s/base.apk' app_process /system/bin ${GeelyCabinLanMain::class.java.name} 4097 0",
            GeelyCabinLan.rootCommand("/data/app/it's/base.apk", GeelyCabinLan.MUSIC, 0))
    }

    @Test fun onlyTheHelpersSentLineCounts() {
        assertTrue(GeelyCabinLan.rootSent(RootShell.Result.Completed(0, "warning\n${GeelyCabinLanMain.HEADER}|SENT\n")))
        assertFalse(GeelyCabinLan.rootSent(RootShell.Result.Completed(0, "${GeelyCabinLanMain.HEADER}|FAILED SecurityException")))
        assertFalse(GeelyCabinLan.rootSent(RootShell.Result.Completed(1, "${GeelyCabinLanMain.HEADER}|SENT")))
        assertFalse(GeelyCabinLan.rootSent(RootShell.Result.Unavailable("su timed out")))
    }
}
