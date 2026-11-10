package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Whether the car's media sound is off, from [start] to [stop]: muted, or its volume at 0. The volume comes from the
 * car service's setting of the media volume group and from the volume broadcasts, whichever came last; the mute only
 * from the mute broadcasts. [changed] runs when [muted] changes. Main thread.
 */
internal class CarMediaVolume(context: Context, private val changed: () -> Unit) {
    /** The media sound as last heard; null for what is not known, which does not turn the sound off. */
    data class Sound(val muted: Boolean? = null, val volume: Int? = null) {
        val off: Boolean get() = muted == true || volume == 0
    }

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hear(describe(intent), after(sound, intent))
    }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = hearSetting("setting")
    }
    private var started = false
    private var sound = Sound()

    val muted: Boolean get() = sound.off

    fun start() {
        if (started) return
        started = true
        runCatching { resolver.registerContentObserver(Settings.System.getUriFor(VOLUME_SETTING), false, observer) }
            .onFailure { Log.w(TAG, "media volume setting not observed (${describe(it)})") }
        runCatching {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(MUTE_CHANGED)
                addAction(VOLUME_CHANGED)
            }, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "volume broadcasts unavailable (${describe(it)})") }
        hearSetting("start, setting")
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { resolver.unregisterContentObserver(observer) }
        runCatching { app.unregisterReceiver(receiver) }
        sound = Sound()
    }

    private fun hearSetting(cause: String) {
        val read = runCatching { Settings.System.getInt(resolver, VOLUME_SETTING) }
        hear("$cause ${read.fold({ "$it" }, { "unread (${describe(it)})" })}", sound.copy(volume = read.getOrNull()))
    }

    private fun hear(cause: String, next: Sound) {
        if (!started) return
        val wasOff = sound.off
        sound = next
        Log.i(TAG, "media sound after $cause: volume=${next.volume} mute=${next.muted} off=${next.off}")
        if (next.off != wasOff) changed()
    }

    companion object {
        private const val TAG = "DiPlay-CustomDashboard"

        /** The car service keeps the media volume group's level here; it keeps the mute nowhere. */
        const val VOLUME_SETTING = "android.car.VOLUME_GROUP/0"
        const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED"
        private const val EXTRA_VOLUME = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREVIOUS_VOLUME = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        /**
         * [sound] after a volume broadcast, which changes only what it says of the media stream. AudioManager is no
         * source: while the firmware mutes the media group, `isStreamMute(STREAM_MUSIC)` says false, and
         * `getStreamVolume(STREAM_MUSIC)` gives a fixed level (Geely Monjaro, Android 11).
         */
        fun after(sound: Sound, intent: Intent): Sound {
            if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return sound
            return when (intent.action) {
                MUTE_CHANGED -> if (intent.hasExtra(EXTRA_MUTED)) {
                    sound.copy(muted = intent.getBooleanExtra(EXTRA_MUTED, false))
                } else {
                    sound
                }
                VOLUME_CHANGED -> if (intent.hasExtra(EXTRA_VOLUME)) {
                    sound.copy(volume = intent.getIntExtra(EXTRA_VOLUME, 0))
                } else {
                    sound
                }
                else -> sound
            }
        }

        private fun describe(intent: Intent): String {
            val extras = listOf(EXTRA_STREAM, EXTRA_MUTED, EXTRA_VOLUME, EXTRA_PREVIOUS_VOLUME)
                .filter(intent::hasExtra)
                .joinToString(" ") { extra ->
                    val value = if (extra == EXTRA_MUTED) intent.getBooleanExtra(extra, false) else intent.getIntExtra(extra, -1)
                    "${extra.substringAfterLast('.')}=$value"
                }
            return "${intent.action?.substringAfterLast('.')} $extras"
        }

        private fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
    }
}
