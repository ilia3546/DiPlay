package com.shilapi.xcertplay.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Process
import com.shilapi.xcertplay.network.PhoneBluetoothProfiles.Outcome
import java.util.Locale
import kotlin.system.exitProcess

/** Root entry point that [PhoneBluetoothProfiles] starts with app_process. Prints only the protocol line. */
object PhoneBluetoothProfilesMain {
    private const val DEADLINE_MS = 10_000L
    private const val ROOT_UID = 0

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "phone-bluetooth-helper-deadline")
            .apply { isDaemon = true; start() }
        val outcome = try { execute(args) } catch (_: Throwable) { Outcome.FAILED }
        println("${PhoneBluetoothProfiles.HEADER}|${outcome.name}")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): Outcome {
        require(Process.myUid() == ROOT_UID)
        require(args.size == 1 && PhoneBluetoothProfiles.validAddress(args[0]))
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return Outcome.NO_ADAPTER
        val device = adapter.getRemoteDevice(args[0].uppercase(Locale.US))
        // A system API since Android 11, so ordinary builds of the app cannot call it directly.
        val disconnect = try {
            BluetoothAdapter::class.java.getMethod("disconnectAllEnabledProfiles", BluetoothDevice::class.java)
        } catch (_: NoSuchMethodException) {
            return Outcome.UNSUPPORTED
        }
        return if (disconnect.invoke(adapter, device) == true) Outcome.DISCONNECTED else Outcome.REFUSED
    }
}
