package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper

/**
 * CarPlay's play state and position, which [ClusterMusicState] leaves out because they change every few seconds,
 * and whether DiPlay holds the car's media audio focus.
 */
internal object ClusterPlaybackState {
    /** [elapsedMillis] is the position at [elapsedAt] on the elapsed-realtime clock. */
    data class Snapshot(val playing: Boolean = false, val elapsedMillis: Long? = null, val elapsedAt: Long = 0L,
        val focusHeld: Boolean = false)
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
