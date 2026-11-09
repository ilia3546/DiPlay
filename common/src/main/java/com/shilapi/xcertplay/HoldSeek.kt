package com.shilapi.xcertplay

/**
 * Fast-forward or rewind while a steering-wheel key is held: one step when the hold starts, then one every
 * [INTERVAL_MILLIS]. Each step counts from the position this hold last sent, not from the iPhone's later updates.
 *
 * No Android types, so the steps are unit-tested without a device.
 */
internal class HoldSeek {
    enum class Reason { NO_ELAPSED, NO_DURATION, AT_LIMIT, NOT_HELD }

    sealed interface Step {
        /** Send [targetMillis]; [number] counts this hold's steps from 1. */
        data class Seek(val targetMillis: Long, val deltaMillis: Long, val number: Int) : Step
        data class Skip(val reason: Reason) : Step
    }

    /**
     * Where the iPhone's track is now. The iPhone reports the position on play, pause and seek only, so it moves on
     * from the last change of either the position or the play state; a resume at the same position restarts it.
     */
    class PlaybackClock {
        private var elapsedMillis: Long? = null
        private var playing = false
        private var changedAt = 0L

        fun observe(elapsedMillis: Long?, playing: Boolean, nowMillis: Long) {
            if (elapsedMillis != this.elapsedMillis || playing != this.playing) changedAt = nowMillis
            this.elapsedMillis = elapsedMillis
            this.playing = playing
        }

        fun positionAt(nowMillis: Long): Long? =
            elapsedMillis?.let { if (playing) it + (nowMillis - changedAt).coerceAtLeast(0) else it }

        fun reset() {
            elapsedMillis = null
            playing = false
            changedAt = 0L
        }
    }

    private var direction = 0
    private var positionMillis = 0L
    private var lastMillis = 0L
    private var startedAt = 0L

    var steps = 0
        private set

    val active: Boolean get() = direction != 0

    /**
     * Starts a hold; its first step comes from [next]. A live stream or radio has no [positionMillis] or
     * [durationMillis] to seek in: the hold does not start then, and the reason is returned.
     */
    fun start(forward: Boolean, positionMillis: Long?, durationMillis: Long?, nowMillis: Long): Reason? {
        stop()
        if (positionMillis == null) return Reason.NO_ELAPSED
        if (durationMillis == null || durationMillis <= 0) return Reason.NO_DURATION
        lastMillis = (durationMillis - END_MARGIN_MILLIS).coerceAtLeast(0)
        this.positionMillis = positionMillis.coerceIn(0, lastMillis)
        direction = if (forward) 1 else -1
        startedAt = nowMillis
        return null
    }

    fun next(): Step {
        if (!active) return Step.Skip(Reason.NOT_HELD)
        val target = (positionMillis + direction * STEP_MILLIS).coerceIn(0, lastMillis)
        if (target == positionMillis) return Step.Skip(Reason.AT_LIMIT)
        val delta = target - positionMillis
        positionMillis = target
        steps++
        return Step.Seek(target, delta, steps)
    }

    /** Held longer than [MAX_HOLD_MILLIS]: the release was most likely lost. */
    fun expired(nowMillis: Long): Boolean = active && nowMillis - startedAt >= MAX_HOLD_MILLIS

    fun stop() {
        direction = 0
        steps = 0
    }

    companion object {
        const val STEP_MILLIS = 10_000L
        const val INTERVAL_MILLIS = 500L
        const val END_MARGIN_MILLIS = 1_000L
        const val MAX_HOLD_MILLIS = 60_000L
    }
}
