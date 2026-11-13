package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.VideoInCar.VideoScreens

/**
 * Whether the car's video player may play on the display it is on. Outside P the driver's display never plays: the
 * player there is blocked (paused under an opaque cover) while a screen the driver cannot watch may take it.
 */
internal object CarPlayVideoPlacement {
    enum class Verdict { PLAY, BLOCK, CLOSE }

    /** The player's move button: from the driver's display to the passenger screen, or back. */
    enum class Move { TO_PASSENGER, TO_DRIVER }

    /**
     * [landedOffTarget]: a new player started for the passenger screen opened somewhere else, so starting players
     * there does not work on this head unit.
     */
    fun verdict(actual: Int, driverDisplay: Int, screens: VideoScreens, passengerIdentity: Int?,
        passengerAvailable: Boolean, landedOffTarget: Boolean): Verdict = when {
        actual == passengerIdentity -> if (passengerAvailable) Verdict.PLAY else Verdict.CLOSE
        actual != driverDisplay -> Verdict.CLOSE
        screens.driverScreen -> Verdict.PLAY
        screens.passengerDisplayId != null && !landedOffTarget -> Verdict.BLOCK
        else -> Verdict.CLOSE
    }

    /** Why [verdict] closed the player, for the diagnostic report. */
    fun closeReason(actual: Int, driverDisplay: Int, passengerIdentity: Int?, landedOffTarget: Boolean): String = when {
        actual == passengerIdentity -> "passenger_screen_gone"
        actual != driverDisplay -> "other_display"
        landedOffTarget -> "passenger_launch_refused"
        else -> "not_allowed_here"
    }

    /** Where a new player opens, or null when no display may show it. */
    fun newPlayerTarget(driverDisplay: Int, screens: VideoScreens): Int? =
        if (screens.driverScreen) driverDisplay else screens.passengerDisplayId

    fun move(actual: Int, driverDisplay: Int, screens: VideoScreens, passengerIdentity: Int?): Move? = when {
        actual == driverDisplay && screens.passengerDisplayId != null && screens.passengerDisplayId != driverDisplay ->
            Move.TO_PASSENGER
        actual == passengerIdentity && actual != driverDisplay && screens.driverScreen -> Move.TO_DRIVER
        else -> null
    }
}
