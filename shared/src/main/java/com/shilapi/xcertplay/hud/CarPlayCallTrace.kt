package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList

/**
 * The report line for an iAP2 CallStateUpdate (0x4155) or CommunicationsUpdate (0x4158): the id and size of each
 * parameter, and the value of one-byte parameters only (status, direction, reason, mute), so caller names and
 * numbers never reach the log. A line that repeats the previous one of the same message is not logged again; for a
 * CommunicationsUpdate the signal strength (0) is left out of that comparison.
 */
internal class CarPlayCallTrace {
    private val last = HashMap<Int, String>()

    @Synchronized
    fun line(frame: Iap2Frame): String? {
        val name = when (frame.messageId) {
            CarPlayCallState.CALL_STATE_UPDATE -> "CallStateUpdate"
            CarPlayCallState.COMMUNICATIONS_UPDATE -> "CommunicationsUpdate"
            else -> return null
        }
        val parameters = runCatching { Iap2ParameterList.parse(frame.payload).asList() }.getOrNull()
        val line = "$name 0x${frame.messageId.toString(16)} " + describe(parameters, frame.payload.size)
        val compared = if (frame.messageId != CarPlayCallState.COMMUNICATIONS_UPDATE) line
        else describe(parameters?.filter { it.id != SIGNAL_STRENGTH }, frame.payload.size)
        if (last[frame.messageId] == compared) return null
        last[frame.messageId] = compared
        return line
    }

    private fun describe(parameters: List<Iap2Parameter>?, size: Int): String {
        if (parameters == null) return "malformed ${size}b"
        if (parameters.isEmpty()) return "empty"
        return parameters.joinToString(" ") { parameter ->
            val bytes = parameter.payload
            when (bytes.size) {
                0 -> "${parameter.id}:void"
                1 -> "${parameter.id}=${bytes[0].toInt() and 0xff}"
                else -> "${parameter.id}:${bytes.size}b"
            }
        }
    }

    private companion object {
        const val SIGNAL_STRENGTH = 0
    }
}
