package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInsets

/**
 * Where the custom dashboard draws, in pixels of the 1920x720 cluster display: design v2 with side blocks A.
 * The firmware draws the speed, warning lights and bottom line over the gaps between these blocks.
 */
internal object MonjaroDashboardLayout {
    const val WIDTH = MonjaroClusterDisplay.WIDTH
    const val HEIGHT = MonjaroClusterDisplay.HEIGHT

    /** A block's position; a block whose content sets its height has [height] null. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int? = null)

    val clock = Box(left = 160, top = 30, width = 300)
    const val CLOCK_TEXT = 34f
    const val CLOCK_LETTER_SPACING = 0.5f

    /** Anchored on its right edge, 150 px from the display's right. */
    val temperature = Box(left = WIDTH - 150 - 300, top = 30, width = 300)
    const val TEMPERATURE_TEXT = 32f

    val trip = Box(left = 210, top = 190, width = 400)
    const val TRIP_ROW_GAP = 30
    const val TRIP_LABEL_TEXT = 19f
    const val TRIP_LABEL_GAP = 4
    const val TRIP_VALUE_TEXT = 46f
    const val TRIP_UNIT_TEXT = 20f
    const val TRIP_UNIT_GAP = 8
    const val TRIP_HOURS_GAP_BEFORE = 5
    const val TRIP_HOURS_GAP_AFTER = 10
    const val TRIP_MINUTES_GAP = 5

    /**
     * The map's size, which the user picks in steps of 8 px so the iPhone streams exactly it. The map itself is
     * centred on the firmware's car, below its drive mode selector; the card's 1 px border lies around it, as CSS
     * content-box sizing puts it. The route cards sit in its top corners.
     */
    data class MapGeometry(val width: Int, val height: Int) {
        val map = Box(left = (WIDTH - width) / 2, top = MAP_CENTRE_Y - height / 2, width = width, height = height)

        /** [Box.width] is the widest the card grows beside the arrival card. */
        val maneuverCard = Box(
            left = map.left + ROUTE_CARD_INSET,
            top = map.top + ROUTE_CARD_INSET,
            width = width - 2 * ROUTE_CARD_INSET - ROUTE_CARD_GAP - ARRIVAL_WIDTH,
        )

        /** The widest the maneuver card grows while the arrival card is hidden. */
        val maneuverCardAloneWidth = maxOf(maneuverCard.width, width * MANEUVER_ALONE_PERCENT / 100)
        val arrivalCard = Box(
            left = map.left + width - ROUTE_CARD_INSET - ARRIVAL_WIDTH,
            top = map.top + ROUTE_CARD_INSET + 6,
            width = ARRIVAL_WIDTH,
        )
    }

    private const val MAP_CENTRE_Y = 440
    private const val MAP_STEP = 8
    val mapWidths = (448..800 step MAP_STEP).toList()
    val mapHeights = (200..480 step MAP_STEP).toList()
    val defaultMap = MapGeometry(width = 456, height = 304)

    const val MAP_BORDER = 1
    const val MAP_RADIUS = 28f
    const val MAP_SHADOW_DY = 18f
    const val MAP_SHADOW_BLUR = 50f

    /**
     * Where the iPhone keeps the car marker and route, in percent of the map: below the maneuver and arrival cards,
     * away from the sides.
     */
    val MAP_SAFE_AREA_PERCENT = AirPlayInsets(top = 30, bottom = 0, left = 8, right = 8)

    private const val ROUTE_CARD_INSET = 15
    private const val ROUTE_CARD_GAP = 12
    private const val ARRIVAL_WIDTH = 230
    private const val MANEUVER_ALONE_PERCENT = 60

    const val MANEUVER_PADDING_START = 10
    const val MANEUVER_PADDING_END = 12
    const val MANEUVER_PADDING_VERTICAL = 10
    const val MANEUVER_RADIUS = 20f
    const val MANEUVER_GAP = 10
    const val MANEUVER_ICON_SIZE = 50
    const val MANEUVER_ICON_RADIUS = 12f
    const val MANEUVER_GLYPH_SIZE = 30
    const val MANEUVER_DISTANCE_TEXT = 26f
    const val MANEUVER_ROAD_TEXT = 16f
    const val MANEUVER_ROAD_GAP = 2
    const val MANEUVER_SHADOW_DY = 10f
    const val MANEUVER_SHADOW_BLUR = 30f

    const val ARRIVAL_PADDING_HORIZONTAL = 20
    const val ARRIVAL_PADDING_VERTICAL = 12
    const val ARRIVAL_RADIUS = 18f
    const val ARRIVAL_GAP = 18
    const val ARRIVAL_VALUE_TEXT = 24f
    const val ARRIVAL_LABEL_TEXT = 14f

    /** The parked map: its blurred frame is the video scaled down by this factor, refreshed this often. */
    const val PARKED_BLUR_DOWNSCALE = 8
    const val PARKED_BLUR_REFRESH_MILLIS = 500L
    const val PARKED_FADE_MILLIS = 300L
    const val PARKED_DIM_CENTRE = 0x99000000.toInt()
    const val PARKED_DIM_EDGE = 0x26000000

    val media = Box(left = 1370, top = 200, width = 400)
    const val ARTWORK_SIZE = 200
    const val ARTWORK_RADIUS = 18f
    const val ARTWORK_SHADOW_DY = 16f
    const val ARTWORK_SHADOW_BLUR = 40f
    const val MEDIA_TITLE_TEXT = 30f
    const val MEDIA_TITLE_GAP = 26
    const val MEDIA_ARTIST_TEXT = 21f
    const val MEDIA_ARTIST_GAP = 4
    const val PROGRESS_WIDTH = 260
    const val PROGRESS_HEIGHT = 4
    const val PROGRESS_GAP = 22

