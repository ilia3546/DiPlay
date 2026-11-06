package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * CarPlay's cluster stream for the custom dashboard: sized as the map card, and decoded straight into the card's
 * texture through the running session's media sink, so it does not depend on which CarPlay screen, if any, is
 * open. Main thread.
 */
internal object DashboardMapStream {
    private const val STREAM_TYPE_ALT_SCREEN = 111

    /** The map card's size, rounded as the iPhone needs it; the card crops the rounding away. */
    val config: AirPlayDisplayConfig = CarPlayClusterDisplay.config(
        widthPixels = L.map.width,
        heightPixels = L.map.height ?: 0,
        scalePercent = 100,
        content = CarPlayClusterDisplay.Content.MAP,
        baseSafeArea = L.MAP_SAFE_AREA_PERCENT,
    )

    private var surface: Surface? = null
    private var sink: AndroidMediaSink? = null

    fun attach(next: Surface) {
        surface = next
        CarPlayBackgroundSession.snapshot()?.sink?.let(::reapply)
    }

    fun detach(old: Surface) {
        if (surface !== old) return
        sink?.clearSurface(STREAM_TYPE_ALT_SCREEN, old)
        surface = null
        sink = null
    }

    /** A CarPlay screen built [next] for a new session: the card's texture goes to it too. */
    fun reapply(next: AndroidMediaSink) {
        val current = surface ?: return
        sink = next
        next.setSurface(STREAM_TYPE_ALT_SCREEN, current)
        next.refreshPicture(STREAM_TYPE_ALT_SCREEN)
    }
}
