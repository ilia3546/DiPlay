package com.shilapi.xcertplay.hud

import android.os.SystemClock
import com.shilapi.xcertplay.network.RootShell

/**
 * Sends CabinLAN common messages to the Desay SV head unit's QNX side, the channel its own dashboard
 * service uses. The `com.desaysv.ivi.vdb` classes are on the head unit's boot class path only, so every
 * call is by reflection.
 */
internal object GeelyCabinLan {
    const val MUSIC = 0x1001
    const val PHONE = 0x1003
    const val ENGINEERING_MODE = 0x1005
    const val ENGINEERING_MODE_SWITCH = 0x10
    private const val CABIN_LAN_MSG_COMMON = 1114113
    private const val SERVICE = "com.desaysv.ivi.vds.cabinlan.service.CabinLanService"
    private val HELPER_FAILURE = Regex("FAILED [A-Za-z0-9_$]{1,80}")

    /** Throws whatever the platform throws: no service, a refused lookup, a missing class or a failed set. */
    fun sendDirect(msgType: Int, subtype: Int, json: String) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, SERVICE) ?: throw IllegalStateException("CabinLAN service not registered")
        val service = Class.forName("com.desaysv.ivi.vdb.IVDBus\$Stub")
            .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder)
            ?: throw IllegalStateException("CabinLAN service has no VDBus interface")
        val messageClass = Class.forName("com.desaysv.ivi.vdb.event.id.cabin.bean.VDCLCommonMessage")
        val message = messageClass.getConstructor().newInstance()
        messageClass.getMethod("setMsgType", Int::class.javaPrimitiveType).invoke(message, msgType)
        messageClass.getMethod("setSubtype", Int::class.javaPrimitiveType).invoke(message, subtype)
        messageClass.getMethod("setMessage", String::class.java).invoke(message, json)
        val event = messageClass.getMethod("createGetEvent", Int::class.javaPrimitiveType, messageClass)
            .invoke(null, CABIN_LAN_MSG_COMMON, message)
        service.javaClass.getMethod("set", Class.forName("com.desaysv.ivi.vdb.event.VDEvent")).invoke(service, event)
    }

    /** The message itself goes through stdin: a cover is far too large for an argument. */
    fun rootCommand(apk: String, msgType: Int, subtype: Int): String {
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        return "CLASSPATH=$quoted app_process /system/bin ${GeelyCabinLanMain::class.java.name} $msgType $subtype"
    }

    /**
     * Null when the helper sent the message, otherwise why not. The helper's protocol line decides; other
     * output, such as runtime warnings, is ignored.
     */
    fun rootFailure(result: RootShell.Result): String? {
        if (result is RootShell.Result.Unavailable) return result.reason
        val completed = result as RootShell.Result.Completed
        if (completed.exitCode != 0) return "exit ${completed.exitCode}"
        val reply = completed.stdout.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("${GeelyCabinLanMain.HEADER}|") }?.substringAfter('|')
        return when {
            reply == "SENT" -> null
            reply != null && HELPER_FAILURE.matches(reply) -> reply
            else -> "no helper reply"
        }
    }

    /**
     * Tries the app's own process for every message and the root helper for one the app process could
     * not send. A [SecurityException] there is the head unit's policy, so the app process is skipped
     * until [DIRECT_RETRY_MILLIS] later; a root helper that failed [MAX_ROOT_FAILURES] times in a row
     * is not started again until [newSession]. [log] hears each change of transport or failure reason,
     * never the message.
     */
    class Sender(
        private val direct: (Int, Int, String) -> Unit,
        /** Returns what [rootFailure] returns. */
        private val root: (Int, Int, String) -> String?,
        private val log: (String) -> Unit,
        private val now: () -> Long = SystemClock::elapsedRealtime,
    ) {
        private var refusedAt: Long? = null
        private var directFailure: String? = null
        private var rootFailures = 0
        private var rootFailure: String? = null
        private var transport: String? = null

        @Synchronized
        fun newSession() {
            rootFailures = 0
        }

        @Synchronized
        fun send(msgType: Int, subtype: Int, json: String): Boolean = attempt(msgType, subtype, json) == Outcome.SENT

        /** Like [send], telling a message that surely did not leave from one that may have. */
        @Synchronized
        fun attempt(msgType: Int, subtype: Int, json: String): Outcome {
            val directSkipped = refusedAt?.let { now() - it < DIRECT_RETRY_MILLIS } == true
            if (sendWithoutRoot(msgType, subtype, json)) return Outcome.SENT
            if (rootFailures >= MAX_ROOT_FAILURES) return if (directSkipped) Outcome.NOT_ATTEMPTED else Outcome.FAILED_KNOWN
            val failure = root(msgType, subtype, json)
            if (failure == null) {
                rootFailures = 0
                rootFailure = null
                announce("root helper")
                return Outcome.SENT
            }
            rootFailures += 1
            if (failure != rootFailure) {
                rootFailure = failure
                log("dashboard CabinLAN root helper failed ($failure)")
            }
            if (rootFailures == MAX_ROOT_FAILURES) {
                log("dashboard CabinLAN root helper failed $MAX_ROOT_FAILURES times in a row; stopped until the next session")
            }
            return if (HELPER_FAILURE.matches(failure)) Outcome.FAILED_KNOWN else Outcome.UNKNOWN
        }

        /** The app process only: for repeats of a message that is already shown, which must not start su. */
        @Synchronized
        fun sendWithoutRoot(msgType: Int, subtype: Int, json: String): Boolean {
            refusedAt?.let { if (now() - it < DIRECT_RETRY_MILLIS) return false }
            try {
                direct(msgType, subtype, json)
            } catch (error: Throwable) {
                val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                if (cause is SecurityException) refusedAt = now()
                val reason = cause.javaClass.simpleName
                if (reason != directFailure) {
                    directFailure = reason
                    log("dashboard CabinLAN refused in the app process ($reason)")
                }
                return false
            }
            refusedAt = null
            directFailure = null
            announce("app process")
            return true
        }

        private fun announce(next: String) {
            if (transport == next) return
            transport = next
            log("dashboard CabinLAN sends through the $next")
        }

        enum class Outcome {
            SENT,

            /** The app process is skipped for now and the root helper is stopped for this session. */
            NOT_ATTEMPTED,

            /** The app process refused it, and the root helper was stopped or answered that it failed. */
            FAILED_KNOWN,

            /** The root helper timed out, failed to start or ended without its answer: it may have sent it. */
            UNKNOWN,
        }

        companion object {
            const val DIRECT_RETRY_MILLIS = 5 * 60_000L
            const val MAX_ROOT_FAILURES = 3
        }
    }
}
