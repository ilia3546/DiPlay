package com.shilapi.xcertplay.hud

import kotlin.system.exitProcess

/** Root entry point that [GeelyCabinLan] starts with app_process; reads the message from stdin. */
object GeelyCabinLanMain {
    internal const val HEADER = "DIPLAY_CABIN_LAN_V1"
    private const val DEADLINE_MS = 10_000L
    private const val MAX_MESSAGE_BYTES = 256 * 1024

    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running.
        Thread({ Thread.sleep(DEADLINE_MS); exitProcess(2) }, "cabin-lan-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try {
            require(args.size == 2)
            val json = System.`in`.readNBytesCompat(MAX_MESSAGE_BYTES).toString(Charsets.UTF_8)
            GeelyCabinLan.sendDirect(args[0].toInt(), args[1].toInt(), json)
            "SENT"
        } catch (error: Throwable) {
            "FAILED ${((error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error).javaClass.simpleName}"
        }
        println("$HEADER|$result")
        exitProcess(0)
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= limit) {
            val count = read(buffer)
            if (count < 0) return out.toByteArray()
            out.write(buffer, 0, count)
        }
        throw IllegalArgumentException("message too large")
    }
}