    /** The call block of design v3 takes the media block's column; its ended card starts [CALL_ENDED_TOP] lower. */
    val call = Box(left = 1370, top = 192, width = 400)
    const val CALL_ENDED_TOP = 38
    const val CALL_AVATAR_SIZE = 150
    const val CALL_AVATAR_GRADIENT_DEGREES = 160.0
    const val CALL_AVATAR_SHADOW_DY = 16f
    const val CALL_AVATAR_SHADOW_BLUR = 40f
    const val CALL_INITIALS_TEXT = 52f
    const val CALL_TALKING_INITIALS_TEXT = 48f
    const val CALL_AVATAR_GLYPH_SIZE = 64
    const val CALL_RING_WIDTH = 3f
    const val CALL_RING_INSET = 5f
    const val CALL_PULSE_SCALE = 1.45f
    const val CALL_PULSE_ALPHA = 0.9f
    const val CALL_PULSE_MILLIS = 1_600L
    const val CALL_BADGE_SIZE = 48
    const val CALL_BADGE_BORDER = 4
    const val CALL_BADGE_GLYPH_SIZE = 24
    const val CALL_CAPTION_TEXT = 17f
    const val CALL_CAPTION_LETTER_SPACING = 2f
    const val CALL_CAPTION_GAP = 22
    const val CALL_NAME_TEXT = 30f
    const val CALL_RINGING_NAME_GAP = 6
    const val CALL_NAME_GAP = 24
    const val CALL_DETAIL_TEXT = 19f
    const val CALL_DETAIL_GAP = 2
    const val CALL_TIMER_TEXT = 24f
    const val CALL_TIMER_GAP = 6
    const val CALL_MUTED_TEXT = 15f
    const val CALL_MUTED_GAP = 10
    const val CALL_MUTED_PADDING_HORIZONTAL = 10
    const val CALL_MUTED_PADDING_VERTICAL = 3
    const val CALL_MUTED_RADIUS = 8f
    const val CALL_OUTCOME_TEXT = 21f
    const val CALL_OUTCOME_GAP = 6
    const val CALL_RINGING_BUTTONS_GAP = 22
    const val CALL_BUTTONS_GAP = 26
    const val CALL_BUTTON_SIZE = 76
    const val CALL_BUTTON_SPACING = 64
    const val CALL_BUTTON_BORDER = 1f
    const val CALL_BUTTON_SHADOW_DY = 10f
    const val CALL_BUTTON_SHADOW_BLUR = 26f
    const val CALL_BUTTON_LABEL_GAP = 8
    const val CALL_BUTTON_LABEL_TEXT = 16f
    const val CALL_END_GLYPH_SIZE = 38
    const val CALL_ANSWER_GLYPH_SIZE = 36
    const val CALL_MICROPHONE_GLYPH_SIZE = 34

    const val TEXT_SHADOW_DY = 1f
    const val TEXT_SHADOW_BLUR = 10f

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TITLE_MUTED = 0xFF9AA6B6.toInt()
    const val LABEL = 0xFFB9C3D0.toInt()
    const val SECONDARY = 0xFFC9D2DE.toInt()
    const val MAP_EMPTY = 0xFF121821.toInt()
    const val ROUTE_CARD = 0xE610141B.toInt()
    const val ROUTE_CARD_SHADOW = 0x66000000
    const val MANEUVER_ICON = 0xFF2F7BFF.toInt()
    const val MAP_BORDER_COLOR = 0x24FFFFFF
    const val TEXT_SHADOW = 0x73000000
    const val MAP_SHADOW = 0x73000000
    const val ARTWORK_SHADOW = 0x8C000000.toInt()
    const val PROGRESS_TRACK = 0x38FFFFFF
    const val CALL_AVATAR_TOP = 0xFF5A6B85.toInt()
    const val CALL_AVATAR_BOTTOM = 0xFF2C3647.toInt()
    const val CALL_AVATAR_SHADOW = 0x8C000000.toInt()
    const val CALL_ENDED_AVATAR_TOP = 0xFF3D4656.toInt()
    const val CALL_ENDED_AVATAR_BOTTOM = 0xFF232A35.toInt()
    const val CALL_ENDED_INITIALS = 0x8CFFFFFF.toInt()
    const val CALL_ENDED_NAME = 0xFFD7DDE6.toInt()
    const val CALL_BADGE_BORDER_COLOR = 0xFF11151C.toInt()
    const val CALL_CAPTION = 0xFF3DDC5A.toInt()
    const val CALL_GREEN = 0xFF30D158.toInt()
    const val CALL_GREEN_SHADOW = 0x5930D158
    const val CALL_RED = 0xFFFF3B30.toInt()
    const val CALL_RED_SHADOW = 0x59FF3B30
    const val CALL_OUTCOME = 0xFFFF6961.toInt()
    const val CALL_MUTED = 0xFFFF9F0A.toInt()
    const val CALL_MUTED_BACKGROUND = 0x26FF9F0A
    const val CALL_BUTTON_IDLE = 0x1AFFFFFF
    const val CALL_BUTTON_BORDER_COLOR = 0x38FFFFFF
    const val CALL_MUTED_GLYPH = 0xFF0C0F14.toInt()
}
