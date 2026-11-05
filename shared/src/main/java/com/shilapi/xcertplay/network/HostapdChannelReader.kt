package com.shilapi.xcertplay.network

/**
 * Reads the live SoftAP channel from the vendor hostapd configuration through `su`.
 *
 * Some firmware reports SoftApInfo frequency 0 and hides SoftApConfiguration, so hostapd's own
 * file is the only observed source. Only the `channel=` line leaves the shell: the same file holds
 * the hotspot passphrase.
 */
object HostapdChannelReader {
    sealed interface Result {
        data class Found(val channel: Int) : Result
        data class Unavailable(val reason: String) : Result
    }

    private const val CONFIG_DIR = "/data/vendor/wifi/hostapd"

    fun read(
        interfaceName: String?,
        run: (String) -> RootShell.Result = { RootShell.run(it) },
    ): Result {
        if (!RootShell.isSafeInterfaceName(interfaceName)) {
            return Result.Unavailable("invalid interface name")
        }
        return when (val output = run("grep -E '^channel=' $CONFIG_DIR/hostapd_$interfaceName.conf")) {
            is RootShell.Result.Unavailable -> Result.Unavailable(output.reason)
            is RootShell.Result.Completed -> when {
                output.exitCode != 0 -> Result.Unavailable("su exit ${output.exitCode}")
                else -> parseChannel(output.stdout)?.let(Result::Found) ?: Result.Unavailable("no channel line")
            }
        }
    }

    fun parseChannel(stdout: String): Int? = stdout.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("channel=") }
        ?.removePrefix("channel=")
        ?.toIntOrNull()
        ?.takeIf { it in 1..196 }
}
