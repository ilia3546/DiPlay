package com.shilapi.xcertplay.network

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Starts the saved hotspot with a firmware-advertised command or, when [start] gets a [helper], with the platform's own
 * tethering call run by the shell ([CarHotspotStartMain]); never configures a replacement AP.
 */
internal object CarHotspotAdbFallback {
    private const val EXIT = "DIPLAY_HOTSPOT_EXIT:"

    fun start(deadline: Long, cancelled: () -> Boolean, state: () -> Boolean?,
        shell: (String, Int) -> String?, log: (String) -> Unit, helper: String? = null): Boolean {
        val run = Run(deadline, cancelled, state, shell)
        if (run.stopped()) return false
        // No blind mutation when no AP state can be observed.
        val initial = run.observed() ?: return false.also { log("car hotspot: ADB cannot read the AP state; not starting it") }
        if (run.stopped()) return false
        if (initial) return true
        if (request(run, helper, log) != true) return false
        while (!run.stopped()) {
            if (run.observed() == true && !run.stopped()) {
                log("car hotspot: AP enabled state confirmed after local ADB request")
                return true
            }
            if (!run.pause()) return false
        }
        return false
    }

    enum class Outcome { ON, UNOBSERVABLE, STOPPED }

    /**
     * Like [start], for a shell that runs through root. An AP that Android reports [enabling] is awaited instead of
     * requested again, and a rejected request ([onRejected]) still waits for the AP until [deadline]: the helper also
     * rejects a start that is already under way.
     */
    fun startAndWait(deadline: Long, cancelled: () -> Boolean, state: () -> Boolean?, enabling: () -> Boolean,
        shell: (String, Int) -> String?, log: (String) -> Unit, helper: String, onRejected: () -> Unit): Outcome {
        val run = Run(deadline, cancelled, state, shell)
        if (run.stopped()) return Outcome.STOPPED
        val initial = run.observed()
            ?: return Outcome.UNOBSERVABLE.also { log("car hotspot: root cannot read the AP state; not starting it") }
        if (run.stopped()) return Outcome.STOPPED
        if (initial) return Outcome.ON
        var requested = false
        var awaitingStart = false
        var on: Boolean? = initial
        while (!run.stopped()) {
            if (on == true) {
                log("car hotspot: AP enabled state confirmed after the root request")
                return Outcome.ON
            }
            if (!requested && enabling()) {
                if (!awaitingStart) log("car hotspot: Android reports the AP starting; waiting for it instead of another request")
                awaitingStart = true
            } else if (!requested) {
                requested = true
                when (request(run, helper, log)) {
                    null -> return Outcome.STOPPED
                    false -> {
                        onRejected()
                        log("car hotspot: waiting for the AP until the deadline in case it is starting anyway")
                    }
                    true -> Unit
                }
            }
            if (!run.pause()) return Outcome.STOPPED
            on = run.observed()
        }
        return Outcome.STOPPED
    }

    /** True when the firmware command or [helper] accepted the start, false when it was rejected, null when stopped. */
    private fun request(run: Run, helper: String?, log: (String) -> Unit): Boolean? {
        var command: String? = null
        for (service in listOf("connectivity", "tethering")) {
            command = advertisedCommand(service, run.read("cmd $service help"))
            if (command != null) break
        }
        if (run.stopped()) return null
        if (command == null) {
            log("car hotspot: firmware lists no hotspot start command in cmd connectivity/tethering help")
            if (helper == null) return false
            // #616: an Android 10 head unit had no such command, and its app-side call was blocked.
            val output = run.read(helper)
            val reply = CarHotspotStartMain.parse(output)
            val accepted = CarHotspotStartMain.accepted(reply)
            // The helper may have started tethering although the attempt was cancelled meanwhile.
            if (output != null || !run.stopped()) {
                log(if (accepted) "car hotspot: shell tethering request $reply; waiting for AP enabled state"
                    else "car hotspot: shell tethering request failed: ${reply ?: "no reply"}")
            }
            if (run.stopped()) return null
            return accepted
        }
        val output = run.read("$command 2>&1; printf '\\n$EXIT%s\\n' \"\$?\"")
        if (run.stopped()) return null
        if (!accepted(output)) {
            log("car hotspot: firmware rejected '$command': " +
                output.orEmpty().lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(120))
            return false
        }
        log("car hotspot: firmware ADB start request accepted; waiting for AP enabled state")
        return true
    }

