package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import java.lang.reflect.Proxy
import java.util.Locale

/**
 * The latest speed and gear values with the time they came, from either ECARX source, and the summary
 * [EcarxWheelSpeed.status] gives of them.
 */
internal class EcarxSignalReadings {
    private var speed: Float? = null
    private var speedMillis = 0L
    private var gearCode: Int? = null
    private var gear: VehicleGear? = null
    private var eventMillis: Long? = null
    private var error: String? = null
    private var errorMillis = 0L
    private var compared = 0
    private var comparedSpeed = 0.0
    private var comparedLongitudinal = 0.0

    /** The speed in m/s. */
    @Synchronized
    fun speed(raw: Float, elapsedMillis: Long) {
        speed = raw
        speedMillis = elapsedMillis
        eventMillis = elapsedMillis
    }

    /** `VehSpdLgtA` in m/s, kept only to compare with the speed while the car moves above [COMPARE_FROM]. */
    @Synchronized
    fun longitudinal(metersPerSecond: Float, elapsedMillis: Long) {
        val current = speed ?: return
        if (elapsedMillis - speedMillis > FRESH_MILLIS || current < COMPARE_FROM) return
        compared++
        comparedSpeed += current
        comparedLongitudinal += metersPerSecond
    }

    /** Average speed and `VehSpdLgtA` in km/h since the last call, or null when the car did not move fast enough. */
    @Synchronized
    fun comparison(): String? {
        if (compared == 0) return null
        val speedKmh = comparedSpeed / compared * 3.6
        val longitudinalKmh = comparedLongitudinal / compared * 3.6
        return String.format(Locale.US, "speed=%.1f VehSpdLgtA=%.1f km/h ratio=%.3f n=%d",
            speedKmh, longitudinalKmh, if (longitudinalKmh > 0) speedKmh / longitudinalKmh else 0.0, compared)
            .also {
                compared = 0
                comparedSpeed = 0.0
                comparedLongitudinal = 0.0
            }
    }

    @Synchronized
    fun gear(code: Int, gear: VehicleGear?, elapsedMillis: Long) {
        gearCode = code
        this.gear = gear
        eventMillis = elapsedMillis
    }

    @Synchronized
    fun error(text: String, elapsedMillis: Long) {
        error = text
        errorMillis = elapsedMillis
    }

    /** The speed in m/s, or null when none came in the last [FRESH_MILLIS] or it is implausible. */
    @Synchronized
    fun metersPerSecond(nowMillis: Long): Double? {
        val raw = speed ?: return null
        if (nowMillis - speedMillis > FRESH_MILLIS) return null
        return EcarxWheelSpeed.metersPerSecond(raw)
    }

    @Synchronized
    fun gear(): VehicleGear? = gear

    @Synchronized
    fun describe(nowMillis: Long): String {
        val age = speed?.let { "${nowMillis - speedMillis} speed=$it" } ?: "none"
        val gearText = gear?.letter?.toString() ?: gearCode?.let { "raw $it" } ?: "none"
        val event = eventMillis?.let { nowMillis - it } ?: "none"
        val lastError = error?.let { " lastError=$it ${(nowMillis - errorMillis) / 1000}s ago" }.orEmpty()
        return "speedAgeMs=$age gear=$gearText lastEventAgoMs=$event$lastError"
    }

    @Synchronized
    fun clear() {
        speed = null
        gearCode = null
        gear = null
        eventMillis = null
        error = null
        compared = 0
        comparedSpeed = 0.0
        comparedLongitudinal = 0.0
    }

    companion object {
        /** `car_signal` sends the cluster's speed about once a second, a little more often on the move. */
        const val FRESH_MILLIS = 2_500L

        const val COMPARE_FROM = 20 / 3.6f

        /** `GearLvrIndcn`, the selector lever position; 4 is manual mode, 5-7 are reserved or undefined. */
        fun leverGear(code: Int): VehicleGear? = when (code) {
            0 -> VehicleGear.PARK
            1 -> VehicleGear.REVERSE
            2 -> VehicleGear.NEUTRAL
            3, 4 -> VehicleGear.DRIVE
            else -> null
        }
    }
}

/**
 * Where the `car_signal` connection stands, and when to open it again. The `ecarx.car` wrapper unbinds itself
 * when its service goes away or its bind retries run out, and never connects that car again, so each loss
 * means a new car after [RETRY_MILLIS]. A [generation] tells a new car's service callbacks from an old one's.
 */
internal class EcarxSignalLink {
    private enum class State { CLOSED, CONNECTING, CONNECTED, SUBSCRIBED, LOST }

    private var state = State.CLOSED
    private var lostReason = ""
    private var lostMillis = 0L
    @Volatile var generation = 0
        private set

    @Synchronized
    fun shouldOpen(nowMillis: Long): Boolean =
        state == State.CLOSED || state == State.LOST && nowMillis - lostMillis >= RETRY_MILLIS

    /** The generation of the car about to open. */
    @Synchronized
    fun opening(): Int {
        state = State.CONNECTING
        return ++generation
    }

    @Synchronized
    fun connected(generation: Int) {
        if (generation == this.generation && state == State.CONNECTING) state = State.CONNECTED
    }

    @Synchronized
    fun shouldSubscribe(): Boolean = state == State.CONNECTED

    @Synchronized
    fun subscribed() {
        if (state == State.CONNECTED) state = State.SUBSCRIBED
    }

    @Synchronized
    fun lost(generation: Int, reason: String, nowMillis: Long) {
        if (generation != this.generation || state == State.LOST) return
        state = State.LOST
        lostReason = reason
        lostMillis = nowMillis
    }

