package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.network.RootShell

/**
 * Sends CabinLAN common messages to the Desay SV head unit's QNX side, the channel its own dashboard
 * service uses. The `com.desaysv.ivi.vdb` classes are on the head unit's boot class path only, so every
 * call is by reflection.
 */
internal object GeelyCabinLan {
    const val MUSIC = 0x1001
    private const val CABIN_LAN_MSG_COMMON = 1114113
    private const val SERVICE = "com.desaysv.ivi.vds.cabinlan.service.CabinLanService"

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

    /** The helper's protocol line decides; other output, such as runtime warnings, is ignored. */
    fun rootSent(result: RootShell.Result): Boolean = result is RootShell.Result.Completed && result.exitCode == 0 &&
        result.stdout.lineSequence().map(String::trim).any { it == "${GeelyCabinLanMain.HEADER}|SENT" }

    /**
     * Tries the app's own process first and, once that is refused, the root helper for the rest of the
     * process. [log] hears the transport once and each refusal reason, never the message.
     */
    class Sender(
        private val direct: (Int, Int, String) -> Unit,
        private val root: (Int, Int, String) -> Boolean,
        private val log: (String) -> Unit,
    ) {
        private var viaRoot = false
        private var announced = false

        @Synchronized
        fun send(msgType: Int, subtype: Int, json: String): Boolean {
            if (!viaRoot) {
                try {
                    direct(msgType, subtype, json)
                    announce("app process")
                    return true
                } catch (error: Throwable) {
                    val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                    log("dashboard CabinLAN refused in the app process (${cause.javaClass.simpleName}); using root")
                    viaRoot = true
                    announced = false
                }
            }
            val sent = root(msgType, subtype, json)
            if (sent) announce("root helper") else log("dashboard CabinLAN root helper failed")
            return sent
        }

        private fun announce(transport: String) {
            if (announced) return
            announced = true
            log("dashboard CabinLAN sends through the $transport")
        }
    }
}
