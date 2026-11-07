package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.hud.CarPlayCalls
import com.shilapi.xcertplay.hud.DashboardCall
import com.shilapi.xcertplay.hud.GeelyDashboardCall
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * Shows the iPhone's call in the stock Monjaro A3 dashboard and HUD call widget while a CarPlay session is attached
 * and the card's option is on; turning either off closes the widget.
 */
internal object MonjaroDashboardCall {
    private val main = Handler(Looper.getMainLooper())
    internal var dashboard: DashboardCall = GeelyDashboardCall
    private var app: Context? = null
    private var sessionAttached = false
    private var stopObserving: (() -> Unit)? = null

    fun enabled(context: Context): Boolean = MonjaroA3Options.load(context).dashboardCalls

    /** At process start, whatever the option: a killed process may have left the widget shown. */
    fun install(context: Context) {
        if (GeelyHeadUnit.monjaroA3) dashboard.clearLeftBehind(context.applicationContext)
    }

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

    /** Runs [done] on the call worker once the work asked for so far, such as closing the widget at shutdown, is done. */
    fun whenIdle(done: () -> Unit) {
        main.post { dashboard.whenIdle(done) }
    }

    private fun refresh() {
        val context = app ?: return
        val on = sessionAttached && enabled(context)
        if (on && stopObserving == null) {
            dashboard.start(context)
            stopObserving = CarPlayCalls.observe { main.post(::showCurrent) }
        } else if (!on) {
            stopObserving?.let {
                it()
                stopObserving = null
                dashboard.stop(context)
            }
        }
    }

    private fun showCurrent() {
        val context = app ?: return
        if (stopObserving == null) return
        dashboard.show(context, CarPlayCalls.current().card)
    }
}
