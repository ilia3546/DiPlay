package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper

/**
 * CarPlay's play state and position, which [ClusterMusicState] leaves out because they change every few seconds,
 * and whether DiPlay holds the car's media audio focus.
 */
internal object ClusterPlaybackState {
    /** [elapsedMillis] is the position at [elapsedAt] on the elapsed-realtime clock. */
    /** [carSource] is null without the option to pause CarPlay for the car's audio, else whether the car's own source has the wheel. */
    /** [mediaAudio] is whether the iPhone streams media audio, which an app may do without any now-playing data. */
    data class Snapshot(val playing: Boolean = false, val elapsedMillis: Long? = null, val elapsedAt: Long = 0L,
        val focusHeld: Boolean = false, val carSource: Boolean? = null, val mediaAudio: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private var state = Snapshot()

    /** May run on any thread. */
    fun publish(next: Snapshot) {
        main.post {
            if (next == state) return@post
            state = next
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }
}
