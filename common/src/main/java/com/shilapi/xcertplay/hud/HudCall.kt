package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.DashboardCallText
import com.shilapi.xcertplay.DashboardFormat
import java.util.Locale

/** The call on the head-up display, as [HudDrawing] draws it: [text] by a green phone, then [mutedLabel] unless empty. */
data class HudCall(
    @JvmField val text: String,
    @JvmField val mutedLabel: String,
) {
    companion object {
        /**
         * The call in [calls] at [nowMillis], wall-clock time like the call's connect time; null without a call, when
         * the calls option does not act ([callsActive]), or unless the custom dashboard holds engineering mode
         * ([engineeringMode]), the only case in which firmware does not show its own call widget. [callsActive] and
         * [words] are only read for a call. A ringing call shows the caller, a dialed one the callee, a connected
         * one its time and, once the iPhone confirmed it, the microphone being off.
         */
        fun of(
            calls: CarPlayCallSnapshot,
            engineeringMode: Boolean,
            callsActive: () -> Boolean,
            nowMillis: Long,
            words: () -> HudCallWords,
            locale: Locale = Locale.getDefault(),
        ): HudCall? {
            val card = calls.card ?: return null
            if (!engineeringMode || !callsActive()) return null
            val caller = card.name.ifBlank { card.number }
            val text = when (card.phase) {
                CarPlayCallCard.Phase.RINGING -> caller.ifBlank { words().incoming }
                CarPlayCallCard.Phase.DIALING -> caller.ifBlank { words().calling }
                CarPlayCallCard.Phase.ACTIVE ->
                    DashboardFormat.callDuration(DashboardCallText.talkSeconds(card, nowMillis), locale)
            }
            val muted = card.phase != CarPlayCallCard.Phase.RINGING && calls.muted == true
            return HudCall(text, if (muted) words().muted else "")
        }
    }
}
