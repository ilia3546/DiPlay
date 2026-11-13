package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.RootShell
import com.shilapi.xcertplay.network.RootShell.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class SystemLogSnapshotTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = Instant.parse("2026-11-13T10:05:00.123Z").toEpochMilli()
    private val since = "11-13 10:00:00.123"

    private fun line(tag: String, message: String, time: String = "11-13 10:00:01.000") =
        "$time  1234  5678 D ${tag.padEnd(8)}: $message"

    private fun root(text: String, result: RootShell.Result = RootShell.Result.Completed(0, "")):
        (String, Long, (String) -> Unit) -> RootShell.Result = { _, _, onLine -> text.lines().forEach(onLine); result }

    private fun app(text: String, exitCode: Int = 0): (List<String>, Long, (String) -> Unit) -> CommandOutput =
        { _, _, onLine -> text.lines().forEach(onLine); CommandOutput(exitCode, "") }

    private val appMustNotRun: (List<String>, Long, (String) -> Unit) -> CommandOutput =
        { _, _, _ -> throw AssertionError("app logcat must not run") }

    private fun section(
        root: (String, Long, (String) -> Unit) -> RootShell.Result,
        app: (List<String>, Long, (String) -> Unit) -> CommandOutput,
        maxBytes: Int = SystemLogSnapshot.MAX_BYTES,
    ) = buildString { SystemLogSnapshot.appendTo(this, now, utc, root, app, maxBytes) }

    @Test fun eachNoisyTagIsDropped() {
        val noisy = listOf(
            line("teseo_hal_gnss", "fix quality=1"),
            line("teseo_hal_nmea", "sentence parsed"),
            line("DrFusionService", "fused heading=12"),
            line("Sensor_API", "sample accel"),
            line("hardware_radio_hw_dab", "tuner state=2"),
            line("hardware_radio_hw_fm", "rds block"),
            line("REC_DAB", "ensemble scan"),
            line("GnssLocationProvider", "reportLocation"),
            line("VehiclePlatform|RemoteHAL|Spi2Ethenet", "frame id=7"),
            line("wlan0", "beacon miss"),
            line("AccessibilityCache", "onAccessibilityEvent"),
            line("ipcp-IPLink", "keepalive"),
            line("UDPClient-IPLink", "send len=64"),
            line("firstLetter", "index built"),
            line("SystemUIPlugin_wifi.AccessPoint", "update rssi"),
            line("chatty", "uid=1000 system_server identical 4 lines"),
            line("android.hardware.gnss@1.0-service", "callback fix"),
            line("ActivityManager", "request component permission com.example.app"),
            "11-13 10:00:01.000 12345 12346 W teseo_hal_gnss: no fix",
        )
        noisy.forEach { assertTrue(it, SystemLogSnapshot.isNoise(it)) }
    }

    @Test fun indentedIap2DumpsAreDroppedAndTheirHeadersKept() {
        for (dump in listOf("  [5] param: \"Example Street\"", "  [3] param: u16=120", "  raw-body=0a0b0c", "\t[1] param: u8=2")) {
            assertTrue(dump, SystemLogSnapshot.isNoise(line("xcertplay-usb", dump)))
            assertNull(SystemLogSnapshot.exported(line("xcertplay-usb", dump)))
        }
        val header = line("xcertplay-usb", "IAP2 RX RouteGuidanceUpdate len=120")
        assertFalse(SystemLogSnapshot.isNoise(header))
        assertEquals(header, SystemLogSnapshot.exported(header))
    }

    @Test fun usefulLinesAreKept() {
        val useful = listOf(
            line("FocusControlManager", "requestAudioFocus usage=1 result=granted"),
            line("ExtCarAudioService", "focus loss transient"),
            line("MediaCenter", "source changed to radio"),
            line("AudioManager", "abandonAudioFocus"),
            line("DiPlay-MediaKeys", "car audio took focus, CarPlay paused sent=true"),
            line("ActivityManager", "Start proc 2345:com.example.player/u0a12 for service"),
            line("SettingsProvider", "the radio was chatty today"),
            line("Weather", "wlan0 link up"),
            line("VehiclePlatform", "frame id=7"),
            line("SystemUIPlugin_wifi-AccessPoint", "update rssi"),
            line("teseo_halx", "fix"),
            line("Other", "  indented message"),
        )
        useful.forEach {
            assertFalse(it, SystemLogSnapshot.isNoise(it))
            assertEquals(it, SystemLogSnapshot.exported(it))
        }
    }

    @Test fun linesWithNetworkNamesTrackMetadataOrADistrictAreDropped() {
        val personal = listOf(
            line("SystemUILog", "getName=HomeNetwork"),
            line("SystemUILog_NetPopupWindow", "list=[ScanResult: Example-5G, level: -40]"),
            line("SystemUIPlugin_WifiTracker", "config==== Example-5G"),
            line("WifiService", "connect WifiConfiguration: Example-5G"),
            line("vdev.service.cabinlan|vehiclebus", "setMessage {\"title\":\"Example Song\",\"artist\":\"Example Band\"}"),
            line("vdev.service.vehiclebus", "setMessage {\"title\":\"Example Song\"}"),
            line("WeatherAppWidgetProvider", "update District=Example District"),
        )
        personal.forEach { assertNull(it, SystemLogSnapshot.exported(it)) }
        val kept = listOf(
            line("SystemUILog", "panel expanded"),
            line("vdev.service.vehiclebus", "speed frame received"),
            line("WeatherAppWidgetProvider", "update temperature=12"),
        )
        kept.forEach { assertEquals(it, SystemLogSnapshot.exported(it)) }
    }

    @Test fun coordinatesAreMasked() {
        assertEquals(line("gnss", "Example teseo callback lat: [coord],lon: [coord]"),
            SystemLogSnapshot.exported(line("gnss", "Example teseo callback lat: 12.345678,lon: 98.765432")))
        assertEquals("fix latitude=[coord] longitude=[coord] lng:[coord]",
            SystemLogSnapshot.mask("fix latitude=12.5 longitude=-98.25 lng:7"))
        assertEquals("{\"lat\":[coord],\"lon\": \"[coord]\"}", SystemLogSnapshot.mask("{\"lat\":12.345678,\"lon\": \"98.765432\"}"))
        assertEquals("latency=12 long=3 flat=4", SystemLogSnapshot.mask("latency=12 long=3 flat=4"))
    }

    @Test fun bareBluetoothAddressesAreMasked() {
        assertEquals("connect [address] ok", SystemLogSnapshot.mask("connect 021A2B3C4D5E ok"))
        assertEquals("peer=[address]", SystemLogSnapshot.mask("peer=021a2b3c4d5e"))
        assertEquals("abcdefabcdef 021A2B3C4D5E7 x021A2B3C4D5E", SystemLogSnapshot.mask("abcdefabcdef 021A2B3C4D5E7 x021A2B3C4D5E"))
    }

    @Test fun roadNamesAndDestinationsAreMasked() {
        val hud = "route frame=0x5201 change=GUIDANCE guidance=BydHudGuidance(distanceMeters=120, maneuver=2, gaode=3, " +
            "road=Example Street, arrivalEpochSeconds=null)"
        assertEquals("route frame=0x5201 change=GUIDANCE guidance=BydHudGuidance(distanceMeters=120, maneuver=2, gaode=3, " +
            "road=[road], arrivalEpochSeconds=null)", SystemLogSnapshot.mask(hud))
        assertEquals("next afterRoad=[road], currentRoad=[road]) destination=[road]",
            SystemLogSnapshot.mask("next afterRoad=Example Avenue, currentRoad=Example Lane) destination=Example Place"))
        assertEquals("railroad=1", SystemLogSnapshot.mask("railroad=1"))
    }

    @Test fun masksRunBeforeTheRedactorCutsTheLine() {
        val prefix = line("Tag", "x".repeat(690 - line("Tag", "").length))
        val shown = SystemLogSnapshot.exported("$prefix XTA00000000000001 lat: 12.345678")!!
        assertTrue(shown, shown.length <= 700)
        assertFalse(shown, shown.contains("XTA0"))
        assertFalse(shown, shown.contains("12.34"))
    }

    @Test fun sinceIsFiveMinutesBeforeNowInTheDeviceZone() {
        assertEquals(since, SystemLogSnapshot.sinceTime(now, utc))
        assertEquals("11-13 13:00:00.123", SystemLogSnapshot.sinceTime(now, TimeZone.getTimeZone("GMT+03:00")))
    }

    @Test fun sinceUsesAsciiDigitsInAnyAppLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            assertEquals(since, SystemLogSnapshot.sinceTime(now, utc))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun onlyAStrictTimestampReachesTheCommand() {
        assertTrue(SystemLogSnapshot.isValidSince(since))
        for (bad in listOf("11-13 10:00:00", "11-13 10:00:00.123'; reboot", "1-13 10:00:00.123", "11-13  10:00:00.123", "")) {
            assertFalse(bad, SystemLogSnapshot.isValidSince(bad))
            try {
                SystemLogSnapshot.rootCommand(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun logcatSilencesTheNamedNoiseTagsItself() {
        val silenced = listOf("DrFusionService", "Sensor_API", "REC_DAB", "GnssLocationProvider", "wlan0", "AccessibilityCache",
            "ipcp-IPLink", "UDPClient-IPLink", "firstLetter", "SystemUIPlugin_wifi.AccessPoint", "chatty",
            "VehiclePlatform|RemoteHAL|Spi2Ethenet", "android.hardware.gnss@1.0-service").map { "$it:S" }
        assertEquals(listOf("logcat", "-d", "-v", "threadtime", "-T", since) + silenced, SystemLogSnapshot.appCommand(since))
        assertEquals("logcat -d -v threadtime -T '$since' DrFusionService:S Sensor_API:S REC_DAB:S GnssLocationProvider:S " +
            "wlan0:S AccessibilityCache:S ipcp-IPLink:S UDPClient-IPLink:S firstLetter:S SystemUIPlugin_wifi.AccessPoint:S " +
            "chatty:S 'VehiclePlatform|RemoteHAL|Spi2Ethenet:S' 'android.hardware.gnss@1.0-service:S'",
            SystemLogSnapshot.rootCommand(since))
    }

    @Test fun theRootCommandIsSafeForSuDashC() {
        val command = SystemLogSnapshot.rootCommand(since)
        assertTrue(command, Regex("logcat( ([A-Za-z0-9_.:-]+|'[^']*'))+").matches(command))
        val unquoted = command.replace(Regex("'[^']*'"), "")
        assertTrue(unquoted, unquoted.none { it in "|;&<>()\$`\\\"*?[]{}~#!@\n" })
    }

    @Test fun aVinLikeTokenIsMasked() {
        assertEquals("vehicle vin=[vin] ready", SystemLogSnapshot.mask("vehicle vin=XTA00000000000001 ready"))
        assertEquals("update VGM_A1_[vin]_OTA_2.zip", SystemLogSnapshot.mask("update VGM_A1_XTA00000000000001_OTA_2.zip"))
        assertEquals("[vin]", SystemLogSnapshot.mask("Z9Y8X7W6V5U4T3S21"))
    }

    @Test fun wordsAndOtherIdentifiersAreNotTakenForAVin() {
        for (text in listOf(
            "ABCDEFGHJKLMNPRST",
            "00000000000000001",
            "XTA0000000000000I",
            "xta00000000000001",
            "XTA000000000000012",
            "AXTA00000000000001",
        )) assertEquals(text, SystemLogSnapshot.mask(text))
    }

    @Test fun aLongHexIdentifierIsLeftToTheRedactor() {
        val shown = SystemLogSnapshot.exported(line("Session", "id=0123456789ABCDEF0123456789ABCDEF"))!!
        assertTrue(shown, shown.endsWith("id=[identifier]"))
    }

    @Test fun everyLineGoesThroughTheRedactor() {
        val collector = SystemLogSnapshot.Collector(1_000)
        listOf(
            line("WifiService", "peer 02:1a:2b:3c:4d:5e at 192.168.43.10"),
            line("WifiService", "password=hunter2"),
            line("chatty", "identical lines"),
            "",
        ).forEach(collector::offer)
        val snapshot = collector.finish()
        assertEquals(listOf(line("WifiService", "peer [address] at [ip]")), snapshot.lines)
        assertEquals(3, snapshot.logcatLines)
    }

    @Test fun theCollectorKeepsTheNewestLinesAndCountsTheRest() {
        val lines = (0 until 10).map { "line-$it" }
        val capped = SystemLogSnapshot.Collector(3 * 7).apply { lines.forEach(::offer) }.finish()
        assertEquals(listOf("line-7", "line-8", "line-9"), capped.lines)
        assertEquals(7, capped.dropped)
        val all = SystemLogSnapshot.Collector(10 * 7).apply { lines.forEach(::offer) }.finish()
        assertEquals(lines, all.lines)
        assertEquals(0, all.dropped)
    }

    @Test fun theCollectorCountsUtf8Bytes() {
        val capped = SystemLogSnapshot.Collector(13).apply { listOf("a", "строка").forEach(::offer) }.finish()
        assertEquals(listOf("строка"), capped.lines)
        assertEquals(1, capped.dropped)
    }

    @Test fun theCollectorStaysWithinItsLimitForAnyVolume() {
        val collector = SystemLogSnapshot.Collector(1_000)
        repeat(200_000) { collector.offer(line("Tag", "n=$it")) }
        val snapshot = collector.finish()
        assertTrue(snapshot.lines.sumOf { it.toByteArray().size + 1 } <= 1_000)
        assertEquals(line("Tag", "n=199999"), snapshot.lines.last())
        assertEquals(200_000, snapshot.dropped + snapshot.lines.size)
    }

    @Test fun linesHandedOverAfterTheRunEndedAreIgnored() {
        val collector = SystemLogSnapshot.Collector(1_000)
        collector.offer(line("Tag", "in time"))
        assertEquals(1, collector.finish().lines.size)
        collector.offer(line("Tag", "late"))
        assertEquals(listOf(line("Tag", "in time")), collector.finish().lines)
    }

    @Test fun aCappedSectionSaysHowManyLinesItDroppedAndWhereItStarts() {
        val output = (0 until 10).joinToString("\n") { line("Tag", "n=$it", time = "11-13 10:00:0$it.000") }
        val size = line("Tag", "n=0").toByteArray().size + 1
        val shown = section(root(output), appMustNotRun, maxBytes = 3 * size).lines()
        assertEquals("--- System log (last 5 minutes, root) ---", shown[0])
        assertEquals("Dropped 7 older lines over the ${3 * size}-byte limit; oldest kept line at 11-13 10:00:07.000", shown[1])
        assertEquals(output.lines().takeLast(3), shown.subList(2, 5))
    }

    @Test fun theSectionIsAppendedToTheReportInPlace() {
        val report = StringBuilder("DiPlay 1.0\n")
        SystemLogSnapshot.appendTo(report, now, utc, root(line("Tag", "x")), appMustNotRun)
        assertEquals("DiPlay 1.0\n--- System log (last 5 minutes, root) ---\n${line("Tag", "x")}\n", report.toString())
    }

    @Test fun rootIsUsedWhenItAnswersWithLogLines() {
        var command = ""
        var timeout = 0L
        val shown = section({ c, t, onLine ->
            command = c; timeout = t
            onLine("--------- beginning of main")
            onLine(line("FocusControlManager", "focus granted"))
            RootShell.Result.Completed(0, "")
        }, appMustNotRun)
        assertEquals(SystemLogSnapshot.rootCommand(since), command)
        assertEquals(30_000L, timeout)
        assertEquals("--- System log (last 5 minutes, root) ---\n--------- beginning of main\n" +
            "${line("FocusControlManager", "focus granted")}\n", shown)
    }

    @Test fun aDeniedRootFallsBackToTheAppAndQuotesSu() {
        val shown = section(root("Permission denied", RootShell.Result.Completed(1, "")), app(line("Own", "line")))
        assertEquals("--- System log (last 5 minutes, app-only) ---\n" +
            "Root not used: root denied; su output: Permission denied\n${line("Own", "line")}\n", shown)
    }

    @Test fun suOutputWithoutLogLinesFallsBackAndIsRedactedAndShort() {
        val su = (1..5).joinToString("\n") { "su: error $it at 02:1a:2b:3c:4d:5e" }
        val shown = section(root(su, RootShell.Result.Completed(2, "")), app(line("Own", "line")))
        assertEquals("--- System log (last 5 minutes, app-only) ---\n" +
            "Root not used: su exited with 2 and no log lines; su output: su: error 1 at [address] | " +
            "su: error 2 at [address] | su: error 3 at [address]\n${line("Own", "line")}\n", shown)
    }

    @Test fun aRootTimeoutKeepsWhatWasReadAndSaysWhatIsMissing() {
        val output = listOf(line("Early", "a", time = "11-13 10:00:01.000"), line("Early", "b", time = "11-13 10:00:02.500"))
            .joinToString("\n")
        val shown = section(root(output, RootShell.Result.Unavailable(RootShell.TIMED_OUT_REASON)), appMustNotRun)
        assertEquals("--- System log (last 5 minutes, root) ---\n" +
            "root logcat timed out after 30 s; lines after 11-13 10:00:02.500 are missing\n$output\n", shown)
    }

    @Test fun withoutRootTheAppReadsItsOwnLinesAndSaysWhy() {
        var appCommand = emptyList<String>()
        val shown = section(root("", RootShell.Result.Unavailable("su unavailable (IOException)")), { c, _, onLine ->
            appCommand = c
            listOf(line("DiPlay-MediaKeys", "car audio took focus"), line("chatty", "x")).forEach(onLine)
            CommandOutput(0, "")
        })
        assertEquals(SystemLogSnapshot.appCommand(since), appCommand)
        assertEquals("--- System log (last 5 minutes, app-only) ---\nRoot not used: su unavailable (IOException)\n" +
            line("DiPlay-MediaKeys", "car audio took focus") + "\n", shown)
    }

    @Test fun aRootRunWithoutLogLinesFallsBackToTheApp() {
        for ((result, why) in listOf<Pair<RootShell.Result, String>>(
            RootShell.Result.Completed(1, "") to "su exited with 1 and no log lines",
            RootShell.Result.Completed(0, "") to "su exited with 0 and no log lines",
            RootShell.Result.Unavailable(RootShell.TIMED_OUT_REASON) to "su timed out",
        )) {
            val shown = section(root(" \n", result), app(line("Own", "line")))
            assertEquals("--- System log (last 5 minutes, app-only) ---\nRoot not used: $why\n${line("Own", "line")}\n", shown)
        }
    }

    @Test fun failuresBecomeNotesInsteadOfErrors() {
        val noRoot = root("", RootShell.Result.Completed(1, ""))
        val thrown = section(noRoot, { _, _, onLine ->
            onLine(line("Own", "before the failure"))
            throw IOException()
        })
        assertEquals("--- System log (last 5 minutes, app-only) ---\nRoot not used: su exited with 1 and no log lines\n" +
            "logcat failed: IOException\n${line("Own", "before the failure")}\n", thrown)
        val timedOut = section(noRoot, app(line("Own", "x", time = "11-13 10:00:03.000"), exitCode = RootShell.TIMED_OUT))
        assertTrue(timedOut, timedOut.contains("\nlogcat timed out after 30 s; lines after 11-13 10:00:03.000 are missing\n"))
        val report = StringBuilder("head\n")
        SystemLogSnapshot.appendTo(report, now, utc, { _, _, onLine -> onLine("x"); throw OutOfMemoryError() }, appMustNotRun)
        assertEquals("head\n--- System log (last 5 minutes, unavailable) ---\nSystem log unavailable: OutOfMemoryError\n",
            report.toString())
    }

    @Test fun anInterruptedAppRunKeepsTheInterrupt() {
        try {
            val shown = section(root("", RootShell.Result.Completed(1, "")), { _, _, _ -> throw InterruptedException() })
            assertTrue(shown, shown.contains("\nlogcat interrupted\n"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun theViewerTextLeavesOutOnlyTheSystemLogSection() {
        val head = "DiPlay 1.0\n--- diplay.log ---\n12:00:00.000  CarPlay connected\n"
        val section = "--- System log (last 5 minutes, root) ---\n${line("FocusControlManager", "focus granted")}\n"
        assertEquals(head, SystemLogSnapshot.withoutSection(head + section))
        assertEquals(head, SystemLogSnapshot.withoutSection(head))
        assertEquals("", SystemLogSnapshot.withoutSection(section))
    }
}
