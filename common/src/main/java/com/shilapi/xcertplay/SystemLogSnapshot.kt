package com.shilapi.xcertplay

import android.os.Build
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.network.RootShell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The head unit's logcat of the last minutes, filtered and redacted, as the last section of a diagnostic report. */
internal object SystemLogSnapshot {
    const val WINDOW_MILLIS = 5 * 60 * 1_000L
    const val MAX_BYTES = 15 * 1024 * 1024
    const val TIMEOUT_MILLIS = 30_000L
    const val MARKER = "--- System log (last 5 minutes, "
    private const val SU_OUTPUT_LINES = 3
    private const val HEADER = "^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3} +[0-9]+ +[0-9]+ [VDIWEFS] "

    // logcat silences these itself, so their tens of megabytes never cross the pipe or the per-line Kotlin filter.
    // Filterspecs match whole tag names only, so the prefix tags in [noise] are dropped in Kotlin alone.
    private val silencedTags = listOf(
        "DrFusionService", "Sensor_API", "REC_DAB", "GnssLocationProvider", "wlan0", "AccessibilityCache",
        "ipcp-IPLink", "UDPClient-IPLink", "firstLetter", "SystemUIPlugin_wifi.AccessPoint", "chatty",
        "VehiclePlatform|RemoteHAL|Spi2Ethenet", "android.hardware.gnss@1.0-service",
    )

