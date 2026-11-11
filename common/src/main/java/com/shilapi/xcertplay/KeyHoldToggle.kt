package com.shilapi.xcertplay

/**
 * Calls [toggle] once a key has stayed down for [holdMillis], for a key service that reports only a press
 * and a release. No Android types, so the hold is unit-tested without a device.
 */
internal class KeyHoldToggle(
    private val holdMillis: Long,
    private val postDelayed: (Long, () -> Unit) -> Unit,
    private val toggle: () -> Unit,
) {
    private var held = false
    private var hold = 0L

    /** A press while the key is already down neither arms a second toggle nor delays the first. */
    fun pressed() {
        val armed = synchronized(this) {
            if (held) return
            held = true
            ++hold
        }
        postDelayed(holdMillis) { toggleAfterHold(armed) }
    }

    fun released() = cancel()

    /** Ends the current hold; its pending toggle sees a newer [hold] and does nothing. */
    @Synchronized
    fun cancel() {
        held = false
        hold++
    }

    private fun toggleAfterHold(armed: Long) {
        synchronized(this) {
            if (!held || armed != hold) return
        }
        toggle()
    }
}