    @Synchronized
    fun describe(): String = when (state) {
        State.CLOSED, State.CONNECTING -> if (generation > 1) "reconnecting" else "connecting"
        State.CONNECTED -> "connected"
        State.SUBSCRIBED -> "subscribed"
        State.LOST -> "$lostReason, reconnecting"
    }

    companion object {
        const val RETRY_MILLIS = 5_000L
    }
}

/**
 * Speed and gear events from the `car_signal` manager of `ecarx.car` into [readings]. Firmware that refuses
 * AdaptAPI to apps without an ECARX signature still serves `car_signal` to an ordinary app. The speed is
 * `SIVehSpdIndcd`, the cluster's whole km/h, about once a second, with no sign, so reverse comes from the gear.
 * `VehSpdLgtA` comes 12-25 times a second in m/s, but on the Geely Monjaro it reads below both the cluster
 * and the phone's GPS, which agree, so it is only compared with the speed for the report.
 * `GearLvrIndcn` arrives at subscription and on each change.
 *
 * [poll] and [close] run on the polling thread; the service connection and the events come on others.
 */
internal class EcarxCarSignal(private val app: Context, private val readings: EcarxSignalReadings) {
    private val link = EcarxSignalLink()
    private var car: Any? = null
    private var manager: Any? = null
    private var callback: Any? = null
    @Volatile private var speedId: Int? = null
    @Volatile private var longitudinalId: Int? = null
    @Volatile private var gearId: Int? = null
    private var failureLogged = false

    /** connecting, connected, subscribed, or what was lost and that it reconnects. */
    fun status(): String = link.describe()

    /** Opens a new car after a loss and subscribes once its service is connected. */
    fun poll(nowMillis: Long) {
        if (link.shouldOpen(nowMillis)) {
            drop()
            open(nowMillis)
        }
        if (link.shouldSubscribe()) subscribe(nowMillis)
    }

    fun close() {
        drop()
    }

    private fun open(nowMillis: Long) {
        val generation = link.opening()
        val listener = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = link.connected(generation)

            override fun onServiceDisconnected(name: ComponentName?) =
                link.lost(generation, "disconnected", SystemClock.elapsedRealtime())
        }
        try {
            val created = EcarxCar.create(app, listener)
            car = created
            EcarxCar.connect(created)
        } catch (error: Throwable) {
            failed(generation, "ecarx.car unavailable", error, nowMillis)
        }
    }

    private fun subscribe(nowMillis: Long) {
        val car = car ?: return
        try {
            val manager = EcarxCar.manager(car, "car_signal")
            val filterType = Class.forName(FILTER)
            val filter = filterType.getConstructor().newInstance()
            val speed = manager.javaClass.getField("SignalId_SIVehSpdIndcd").getInt(null)
            val longitudinal = manager.javaClass.getField("SignalId_VehSpdLgtA").getInt(null)
            val gear = manager.javaClass.getField("SignalId_GearLvrIndcn").getInt(null)
            val add = filterType.getMethod("add", Int::class.javaObjectType)
            add.invoke(filter, speed)
            add.invoke(filter, longitudinal)
            add.invoke(filter, gear)
            speedId = speed
            longitudinalId = longitudinal
            gearId = gear
            val callbackType = Class.forName(CALLBACK)
            val callback = Proxy.newProxyInstance(app.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> args?.firstOrNull()?.let(::changed)
                    "onErrorEvent" -> readings.error("onErrorEvent ${args?.contentToString()}", SystemClock.elapsedRealtime())
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "DiPlay car_signal speed callback"
                }
                null
            }
            // The manager keeps the callback and its signals before the remote call can throw, and then
            // skips signals it already holds, so after a failure only a new car registers them again.
            this.manager = manager
            this.callback = callback
            manager.javaClass.getMethod("registerCallback", callbackType, filterType).invoke(manager, callback, filter)
            link.subscribed()
            failureLogged = false
            Log.i(TAG, "car_signal speed and gear subscribed")
        } catch (error: Throwable) {
            failed(link.generation, "car_signal subscription failed", error, nowMillis)
            drop()
        }
    }

    private fun drop() {
        val manager = manager
        val callback = callback
        if (manager != null && callback != null) {
            runCatching { manager.javaClass.getMethod("unregisterCallback", Class.forName(CALLBACK)).invoke(manager, callback) }
        }
        this.manager = null
        this.callback = null
        car?.let(EcarxCar::disconnect)
        car = null
    }

    private fun changed(value: Any) {
        // An exception thrown here would end on the car service's callback thread.
        runCatching {
            val id = value.javaClass.getMethod("getPropertyId").invoke(value) as? Int
            val raw = value.javaClass.getMethod("getValue").invoke(value) as? Number ?: return
            val now = SystemClock.elapsedRealtime()
            when (id) {
                speedId -> readings.speed(raw.toFloat() / 3.6f, now)
                longitudinalId -> readings.longitudinal(raw.toFloat(), now)
                gearId -> raw.toInt().let { readings.gear(it, EcarxSignalReadings.leverGear(it), now) }
            }
        }
    }

    private fun failed(generation: Int, what: String, error: Throwable, nowMillis: Long) {
        link.lost(generation, "error ${EcarxCar.describe(error)}", nowMillis)
        if (!failureLogged) Log.w(TAG, what, error)
        failureLogged = true
    }

    private companion object {
        const val TAG = "DiPlay-EcarxSpeed"
        const val FILTER = "ecarx.car.hardware.signal.SignalFilter"
        const val CALLBACK = "ecarx.car.hardware.signal.CarSignalManager\$CarSignalEventCallback"
    }
}
