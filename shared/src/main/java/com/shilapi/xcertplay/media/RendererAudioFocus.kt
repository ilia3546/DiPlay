package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicReference

/**
 * Whether a CarPlay audio renderer of this process holds Android audio focus right now. The media-key
 * focus request loses focus to it like to any other app, and has to tell the two apart.
 */
object RendererAudioFocus {
    private val holder = AtomicReference<Any?>(null)

    val held: Boolean get() = holder.get() != null

    fun claim(owner: Any) = holder.set(owner)

    fun release(owner: Any) {
        holder.compareAndSet(owner, null)
    }
}
