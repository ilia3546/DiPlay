package com.shilapi.xcertplay.network

import java.util.Locale

/**
 * Disconnects every Bluetooth profile of the iPhone from the head unit through [PhoneBluetoothProfilesMain] as root.
 *
 * Some firmware answers each CarPlay play with an AVRCP pause to the iPhone while its Bluetooth profiles stay
 * connected; the car's own CarPlay disconnects them once its session starts. The framework call needs
 * BLUETOOTH_PRIVILEGED, which neither DiPlay nor the adb shell holds.
 */
object PhoneBluetoothProfiles {
    internal const val HEADER = "DIPLAY_PHONE_BT_V1"
    private val ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$", RegexOption.IGNORE_CASE)

    enum class Outcome { DISCONNECTED, REFUSED, UNSUPPORTED, NO_ADAPTER, FAILED, UNKNOWN }

    fun validAddress(address: String?): Boolean = address != null && ADDRESS.matches(address)

    /** Null unless [address] is a Bluetooth MAC, so nothing else reaches the root shell. */
    fun command(apk: String, address: String?): String? {
        if (address == null || !validAddress(address)) return null
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${PhoneBluetoothProfilesMain::class.java.name} " +
            address.uppercase(Locale.US)
    }

    /** Reads the helper's protocol line; other output, such as runtime warnings, is ignored. */
    fun parse(output: String): Outcome {
        val prefix = "$HEADER|"
        val line = output.lineSequence().map(String::trim).lastOrNull { it.startsWith(prefix) } ?: return Outcome.UNKNOWN
        return Outcome.entries.firstOrNull { it.name == line.removePrefix(prefix) } ?: Outcome.UNKNOWN
    }

    /** Blocks for up to [RootShell.DEFAULT_TIMEOUT_MILLIS]; returns a log-safe description without the address. */
    fun disconnect(apk: String, address: String?, run: (String) -> RootShell.Result = { RootShell.run(it) }): String {
        val command = command(apk, address) ?: return "skipped: no valid iPhone Bluetooth address"
        return when (val result = run(command)) {
            is RootShell.Result.Unavailable -> "failed: ${result.reason}"
            is RootShell.Result.Completed ->
                if (result.exitCode != 0) "failed: su exit ${result.exitCode}" else "result=${parse(result.stdout)}"
        }
    }
}
