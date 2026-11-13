package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/** The displays [CarPlayVideo] places its player by. */
internal interface PassengerScreens {
    /** Whether video may move to a second screen the driver cannot watch; the player then runs in a task of its own. */
    fun enabled(context: Context): Boolean

    /** That screen's display, whether or not it is on; null when there is none or the option is off. */
    fun identity(context: Context): Int?

    /** [identity] while that screen is on. */
    fun available(context: Context): Int?

    /** The display [activity] is shown on, or null before it has a window. */
    fun displayOf(activity: Activity): Int?
}

/**
 * A second screen the driver cannot watch, where CarPlay video may play in any gear: the passenger screen of the
 * Geely Monjaro A3, with "Video on the passenger screen" on.
 */
internal object PassengerVideoScreen : PassengerScreens {
    private const val TAG = "DiPlay-Video"
    @Volatile private var reported: String? = null

    override fun enabled(context: Context): Boolean = MonjaroA3Options.load(context).passengerVideo

    override fun identity(context: Context): Int? {
        if (!enabled(context)) return null
        val displays = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        val choice = MonjaroPassengerDisplay.choose(displays.map(PhysicalDisplay::describe))
        if (choice.reason != reported) {
            reported = choice.reason
            Log.i(TAG, "passenger screen ${choice.reason}")
        }
        return choice.displayId
    }

    override fun available(context: Context): Int? = identity(context)?.let { id ->
        available(id, context.getSystemService(DisplayManager::class.java)?.getDisplay(id)?.state)
    }

    internal fun available(identity: Int, state: Int?): Int? = identity.takeIf { state == Display.STATE_ON }

    override fun displayOf(activity: Activity): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display?.displayId
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay?.displayId
}
