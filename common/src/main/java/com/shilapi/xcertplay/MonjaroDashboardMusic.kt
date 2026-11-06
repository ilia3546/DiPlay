package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.DashboardMusic
import com.shilapi.xcertplay.hud.GeelyDashboardMusic

/**
 * Feeds the CarPlay track to the stock Monjaro A3 dashboard and media widgets while a CarPlay session is
 * attached and the card's option is on; turning either off sends one empty card and gives the widgets back.
 */
internal object MonjaroDashboardMusic {
    private val main = Handler(Looper.getMainLooper())
    internal var dashboard: DashboardMusic = GeelyDashboardMusic
    private var app: Context? = null
    private var sessionAttached = false
    private var iphonePlaying: () -> Boolean = { false }
    private var position: Pair<Long?, Long>? = null
    private var stopObserving: (() -> Unit)? = null

    fun enabled(context: Context): Boolean = MonjaroA3Options.load(context).dashboardMusic

    /** [playing] reads the session's play state now: the music snapshots are not published when only that changes. */
    fun sessionStarted(context: Context, playing: () -> Boolean) {
        main.post {
            app = context.applicationContext
            iphonePlaying = playing
            sessionAttached = true
            refresh()
        }
    }

    fun sessionEnded() {
        main.post {
            sessionAttached = false
            position = null
            refresh()
        }
    }

    fun settingChanged(context: Context) {
        main.post {
            app = context.applicationContext
            refresh()
        }
    }

    /** May run on any thread. */
    fun playingChanged(playing: Boolean) {
        main.post {
            val context = app
            if (stopObserving != null && context != null) dashboard.playing(context, playing)
        }
    }

    /** The iPhone's position, [elapsedMillis] at [atMillis] on the elapsed-realtime clock; may run on any thread. */
    fun positionChanged(elapsedMillis: Long?, atMillis: Long) {
        main.post {
            position = elapsedMillis to atMillis
            val context = app
            if (stopObserving != null && context != null) dashboard.position(context, elapsedMillis, atMillis)
        }
    }

    /** Runs [done] on the dashboard worker once the work asked for so far, such as the empty card at shutdown, is done. */
    fun whenIdle(done: () -> Unit) {
        main.post { dashboard.whenIdle(done) }
    }

    private fun refresh() {
        val context = app ?: return
        val on = sessionAttached && enabled(context)
        if (on && stopObserving == null) {
            dashboard.start(context, CarPlayMediaKeys::onWidgetMediaKey)
            dashboard.playing(context, iphonePlaying())
            position?.let { (elapsed, at) -> dashboard.position(context, elapsed, at) }
            stopObserving = ClusterMusicState.observe { snapshot ->
                dashboard.show(context, snapshot.info.title, snapshot.info.artist, snapshot.info.album,
                    snapshot.info.durationMillis, snapshot.artwork)
            }
        } else if (!on) {
            stopObserving?.let {
                it()
                stopObserving = null
                dashboard.stop(context)
            }
        }
    }
}
