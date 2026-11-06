package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear

/**
 * Whether the car stands, for the parked map: in P or N, below [STANDING_KMH] for [STANDING_HOLD_MILLIS], or
 * without a speed read in the last [FRESH_MILLIS]; it drives from [DRIVING_KMH]. Between the two speeds the last
 * answer holds, so the map does not switch back and forth near either of them.
 */
internal class DashboardParking {
    var parked = true
        private set
    private var slowSince: Long? = null

    fun next(motion: EcarxWheelSpeed.Motion?, nowMillis: Long): Boolean {
        val fresh = motion?.takeIf { nowMillis - it.elapsedMillis <= FRESH_MILLIS }
        val kmh = fresh?.metersPerSecond?.times(3.6)
        val gear = fresh?.gear
        if (kmh == null || gear == VehicleGear.PARK || gear == VehicleGear.NEUTRAL) {
            slowSince = null
            parked = true
        } else if (kmh >= DRIVING_KMH) {
            slowSince = null
            parked = false
        } else if (kmh < STANDING_KMH) {
            val since = slowSince ?: nowMillis.also { slowSince = it }
            if (nowMillis - since >= STANDING_HOLD_MILLIS) parked = true
        } else {
            slowSince = null
        }
        return parked
    }

    companion object {
        const val STANDING_KMH = 1.0
        const val DRIVING_KMH = 3.0
        const val STANDING_HOLD_MILLIS = 2_000L
        const val FRESH_MILLIS = 1_000L
    }
}
