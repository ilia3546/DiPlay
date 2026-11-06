package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) with its lanes (0x5204), and the song from
 * NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session, whatever the BYD output settings.
 */
object CarPlayGlance {
    /**
     * One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route.
     * [session] counts the sessions of the process while one is connected, and is 0 between them.
     */
    data class Snapshot(
        val connected: Boolean = false,
        val session: Long = 0,
        val maneuverType: Int? = null,
        val maneuverIndex: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        val song: String? = null,
        val playing: Boolean = false,
        val lanes: LaneStrip? = null,
    )

    /** The last lane strip from 0x5204, with the index of the maneuver that was next when it arrived. */
    data class LaneStrip(val maneuverIndex: Int, val lanes: List<LaneGuidanceLane>)

    private const val LANE_GUIDANCE_UPDATE = 0x5204
    private const val TAG = "DiPlay-Lane"

    private val route = BydHudRouteState()
    private val song = ClusterSongState()
    private var connected = false
    private var session = 0L
    private var lanes: LaneStrip? = null
    private var last = Snapshot()

    /** Called with each new snapshot, on the thread that changed it. */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    private val observers = CopyOnWriteArraySet<(Snapshot) -> Unit>()

    /**
     * Calls [observer] with the current snapshot now and with each new one, on the thread that changed it, until
     * the returned function runs. Unlike [listener], any number of observers may follow the glance.
     */
    fun observe(observer: (Snapshot) -> Unit): () -> Unit {
        observers.add(observer)
        observer(synchronized(this) { last })
        return { observers.remove(observer) }
    }

    /** Whether a CarPlay session is active now, whatever the snapshots still on their way say. */
    val isConnected: Boolean get() = synchronized(this) { connected }

    /**
     * Follows [isConnected] on another thread: [post] moves each change there, and [observer] reads the level
     * only when it runs. Changes of two threads are notified outside the lock and may arrive in either order;
     * the last call still has the current level.
     */
    fun observeConnection(post: (Runnable) -> Unit, observer: (Boolean) -> Unit): () -> Unit =
        observe { post(Runnable { observer(isConnected) }) }

    /** Lane guidance from 0x5204, on the iAP2 thread; empty when the lane strip goes away. */
    @Volatile var laneListener: ((List<LaneGuidanceLane>) -> Unit)? = null

    /** The last strip written to the log: Apple Maps repeats it while the overlap lasts. */
    private var loggedLanes: List<LaneGuidanceLane>? = null

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let(::notify)
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == LANE_GUIDANCE_UPDATE) {
            // Lane guidance is a side channel: log it, hand it to whoever draws lanes, and leave
            // the route/now-playing state alone.
            val strip = runCatching { Iap2LaneGuidance.parse(frame.payload) }.getOrDefault(emptyList())
            if (strip != loggedLanes) {
                loggedLanes = strip
                android.util.Log.i(TAG, "0x5204 " + describeLanes(strip, frame.payload))
            }
            laneListener?.invoke(strip)
            val changed = synchronized(this) {
                val index = route.currentApple()?.index
                lanes = if (strip.isEmpty() || index == null) null else LaneStrip(index, strip)
                publishLocked()
            }
            changed?.let(::notify)
            return
        }
        val changed = synchronized(this) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE, BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE ->
                    runCatching { route.accept(frame.messageId, frame.payload) }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        changed?.let(::notify)
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (next) {
                session++
            } else {
                route.clear()
                song.clear()
                lanes = null
            }
            publishLocked()
        }
        changed?.let(::notify)
    }

    /**
     * One line per lane strip for the diagnostic report: the parsed fields, and the raw payload once,
     * so a report from a car we cannot test still shows what the phone actually sent.
     */
    private fun describeLanes(lanes: List<LaneGuidanceLane>, payload: ByteArray): String {
        if (lanes.isEmpty()) return "lane strip cleared"
        val strip = lanes.joinToString("; ") {
            "i=${it.index} st=${it.status} a=${it.angle} h=${it.highlightAngle}"
        }
        return "lanes=${lanes.size} [$strip] raw=" + payload.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun notify(changed: Snapshot) {
        listener?.invoke(changed)
        observers.forEach { it(changed) }
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        if (lanes?.maneuverIndex != maneuver?.index) lanes = null
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            session = if (connected) session else 0,
            maneuverType = maneuver?.type,
            maneuverIndex = maneuver?.index,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            song = current?.text,
            playing = current?.playing ?: false,
            lanes = lanes,
        )
        if (next == last) return null
        last = next
        return next
    }
}
