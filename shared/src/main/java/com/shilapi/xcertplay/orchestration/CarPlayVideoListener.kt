package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.VideoInCar
import java.io.Closeable

/** iOS 27 video in car, played by the host in the car's own player (see [VideoInCar]). */
interface CarPlayVideoListener {
    /** Current persisted setting, read without reconnecting the CarPlay control session. */
    fun usesIphoneNetworkForVideo(): Boolean = false
    fun onVideoResourceRejected(streamId: Long, reason: String, bodyBytes: Int) {}
    /** Claim one pending reply before spooling/posting; late or duplicate replies must be dropped. */
    fun claimVideoResourceResponse(streamId: Long, requestId: Long): Boolean = false
    /** Whether the car is in P, or null when it cannot tell. Blocking; read once a second while CarPlay runs. */
    fun readParked(): Boolean?

    /** Starts what [readParked] reads from; the returned function stops it. Called on the reading thread. */
    fun startReadingParked(): () -> Unit = {}

    /**
     * A display the driver cannot watch where video may play in any gear, or null. Read with [readParked], never
     * while video support is forced.
     */
    fun videoScreenAwayFromDriver(): Int? = null

    /** After every gate reading, once [VideoInCar.screens] and [VideoInCar.allowed] are up to date. Gate thread. */
    fun onVideoGateTick() {}

    /** Video became allowed or not; when not, the player must close. Any thread. */
    fun onVideoAllowedChanged(allowed: Boolean)

    /** A playback message from the iPhone, e.g. insertPlayQueueItem, setRate, seek, stop. Any thread. */
    fun onVideoMessage(streamId: Long, message: Map<String, Any?>)

    /** The iPhone asks the car to show its video player (requestUI "videoplayback:"). Any thread. */
    fun onVideoUiRequested()

    /** The CarPlay session ended; the player must close. Any thread. */
    fun onVideoSessionEnded()
}

/**
 * Owns [VideoInCar.allowed] and [VideoInCar.screens]. Normally only P permits video on the driver's screen; unknown
 * gear keeps it off. A screen the driver cannot watch ([awayFromDriver]) permits video in any gear. The explicit
 * connection-scoped override skips gear and screen reads altogether. Changes go to [onChanged] and [onScreens].
 */
internal class VideoInCarGate(
    private val readParked: () -> Boolean?,
    private val onChanged: (Boolean) -> Unit,
    private val onObserved: (Boolean?) -> Unit = {},
    private val startReading: () -> () -> Unit = { {} },
    private val forceVideoInCar: Boolean = false,
    private val awayFromDriver: () -> Int? = { null },
    private val onScreens: (VideoInCar.VideoScreens) -> Unit = {},
    private val onTick: () -> Unit = {},
) : Closeable {
    @Volatile private var closed = false
    private var observed = false
    private var lastParked: Boolean? = null

    fun start() {
        if (forceVideoInCar) {
            update(null)
            return
        }
        Thread({
            val stopReading = runCatching(startReading).getOrDefault {}
            try {
                while (!closed) {
                    update(runCatching(readParked).getOrNull())
                    try {
                        Thread.sleep(POLL_MILLIS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            } finally {
                runCatching(stopReading)
            }
        }, "diplay-video-gate").apply { isDaemon = true }.start()
    }

    internal fun update(parked: Boolean?) {
        if (!forceVideoInCar && (!observed || parked != lastParked)) {
            observed = true
            lastParked = parked
            onObserved(parked)
        }
        if (closed) return
        val screens = VideoInCar.VideoScreens(
            driverScreen = forceVideoInCar || parked == true,
            passengerDisplayId = if (forceVideoInCar) null else runCatching(awayFromDriver).getOrNull(),
        )
        if (screens != VideoInCar.screens) {
            VideoInCar.screens = screens
            onScreens(screens)
        }
        val allowed = screens.driverScreen || screens.passengerDisplayId != null
        if (allowed != VideoInCar.allowed) {
            VideoInCar.allowed = allowed
            onChanged(allowed)
        }
        onTick()
    }

    override fun close() {
        closed = true
        VideoInCar.screens = VideoInCar.VideoScreens()
        VideoInCar.allowed = false
    }

    private companion object {
        const val POLL_MILLIS = 1_000L
    }
}