    private val threadtime = Regex(HEADER)
    // Matches the threadtime tag field, so a message that only mentions one of these words is kept.
    // Indented xcertplay-usb lines are iAP2 parameter dumps, which can quote text sent by the iPhone.
    private val noise = Regex(
        HEADER + "(" +
            "(teseo_hal_[^ :]*|hardware_radio_hw_[^ :]*|${silencedTags.joinToString("|") { Regex.escape(it) }}) *: |" +
            "ActivityManager *: request component permission|xcertplay-usb *: [ \\t])",
    )
    // Firmware lines that carry Wi-Fi network names, the iPhone's track and artwork, or the driver's district.
    private val personal = Regex(
        "ScanResult|WifiConfiguration|" + HEADER + "(" +
            "(SystemUILog|SystemUILog_NetPopupWindow|SystemUIPlugin_WifiTracker) *: .*(getName|config====)|" +
            "vdev[.]service[.][^ :]* *: .*setMessage|" +
            "WeatherAppWidgetProvider *: .*District)",
    )
    private val coordinate = Regex(
        "(?i)(?<![A-Za-z0-9_])((?:lat|lon|lng|latitude|longitude)\"?\\s*[:=]\\s*\"?)-?[0-9]+(?:\\.[0-9]+)?",
    )
    private val place = Regex("(?<![A-Za-z0-9_])(road|afterRoad|currentRoad|destination)=[^,)]*")
    // An underscore is a boundary too: firmware puts the VIN between underscores in update file names.
    private val vin = Regex(
        "(?<![A-Za-z0-9])(?=[A-HJ-NPR-Z0-9]{0,16}[A-HJ-NPR-Z])(?=[A-HJ-NPR-Z0-9]{0,16}[0-9])" +
            "[A-HJ-NPR-Z0-9]{17}(?![A-Za-z0-9])",
    )
    // Bluetooth addresses written without colons.
    private val bareAddress = Regex("(?i)(?<![0-9a-z])(?=[0-9a-f]{0,11}[0-9])[0-9a-f]{12}(?![0-9a-z])")
    private val logcatTime = Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}")
    private val shellPlain = Regex("[A-Za-z0-9_.:-]+")

    /** Keeps the newest exported lines that fit [maxBytes] in UTF-8, one newline each included. */
    internal class Collector(val maxBytes: Int) {
        /** [lastTime] is the newest logcat time read, [other] the first lines that are not logcat lines. */
        class Snapshot(val lines: List<String>, val dropped: Int, val logcatLines: Int, val lastTime: String?, val other: List<String>)

        private val kept = ArrayDeque<String>()
        private var keptBytes = 0L
        private var dropped = 0
        private var logcatLines = 0
        private var lastTime: String? = null
        private val other = mutableListOf<String>()
        private var finished = false

        @Synchronized fun offer(raw: String) {
            if (finished || raw.isBlank()) return
            val logcat = threadtime.containsMatchIn(raw)
            if (logcat) {
                logcatLines++
                lastTime = raw.take(18)
            }
            val line = exported(raw) ?: return
            if (!logcat && other.size < SU_OUTPUT_LINES) other += line
            kept.addLast(line)
            keptBytes += size(line)
            while (keptBytes > maxBytes) {
                keptBytes -= size(kept.removeFirst())
                dropped++
            }
        }

        /** Lines a reader thread still hands over after this are ignored. */
        @Synchronized fun finish(): Snapshot {
            finished = true
            return Snapshot(kept.toList(), dropped, logcatLines, lastTime, other.toList())
        }

        private fun size(line: String) = line.toByteArray(Charsets.UTF_8).size + 1
    }

    internal class Capture(val source: String, val lines: Collector.Snapshot, val maxBytes: Int, val notes: List<String>)

    /** Never throws: a failure becomes a note line in the section. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun appendTo(
        report: StringBuilder,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        root: (String, Long, (String) -> Unit) -> RootShell.Result = { command, timeout, onLine ->
            RootShell.stream(command, timeout, onLine)
        },
        app: (List<String>, Long, (String) -> Unit) -> RootShell.CommandOutput = RootShell::streamProcess,
        maxBytes: Int = MAX_BYTES,
    ) {
        val start = report.length
        try {
            render(report, capture(sinceTime(nowMillis, zone), root, app, maxBytes))
        } catch (error: Throwable) {
            report.setLength(start)
            report.append(MARKER).append("unavailable) ---\n")
                .append("System log unavailable: ").append(error.javaClass.simpleName).append('\n')
        }
    }

    /** The in-app viewer shows only the newest part of a report, so this section would hide the session logs. */
    fun withoutSection(report: String): String {
        if (report.startsWith(MARKER)) return ""
        val start = report.indexOf("\n$MARKER")
        return if (start < 0) report else report.substring(0, start + 1)
    }

    internal fun sinceTime(nowMillis: Long, zone: TimeZone): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
            .format(Date(nowMillis - WINDOW_MILLIS))

    internal fun isValidSince(value: String): Boolean = logcatTime.matches(value)

    internal fun appCommand(since: String): List<String> {
        require(isValidSince(since)) { "invalid logcat time" }
        return listOf("logcat", "-d", "-v", "threadtime", "-T", since) + silencedTags.map { "$it:S" }
    }

    internal fun rootCommand(since: String): String = appCommand(since).joinToString(" ") { argument ->
        if (shellPlain.matches(argument)) argument
        else "'$argument'".also { require(argument.none { it == '\'' }) { "quote in a logcat argument" } }
    }

    internal fun isNoise(line: String): Boolean = noise.containsMatchIn(line)

    /** Runs before [DiagnosticRedactor.redact], whose length cut could leave part of a value behind. */
    internal fun mask(line: String): String = line
        .replace(coordinate, "\$1[coord]")
        .replace(place, "\$1=[road]")
        .replace(vin, "[vin]")
        .replace(bareAddress, "[address]")

    /** The line as it goes into the report, or null when it is noise, personal or rejected by the redactor. */
    internal fun exported(line: String): String? =
        if (isNoise(line) || personal.containsMatchIn(line)) null else DiagnosticRedactor.redact(mask(line))

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun capture(
        since: String,
        root: (String, Long, (String) -> Unit) -> RootShell.Result,
        app: (List<String>, Long, (String) -> Unit) -> RootShell.CommandOutput,
        maxBytes: Int,
    ): Capture {
        val rootCollector = Collector(maxBytes)
        val rootResult = root(rootCommand(since), TIMEOUT_MILLIS, rootCollector::offer)
        val rootLines = rootCollector.finish()
        if (rootLines.logcatLines > 0) {
            return Capture("root", rootLines, maxBytes, listOfNotNull(rootEnd(rootResult, rootLines)))
        }
        val why = when (rootResult) {
            is RootShell.Result.Unavailable -> rootResult.reason
            is RootShell.Result.Completed ->
                if (RootShell.denied(rootResult.exitCode, rootLines.other.joinToString("\n"))) "root denied"
                else "su exited with ${rootResult.exitCode} and no log lines"
        }
        val suOutput = if (rootLines.other.isEmpty()) "" else rootLines.other.joinToString(" | ", prefix = "; su output: ")
        val notes = mutableListOf("Root not used: $why$suOutput")
        val appCollector = Collector(maxBytes)
        // Without READ_LOGS, Android gives an app only its own lines.
        val appResult = try {
            app(appCommand(since), TIMEOUT_MILLIS, appCollector::offer)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            notes += "logcat interrupted"
            null
        } catch (error: Exception) {
            notes += "logcat failed: ${error.javaClass.simpleName}"
            null
        }
        val appLines = appCollector.finish()
        appResult?.let { appEnd(it.exitCode, appLines) }?.let(notes::add)
        return Capture("app-only", appLines, maxBytes, notes)
    }

    private fun render(report: StringBuilder, capture: Capture) {
        report.append(MARKER).append(capture.source).append(") ---\n")
        capture.notes.forEach { report.append(it).append('\n') }
        val lines = capture.lines.lines
        if (capture.lines.dropped > 0) {
            val oldest = lines.firstOrNull()?.take(18)?.takeIf(::isValidSince) ?: "unknown"
            report.append("Dropped ${capture.lines.dropped} older lines over the ${capture.maxBytes}-byte limit; ")
                .append("oldest kept line at $oldest\n")
        }
        lines.forEach { report.append(it).append('\n') }
    }

    private fun rootEnd(result: RootShell.Result, lines: Collector.Snapshot): String? = when (result) {
        is RootShell.Result.Completed -> appEnd(result.exitCode, lines)?.let { "root $it" }
        is RootShell.Result.Unavailable ->
            if (result.reason == RootShell.TIMED_OUT_REASON) "root ${timedOut(lines)}"
            else "root logcat stopped (${result.reason}); ${missing(lines)}"
    }

    private fun appEnd(exitCode: Int, lines: Collector.Snapshot): String? = when (exitCode) {
        0 -> null
        RootShell.TIMED_OUT -> timedOut(lines)
        else -> "logcat exited with $exitCode"
    }

    // logcat -d prints the oldest lines first, so a cut-off run lacks the newest ones.
    private fun timedOut(lines: Collector.Snapshot) = "logcat timed out after ${TIMEOUT_MILLIS / 1_000} s; ${missing(lines)}"

    private fun missing(lines: Collector.Snapshot) =
        lines.lastTime?.let { "lines after $it are missing" } ?: "no log line was read"
}
