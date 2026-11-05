package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.GeelyDashboardMusic

/**
 * Feeds the CarPlay track to the stock Monjaro A3 dashboard while a CarPlay session is attached and
 * the card's option is on; turning either off sends one empty card.
 */
internal object MonjaroDashboardMusic {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var sessionAttached = false
    private var stopObserving: (() -> Unit)? = null

    fun sessionStarted(context: Context) {
        main.post {
            app = context.applicationContext
            sessionAttached = true
            refresh()
        }
    }

    fun sessionEnded() {
        main.post {
            sessionAttached = false
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
            if (stopObserving != null && context != null) GeelyDashboardMusic.playing(context, playing)
        }
    }

    private fun refresh() {
        val context = app ?: return
        val on = sessionAttached && MonjaroA3Options.load(context).dashboardMusic
        if (on && stopObserving == null) {
            stopObserving = ClusterMusicState.observe { snapshot ->
                GeelyDashboardMusic.playing(context, snapshot.info.playing)
                GeelyDashboardMusic.show(context, snapshot.info.title, snapshot.info.artist, snapshot.info.album,
                    snapshot.artwork)
            }
        } else if (!on) {
            stopObserving?.let {
                it()
                stopObserving = null
                GeelyDashboardMusic.clear(context)
            }
        }
    }
}
