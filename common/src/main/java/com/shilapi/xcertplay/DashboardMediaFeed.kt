package com.shilapi.xcertplay

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import com.shilapi.xcertplay.hud.CarPlayCalls

/**
 * Keeps a [DashboardMediaBlock] on what plays now, from [start] to [stop]: picks the source with
 * [DashboardMediaSource.of], then shows only that source's data, and moves CarPlay's progress on once a second.
 * While [DashboardCallShown.of] gives a call, the [call] block takes the media block's place, and the source is
 * still picked behind it. Main thread.
 */
internal class DashboardMediaFeed(private val block: DashboardMediaBlock, private val call: DashboardCallBlock) {
    private val main = Handler(Looper.getMainLooper())
    private val flags = HeadUnitMediaFlags(block.context, ::refresh)
    private val radio = DashboardRadio(block.context, ::refresh)
    private var music = ClusterMusicState.Snapshot()
    private var playback = ClusterPlaybackState.Snapshot()
    private var stopObservingMusic: (() -> Unit)? = null
    private var stopObservingPlayback: (() -> Unit)? = null
    private var stopObservingCalls: (() -> Unit)? = null
    private var started = false
    private var source: DashboardMediaSource? = null
    private var callState = describe(null)
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, PROGRESS_REFRESH_MILLIS)
        }
    }

    fun start() {
        if (started) return
        started = true
        flags.start()
        stopObservingMusic = ClusterMusicState.observe { snapshot ->
            music = snapshot
            refresh()
        }
        stopObservingPlayback = ClusterPlaybackState.observe { snapshot ->
            playback = snapshot
            refresh()
        }
        stopObservingCalls = CarPlayCalls.observe { main.post(::refresh) }
        tick.run()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(tick)
        flags.stop()
        stopObservingMusic?.invoke()
        stopObservingMusic = null
        stopObservingPlayback?.invoke()
        stopObservingPlayback = null
        stopObservingCalls?.invoke()
        stopObservingCalls = null
        radio.disconnect()
        source = null
        block.show(DashboardMedia.Empty)
        showCall(null)
    }

    private fun refresh() {
        if (!started) return
        val heard = flags.current
        if (heard.radio) radio.connect() else radio.disconnect()
        val info = music.info
        val track = !info.title.isNullOrBlank() || !info.artist.isNullOrBlank()
        val signals = DashboardMediaSignals(
            carPlayFocus = playback.focusHeld,
            carPlayFocusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(block.context),
            carPlayPlaying = playback.playing,
            carPlayTrack = track,
            radioPlaying = heard.radio,
            bluetoothPlaying = heard.bluetooth,
            usbPlaying = heard.usb,
            radioServicePlaying = radio.servicePlaying,
            carSource = playback.carSource,
        )
        val next = DashboardMediaSource.after(source, signals)
        if (next != source) {
            Log.i(TAG, "media source $next: $signals")
            source = next
        }
        val station = radio.station
        val nothing = nothingShown(signals)
        block.show(when (next) {
            DashboardMediaSource.CARPLAY -> if (!track) nothing else DashboardMedia.Track(
                title = info.title.orEmpty(),
                artist = info.artist,
                artwork = music.artwork?.takeUnless { it.isRecycled },
                progress = DashboardProgress.of(playback.elapsedMillis, playback.elapsedAt, info.durationMillis,
                    playback.playing, SystemClock.elapsedRealtime()),
            )
            DashboardMediaSource.RADIO -> station?.let { DashboardMedia.Radio(it.title, it.subtitle, radio.logo) }
                ?: nothing
            DashboardMediaSource.OTHER, DashboardMediaSource.UNKNOWN -> nothing
        })
        val now = System.currentTimeMillis()
        showCall(DashboardCallShown.of(MonjaroDashboardCall.active(block.context), CarPlayCalls.current(), now), now)
    }

    private fun showCall(shown: DashboardCallShown?, nowMillis: Long = System.currentTimeMillis()) {
        val state = describe(shown)
        if (state != callState) {
            Log.i(TAG, "call block $state")
            callState = state
        }
        if (shown != null) call.show(shown, nowMillis)
        block.visibility = if (shown == null) View.VISIBLE else View.GONE
        call.visibility = if (shown == null) View.GONE else View.VISIBLE
    }

    private fun describe(shown: DashboardCallShown?): String = when (shown) {
        null -> "hidden"
        is DashboardCallShown.Live -> shown.card.phase.name.lowercase()
        is DashboardCallShown.Ended -> "ended ${shown.outcome.kind.name.lowercase()}"
    }

    companion object {
        private const val TAG = "DiPlay-CustomDashboard"
        private const val PROGRESS_REFRESH_MILLIS = 1_000L

        /**
         * What the block shows without a track or a station to show: [DashboardMedia.Idle] only when no signal says
         * that something sounds and there is no CarPlay track at all, [DashboardMedia.Empty] otherwise. A station the
         * radio service remembers while the radio is silent does not count.
         */
        fun nothingShown(signals: DashboardMediaSignals): DashboardMedia = with(signals) {
            val sounding = carPlayPlaying || radioSounding || bluetoothPlaying || usbPlaying || radioServicePlaying == true
            if (sounding || carPlayTrack) DashboardMedia.Empty else DashboardMedia.Idle
        }
    }
}

/** The firmware's `Settings.Global` flags of the head-unit sources that play now; [changed] runs on the main thread. */
internal class HeadUnitMediaFlags(context: Context, private val changed: () -> Unit) {
    /** [carPlay] is the firmware's own CarPlay, only logged. */
    data class Flags(val radio: Boolean = false, val bluetooth: Boolean = false, val usb: Boolean = false,
        val carPlay: Boolean = false)

    private val resolver = context.applicationContext.contentResolver
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = read()
    }
    private var observing = false

    var current = Flags()
        private set

    fun start() {
        if (observing) return
        observing = true
        for (key in listOf(RADIO, BLUETOOTH, USB, CARPLAY)) {
            runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer) }
        }
        read()
    }

    fun stop() {
        if (!observing) return
        observing = false
        runCatching { resolver.unregisterContentObserver(observer) }
        current = Flags()
    }

    private fun read() {
        if (!observing) return
        val next = read(resolver)
        if (next == current) return
        Log.i(TAG, "head-unit media flags $next")
        current = next
        changed()
    }

    companion object {
        fun read(context: Context): Flags = read(context.applicationContext.contentResolver)

        private fun read(resolver: android.content.ContentResolver) = Flags(playing(resolver, RADIO),
            playing(resolver, BLUETOOTH), playing(resolver, USB), playing(resolver, CARPLAY))

        private fun playing(resolver: android.content.ContentResolver, key: String): Boolean =
            runCatching { Settings.Global.getInt(resolver, key, 0) == 1 }.getOrDefault(false)

        private const val TAG = "DiPlay-CustomDashboard"
        private const val RADIO = "radio_is_playing"
        private const val BLUETOOTH = "bt_is_playing"
        private const val USB = "usb_is_playing"
        private const val CARPLAY = "cp_is_playing"
    }
}
