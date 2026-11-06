package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.view.View

/**
 * Keeps a [MonjaroDashboardView] current while it is in its window: the clock, the background and dimming from
 * the settings, and the outside temperature from [EcarxCarData].
 */
internal class MonjaroDashboardSources(private val view: MonjaroDashboardView) : View.OnAttachStateChangeListener {
    private val app = view.context.applicationContext
    private var stopObservingSettings: (() -> Unit)? = null
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
        EcarxCarData.start(app, view::showTemperature)
    }

    override fun onViewDetachedFromWindow(detached: View) {
        runCatching { app.unregisterReceiver(time) }
        stopObservingSettings?.invoke()
        stopObservingSettings = null
        EcarxCarData.stop()
    }

    private fun showTime() = view.showTime(System.currentTimeMillis(), DateFormat.is24HourFormat(app))

    private fun showBackground() {
        val options = MonjaroA3Options.load(app)
        view.showBackground(options.dashboardBackground, options.dashboardDimmingPercent) { null }
    }

    companion object {
        fun create(context: Context): View =
            MonjaroDashboardView(context).also { it.addOnAttachStateChangeListener(MonjaroDashboardSources(it)) }
    }
}
