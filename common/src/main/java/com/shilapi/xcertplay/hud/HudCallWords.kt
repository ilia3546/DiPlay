package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.host.R

/** The words of the HUD's call: for a caller without a name or number, a callee without one, and a muted microphone. */
data class HudCallWords(
    val incoming: String,
    val calling: String,
    val muted: String,
) {
    companion object {
        /** The words of [context]'s language; give it a context in the app language. */
        fun of(context: Context): HudCallWords = HudCallWords(
            incoming = context.getString(R.string.dashboard_call_incoming),
            calling = context.getString(R.string.dashboard_call_calling),
            muted = context.getString(R.string.dashboard_call_muted),
        )
    }
}
