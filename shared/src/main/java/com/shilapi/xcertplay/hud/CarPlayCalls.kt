package com.shilapi.xcertplay.hud

import java.util.concurrent.CopyOnWriteArraySet

/** The iPhone's call as screens other than BYD's call card follow it; [card] is null without a call. */
data class CarPlayCallSnapshot(val card: CarPlayCallCard? = null)

/**
 * The iPhone's calls for any number of observers, fed by every CarPlay session whatever the BYD call setting.
 * Observers run on the thread that changed the calls (the iAP2 thread, or the one that ends the session). Two such
 * changes may reach an observer in either order, so an observer that moves to its own thread reads [current] there.
 */
object CarPlayCalls {
    private val observers = CopyOnWriteArraySet<(CarPlayCallSnapshot) -> Unit>()
    private var last = CarPlayCallSnapshot()

    fun current(): CarPlayCallSnapshot = synchronized(this) { last }

    /** Calls [observer] with the current snapshot now and with each new one until the returned function runs. */
    fun observe(observer: (CarPlayCallSnapshot) -> Unit): () -> Unit {
        observers.add(observer)
        observer(current())
        return { observers.remove(observer) }
    }

    internal fun publish(read: () -> CarPlayCallSnapshot) {
        val next = synchronized(this) {
            val next = read()
            if (next == last) return
            last = next
            next
        }
        observers.forEach { it(next) }
    }
}
