package com.shilapi.xcertplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.network.RootShell

/**
 * Starts one of DiPlay's screens on a display through `am start` as root. Firmware such as Desay's asks to confirm
 * when an app starts its own activity on one display while the app is on top of another, and then clears the app's
 * task on that other display: CarPlay on the driver's screen, or the film on the passenger screen. A start from the
 * shell is not checked.
 */
internal object RootActivityStart {
    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")
    private val EXTRAS = setOf(CarPlayVideo.EXTRA_GENERATION, CarPlayVideo.EXTRA_LAUNCH)
    private val main = Handler(Looper.getMainLooper())

    /** The `am start` command for [intent] on [displayId], or null when the intent carries more than `am` is given. */
    fun command(intent: Intent, displayId: Int): String? {
        if (displayId < 0) return null
        val component = intent.component?.flattenToString()?.takeIf(COMPONENT::matches) ?: return null
        val extras = intent.extras
        if (extras != null && !EXTRAS.containsAll(extras.keySet())) return null
        if (intent.action != null || intent.data != null || !intent.categories.isNullOrEmpty()) return null
        val longs = EXTRAS.filter { extras?.containsKey(it) == true }
            .joinToString("") { " --el $it ${intent.getLongExtra(it, -1L)}" }
        return "am start --display $displayId -f 0x${Integer.toHexString(intent.flags)} -n $component$longs"
    }

    /** Blocks until `am` answers; call off the main thread. */
    fun start(command: String, run: (String) -> RootShell.Result = { RootShell.run(it) }): Boolean {
        val result = run(command) as? RootShell.Result.Completed ?: return false
        return result.exitCode == 0 && !result.stdout.contains("Error")
    }

    /**
     * Runs [command] on a worker thread; [orElse] runs on the main thread when root did not start the screen. Errors
     * there do not reach the caller of this method.
     */
    fun startAsync(command: String, report: (started: Boolean, millis: Long) -> Unit, orElse: () -> Unit) {
        Thread({
            val began = SystemClock.elapsedRealtime()
            val started = start(command)
            report(started, SystemClock.elapsedRealtime() - began)
            if (!started) main.post(orElse)
        }, "root-activity-start").start()
    }
}