    private class Run(
        private val deadline: Long,
        private val cancelled: () -> Boolean,
        private val state: () -> Boolean?,
        private val shell: (String, Int) -> String?,
    ) {
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted || remaining() <= 0
        fun read(command: String): String? = if (stopped()) null else shell(command, remaining().coerceAtLeast(1))
        fun observed(): Boolean? = state() ?: apState(read("dumpsys wifi"))

        /** False when interrupted. */
        fun pause(): Boolean = try {
            Thread.sleep(minOf(250, remaining().coerceAtLeast(1)).toLong())
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** The PR's command forms are vendor extensions, not generic Android commands. */
    internal fun advertisedCommand(service: String, help: String?): String? {
        if (service !in listOf("connectivity", "tethering") || help == null || rejected(help)) return null
        for (line in help.lineSequence()) {
            val form = Regex("^\\s*(start-tethering|start)\\s+(wifi|[<\\[(][^>\\])]*\\bwifi\\b[^>\\])]*[>\\])])(?:\\s*\\[[^\\n]*])?\\s*$")
                .matchEntire(line) ?: continue
            val verb = form.groupValues[1]
            if (service == "connectivity" && verb != "start-tethering") continue
            return "cmd $service $verb wifi"
        }
        return null
    }

    internal fun accepted(output: String?): Boolean = output != null && !rejected(output) &&
        Regex("(?m)^$EXIT(\\d+)\\s*$").findAll(output).lastOrNull()?.groupValues?.get(1) == "0"

    private fun rejected(output: String): Boolean = Regex(
        "unknown command|permission denied|securityexception|(?:^|\\n)\\s*error:|failed|can't find service|not found|not supported|unsupported",
        RegexOption.IGNORE_CASE).containsMatchIn(output)

    /** Read current AP fields only, never a past log, station Wi-Fi state or an interface address. */
    internal fun apState(dump: String?): Boolean? {
        if (dump == null) return null
        val managers = dump.split(Regex("(?m)^\\s*(?:--)?Dump of SoftApManager(?:--| id=\\S+)?\\s*$")).drop(1)
        if (managers.isEmpty()) {
            val numeric = Regex("(?m)^\\s*(?:mWifiApState|mSoftApState|mTetheredSoftApState)\\s*[:=]\\s*(\\d+)\\s*$")
                .findAll(dump).map { CarHotspotStatus.stateEnabled(it.groupValues[1].toIntOrNull()) }.toList()
            return if (numeric.any { it == null }) null else numeric.distinct().singleOrNull()
        }
        val tethered = managers.filter { block ->
            val mode = Regex("(?m)^\\s*(?:mMode|mOriginalModeConfiguration\\.targetMode):\\s*(\\d+)\\s*$").find(block)?.groupValues?.get(1)
            mode == "1"
        }
        val states = tethered.map { block ->
            val current = Regex("(?m)^\\s*current StateMachine mode:\\s*(\\w+)\\s*$").find(block)?.groupValues?.get(1)
            val up = Regex("(?m)^\\s*mIfaceIsUp:\\s*(true|false)\\s*$").find(block)?.groupValues?.get(1)
            when {
                current == "StartedState" && up == "true" -> true
                current == "IdleState" && up == "false" -> false
                else -> null
            }
        }
        return if (states.any { it == null }) null else states.distinct().singleOrNull()
    }

    /** Deadline includes connection, capability reads, writes and AP-state polling. Cancellation aborts I/O. */
    internal fun bounded(deadline: Long, cancelled: () -> Boolean, abort: () -> Unit, action: () -> Boolean): Boolean {
        if (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return false
        val task = FutureTask(action)
        Thread(task, "diplay-hotspot-adb").apply { isDaemon = true; start() }
        try {
            while (!cancelled() && !Thread.currentThread().isInterrupted) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    return task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) { }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.ExecutionException) { }
        finally {
            if (!task.isDone) { abort(); task.cancel(true) }
        }
        return false
    }
}
