package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.DashboardCallText
import com.shilapi.xcertplay.DashboardFormat
import java.util.Locale

/**
 * The call on the head-up display, as [HudDrawing] draws it by a green phone: [name] over [status], or [status] alone
 * when there is no name. Until the call connects ([phase]) a ring goes round the phone; once it has, [muted] adds a
 * crossed-out microphone after the time.
 */
data class HudCall(
    @JvmField val name: String,
    @JvmField val status: String,
    @JvmField val phase: CarPlayCallCard.Phase,
    @JvmField val muted: Boolean = false,
) {
    companion object {
        /**
         * The call in [calls] at [nowMillis], wall-clock time like the call's connect time; null without a call, when
         * the calls option does not act ([callsActive]), or unless the custom dashboard holds engineering mode
         * ([engineeringMode]), the only case in which firmware does not show its own call widget. [callsActive] and
         * [words] are only read for a call. The name is the other party's name, else their number; the status says
         * whether the call rings or is being dialed, or runs its time once connected.
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
            val name = listOf(card.name, card.number).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val status = when (card.phase) {
                CarPlayCallCard.Phase.RINGING -> words().incoming
                CarPlayCallCard.Phase.DIALING -> words().calling
                CarPlayCallCard.Phase.ACTIVE ->
                    DashboardFormat.callDuration(DashboardCallText.talkSeconds(card, nowMillis), locale)
            }
            return HudCall(name, status, card.phase, card.phase == CarPlayCallCard.Phase.ACTIVE && calls.muted == true)
        }
    }
}
