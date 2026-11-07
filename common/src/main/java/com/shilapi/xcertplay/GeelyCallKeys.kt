package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.CarPlayCallCard

/**
 * The Geely wheel's previous and next keys during a CarPlay call with the Monjaro A3 calls option on, laid out like
 * the dashboard call card: the left key declines a ringing call or mutes one in progress, the right key answers or
 * ends it.
 */
internal object GeelyCallKeys {
    enum class Action { PASS, ANSWER, END, MUTE }

    fun action(index: Int, card: CarPlayCallCard?): Action = when (card?.phase) {
        null -> Action.PASS
        CarPlayCallCard.Phase.RINGING -> when (index) {
            CarPlayMediaButton.PREVIOUS -> Action.END
            CarPlayMediaButton.NEXT -> Action.ANSWER
            else -> Action.PASS
        }
        CarPlayCallCard.Phase.DIALING, CarPlayCallCard.Phase.ACTIVE -> when (index) {
            CarPlayMediaButton.PREVIOUS -> Action.MUTE
            CarPlayMediaButton.NEXT -> Action.END
            else -> Action.PASS
        }
    }

    /**
     * Gives the media key [index] to [call] when [enabled] and a [session] has a call for it, otherwise to [media].
     * Decided before [media], which may leave the key to the car's source: during a call the wheel belongs to it.
     */
    fun route(index: Int, enabled: Boolean, session: Boolean, card: CarPlayCallCard?, call: (Action) -> Unit,
        media: (Int) -> Unit) {
        val action = if (enabled && session) action(index, card) else Action.PASS
        if (action == Action.PASS) media(index) else call(action)
    }
}
