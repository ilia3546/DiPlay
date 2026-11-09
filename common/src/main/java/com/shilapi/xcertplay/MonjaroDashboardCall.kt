package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.hud.CarPlayCalls
import com.shilapi.xcertplay.hud.DashboardCall
import com.shilapi.xcertplay.hud.GeelyDashboardCall
import com.shilapi.xcertplay.network.GeelyHeadUnit

/**
 * Owns whether the Monjaro A3 calls option acts: [active] decides for the stock dashboard and HUD call widget, the
 * custom dashboard's call card and the wheel. Shows the iPhone's call in the call widget while a CarPlay session is
 * attached and [active] holds; turning either off closes the widget.
 */
internal object MonjaroDashboardCall {
    private const val TAG = "DiPlay-DashboardCall"
    private const val RECHECK_MILLIS = 1_000L
    private val main = Handler(Looper.getMainLooper())
    internal var dashboard: DashboardCall = GeelyDashboardCall
    internal var liveSession: () -> Boolean = {
        CarPlayBackgroundSession.snapshot()?.controller?.activeAirPlaySessionToken() != null
    }
    private var app: Context? = null
    private var sessionAttached = false
    private var stopObserving: (() -> Unit)? = null
    private var shown: Any? = Unset
    private val recheck = Runnable { showCurrent() }

    private object Unset

    /** The option is on, on the Monjaro A3; whether a session is live does not matter. */
    fun enabled(context: Context): Boolean = MonjaroA3Options.load(context).dashboardCalls

    /** The option is on, on the Monjaro A3, and an AirPlay session is live: calls show and the wheel answers them. */
    fun active(context: Context): Boolean = enabled(context) && liveSession()

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
            GeelyWheelKeys.refreshRegistration()
            MonjaroHud.callsChanged()
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
            if (!MonjaroA3Options.load(context).disconnectPhoneBluetooth) {
                Log.w(TAG, "CarPlay calls on with the phone's Bluetooth kept: the stock phone may show the same call")
            }
            dashboard.start(context)
            shown = Unset
            stopObserving = CarPlayCalls.observe { main.post(::showCurrent) }
        } else if (!on) {
            main.removeCallbacks(recheck)
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
        main.removeCallbacks(recheck)
        val card = CarPlayCalls.current().card
        val wanted = card.takeIf { active(context) }
        if (wanted != shown) {
            shown = wanted
            dashboard.show(context, wanted)
        }
        // A call stays with the iAP2 link, which reports it before an AirPlay session is live and outlives one that
        // ends; neither publishes anything.
        if (card != null) main.postDelayed(recheck, RECHECK_MILLIS)
    }
}
