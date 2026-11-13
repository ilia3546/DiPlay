package com.shilapi.xcertplay.network

import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Runs one shell command as root through `su -c`. Callers must validate every value they put in the command. */
object RootShell {
    sealed interface Result {
        data class Completed(val exitCode: Int, val stdout: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    class CommandOutput(val exitCode: Int, val stdout: String)

    const val TIMED_OUT = -1
    const val TIMED_OUT_REASON = "su timed out"
    const val INTERRUPTED_REASON = "interrupted"

    private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")

    // Covers a first-time root prompt, which waits for the driver's answer on the head unit.
    const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    private const val SHELL_UID = "2000"
    private const val OUTPUT_DRAIN_MILLIS = 1_000L

    fun run(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        exec: (List<String>, Long) -> CommandOutput = ::runProcess,
    ): Result = complete { exec(listOf("su", "-c", command), timeoutMillis) }

    /** Like [run], as the shell user (uid 2000), which is what ADB runs as. */
    fun runAsShell(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        exec: (List<String>, Long) -> CommandOutput = ::runProcess,
    ): Result = complete { exec(listOf("su", SHELL_UID, "-c", command), timeoutMillis) }

    /** Like [run], with [stdin] written to the command: for payloads too large for an argument. */
    fun runWithInput(
        command: String,
        stdin: ByteArray,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        exec: (List<String>, Long, ByteArray) -> CommandOutput = ::runProcess,
    ): Result = complete { exec(listOf("su", "-c", command), timeoutMillis, stdin) }

    /** Like [run], handing each output line to [onLine] as it arrives; a timeout keeps the lines already handed over. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun stream(
        command: String,
        timeoutMillis: Long,
        onLine: (String) -> Unit,
        exec: (List<String>, Long, (String) -> Unit) -> CommandOutput = ::streamProcess,
    ): Result = complete { exec(listOf("su", "-c", command), timeoutMillis, onLine) }

    /** Like [stream], for [command] run as this app without su. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun streamProcess(command: List<String>, timeoutMillis: Long, onLine: (String) -> Unit): CommandOutput =
        executeStreaming(start(command), timeoutMillis, onLine)

    // Magisk's su prints only this line and exits non-zero when root is denied to the app.
    fun denied(exitCode: Int, output: String): Boolean =
        exitCode != 0 && output.trim().lowercase().removePrefix("su: ") == "permission denied"

    private inline fun complete(exec: () -> CommandOutput): Result {
        val output = try {
            exec()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Unavailable(INTERRUPTED_REASON)
        } catch (error: Exception) {
            return Result.Unavailable("su unavailable (${error.javaClass.simpleName})")
        }
        if (output.exitCode == TIMED_OUT) return Result.Unavailable(TIMED_OUT_REASON)
        return Result.Completed(output.exitCode, output.stdout)
    }

    /** A Linux interface name that is safe to place in a command without quoting. */
    fun isSafeInterfaceName(name: String?): Boolean = name != null && INTERFACE_NAME.matches(name)

    private fun runProcess(command: List<String>, timeoutMillis: Long): CommandOutput =
        execute(start(command), timeoutMillis) { it.close() }

    private fun runProcess(command: List<String>, timeoutMillis: Long, stdin: ByteArray): CommandOutput =
        execute(start(command), timeoutMillis) { input ->
            // A child that never reads stdin must not hold this thread past the timeout.
            Thread({ runCatching { input.use { it.write(stdin) } } }, "root-shell-stdin")
                .apply { isDaemon = true; start() }
        }

    private fun start(command: List<String>): Process = ProcessBuilder(command).redirectErrorStream(true).start()

    internal fun execute(process: Process, timeoutMillis: Long, feed: (OutputStream) -> Unit): CommandOutput {
        val output = ByteArrayOutputStream()
        val exitCode = await(process, timeoutMillis, feed, streaming = false) { input ->
            val chunk = ByteArray(8_192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                synchronized(output) { output.write(chunk, 0, read) }
            }
        }
        if (exitCode == TIMED_OUT) return CommandOutput(TIMED_OUT, stdout = "")
        return CommandOutput(exitCode, synchronized(output) { output.toString(Charsets.UTF_8.name()) })
    }

    @RequiresApi(Build.VERSION_CODES.O)
    internal fun executeStreaming(process: Process, timeoutMillis: Long, onLine: (String) -> Unit): CommandOutput =
        CommandOutput(await(process, timeoutMillis, { it.close() }, streaming = true) { input ->
            input.bufferedReader(Charsets.UTF_8).forEachLine(onLine)
        }, stdout = "")

    /**
     * Reads the output while the command runs: a child that fills the pipe would otherwise never exit.
     * A [streaming] caller has nothing without the reader, so it waits for the reader until the deadline
     * and, on a timeout, still lets the reader hand over what the pipe holds.
     */
    private fun await(
        process: Process,
        timeoutMillis: Long,
        feed: (OutputStream) -> Unit,
        streaming: Boolean,
        read: (InputStream) -> Unit,
    ): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            val reader = Thread({ runCatching { process.inputStream.use(read) } }, "root-shell-stdout")
                .apply { isDaemon = true; start() }
            feed(process.outputStream)
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                if (streaming) {
                    process.destroy()
                    reader.join(OUTPUT_DRAIN_MILLIS)
                }
                return TIMED_OUT
            }
            if (!streaming) {
                // A background child can keep the pipe open after the command exits.
                reader.join(OUTPUT_DRAIN_MILLIS)
                return process.exitValue()
            }
            reader.join(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1))
            return if (reader.isAlive) TIMED_OUT else process.exitValue()
        } finally {
            process.destroy()
        }
    }
}
