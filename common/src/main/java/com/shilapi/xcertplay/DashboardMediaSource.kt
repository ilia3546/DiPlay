package com.shilapi.xcertplay

/** What the head unit plays now, as the dashboard's media block can tell. */
internal enum class DashboardMediaSource {
    CARPLAY,
    RADIO,

    /** Bluetooth or USB music of the head unit: the block shows nothing. */
    OTHER,

    /** Nothing plays, or the signals disagree: the block shows nothing. */
    UNKNOWN;

    companion object {
        /**
         * CarPlay while DiPlay plays with the media focus, or holds the focus paused on a track and nothing else
         * plays; the radio while the firmware says it plays, the radio service does not deny it and DiPlay is
         * silent. More than one playing source is a contradiction. With the focus turned off in the settings,
         * CarPlay counts while it plays and nothing else does, and a paused CarPlay shows nothing.
         */
        /** As [of], but a paused radio stays shown after [previous] showed it, until CarPlay plays or another source does. */
        fun after(previous: DashboardMediaSource?, signals: DashboardMediaSignals): DashboardMediaSource {
            signals.carSource?.let { return ofMode(it, signals) }
            val next = of(signals)
            val radioHeld = previous == RADIO && next != OTHER && !signals.carPlayPlaying
            return if (radioHeld) RADIO else next
        }

        /**
         * With the option to pause CarPlay for the car's audio, the wheel's owner decides: the car's source while
         * it has the wheel, paused or not, and CarPlay's track otherwise, so a key the firmware also hands its radio
         * does not flip the block.
         */
        private fun ofMode(carSource: Boolean, signals: DashboardMediaSignals): DashboardMediaSource = with(signals) {
            when {
                !carSource -> if (carPlayTrack || carPlayPlaying) CARPLAY else UNKNOWN
                bluetoothPlaying || usbPlaying -> OTHER
                radioPlaying -> RADIO
                else -> UNKNOWN
            }
        }

        fun of(signals: DashboardMediaSignals): DashboardMediaSource = with(signals) {
            val carPlaySounding = (carPlayFocus || carPlayFocusDisabled) && carPlayPlaying
            // The firmware leaves its radio flag set after the radio pauses for another source; the radio service knows.
            val radioSounding = radioPlaying && radioServicePlaying != false
            val headUnitSources = listOf(radioSounding, bluetoothPlaying, usbPlaying).count { it }
            when {
                carPlaySounding -> if (headUnitSources == 0) CARPLAY else UNKNOWN
                headUnitSources > 1 -> UNKNOWN
                radioSounding -> RADIO
                bluetoothPlaying || usbPlaying -> OTHER
                carPlayFocus && carPlayTrack -> CARPLAY
                else -> UNKNOWN
            }
        }
    }
}

/**
 * The inputs of [DashboardMediaSource.of]: DiPlay's media focus, or whether the settings keep DiPlay from taking
 * it, and CarPlay's play state and track, and the firmware's `Settings.Global` play flags; [radioServicePlaying]
 * is the radio service's own answer, or null while DiPlay is not bound to it.
 */
internal data class DashboardMediaSignals(
    val carPlayFocus: Boolean = false,
    val carPlayFocusDisabled: Boolean = false,
    val carPlayPlaying: Boolean = false,
    val carPlayTrack: Boolean = false,
    val radioPlaying: Boolean = false,
    val bluetoothPlaying: Boolean = false,
    val usbPlaying: Boolean = false,
    val radioServicePlaying: Boolean? = null,
    val carSource: Boolean? = null,
)

internal object DashboardProgress {
    /** How far a CarPlay track is at [nowMillis], counted on from the iPhone's last position while it plays. */
    fun of(elapsedMillis: Long?, elapsedAt: Long, durationMillis: Long?, playing: Boolean, nowMillis: Long): Float? {
        val duration = durationMillis?.takeIf { it > 0 } ?: return null
        val elapsed = elapsedMillis ?: return null
        val position = elapsed + if (playing) (nowMillis - elapsedAt).coerceAtLeast(0) else 0
        return (position.coerceIn(0, duration).toDouble() / duration).toFloat()
    }
}
