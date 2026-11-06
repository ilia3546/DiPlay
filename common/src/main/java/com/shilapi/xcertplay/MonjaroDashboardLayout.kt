package com.shilapi.xcertplay

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

    val trip = Box(left = 150, top = 200, width = 400)
    const val TRIP_TITLE_TEXT = 17f
    const val TRIP_TITLE_LETTER_SPACING = 2f
    const val TRIP_ROW_GAP = 30
    const val TRIP_LABEL_TEXT = 19f
    const val TRIP_LABEL_GAP = 4
    const val TRIP_VALUE_TEXT = 46f
    const val TRIP_UNIT_TEXT = 20f
    const val TRIP_UNIT_GAP = 8
    const val TRIP_HOURS_GAP_BEFORE = 5
    const val TRIP_HOURS_GAP_AFTER = 10
    const val TRIP_MINUTES_GAP = 5

    /** The map itself; the card's 1 px border lies around it, as CSS content-box sizing puts it. */
    val map = Box(left = 611, top = 187, width = 700, height = 404)
    const val MAP_BORDER = 1
    const val MAP_RADIUS = 28f
    const val MAP_SHADOW_DY = 18f
    const val MAP_SHADOW_BLUR = 50f

    /** Over the map, in display pixels; drawn by the map stage. */
    val maneuverCard = Box(left = 626, top = 202, width = 330)
    val arrivalCard = Box(left = WIDTH - 626 - 230, top = 208, width = 230)

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

    const val TEXT_SHADOW_DY = 1f
    const val TEXT_SHADOW_BLUR = 10f

    const val TEXT = 0xFFFFFFFF.toInt()
    const val TITLE_MUTED = 0xFF9AA6B6.toInt()
    const val LABEL = 0xFFB9C3D0.toInt()
    const val SECONDARY = 0xFFC9D2DE.toInt()
    const val MAP_EMPTY = 0xFF121821.toInt()
    const val MAP_BORDER_COLOR = 0x24FFFFFF
    const val TEXT_SHADOW = 0x73000000
    const val MAP_SHADOW = 0x73000000
    const val ARTWORK_SHADOW = 0x8C000000.toInt()
    const val PROGRESS_TRACK = 0x38FFFFFF
}
