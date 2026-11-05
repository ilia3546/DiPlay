package com.shilapi.xcertplay.network

import java.util.concurrent.TimeUnit

/** Runs one shell command as root through `su -c`. Callers must validate every value they put in the command. */
object RootShell {
    sealed interface Result {
        data class Completed(val exitCode: Int, val stdout: String) : Result
        data class Unavailable(val reason: String) : Result
    }

    class CommandOutput(val exitCode: Int, val stdout: String)

    const val TIMED_OUT = -1

    private val INTERFACE_NAME = Regex("[A-Za-z0-9_.-]{1,15}")

    // Covers a first-time root prompt, which waits for the driver's answer on the head unit.
    const val DEFAULT_TIMEOUT_MILLIS = 15_000L

    fun run(
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        exec: (List<String>, Long) -> CommandOutput = ::runProcess,
    ): Result {
        val output = try {
            exec(listOf("su", "-c", command), timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result.Unavailable("interrupted")
        } catch (error: Exception) {
            return Result.Unavailable("su unavailable (${error.javaClass.simpleName})")
        }
        if (output.exitCode == TIMED_OUT) return Result.Unavailable("su timed out")
        return Result.Completed(output.exitCode, output.stdout)
    }

    /** A Linux interface name that is safe to place in a command without quoting. */
    fun isSafeInterfaceName(name: String?): Boolean = name != null && INTERFACE_NAME.matches(name)

    private fun runProcess(command: List<String>, timeoutMillis: Long): CommandOutput {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return CommandOutput(TIMED_OUT, stdout = "")
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            return CommandOutput(process.exitValue(), stdout)
        } finally {
            process.destroy()
        }
    }
}
