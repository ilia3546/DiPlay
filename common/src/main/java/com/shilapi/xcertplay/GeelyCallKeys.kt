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
     * Gives the media key [index] to [call] when the option is [active] and [card] has a call for it, otherwise to
     * [media], or to [carBluetooth] with CarPlay audio on the car's [bluetoothAudio], whose own AVRCP already sends
     * the key to the iPhone. Decided before [media], which may leave the key to the car's source: during a call the
     * wheel belongs to it.
     */
    fun route(index: Int, active: Boolean, bluetoothAudio: Boolean, card: CarPlayCallCard?, call: (Action) -> Unit,
        media: (Int) -> Unit, carBluetooth: (Int) -> Unit) {
        val action = if (active) action(index, card) else Action.PASS
        when {
            action != Action.PASS -> call(action)
            bluetoothAudio -> carBluetooth(index)
            else -> media(index)
        }
    }
}
