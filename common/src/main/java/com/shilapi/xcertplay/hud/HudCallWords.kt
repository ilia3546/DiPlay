package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.host.R

/** The words of the HUD's call: the status of a ringing call and of one being dialed. */
data class HudCallWords(
    val incoming: String,
    val calling: String,
) {
    companion object {
        /** The words of [context]'s language; give it a context in the app language. */
        fun of(context: Context): HudCallWords = HudCallWords(
            incoming = context.getString(R.string.dashboard_call_incoming),
            calling = context.getString(R.string.dashboard_call_calling),
        )
    }
}
