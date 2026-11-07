package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.MonjaroDashboardLayout as L
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink

/**
 * CarPlay's cluster stream for the custom dashboard: sized as the map card, and decoded straight into the card's
 * texture through the running session's media sink, which also tells when the stream runs, so neither depends on
 * which CarPlay screen, if any, is open. Main thread.
 */
internal object DashboardMapStream {
    private const val STREAM_TYPE_ALT_SCREEN = 111

    /** The map card's size, rounded as the iPhone needs it; the card crops the rounding away. */
    fun config(map: L.MapGeometry): AirPlayDisplayConfig = CarPlayClusterDisplay.config(
        widthPixels = map.width,
        heightPixels = map.height,
        scalePercent = 100,
        content = CarPlayClusterDisplay.Content.MAP,
        baseSafeArea = L.MAP_SAFE_AREA_PERCENT,
    )

    /** What the running session asked the iPhone for: the stream keeps this size until the next connection. */
    @Volatile var requested: AirPlayDisplayConfig = config(L.defaultMap)
        private set

    fun request(map: L.MapGeometry): AirPlayDisplayConfig = config(map).also { requested = it }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var surface: Surface? = null
    private var sink: AndroidMediaSink? = null
    private var followed: AndroidMediaSink? = null
    private var stopFollowing: (() -> Unit)? = null
    private val streamListeners = mutableSetOf<(Boolean) -> Unit>()
    private var streamActive = false

    /** Calls [listener] with whether the stream runs now and after each change, until the returned function runs. */
    fun observeStream(listener: (Boolean) -> Unit): () -> Unit {
        streamListeners.add(listener)
        listener(streamActive)
        return { streamListeners.remove(listener) }
    }

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
        follow(next)
        val current = surface ?: return
        sink = next
        next.setSurface(STREAM_TYPE_ALT_SCREEN, current)
        next.refreshPicture(STREAM_TYPE_ALT_SCREEN)
    }

    private fun follow(next: AndroidMediaSink) {
        if (followed === next) return
        stopFollowing?.invoke()
        followed = next
        streamChanged(false)
        stopFollowing = next.observeScreenStreams { type, active ->
            if (type == STREAM_TYPE_ALT_SCREEN) main.post { if (followed === next) streamChanged(active) }
        }
    }

    private fun streamChanged(active: Boolean) {
        if (active == streamActive) return
        streamActive = active
        streamListeners.toList().forEach { it(active) }
    }
}
