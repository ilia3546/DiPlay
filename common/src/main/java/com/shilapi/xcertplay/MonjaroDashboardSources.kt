package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import com.shilapi.xcertplay.glance.CarPlayGlance

/**
 * Keeps a [MonjaroDashboardView] current while it is in its window: the clock, the background and dimming from
 * the settings, the outside temperature from [EcarxCarData], CarPlay's route from [CarPlayGlance], the map
 * stream's stops, the parked map from [EcarxWheelSpeed], and the media block through [DashboardMediaFeed].
 */
internal class MonjaroDashboardSources(private val view: MonjaroDashboardView) : View.OnAttachStateChangeListener {
    private val app = view.context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stopObservingSettings: (() -> Unit)? = null
    private var stopObservingTripPassword: (() -> Unit)? = null
    private var stopObservingGlance: (() -> Unit)? = null
    private var stopObservingConnection: (() -> Unit)? = null
    private var stopObservingStream: (() -> Unit)? = null
    private var stopFollowingSpeed: (() -> Unit)? = null
    private val parking = DashboardParking()
    private val media = DashboardMediaFeed(view.media)
    private val trip = QnxTripReader(app) { reading -> view.trip.show(reading ?: DashboardTrip()) }
    private val streamChanged: (Boolean) -> Unit = { active -> if (!active) view.map.video.streamStopped() }
    // A route expires without a new frame, and the glance notices that only when asked.
    private val routeTick = object : Runnable {
        override fun run() {
            showRoute(CarPlayGlance.snapshot())
            main.postDelayed(this, ROUTE_REFRESH_MILLIS)
        }
    }
    private val parkingTick = object : Runnable {
        override fun run() {
            showParking()
            main.postDelayed(this, PARKING_READ_MILLIS)
        }
    }
    private val time = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showTime()
    }

    override fun onViewAttachedToWindow(attached: View) {
        app.registerReceiver(time, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        showTime()
        showBackground()
        stopObservingSettings = MonjaroCustomDashboard.observeSettings(::showBackground)
        stopObservingTripPassword = MonjaroCustomDashboard.observeSettings(trip::start)
        EcarxCarData.start(app, view::showTemperature)
        // A snapshot delivered late may be older than the glance; the post reads it again.
        stopObservingGlance = CarPlayGlance.observe { main.post { showRoute(CarPlayGlance.snapshot()) } }
        routeTick.run()
        stopObservingStream = DashboardMapStream.observeStream(streamChanged)
        stopObservingConnection = CarPlayGlance.observeConnection(main::post) { connected ->
            if (!connected) view.map.video.streamStopped()
        }
        stopFollowingSpeed = EcarxWheelSpeed.follow(app)
        parkingTick.run()
        media.start()
        trip.start()
    }

    override fun onViewDetachedFromWindow(detached: View) {
        runCatching { app.unregisterReceiver(time) }
        stopObservingSettings?.invoke()
        stopObservingSettings = null
        stopObservingTripPassword?.invoke()
        stopObservingTripPassword = null
        EcarxCarData.stop()
        stopObservingGlance?.invoke()
        stopObservingGlance = null
        main.removeCallbacks(routeTick)
        stopObservingStream?.invoke()
        stopObservingStream = null
        stopObservingConnection?.invoke()
        stopObservingConnection = null
        stopFollowingSpeed?.invoke()
        stopFollowingSpeed = null
        main.removeCallbacks(parkingTick)
        media.stop()
        trip.stop()
    }

    private fun showTime() = view.showTime(System.currentTimeMillis(), DateFormat.is24HourFormat(app))

    private fun showBackground() {
        val options = MonjaroA3Options.load(app)
        view.showBackground(options.dashboardBackground, options.dashboardDimmingPercent) { DashboardPhoto.load(app) }
        view.showMap(options.dashboardMap)
        view.map.maneuverShown = options.dashboardManeuver
        view.map.arrivalShown = options.dashboardArrival
        showRoute(CarPlayGlance.snapshot())
    }

    private fun showRoute(glance: CarPlayGlance.Snapshot) =
        view.map.showRoute(DashboardRoute.of(glance), DateFormat.is24HourFormat(app))

    private fun showParking() {
        val motion = EcarxWheelSpeed.motion()
        val wasParked = parking.parked
        val parked = parking.next(motion, SystemClock.elapsedRealtime())
        if (parked != wasParked) {
            val speed = motion?.metersPerSecond?.let { "%.1f km/h".format(it * 3.6) } ?: "unknown speed"
            Log.i(TAG, "map ${if (parked) "parked" else "driving"}: $speed, gear ${motion?.gear ?: "unknown"}")
        }
    }

    companion object {
        private const val TAG = "DiPlay-CustomDashboard"
        private const val ROUTE_REFRESH_MILLIS = 1_000L
        private const val PARKING_READ_MILLIS = 500L

        fun create(context: Context): View =
            MonjaroDashboardView(context).also { it.addOnAttachStateChangeListener(MonjaroDashboardSources(it)) }
    }
}
